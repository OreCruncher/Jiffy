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
import java.util.Arrays;
import java.util.BitSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
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
 * + Avoid reallocating stream sectors if the chunk stream shrinks a little bit.
 * Chunk streams have a tendency to vacillate in size because of things like mob
 * spawns. Keeping the same chunk stream size maximizes write throughput.
 * 
 * + Use a byte array to logically lock chunk streams to avoid concurrent
 * operations on the same chunk stream. Rare occurrence but it would be possible
 * and could lead to corruption.
 * 
 * + Use a concurrent cache to pre-load chunk streams whenever a chunkExist()
 * call requires a read to the file on disk. Minecraft access pattern has a
 * chunkExist() followed by a read() operation and it is more efficient to read
 * the stream during the exist call and cache.
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
	private static void freeMemoryMap(final MappedByteBuffer buffer) {
		if (clean != null && buffer != null && buffer.isDirect()) {
			try {
				clean.invoke(cleaner.invoke(buffer));
			} catch (final Exception ex) {
				ex.printStackTrace();
			}
		}
	}

	private final static int INT_SIZE = 4;
	private final static int BYTE_SIZE = 1;
	public final static int CHUNK_STREAM_HEADER_SIZE = INT_SIZE + BYTE_SIZE;
	public final static int SECTOR_SIZE = 4096;
	private final static int MAX_SECTORS_PER_CHUNK_STREAM = 255;
	public final static int MIN_SECTORS_PER_CHUNK_STREAM = 3;
	private final static int ALLOWED_SECTOR_SHRINKAGE = 2;
	private final static int SECTOR_COUNT_MASK = MAX_SECTORS_PER_CHUNK_STREAM;
	private final static int SECTOR_NUMBER_SHIFT = 8;
	private final static int REGION_CHUNK_DIMENSION = 32;
	private final static int CHUNKS_IN_REGION = REGION_CHUNK_DIMENSION * REGION_CHUNK_DIMENSION;
	private final static int EXTEND_SECTOR_QUANTITY = MIN_SECTORS_PER_CHUNK_STREAM * 128;
	private final static byte[] EMPTY_SECTOR = new byte[SECTOR_SIZE];

	// Information about a given chunk stream
	private final static int SECTOR_START = 0;
	private final static int SECTOR_COUNT = 1;
	private final static int[] NO_CHUNK_INFORMATION = { 0, 0 };

	// Stream versions as of this release
	private final static byte STREAM_VERSION_UNKNOWN = 0;
	private final static byte STREAM_VERSION_GZIP = 1;
	private final static byte STREAM_VERSION_FLATION = 2;

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
	private byte[] streamVersion = new byte[CHUNKS_IN_REGION];

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

	// Pre-read cache
	private final static long EXPIRY_TIME = 20;

	private static final class ChunkStreamEviction implements RemovalListener<Integer, ChunkInputStream> {
		@Override
		public void onRemoval(RemovalNotification<Integer, ChunkInputStream> notification) {
			if (notification.wasEvicted())
				try {
					ChunkInputStream.returnStream(notification.getValue());
				} catch (IOException e) {
					e.printStackTrace();
				}
		}
	}

	private final Cache<Integer, ChunkInputStream> preRead = CacheBuilder.newBuilder()
			.expireAfterWrite(EXPIRY_TIME, TimeUnit.MILLISECONDS).removalListener(new ChunkStreamEviction()).build();

	public RegionFile(final File regionFile) {

		try {

			this.name = regionFile.getPath();
			this.channel = FileChannel.open(regionFile.toPath(), OPEN_OPTIONS);
			this.sectorsInFile = sectorCount();
			final boolean needsInit = this.sectorsInFile < 2;

			// Initialize control sectors plus some blank
			// region. sectorsInFile will automagically
			// update when writing empty sectors.
			if (needsInit)
				extendFile(EXTEND_SECTOR_QUANTITY + 2);

			this.mapped = this.channel.map(MapMode.READ_WRITE, 0, SECTOR_SIZE * 2);
			this.control = this.mapped.asIntBuffer();

			// Pre-allocate enough bits to fit either the number of sectors
			// currently present in the file, or 1024 minimum size chunk
			// streams.
			this.sectorUsed = new BitSet(Math.max(CHUNKS_IN_REGION * MIN_SECTORS_PER_CHUNK_STREAM, this.sectorsInFile));
			this.sectorUsed.set(0, 2); // Control sectors

			// If the region file has stream data process the control
			// cache information and initialize the used sector map.
			if (!needsInit) {
				for (int j = 0; j < CHUNKS_IN_REGION; j++) {
					final int streamInfo = this.control.get(j);
					if (streamInfo != 0) {
						final int sectorNumber = streamInfo >> SECTOR_NUMBER_SHIFT;
						final int numberOfSectors = streamInfo & SECTOR_COUNT_MASK;
						if (sectorNumber + numberOfSectors <= this.sectorsInFile) {
							this.sectorUsed.set(sectorNumber, sectorNumber + numberOfSectors);
						} else {
							logger.error(
									String.format("%s: stream control data exceeds file size (streamId: %d, info: %d)",
											name, j, streamInfo));
							this.control.put(j, 0);
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
		return (sector + count) <= this.sectorUsed.length();
	}

	private int sectorCount() throws Exception {
		return (int) (this.channel.size() / SECTOR_SIZE);
	}

	public String name() {
		return this.name;
	}

	public boolean chunkExists(final int regionX, final int regionZ) {

		if (outOfBounds(regionX, regionZ))
			return false;

		final int streamId = getChunkStreamId(regionX, regionZ);
		if (getStreamVersion(streamId) != STREAM_VERSION_UNKNOWN)
			return true;

		try {

			// Read the chunk stream and cache. If it loads it is
			// a recognized stream version. Otherwise we don't know
			// about it.
			final DataInputStream stream = (ChunkInputStream) getChunkDataInputStream(regionX, regionZ);
			if (stream instanceof ChunkInputStream)
				preRead.put(streamId, (ChunkInputStream) stream);

			return stream != null;

		} catch (final Exception e) {
			e.printStackTrace();
		}

		return false;
	}

	public DataInputStream getChunkDataInputStream(final int regionX, final int regionZ) throws Exception {

		if (outOfBounds(regionX, regionZ))
			return null;

		final int streamId = getChunkStreamId(regionX, regionZ);
		lockChunk(streamId);

		try {
			// Check the pre-read cache first to see if it is there.
			// This is possible because the stream could have been
			// loaded via chunkExists().
			ChunkInputStream stream = preRead.getIfPresent(streamId);
			if (stream != null) {
				preRead.invalidate(streamId);
				return stream;
			}

			final int[] info = getChunkInformation(streamId);
			if (info == NO_CHUNK_INFORMATION)
				return null;

			final int sectorNumber = info[SECTOR_START];
			final int numberOfSectors = info[SECTOR_COUNT];

			// Looks like we have to do a read
			stream = ChunkInputStream.getStream();
			final int dataLength = numberOfSectors * SECTOR_SIZE;
			final byte[] buffer = stream.getBuffer(dataLength);

			synchronized (this) {
				if (!isValidFileRegion(sectorNumber, numberOfSectors)) {
					logger.error(String.format("%s: returning null (%d, %d) for streamId %d", name, sectorNumber,
							numberOfSectors, streamId));
					ChunkInputStream.returnStream(stream);
					return null;
				}
			}

			readSectors(sectorNumber, numberOfSectors, buffer);

			final int streamLength = getInt(buffer);

			if (streamLength <= 0 || streamLength > dataLength) {
				logger.error(String.format("%s: x%d z%d streamLength (%d) return null", name, regionX, regionZ,
						streamLength));
				ChunkInputStream.returnStream(stream);
				return null;
			}

			// Pass back an appropriate stream for the requester
			DataInputStream result = null;
			if (buffer[4] == STREAM_VERSION_FLATION) {
				// Seems redundant, but Minecraft sometimes does a load
				// without an exist check.
				setStreamVersion(streamId, STREAM_VERSION_FLATION);
				result = stream.bake();
			} else if (buffer[4] == STREAM_VERSION_GZIP) {
				// Older MC version - going to be upgraded next write. Can't use
				// the ChunkInputStream for this.
				final InputStream is = new ByteArrayInputStream(Arrays.copyOf(buffer, buffer.length),
						CHUNK_STREAM_HEADER_SIZE, dataLength);
				setStreamVersion(streamId, STREAM_VERSION_GZIP);
				ChunkInputStream.returnStream(stream);
				result = new DataInputStream(new GZIPInputStream(is));
			} else {
				setStreamVersion(streamId, STREAM_VERSION_UNKNOWN);
				logger.error(String.format("%s: Unrecognized stream version: %d", name, buffer[4]));
			}

			return result;

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

		// Invalidate any cached data
		final ChunkInputStream stream = preRead.getIfPresent(streamId);
		if (stream != null) {
			preRead.invalidate(streamId);
			ChunkInputStream.returnStream(stream);
		}

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
			else
				// In case it is an upgrade from GZIP
				setStreamVersion(streamId, STREAM_VERSION_FLATION);

			writeSectors(sectorNumber, buffer, length);
			setChunkTimestamp(streamId, (int) System.currentTimeMillis() / 1000);

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
		return this.control.get(getChunkStreamId(regionX, regionZ)) != 0;
	}

	private int[] getChunkInformation(final int streamId) {
		final int streamInfo = this.control.get(streamId);
		if (streamInfo == 0)
			return NO_CHUNK_INFORMATION;

		final int sectorNumber = streamInfo >> SECTOR_NUMBER_SHIFT;
		final int numberOfSectors = streamInfo & SECTOR_COUNT_MASK;
		return new int[] { sectorNumber, numberOfSectors };
	}

	private void setChunkInformation(final int streamId, final int sectorNumber, final int sectorCount,
			final byte streamVersion) throws IOException {
		final int info = sectorNumber << SECTOR_NUMBER_SHIFT | sectorCount;
		this.control.put(streamId, info);
		this.streamVersion[streamId] = streamVersion;
	}

	private void setChunkTimestamp(final int streamId, final int value) throws IOException {
		final int idx = streamId + CHUNKS_IN_REGION;
		this.control.put(idx, value);
	}

	private int getStreamVersion(final int streamId) {
		return this.streamVersion[streamId];
	}

	private void setStreamVersion(final int streamId, final byte value) {
		this.streamVersion[streamId] = value;
	}

	private int[] analyzeSectors() {
		final int lastUsedSector = this.sectorUsed.length() - 1;
		final int gapSectors = lastUsedSector - this.sectorUsed.cardinality() + 1;
		return new int[] { this.sectorsInFile, lastUsedSector, gapSectors };
	}

	public void close() throws Exception {
		final int[] result = analyzeSectors();
		logger.debug(String.format("'%s': total sectors %d, last used sector %d, gaps %d (%d%%)", name(), result[0],
				result[1], result[2], result[2] * 100 / result[1]));
		if (this.channel != null) {
			preRead.invalidateAll();
			this.mapped.force();
			this.channel.force(true);
			this.channel.close();
			this.channel = null;
			freeMemoryMap(this.mapped);
		}
	}

	@Override
	public String toString() {
		return name();
	}
}
