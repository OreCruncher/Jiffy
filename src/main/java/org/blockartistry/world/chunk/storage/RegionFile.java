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
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.Sets;

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
 * + Chunk stream version information is now encoded in the offset table. Avoids
 * the need of actually doing a partial read of the stream from the file on a
 * chunkExist() check. Improves concurrency with ChunkIOExector.
 * 
 * + Increase the size of the chunk stream header to 32 bytes. Ditched the
 * encoded version data, kept the length. 7 integers are reserved.
 * 
 * + Reserve 4 sectors for the control region for further growth.
 * 
 * + Dump the timestamp update. Nothing in application logic used it. Could have
 * been an artifact.  There's space in the control region if needed.
 * 
 * + Extend the data file with multiple empty sectors rather than just what is
 * needed for a given chunk write. This improves the overall time taken to
 * initialize a new region file.
 * 
 * + Minimum sectors per chunk stream to give a bit of room for lightweight
 * chunks to grow without having to reallocate storage from a region file.
 * 
 * + Avoid reallocating stream sectors if the chunk stream shrinks a little bit.
 * Chunk streams have a tendency to vacillate in size because of things like mob
 * spawns. Keeping the same chunk stream size maximizes write throughput.
 * 
 * + Use a byte array to logically lock chunk streams to avoid concurrent
 * operations on the same chunk stream. Rare occurrence but it would be possible
 * and could lead to corruption.
 * 
 * + Use a file mapped memory for handling the control region of the file. This
 * permits the best performance of managing/updating the region file control
 * data.
 */
public final class RegionFile {

	private static final Logger logger = LogManager.getLogger("RegionFile");

	// This is a kinda nasty work around for mapped memory hanging around.
	// In order to get it to close out and free up resources the "cleaner"
	// has to be invoked on the buffer. The cleaner would normally close
	// out native resources during finalization, but we don't want to wait
	// for the GC for that to occur.
	private static Method cleaner = null;
	private static Method clean = null;

	static {

		try {
			cleaner = Class.forName("java.nio.DirectByteBuffer").getMethod("cleaner");
			if (cleaner != null) {
				cleaner.setAccessible(true);
				clean = Class.forName("sun.misc.Cleaner").getMethod("clean");
				if (clean != null)
					clean.setAccessible(true);
				else
					cleaner = null;
			} else {
				logger.warn("Can't find cleaner!");
			}
		} catch (final Exception ex) {
			logger.warn("Unable to hook cleaner API for DirectByteBuffer release");
		}
	}

	// Force a free of the underlying resources of the MappedByteBuffer.
	// Normally the JVM will leave them hanging around till the GC
	// finishes them, but they need to be closed sooner.
	private static void freeMemoryMap(final MappedByteBuffer buffer) {
		if (clean != null && buffer != null && buffer.isDirect()) {
			try {
				clean.invoke(cleaner.invoke(buffer));
			} catch (final Exception ex) {
				ex.printStackTrace();
			}
		}
	}

	// Extension for the new region file.
	public final static String REGION_FILE_EXTENSION = ".mca2";

	private final static int INT_SIZE = 4;

	// Header is comprised of 32 bytes. First 4 bytes is the stream
	// size. The rest of the bytes are reserved.
	public final static int CHUNK_STREAM_HEADER_SIZE = INT_SIZE * 8;
	public final static int SECTOR_SIZE = 4096;
	private final static int MAX_SECTORS_PER_CHUNK_STREAM = 255;
	public final static int MIN_SECTORS_PER_CHUNK_STREAM = 2;
	private final static int ALLOWED_SECTOR_SHRINKAGE = 1;
	private final static int NUM_CONTROL_SECTORS = 4;
	private final static int REGION_CHUNK_DIMENSION = 32;
	private final static int CHUNKS_IN_REGION = REGION_CHUNK_DIMENSION * REGION_CHUNK_DIMENSION;
	private final static int EXTEND_SECTOR_QUANTITY = MIN_SECTORS_PER_CHUNK_STREAM * 128;
	private final static byte[] EMPTY_SECTOR = new byte[SECTOR_SIZE];

	// Information about the file layout. First integer in the file
	// is the version.
	private final static int REGION_FILE_SIGNATURE = 0xB10CD00D;
	private final static int REGION_FILE_VERSION_1 = 1;

	// Starting offset of the chunk directory in the control region,
	// in 32 bit integers.
	private final static int OFFSET_ENTRY_BASE = 1024;

	// Information about a given chunk stream returned by
	// getChunkInformation().  Essentially the stuff
	// encoded in control region for the chunk.
	private final static int SECTOR_START = 0;
	private final static int SECTOR_COUNT = 1;
	private final static int STREAM_VERSION = 2;
	private final static int[] NO_CHUNK_INFORMATION = { 0, 0, 0 };

	// Stream versions as of this release
	private final static byte STREAM_VERSION_UNKNOWN = 0;
	private final static byte STREAM_VERSION_FLATION = 1;

	// Masks for cracking a control entry. Upper byte is
	// not used and reserved.
	private final static int SECTOR_COUNT_MASK = 0x000000FF;
	private final static int SECTOR_START_MASK = 0x00FFFF00;
	private final static int SECTOR_START_SHIFT = 8;
	private final static int STREAM_VERSION_MASK = 0x0F000000;
	private final static int STREAM_VERSION_SHIFT = 24;

	// Standard options for opening a FileChannel
	private final static Set<StandardOpenOption> OPEN_OPTIONS = Sets.newHashSet(StandardOpenOption.READ,
			StandardOpenOption.WRITE, StandardOpenOption.CREATE);

	// Instance members
	private String name;
	private FileChannel channel;
	private BitSet sectorUsed;
	private int sectorsInFile;
	private MappedByteBuffer mapped;
	private IntBuffer control;

	// Used to logically lock a chunk while it is being operated on.
	// It is expected that concurrent calls into RegionFile will be
	// for different chunks thus allowing good concurrency, but in
	// the off chance a chunk is being worked on by different threads
	// we need to put in a guard.
	private final byte[] chunkLockVector = new byte[CHUNKS_IN_REGION];

	private void lockChunk(final int streamId) throws Exception {
		synchronized (chunkLockVector) {
			while (chunkLockVector[streamId] != 0)
				chunkLockVector.wait();
			chunkLockVector[streamId] = 1;
		}
	}

	private void unlockChunk(final int streamId) throws Exception {
		synchronized (chunkLockVector) {
			chunkLockVector[streamId] = 0;
			chunkLockVector.notifyAll();
		}
	}

	public RegionFile(final File regionFile) {

		try {

			this.name = regionFile.getPath();
			this.channel = FileChannel.open(regionFile.toPath(), OPEN_OPTIONS);
			this.sectorsInFile = sectorCount();
			final boolean needsInit = this.sectorsInFile < NUM_CONTROL_SECTORS;

			// Initialize control sectors plus some blank
			// region. sectorsInFile will auto-magically
			// update when writing empty sectors.
			if (needsInit)
				extendFile(EXTEND_SECTOR_QUANTITY + NUM_CONTROL_SECTORS);

			this.mapped = this.channel.map(MapMode.READ_WRITE, 0, SECTOR_SIZE * NUM_CONTROL_SECTORS);
			this.control = this.mapped.asIntBuffer();

			if (needsInit) {
				this.control.put(0, REGION_FILE_SIGNATURE);
				this.control.put(1, REGION_FILE_VERSION_1);
			} else if (this.control.get(0) != REGION_FILE_SIGNATURE)
				throw new Exception("Not a recognized region file");
			else if (this.control.get(1) != REGION_FILE_VERSION_1)
				throw new Exception("Not a recognized region file version");

			// Pre-allocate enough bits to fit either the number of sectors
			// currently present in the file, or 1024 minimum size chunk
			// streams.
			this.sectorUsed = new BitSet(Math.max(CHUNKS_IN_REGION * MIN_SECTORS_PER_CHUNK_STREAM + NUM_CONTROL_SECTORS,
					this.sectorsInFile));
			this.sectorUsed.set(0, NUM_CONTROL_SECTORS); // Control sectors

			// If the region file has stream data process the control
			// cache information and initialize the used sector map.
			if (!needsInit) {
				for (int j = 0; j < CHUNKS_IN_REGION; j++) {
					final int[] streamInfo = crackStreamInfo(this.control.get(j + OFFSET_ENTRY_BASE));
					if (streamInfo != NO_CHUNK_INFORMATION) {
						final int sectorNumber = streamInfo[SECTOR_START];
						final int numberOfSectors = streamInfo[SECTOR_COUNT];
						if (sectorNumber + numberOfSectors <= this.sectorsInFile) {
							this.sectorUsed.set(sectorNumber, sectorNumber + numberOfSectors);
						} else {
							logger.error(
									String.format("%s: stream control data exceeds file size (streamId: %d, info: %d)",
											name, j, streamInfo));
							this.control.put(j + OFFSET_ENTRY_BASE, 0);
						}
					}
				}
			}
		} catch (final Exception e) {
			e.printStackTrace();
		}
	}

	private void extendFile(final int count) throws Exception {
		final int base = sectorCount();
		for (int i = 0; i < count; i++)
			writeSectors(base + i, EMPTY_SECTOR, SECTOR_SIZE);
		this.sectorsInFile = sectorCount();
		if (base + count != this.sectorsInFile)
			logger.error(String.format("%d: extend file incorrect length", name));
	}

	private byte[] readSectors(final int sectorNumber, final int count, byte[] buffer) throws Exception {
		final int dataPosition = sectorNumber * SECTOR_SIZE;
		final int dataLength = count * SECTOR_SIZE;
		if (buffer == null || buffer.length < dataLength)
			buffer = new byte[dataLength];
		final int bytesRead = channel.read(ByteBuffer.wrap(buffer, 0, dataLength), dataPosition);
		if (bytesRead != dataLength)
			logger.error(String.format("%s: Incorrect bytes read: %d, expected %d", name, bytesRead, dataLength));

		return buffer;
	}

	private void writeSectors(final int sectorNumber, final byte[] buffer, final int length) throws Exception {
		final int bytesWritten = channel.write(ByteBuffer.wrap(buffer, 0, length), sectorNumber * SECTOR_SIZE);
		if (bytesWritten != length)
			logger.error(String.format("%s: Incorrect bytes written: %d, expected %d", name, bytesWritten, length));
	}

	private boolean isValidFileRegion(final int sector, final int count) {
		return sector >= NUM_CONTROL_SECTORS && (sector + count) <= this.sectorUsed.length();
	}

	private int sectorCount() throws Exception {
		return (int) (this.channel.size() / SECTOR_SIZE);
	}

	public String name() {
		return this.name;
	}

	public boolean chunkExists(final int regionX, final int regionZ) throws Exception {

		if (outOfBounds(regionX, regionZ))
			return false;

		final int streamId = getChunkStreamId(regionX, regionZ);
		lockChunk(streamId);

		try {

			final int[] streamInfo = getChunkInformation(streamId);
			return streamInfo[STREAM_VERSION] != STREAM_VERSION_UNKNOWN;

		} finally {
			unlockChunk(streamId);
		}
	}

	public DataInputStream getChunkDataInputStream(final int regionX, final int regionZ) throws Exception {

		if (outOfBounds(regionX, regionZ))
			return null;

		final int streamId = getChunkStreamId(regionX, regionZ);
		lockChunk(streamId);

		try {
			final int[] info = getChunkInformation(streamId);
			if (info == NO_CHUNK_INFORMATION)
				return null;

			final int sectorNumber = info[SECTOR_START];
			final int numberOfSectors = info[SECTOR_COUNT];
			final int streamVersion = info[STREAM_VERSION];

			if (streamVersion != STREAM_VERSION_FLATION) {
				logger.error(String.format("%s: Unrecognized stream version: %d", name, streamVersion));
				return null;
			}

			boolean isValid;
			synchronized (this) {
				isValid = isValidFileRegion(sectorNumber, numberOfSectors);
			}

			if (isValid) {
				final ChunkInputStream stream = ChunkInputStream.getStream();
				final int dataLength = numberOfSectors * SECTOR_SIZE;

				final int streamLength = getInt(
						readSectors(sectorNumber, numberOfSectors, stream.getBuffer(dataLength)));

				if (streamLength > 0 && streamLength <= (dataLength - CHUNK_STREAM_HEADER_SIZE))
					return stream.bake();

				logger.error(String.format("%s: x%d z%d streamLength (%d) return null", name, regionX, regionZ,
						streamLength));
				ChunkInputStream.returnStream(stream);
			} else {
				logger.error(String.format("%s: returning null (%d, %d) for streamId %d", name, sectorNumber,
						numberOfSectors, streamId));
			}

		} catch (final Exception e) {
			e.printStackTrace();
		} finally {
			unlockChunk(streamId);
		}

		return null;
	}

	public DataOutputStream getChunkDataOutputStream(final int regionX, final int regionZ) {
		if (outOfBounds(regionX, regionZ))
			return null;

		return ChunkOutputStream.getStream(regionX, regionZ, this);
	}

	protected int findContiguousSectors(final int count) {
		// Start the search after the control sectors
		int index = NUM_CONTROL_SECTORS;
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

	void write(final int regionX, final int regionZ, final byte[] buffer, final int length) throws Exception {

		if (outOfBounds(regionX, regionZ))
			return;

		// Incoming buffer has header incorporated. Need to enforce the
		// minimum sectors per chunk stream policy.
		int sectorsRequired = Math.max((length + SECTOR_SIZE - 1) / SECTOR_SIZE, MIN_SECTORS_PER_CHUNK_STREAM);
		if (sectorsRequired > MAX_SECTORS_PER_CHUNK_STREAM) {
			logger.error("Chunk stream required more than " + MAX_SECTORS_PER_CHUNK_STREAM + "sectors to write");
			return;
		}

		final int streamId = getChunkStreamId(regionX, regionZ);
		lockChunk(streamId);

		try {

			final int[] info = getChunkInformation(streamId);
			int sectorNumber = info[SECTOR_START];
			final int currentSectorCount = info[SECTOR_COUNT];

			// A new stream is required if:
			//
			// - Its a brand new chunk stream for the file
			//
			// - The size of the incoming stream is greater than the current
			// sector allocation.
			//
			// - The size of the incoming stream is less, and exceeds the
			// allowed shrinkage amount
			final boolean newChunkStream = sectorNumber == 0 || sectorsRequired > currentSectorCount
					|| sectorsRequired < (currentSectorCount - ALLOWED_SECTOR_SHRINKAGE);

			if (newChunkStream)
				synchronized (this) {
					// "Free" up the existing sectors
					if (sectorNumber != 0)
						this.sectorUsed.clear(sectorNumber, sectorNumber + currentSectorCount);

					// Find some free sectors to write on. If we can't find any
					// need to extend the file.
					sectorNumber = findContiguousSectors(sectorsRequired);
					if (this.sectorsInFile - sectorNumber < sectorsRequired)
						extendFile(Math.max(sectorsRequired, EXTEND_SECTOR_QUANTITY));

					// Mark our sectors used and update the mapping
					this.sectorUsed.set(sectorNumber, sectorNumber + sectorsRequired);
					setChunkInformation(streamId, sectorNumber, sectorsRequired, STREAM_VERSION_FLATION);
				}

			writeSectors(sectorNumber, buffer, length);

		} catch (final Exception ex) {
			ex.printStackTrace();
		} finally {
			unlockChunk(streamId);
		}
	}

	private static int getChunkStreamId(final int regionX, final int regionZ) {
		return regionX + regionZ * REGION_CHUNK_DIMENSION;
	}

	private int getInt(final byte[] buffer) {
		return getInt(buffer, 0);
	}

	private int getInt(final byte[] buffer, final int index) {
		int base = index * INT_SIZE;
		final int b0 = buffer[base++];
		final int b1 = buffer[base++];
		final int b2 = buffer[base++];
		final int b3 = buffer[base];
		return (b0 << 24) | ((b1 & 0xFF) << 16) | ((b2 & 0xFF) << 8) | (b3 & 0xFF);
	}

	private boolean outOfBounds(final int regionX, final int regionZ) {
		return regionX < 0 || regionX >= REGION_CHUNK_DIMENSION || regionZ < 0 || regionZ >= REGION_CHUNK_DIMENSION;
	}

	public boolean isChunkSaved(final int regionX, final int regionZ) {
		return this.control.get(getChunkStreamId(regionX, regionZ) + OFFSET_ENTRY_BASE) != 0;
	}

	private int[] crackStreamInfo(final int streamInfo) {
		if (streamInfo == 0)
			return NO_CHUNK_INFORMATION;

		final int streamVersion = (streamInfo & STREAM_VERSION_MASK) >> STREAM_VERSION_SHIFT;
		final int sectorNumber = (streamInfo & SECTOR_START_MASK) >> SECTOR_START_SHIFT;
		final int numberOfSectors = streamInfo & SECTOR_COUNT_MASK;
		return new int[] { sectorNumber, numberOfSectors, streamVersion };
	}

	private int[] getChunkInformation(final int streamId) {
		return crackStreamInfo(this.control.get(streamId + OFFSET_ENTRY_BASE));
	}

	private void setChunkInformation(final int streamId, final int sectorNumber, final int sectorCount,
			final byte streamVersion) throws IOException {
		final int info = streamVersion << STREAM_VERSION_SHIFT | sectorNumber << SECTOR_START_SHIFT | sectorCount;
		this.control.put(streamId + OFFSET_ENTRY_BASE, info);
	}

	private int[] analyzeSectors() {
		final int lastUsedSector = this.sectorUsed.length() - 1;
		final int gapSectors = lastUsedSector - this.sectorUsed.cardinality() + 1;
		return new int[] { this.sectorsInFile, lastUsedSector, gapSectors };
	}

	public void close() throws Exception {
		if (this.channel != null) {
			final int[] result = analyzeSectors();
			logger.debug(String.format("'%s': total sectors %d, last used sector %d, gaps %d (%d%%)", name(), result[0],
					result[1], result[2], result[2] * 100 / result[1]));
			if (this.mapped != null) {
				this.mapped.force();
				freeMemoryMap(this.mapped);
				this.mapped = null;
			}
			this.channel.force(true);
			this.channel.close();
			this.channel = null;
		}
	}

	@Override
	public String toString() {
		return name();
	}
}
