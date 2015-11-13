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
import java.util.BitSet;
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
 * + Pre-read chunk data during chunkExist() in anticipation of the subsequent
 * getChunkDataInputStream().
 * 
 * + Write to the data file control region is via a direct IntBuffer that
 * overlays the file region.
 * 
 * + Extend the data file with multiple empty sectors rather than just what is
 * needed for a given chunk write. This improves the overall time taken to
 * initialize a new region file.
 */
public class RegionFile {

	// Flag whether or not to update the chunk time stamps in the
	// control file. They seem to be updated, but not read. Need
	// to do more research as to the why's.
	private final static boolean TIMESTAMP_UPDATE = false;

	// Use the time stamp table to store CRC information and
	// and used to confirm read data.
	private final static boolean CHECKSUM_CHECK = false;

	// Useful constants
	private final static int INT_SIZE = 4;
	private final static int BYTE_SIZE = 1;
	private final static int CHUNK_HEADER_SIZE = INT_SIZE + BYTE_SIZE;
	private final static int SECTOR_SIZE = 4096;
	// private final static int CONTROL_REGION_SIZE = SECTOR_SIZE * 2;
	private final static int MAX_SECTORS_PER_CHUNK = 255;
	private final static int SECTOR_COUNT_MASK = MAX_SECTORS_PER_CHUNK;
	private final static int SECTOR_NUMBER_SHIFT = 8;
	private final static int REGION_CHUNK_DIMENSION = 32;
	private final static int CHUNKS_IN_REGION = REGION_CHUNK_DIMENSION * REGION_CHUNK_DIMENSION;
	private final static int EXTEND_CHUNKS_QUANTITY = 256;
	private final static byte[] EMPTY_SECTOR = new byte[SECTOR_SIZE];

	// Used to report back if there isn't chunk information
	// available.
	private final static int SECTOR_START = 0;
	private final static int SECTOR_COUNT = 1;
	private final static int STREAM_VERSION = 2;
	private final static int[] NO_CHUNK_INFORMATION = new int[] { 0, 0, 0 };
	private final static int[] INVALID = {};

	// Known stream versions that can be encountered within
	// the data file. Current active version is inflate/deflate.
	private final static byte STREAM_VERSION_UNKNOWN = 0;
	private final static byte STREAM_VERSION_GZIP = 1;
	private final static byte STREAM_VERSION_FLATION = 2;

	// Chunk exist check cache. It appears that Minecraft will check
	// if a chunk exists prior to reading. What this does is cause
	// the actual read to occur during chunk exist since it has
	// to read some of the data from the file to determine
	// if it exists.
	private ChunkInputStream ceStream = null;
	private int ceX = -1;
	private int ceZ = -1;

	// Information for the actual file on disk
	private String name;
	private RandomAccessFile dataFile;

	// Tracks the last time a query was made to the region file.
	// Used by the region file caching routine to evict idle
	// region files when the opportunity presents. By default
	// the idle time is 5 minutes. Note that region files
	// may be kept in the cache longer than the idle threshold
	// due to how the LRU algorithm works. This is more of a
	// hint/suggestion to the logic.
	private final static long IDLE_TIME_THRESHOLD = 5 * 60 * 1000;
	private long lastAccess;

	// Bits that define what sectors within the file that
	// are in active use.
	private BitSet sectorUsed;

	// Cached amount of sectors in the data file to avoid
	// repeated length read operations.
	private int sectorsInFile;

	// Cached version of the control buffer to avoid the
	// overhead of repeated reads.
	private int[] controlCache = new int[CHUNKS_IN_REGION * 2];

	// Cached information about the compression (existence)
	// information about a chunk. Minecraft likes calling
	// chunkExists() and this mitigates the overhead of
	// repeated reads.
	private byte[] compressionVersion = new byte[CHUNKS_IN_REGION];

	private static final Logger logger = LogManager.getLogger();

	public RegionFile(final File regionFile) {

		try {

			name = regionFile.getName();
			dataFile = new RandomAccessFile(regionFile, "rw");
			lastAccess = System.currentTimeMillis();

			// Get current sector length and determine if
			// the file needs initialization.
			sectorsInFile = sectorCount();
			final boolean needsInit = sectorsInFile < 2;

			if (needsInit) {
				// Write empty sectors to the disk. Reserve
				// space for 2 control sectors plus whatever
				// the default extension constant is set.
				writeEmptySectors(0, EXTEND_CHUNKS_QUANTITY + 2);
			}

			// Initialize the used sector map
			sectorUsed = new BitSet(sectorsInFile);
			sectorUsed.set(0);
			sectorUsed.set(1);

			// If this file was just created there is no need
			// to scan the offsets - there are none. Otherwise
			// initialize the cache and setup the sectorUsed
			// BitSet.
			if (!needsInit) {

				final IntBuffer ints = ByteBuffer.wrap(readSectors(0, 2, null)).asIntBuffer();

				for (int j = 0; j < CHUNKS_IN_REGION * 2; j++) {
					controlCache[j] = ints.get(j);

					if (controlCache[j] == 0 || j >= CHUNKS_IN_REGION)
						continue;

					final int sectorNumber = controlCache[j] >> SECTOR_NUMBER_SHIFT;
					final int numberOfSectors = controlCache[j] & SECTOR_COUNT_MASK;

					if (sectorNumber + numberOfSectors <= sectorsInFile) {
						sectorUsed.set(sectorNumber, sectorNumber + numberOfSectors);
					}
				}
			}

		} catch (final IOException e) {
			e.printStackTrace();
		}
	}

	public boolean isIdle() {
		return (System.currentTimeMillis() - lastAccess) > IDLE_TIME_THRESHOLD;
	}

	private void writeEmptySectors(final int sectorNumber, final int count) throws IOException {
		dataFile.seek(sectorNumber * SECTOR_SIZE);
		for (int i = 0; i < count; i++)
			dataFile.write(EMPTY_SECTOR);
		sectorsInFile = sectorCount();
	}

	private byte[] readSectors(final int sectorNumber, final int count, byte[] buffer) throws IOException {

		final int dataPosition = sectorNumber * SECTOR_SIZE;
		final int dataLength = count * SECTOR_SIZE;

		if (buffer == null || buffer.length < dataLength)
			buffer = new byte[dataLength];

		dataFile.seek(dataPosition);
		int bytesRead = dataFile.read(buffer, 0, dataLength);
		if (bytesRead != dataLength) {
			logger.error("Incorrect bytes read: " + bytesRead + ", expected " + dataLength);
		}

		return buffer;
	}

	private void writeSectors(final int sectorNumber, final byte[] buffer, final int length) throws IOException {
		dataFile.seek(sectorNumber * SECTOR_SIZE);
		dataFile.write(buffer, 0, length);
	}

	private int lastUsedSector() {
		return sectorUsed.previousSetBit(sectorUsed.length());
	}

	private boolean isValidFileRegion(final int sector, final int count) {
		return sector != 0 && sector != 1 && (sector + count - 1) <= lastUsedSector();
	}

	private int sectorCount() throws IOException {
		return (int) (dataFile.length() / SECTOR_SIZE);
	}

	private boolean isValidStreamVersion(final int ver) {
		return ver != STREAM_VERSION_UNKNOWN && (ver == STREAM_VERSION_FLATION || ver == STREAM_VERSION_GZIP);
	}

	public String name() {
		return name;
	}

	public boolean chunkExists(final int regionX, final int regionZ) {

		lastAccess = System.currentTimeMillis();

		final int[] info = getChunkInformation(regionX, regionZ);
		if (info == NO_CHUNK_INFORMATION || info == INVALID)
			return false;

		final int sectorNumber = info[SECTOR_START];
		final int numberOfSectors = info[SECTOR_COUNT];

		if (isValidStreamVersion(info[STREAM_VERSION]))
			return true;

		final int dataLength = numberOfSectors * SECTOR_SIZE;

		try {

			synchronized (this) {
				if (!isValidFileRegion(sectorNumber, numberOfSectors))
					return false;

				// Pre-load the chunk because there is going to be
				// a read right behind.
				ceX = ceZ = -1;
				if (ceStream == null)
					ceStream = ChunkInputStream.getStream();

				final byte[] buffer = readSectors(sectorNumber, numberOfSectors, ceStream.getBuffer(dataLength));
				final int streamLength = getInt(buffer);

				if (streamLength <= 0 || streamLength > dataLength)
					return false;

				if (isValidStreamVersion(buffer[4])) {
					ceX = regionX;
					ceZ = regionZ;
					setCompressionVersion(regionX, regionZ, buffer[4]);
					return true;
				}
			}

		} catch (final IOException e) {
			e.printStackTrace();
		}

		return false;
	}

	public DataInputStream getChunkDataInputStream(final int regionX, final int regionZ) {

		lastAccess = System.currentTimeMillis();

		final int[] info = getChunkInformation(regionX, regionZ);
		if (info == NO_CHUNK_INFORMATION || info == INVALID) {
			return null;
		}

		final int sectorNumber = info[SECTOR_START];
		final int numberOfSectors = info[SECTOR_COUNT];

		final int dataLength = numberOfSectors * SECTOR_SIZE;

		try {

			ChunkInputStream stream;
			byte[] buffer = null;

			synchronized (this) {
				if (!isValidFileRegion(sectorNumber, numberOfSectors)) {
					logger.error(String.format("Returning null (%d, %d, %d)", sectorNumber, numberOfSectors,
							lastUsedSector()));
					return null;
				}

				if (ceStream != null && ceX == regionX && ceZ == regionZ) {
					stream = ceStream;
					buffer = stream.getBuffer();
					ceStream = null;
					ceX = ceZ = -1;
				} else {
					stream = ChunkInputStream.getStream();
					buffer = readSectors(sectorNumber, numberOfSectors, stream.getBuffer(dataLength));
				}
			}

			final int streamLength = getInt(buffer);

			if (streamLength <= 0 || streamLength > dataLength) {
				logger.warn("getChunkDataInputStream() " + regionX + " " + regionZ + " streamLength (" + streamLength
						+ ") return null");
				ChunkInputStream.returnStream(stream);
				return null;
			}

			if (CHECKSUM_CHECK) {
				if (calculateCRC(buffer, streamLength + INT_SIZE) != getChunkTimestamp(regionX, regionZ)) {
					logger.warn("CHECKSUM mismatch '" + name + "', " + regionX + " " + regionZ);
				}
			}

			// Pass back an appropriate stream for the requester
			switch (buffer[4]) {
			case STREAM_VERSION_FLATION:
				setCompressionVersion(regionX, regionZ, STREAM_VERSION_FLATION);
				return stream.bake();
			case STREAM_VERSION_GZIP:
				final InputStream is = new ByteArrayInputStream(buffer, CHUNK_HEADER_SIZE, dataLength);
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

		lastAccess = System.currentTimeMillis();
		if (outOfBounds(regionX, regionZ))
			return null;

		return ChunkOutputStream.getStream(regionX, regionZ, this);
	}

	protected int findContiguousSectors(final int count) {

		// Start at 2 - it is the first sector
		// of the chunk regions.
		int index = 2;
		do {
			// Find the first clear bit. If it winds up
			// at the end or past, we are appending
			index = sectorUsed.nextClearBit(index);
			if (index >= sectorsInFile)
				return sectorsInFile;

			// Find the next used sector. If there isn't
			// one we are free to write.
			final int nextUsedSector = sectorUsed.nextSetBit(index);
			if (nextUsedSector == -1)
				return index;

			// If the region is large enough, go for it.
			if ((nextUsedSector - index) >= count)
				return index;

			// Doesn't fit our criteria. Advance to the
			// used bit and start searching again.
			index = nextUsedSector;

		} while (index < sectorsInFile);

		// If we get here we fell off the end.
		return sectorsInFile;
	}

	void write(final int regionX, final int regionZ, final byte[] buffer, final int length) {

		lastAccess = System.currentTimeMillis();

		// The replacement ChunkBuffer has already filled out the header
		// before this write was initiated. The provided length includes
		// the header so we do not add the header length in the following
		// calculation.

		// Note that the Vanilla version of this calculation would add
		// an extra sector if the length to be written fell exactly on
		// a sector boundary, meaning that if the length was SECTOR_SIZE
		// it would consume 2 sectors, the 2nd being left empty. The
		// chances of that happening are pretty darn slim, but I feel better
		// being more exact. :)
		final int sectorsRequired = (length + SECTOR_SIZE - 1) / SECTOR_SIZE;
		if (sectorsRequired > MAX_SECTORS_PER_CHUNK)
			return;

		final int[] info = getChunkInformation(regionX, regionZ);
		if (info == INVALID)
			return;

		int sectorNumber = info[SECTOR_START];
		final int numberOfSectors = info[SECTOR_COUNT];

		boolean zeroRegion = false;
		boolean setMetadata = true;

		try {

			synchronized (this) {
				// If we are writing this chunk it is stale. Keep
				// any cached stream - it can be reused for the next
				// go around.
				if (ceX == regionX && ceZ == regionZ) {
					ceX = ceZ = -1;
				}

				// If it hasn't been written before, or it no longer fits
				// exactly in it's current region, find a better spot.
				if (sectorNumber == 0 || numberOfSectors != sectorsRequired) {

					// "Free" up the existing sectors
					if (sectorNumber != 0)
						sectorUsed.clear(sectorNumber, sectorNumber + numberOfSectors);

					sectorNumber = findContiguousSectors(sectorsRequired);
					if (sectorsInFile - sectorNumber < sectorsRequired) {
						zeroRegion = true;
					}

				} else {
					// It's an exact fit - don't need to update the metadata
					setMetadata = false;
				}

				if (zeroRegion) {
					writeEmptySectors(sectorNumber, Math.max(sectorsRequired, EXTEND_CHUNKS_QUANTITY));
				}

				writeSectors(sectorNumber, buffer, length);

				if (setMetadata) {
					setChunkInformation(regionX, regionZ, sectorNumber, sectorsRequired, STREAM_VERSION_FLATION);
					sectorUsed.set(sectorNumber, sectorNumber + sectorsRequired);
				}

				if (CHECKSUM_CHECK)
					setChunkTimestamp(regionX, regionZ, calculateCRC(buffer, length));
				else if (TIMESTAMP_UPDATE)
					setChunkTimestamp(regionX, regionZ, (int) (MinecraftServer.getSystemTimeMillis() / 1000L));
			}

		} catch (final IOException e) {
			e.printStackTrace();
		}
	}

	private int calculateCRC(final byte[] buffer, final int length) {
		final CRC32 crc = new CRC32();
		crc.update(buffer, 0, length);
		return (int) crc.getValue();
	}

	// Cracks the first 4 bytes of the buffer as an integer. Used
	// to extract the stream size from a buffer without having to
	// wrap it up in special things and create overhead. Note that
	// this is in BigEndian which is what ChunkBuffer uses in the
	// stream header.
	private int getInt(final byte[] buffer) {
		return (buffer[0] << 24) | ((buffer[1] & 0xFF) << 16) | ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
	}

	private boolean outOfBounds(final int regionX, final int regionZ) {
		return regionX < 0 || regionX >= REGION_CHUNK_DIMENSION || regionZ < 0 || regionZ >= REGION_CHUNK_DIMENSION;
	}

	public boolean isChunkSaved(final int regionX, final int regionZ) {
		return getChunkInformation(regionX, regionZ) != NO_CHUNK_INFORMATION;
	}

	/**
	 * Returns back region file information about the chunk in question.
	 * 
	 * @param regionX
	 *            X dimension of the chunk
	 * @param regionZ
	 *            Z dimension of the chunk
	 * @return array containing either:
	 * 
	 *         INVALID - the dimensions provided are invalid
	 *         NO_CHUNK_INFORMATION - the chunk is unknown to the region file
	 *         int array - { sectorNumber, numberOfSectors, streamVersion }
	 */
	private int[] getChunkInformation(final int regionX, final int regionZ) {
		if (outOfBounds(regionX, regionZ))
			return INVALID;

		int idx = regionX + regionZ * REGION_CHUNK_DIMENSION;
		if (controlCache[idx] == 0)
			return NO_CHUNK_INFORMATION;

		final int sectorNumber = controlCache[idx] >> SECTOR_NUMBER_SHIFT;
		final int numberOfSectors = controlCache[idx] & SECTOR_COUNT_MASK;
		return new int[] { sectorNumber, numberOfSectors, compressionVersion[idx] };
	}

	private void setChunkInformation(final int regionX, final int regionZ, final int sectorNumber,
			final int sectorCount, final byte streamVersion) throws IOException {
		int idx = regionX + regionZ * REGION_CHUNK_DIMENSION;
		final int info = sectorNumber << SECTOR_NUMBER_SHIFT | sectorCount;
		if (info != controlCache[idx]) {
			controlCache[idx] = info;
			dataFile.seek(idx * INT_SIZE);
			dataFile.writeInt(info);
		}
		compressionVersion[idx] = streamVersion;
	}

	private void setChunkTimestamp(final int regionX, final int regionZ, final int value) throws IOException {
		int idx = regionX + regionZ * REGION_CHUNK_DIMENSION + CHUNKS_IN_REGION;
		if (value != controlCache[idx]) {
			controlCache[idx] = value;
			dataFile.seek(idx * INT_SIZE);
			dataFile.writeInt(value);
		}
	}

	private int getChunkTimestamp(final int regionX, final int regionZ) throws IOException {
		return controlCache[regionX + regionZ * REGION_CHUNK_DIMENSION + CHUNKS_IN_REGION];
	}

	private void setCompressionVersion(final int regionX, final int regionZ, final byte value) {
		compressionVersion[regionX + regionZ * REGION_CHUNK_DIMENSION] = value;
	}

	public void close() throws IOException {
		if (dataFile != null) {
			dataFile.close();
			dataFile = null;
		}
	}

	@Override
	public String toString() {
		return name;
	}
}
