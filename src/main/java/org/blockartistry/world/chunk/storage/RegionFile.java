/* This file is part of Jiffy, licensed under the MIT License (MIT).
 *
 * Copyright (c) OreCruncher
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.blockartistry.world.chunk.storage;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.util.BitSet;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.server.MinecraftServer;

/**
 * Replacement for Minecraft's RegionFile implementation. This version improves
 * on Minecraft's implementation in the following ways:
 * 
 * + Sector based reads/writes. It is more efficient to read buffers of
 * information than individual pieces from the file.
 * 
 * + Using BitSet to track used sectors within the data file rather than an
 * array of Boolean objects.
 * 
 * + Cache compression version of a chunk to mitigate impact of repeated
 * chunkExist() calls.
 * 
 * + Extend the data file with multiple empty sectors rather than just what is
 * needed for a given chunk write. This improves the overall time taken to
 * initialize a new region file.
 * 
 * + Minimum sectors per chunk stream to give a bit of room for lightweight
 * chunks to grow without having to reallocate storage from a region file.
 * 
 * + Use ReadWriteLock to guard file operations to allow for multiple readers
 * and a single writer.  Locks are held for the narrowest time possible to
 * improve concurrency.
 * 
 */
public class RegionFile {
	private static final Logger logger = LogManager.getLogger();

	// Flag whether or not to update the chunk time stamps in the
	// control file. They seem to be updated, but not read. Need
	// to do more research as to the why's. If they are not
	// needed it can be turned off.
	private final static boolean TIMESTAMP_UPDATE = true;

	private final static int INT_SIZE = 4;
	private final static int BYTE_SIZE = 1;
	public final static int CHUNK_STREAM_HEADER_SIZE = INT_SIZE + BYTE_SIZE;
	public final static int SECTOR_SIZE = 4096;
	private final static int MAX_SECTORS_PER_CHUNK_STREAM = 255;
	private final static int MIN_SECTORS_PER_CHUNK_STREAM = 2;
	private final static int SECTOR_COUNT_MASK = MAX_SECTORS_PER_CHUNK_STREAM;
	private final static int SECTOR_NUMBER_SHIFT = 8;
	private final static int REGION_CHUNK_DIMENSION = 32;
	private final static int CHUNKS_IN_REGION = REGION_CHUNK_DIMENSION * REGION_CHUNK_DIMENSION;
	private final static int EXTEND_SECTOR_QUANTITY = 256;
	private final static byte[] EMPTY_SECTOR = new byte[SECTOR_SIZE];
	private final static long IDLE_TIME_THRESHOLD = 5 * 60 * 1000;

	// Information about a given chunk stream
	private final static int SECTOR_START = 0;
	private final static int SECTOR_COUNT = 1;
	private final static int STREAM_VERSION = 2;
	private final static int[] NO_CHUNK_INFORMATION = { 0, 0, 0 };
	private final static int[] INVALID = {};

	// Stream versions as of this release
	private final static byte STREAM_VERSION_UNKNOWN = 0;
	private final static byte STREAM_VERSION_GZIP = 1;
	private final static byte STREAM_VERSION_FLATION = 2;

	// Instance members
	private String name;
	private RandomAccessFile dataFile;
	private FileChannel channel;
	private ReadWriteLock fileLock = new ReentrantReadWriteLock();
	private long lastAccess;
	private BitSet sectorUsed;
	private int sectorsInFile;
	private int[] controlCache = new int[CHUNKS_IN_REGION * 2];
	private byte[] compressionVersion = new byte[CHUNKS_IN_REGION];

	public RegionFile(final File regionFile) {

		try {
			this.name = regionFile.getName();
			this.dataFile = new RandomAccessFile(regionFile, "rw");
			this.channel = this.dataFile.getChannel();
			this.lastAccess = System.currentTimeMillis();
			this.sectorsInFile = sectorCount();
			final boolean needsInit = this.sectorsInFile < 2;

			// Initialize control sectors plus some blank
			// region. sectorsInFile will automagically
			// update when writing empty sectors.
			if (needsInit)
				writeEmptySectors(0, EXTEND_SECTOR_QUANTITY + 2);

			this.sectorUsed = new BitSet(this.sectorsInFile);
			this.sectorUsed.set(0); // Offset data
			this.sectorUsed.set(1); // Timestamp data

			if (!needsInit) {
				final IntBuffer ints = ByteBuffer.wrap(readSectors(0, 2, null)).asIntBuffer();
				for (int j = 0; j < CHUNKS_IN_REGION * 2; j++) {
					this.controlCache[j] = ints.get(j);
					if (this.controlCache[j] == 0 || j >= CHUNKS_IN_REGION)
						continue;

					final int sectorNumber = this.controlCache[j] >> SECTOR_NUMBER_SHIFT;
					final int numberOfSectors = this.controlCache[j] & SECTOR_COUNT_MASK;
					if (sectorNumber + numberOfSectors <= this.sectorsInFile)
						this.sectorUsed.set(sectorNumber, sectorNumber + numberOfSectors);
				}
			}
		} catch (final IOException e) {
			e.printStackTrace();
		}
	}

	public boolean isIdle() {
		return (System.currentTimeMillis() - this.lastAccess) > IDLE_TIME_THRESHOLD;
	}

	private void writeEmptySectors(final int sectorNumber, final int count) throws IOException {
		for (int i = 0; i < count; i++)
			writeSectors(sectorNumber + i, EMPTY_SECTOR, SECTOR_SIZE);
		this.sectorsInFile = sectorCount();
	}

	private byte[] readSectors(final int sectorNumber, final int count, byte[] buffer) throws IOException {
		final int dataPosition = sectorNumber * SECTOR_SIZE;
		final int dataLength = count * SECTOR_SIZE;
		if (buffer == null || buffer.length < dataLength)
			buffer = new byte[dataLength];

		int bytesRead = channel.read(ByteBuffer.wrap(buffer, 0, dataLength), dataPosition);
		if (bytesRead != dataLength)
			logger.error("Incorrect bytes read: " + bytesRead + ", expected " + dataLength);

		return buffer;
	}

	private void writeSectors(final int sectorNumber, final byte[] buffer, final int length) throws IOException {
		int bytesWritten = channel.write(ByteBuffer.wrap(buffer, 0, length), sectorNumber * SECTOR_SIZE);
		if (bytesWritten != length)
			logger.error("Incorrect bytes written: " + bytesWritten + ", expected " + length);
	}

	private boolean isValidFileRegion(final int sector, final int count) {
		return sector != 0 && sector != 1 && (sector + count - 1) <= this.sectorUsed.previousSetBit(this.sectorUsed.length());
	}

	private int sectorCount() throws IOException {
		return (int) (this.dataFile.length() / SECTOR_SIZE);
	}

	private boolean isValidStreamVersion(final int ver) {
		return ver != STREAM_VERSION_UNKNOWN && (ver == STREAM_VERSION_FLATION || ver == STREAM_VERSION_GZIP);
	}

	public String name() {
		return this.name;
	}

	public boolean chunkExists(final int regionX, final int regionZ) {
		this.lastAccess = System.currentTimeMillis();

		final int[] info = getChunkInformation(regionX, regionZ);
		if (info == NO_CHUNK_INFORMATION || info == INVALID)
			return false;
		if (isValidStreamVersion(info[STREAM_VERSION]))
			return true;

		final int sectorNumber = info[SECTOR_START];
		final int numberOfSectors = info[SECTOR_COUNT];
		final int dataLength = numberOfSectors * SECTOR_SIZE;

		try {
			byte[] buffer = null;

			try {
				fileLock.readLock().lock();

				if (!isValidFileRegion(sectorNumber, numberOfSectors))
					return false;

				buffer = readSectors(sectorNumber, 1, null);
			} finally {
				fileLock.readLock().unlock();
			}

			final int streamLength = getInt(buffer);
			if (streamLength <= 0 || streamLength > dataLength)
				return false;

			if (isValidStreamVersion(buffer[4])) {
				setCompressionVersion(regionX, regionZ, buffer[4]);
				return true;
			}
		} catch (final IOException e) {
			e.printStackTrace();
		}

		return false;
	}

	public DataInputStream getChunkDataInputStream(final int regionX, final int regionZ) {
		this.lastAccess = System.currentTimeMillis();

		final int[] info = getChunkInformation(regionX, regionZ);
		if (info == NO_CHUNK_INFORMATION || info == INVALID)
			return null;

		final int sectorNumber = info[SECTOR_START];
		final int numberOfSectors = info[SECTOR_COUNT];
		final int dataLength = numberOfSectors * SECTOR_SIZE;

		try {
			ChunkInputStream stream;
			byte[] buffer = null;

			try {
				fileLock.readLock().lock();

				if (!isValidFileRegion(sectorNumber, numberOfSectors)) {
					logger.error(String.format("'%s' eturning null (%d, %d)", name, sectorNumber, numberOfSectors));
					return null;
				}

				stream = ChunkInputStream.getStream();
				buffer = readSectors(sectorNumber, numberOfSectors, stream.getBuffer(dataLength));
			} finally {
				fileLock.readLock().unlock();
			}

			final int streamLength = getInt(buffer);

			if (streamLength <= 0 || streamLength > dataLength) {
				logger.error(name + " " + regionX + " " + regionZ + " streamLength (" + streamLength
						+ ") return null");
				ChunkInputStream.returnStream(stream);
				return null;
			}

			// Pass back an appropriate stream for the requester
			switch (buffer[4]) {
			case STREAM_VERSION_FLATION:
				setCompressionVersion(regionX, regionZ, STREAM_VERSION_FLATION);
				return stream.bake();
			case STREAM_VERSION_GZIP:
				final InputStream is = new ByteArrayInputStream(buffer, CHUNK_STREAM_HEADER_SIZE, dataLength);
				setCompressionVersion(regionX, regionZ, STREAM_VERSION_GZIP);
				return new DataInputStream(new GZIPInputStream(is));
			default:
				;
			}
		} catch (final IOException e) {
			e.printStackTrace();
		}

		return null;
	}

	public DataOutputStream getChunkDataOutputStream(final int regionX, final int regionZ) {
		this.lastAccess = System.currentTimeMillis();
		if (outOfBounds(regionX, regionZ))
			return null;

		return ChunkOutputStream.getStream(regionX, regionZ, this);
	}

	protected int findContiguousSectors(final int count) {
		int index = 2;
		do {
			// Find the first clear bit. If it winds up
			// at the end or past, we are appending
			index = this.sectorUsed.nextClearBit(index);
			if (index >= this.sectorsInFile)
				return this.sectorsInFile;

			// Find the next used sector. If there isn't
			// one we are free to write.
			final int nextUsedSector = this.sectorUsed.nextSetBit(index);
			if (nextUsedSector == -1)
				return index;

			// If the region is large enough, go for it.
			if ((nextUsedSector - index) >= count)
				return index;

			// Doesn't fit our criteria. Advance to the
			// used bit and start searching again.
			index = nextUsedSector;
		} while (index < this.sectorsInFile);

		return this.sectorsInFile;
	}

	void write(final int regionX, final int regionZ, final byte[] buffer, final int length) {
		this.lastAccess = System.currentTimeMillis();

		// Incoming buffer has header incorporated.  Need to enforce the
		// minimum sectors per chunk stream policy.
		final int sectorsRequired = Math.max((length + SECTOR_SIZE - 1) / SECTOR_SIZE, MIN_SECTORS_PER_CHUNK_STREAM);
		if (sectorsRequired > MAX_SECTORS_PER_CHUNK_STREAM) {
			logger.error("Chunk stream required more than " + MAX_SECTORS_PER_CHUNK_STREAM + "sectors to write");
			return;
		}
		
		final int[] info = getChunkInformation(regionX, regionZ);
		if (info == INVALID)
			return;

		int sectorNumber = info[SECTOR_START];
		final int numberOfSectors = info[SECTOR_COUNT];

		boolean zeroRegion = false;
		boolean setMetadata = true;

		try {
			fileLock.writeLock().lock();

			// If it hasn't been written before, or it no longer fits
			// exactly in it's current region, find a better spot.
			if (sectorNumber == 0 || numberOfSectors != sectorsRequired) {
				// "Free" up the existing sectors
				if (sectorNumber != 0) {
					this.sectorUsed.clear(sectorNumber, sectorNumber + numberOfSectors);
					logger.info("Stream size change: " + numberOfSectors + " -> " + sectorsRequired);
				}

				sectorNumber = findContiguousSectors(sectorsRequired);
				zeroRegion = this.sectorsInFile - sectorNumber < sectorsRequired;
			} else {
				// It's an exact fit - don't need to update the metadata
				setMetadata = false;
			}

			if (zeroRegion)
				writeEmptySectors(sectorNumber, Math.max(sectorsRequired, EXTEND_SECTOR_QUANTITY));

			writeSectors(sectorNumber, buffer, length);

			if (setMetadata) {
				setChunkInformation(regionX, regionZ, sectorNumber, sectorsRequired, STREAM_VERSION_FLATION);
				this.sectorUsed.set(sectorNumber, sectorNumber + sectorsRequired);
			}

			if (TIMESTAMP_UPDATE)
				setChunkTimestamp(regionX, regionZ, (int) (MinecraftServer.getSystemTimeMillis() / 1000L));

		} catch (final IOException e) {
			e.printStackTrace();
		} finally {
			fileLock.writeLock().unlock();
		}
	}

	private int getInt(final byte[] buffer) {
		return (buffer[0] << 24) | ((buffer[1] & 0xFF) << 16) | ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
	}

	private boolean outOfBounds(final int regionX, final int regionZ) {
		return regionX < 0 || regionX >= REGION_CHUNK_DIMENSION || regionZ < 0 || regionZ >= REGION_CHUNK_DIMENSION;
	}

	public boolean isChunkSaved(final int regionX, final int regionZ) {
		return getChunkInformation(regionX, regionZ) != NO_CHUNK_INFORMATION;
	}

	private int[] getChunkInformation(final int regionX, final int regionZ) {
		if (outOfBounds(regionX, regionZ))
			return INVALID;

		int idx = regionX + regionZ * REGION_CHUNK_DIMENSION;
		if (this.controlCache[idx] == 0)
			return NO_CHUNK_INFORMATION;

		final int sectorNumber = this.controlCache[idx] >> SECTOR_NUMBER_SHIFT;
		final int numberOfSectors = this.controlCache[idx] & SECTOR_COUNT_MASK;
		return new int[] { sectorNumber, numberOfSectors, this.compressionVersion[idx] };
	}

	private void setChunkInformation(final int regionX, final int regionZ, final int sectorNumber,
			final int sectorCount, final byte streamVersion) throws IOException {
		final int idx = regionX + regionZ * REGION_CHUNK_DIMENSION;
		final int info = sectorNumber << SECTOR_NUMBER_SHIFT | sectorCount;
		if (info != this.controlCache[idx]) {
			this.controlCache[idx] = info;
			this.dataFile.seek(idx * INT_SIZE);
			this.dataFile.writeInt(info);
		}
		this.compressionVersion[idx] = streamVersion;
	}

	private void setChunkTimestamp(final int regionX, final int regionZ, final int value) throws IOException {
		final int idx = regionX + regionZ * REGION_CHUNK_DIMENSION + CHUNKS_IN_REGION;
		if (value != this.controlCache[idx]) {
			this.controlCache[idx] = value;
			this.dataFile.seek(idx * INT_SIZE);
			this.dataFile.writeInt(value);
		}
	}

	private void setCompressionVersion(final int regionX, final int regionZ, final byte value) {
		this.compressionVersion[regionX + regionZ * REGION_CHUNK_DIMENSION] = value;
	}

	public void close() throws IOException {
		if (this.dataFile != null) {
			this.channel = null;
			this.dataFile.close();
			this.dataFile = null;
		}
	}

	@Override
	public String toString() {
		return this.name;
	}
}
