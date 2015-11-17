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
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

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
 */
public final class RegionFile {

	private static final Logger logger = LogManager.getLogger("RegionFile");

	private final static int INT_SIZE = 4;
	private final static int BYTE_SIZE = 1;
	public final static int CHUNK_STREAM_HEADER_SIZE = INT_SIZE + BYTE_SIZE;
	public final static int SECTOR_SIZE = 4096;
	private final static int MAX_SECTORS_PER_CHUNK_STREAM = 255;
	public final static int MIN_SECTORS_PER_CHUNK_STREAM = 2;
	private final static int SECTOR_COUNT_MASK = MAX_SECTORS_PER_CHUNK_STREAM;
	private final static int SECTOR_NUMBER_SHIFT = 8;
	private final static int REGION_CHUNK_DIMENSION = 32;
	private final static int CHUNKS_IN_REGION = REGION_CHUNK_DIMENSION * REGION_CHUNK_DIMENSION;
	private final static int EXTEND_SECTOR_QUANTITY = MIN_SECTORS_PER_CHUNK_STREAM * 128;
	private final static byte[] EMPTY_SECTOR = new byte[SECTOR_SIZE];

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
	private AsynchronousFileChannel channel;
	private BitSet sectorUsed;
	private int sectorsInFile;
	private byte[] controlSectors;
	private IntBuffer controlCache;
	private boolean dirtyControl;
	private byte[] compressionVersion = new byte[CHUNKS_IN_REGION];

	// Used to handle flushing of the control region
	// when used concurrently.
	private AtomicInteger outstandingWrites = new AtomicInteger();
	
	private static int getChunkStreamId(final int regionX, final int regionZ) {
		return regionX + regionZ * REGION_CHUNK_DIMENSION;
	}
	
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
	/**
	 * Listener that receives notification when a RegionFile is evicted from the
	 * cache. It ensures that the RegionFile is properly closed out.
	 */
	private final static long EXPIRY_TIME = 10;

	private static final class ChunkStreamEviction implements RemovalListener<Integer, ChunkInputStream> {
		@Override
		public void onRemoval(RemovalNotification<Integer, ChunkInputStream> notification) {
			final ChunkInputStream stream = notification.getValue();
			if (!stream.isBaked())
				try {
					ChunkInputStream.returnStream(stream);
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
			this.channel = AsynchronousFileChannel.open(regionFile.toPath(), StandardOpenOption.READ,
					StandardOpenOption.WRITE, StandardOpenOption.CREATE);
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

			this.controlSectors = readSectors(0, 2, null);
			this.controlCache = ByteBuffer.wrap(controlSectors).asIntBuffer();

			if (!needsInit) {
				for (int j = 0; j < CHUNKS_IN_REGION * 2; j++) {
					final int streamInfo = this.controlCache.get(j);
					if (streamInfo == 0 || j >= CHUNKS_IN_REGION)
						continue;

					final int sectorNumber = streamInfo >> SECTOR_NUMBER_SHIFT;
					final int numberOfSectors = streamInfo & SECTOR_COUNT_MASK;
					if (sectorNumber + numberOfSectors <= this.sectorsInFile)
						this.sectorUsed.set(sectorNumber, sectorNumber + numberOfSectors);
				}
			}
		} catch (final Exception e) {
			e.printStackTrace();
		}
	}

	private void writeEmptySectors(final int sectorNumber, final int count) throws Exception {
		for (int i = 0; i < count; i++)
			writeSectors(sectorNumber + i, EMPTY_SECTOR, SECTOR_SIZE);
		this.sectorsInFile = sectorCount();
	}

	private byte[] readSectors(final int sectorNumber, final int count, byte[] buffer)
			throws IOException, InterruptedException, ExecutionException {
		final int dataPosition = sectorNumber * SECTOR_SIZE;
		final int dataLength = count * SECTOR_SIZE;
		if (buffer == null || buffer.length < dataLength)
			buffer = new byte[dataLength];
		final Future<Integer> bytesRead = channel.read(ByteBuffer.wrap(buffer, 0, dataLength), dataPosition);
		if (bytesRead.get().intValue() != dataLength)
			logger.error("Incorrect bytes read: " + bytesRead + ", expected " + dataLength);

		return buffer;
	}

	private void writeSectors(final int sectorNumber, final byte[] buffer, final int length)
			throws IOException, InterruptedException, ExecutionException {
		final Future<Integer> bytesWritten = channel.write(ByteBuffer.wrap(buffer, 0, length),
				sectorNumber * SECTOR_SIZE);
		if (bytesWritten.get().intValue() != length)
			logger.error("Incorrect bytes written: " + bytesWritten + ", expected " + length);
	}

	private boolean isValidFileRegion(final int sector, final int count) {
		return sector != 0 && sector != 1
				&& (sector + count - 1) <= this.sectorUsed.previousSetBit(this.sectorUsed.length());
	}

	private int sectorCount() throws Exception {
		return (int) (this.channel.size() / SECTOR_SIZE);
	}

	public String name() {
		return this.name;
	}

	public boolean chunkExists(final int regionX, final int regionZ) {

		final int[] info = this.getChunkInformation(regionX, regionZ);
		if (info == INVALID || info == NO_CHUNK_INFORMATION)
			return false;

		if (info[STREAM_VERSION] != STREAM_VERSION_UNKNOWN)
			return true;

		try {

			// Read the chunk stream and cache. If it loads it is
			// a recognized stream version. Otherwise we don't know
			// about it.
			final DataInputStream stream = (ChunkInputStream) getChunkDataInputStream(regionX, regionZ);
			if (stream instanceof ChunkInputStream)
				preRead.put(getChunkStreamId(regionX, regionZ), (ChunkInputStream) stream);

			return stream != null;

		} catch (final Exception e) {
			e.printStackTrace();
		}

		return false;
	}

	public DataInputStream getChunkDataInputStream(final int regionX, final int regionZ) {
		final int[] info = getChunkInformation(regionX, regionZ);
		if (info == NO_CHUNK_INFORMATION || info == INVALID)
			return null;

		final int sectorNumber = info[SECTOR_START];
		final int numberOfSectors = info[SECTOR_COUNT];

		try {

			// Check the pre-read cache first to see if it is there.
			final int streamId = getChunkStreamId(regionX, regionZ);
			ChunkInputStream stream = preRead.getIfPresent(streamId);
			if (stream != null) {
				stream.bake();
				preRead.invalidate(streamId);
				return stream;
			}

			// Looks like we have to do a read
			stream = ChunkInputStream.getStream();
			final int dataLength = numberOfSectors * SECTOR_SIZE;
			final byte[] buffer = stream.getBuffer(dataLength);

			synchronized (this) {
				if (!isValidFileRegion(sectorNumber, numberOfSectors)) {
					logger.error(String.format("'%s' returning null (%d, %d)", name, sectorNumber, numberOfSectors));
					ChunkInputStream.returnStream(stream);
					return null;
				}
			}

			try {
				lockChunk(streamId);
				readSectors(sectorNumber, numberOfSectors, buffer);
			} finally {
				unlockChunk(streamId);
			}

			final int streamLength = getInt(buffer);

			if (streamLength <= 0 || streamLength > dataLength) {
				logger.error(name + " " + regionX + " " + regionZ + " streamLength (" + streamLength + ") return null");
				ChunkInputStream.returnStream(stream);
				return null;
			}

			// Pass back an appropriate stream for the requester
			switch (buffer[4]) {
			case STREAM_VERSION_FLATION:
				setCompressionVersion(streamId, STREAM_VERSION_FLATION);
				return stream.bake();
			case STREAM_VERSION_GZIP:
				// Older MC version - going to be upgraded next write. Can't use
				// the ChunkInputStream for this.
				final InputStream is = new ByteArrayInputStream(Arrays.copyOf(buffer, buffer.length),
						CHUNK_STREAM_HEADER_SIZE, dataLength);
				setCompressionVersion(streamId, STREAM_VERSION_GZIP);
				ChunkInputStream.returnStream(stream);
				return new DataInputStream(new GZIPInputStream(is));
			default:
				;
			}
		} catch (final Exception e) {
			e.printStackTrace();
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

	void write(final int regionX, final int regionZ, final byte[] buffer, final int length) {
		// Incoming buffer has header incorporated. Need to enforce the
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

		// Only need to worry about setting metadata if the chunk is new
		// or it changed sizes. Otherwise it will be rewriting the same
		// sectors so there is no contention.
		boolean setMetadata = sectorNumber == 0 || numberOfSectors != sectorsRequired;

		outstandingWrites.incrementAndGet();
		try {
			
			final int streamId = getChunkStreamId(regionX, regionZ);
			
			// Invalidate any cached data
			preRead.invalidate(streamId);

			if (setMetadata)
				synchronized (this) {
					// "Free" up the existing sectors
					if (sectorNumber != 0)
						this.sectorUsed.clear(sectorNumber, sectorNumber + numberOfSectors);

					// Find some free sectors to write on. If we can't find any
					// need to extend the file.
					sectorNumber = findContiguousSectors(sectorsRequired);
					if (this.sectorsInFile - sectorNumber < sectorsRequired)
						writeEmptySectors(sectorNumber, Math.max(sectorsRequired, EXTEND_SECTOR_QUANTITY));

					// Mark our sectors used and update the mapping
					this.sectorUsed.set(sectorNumber, sectorNumber + sectorsRequired);
					setChunkInformation(streamId, sectorNumber, sectorsRequired, STREAM_VERSION_FLATION);
					setChunkTimestamp(streamId, (int) (System.currentTimeMillis() / 1000L));
				}

			try {
				lockChunk(streamId);
				writeSectors(sectorNumber, buffer, length);
			} finally {
				unlockChunk(streamId);
			}

			if(outstandingWrites.decrementAndGet() == 0)
				flushControl();

		} catch(final IOException ex) {
			outstandingWrites.decrementAndGet();
			ex.printStackTrace();
		} catch (final Exception ex) {
			ex.printStackTrace();
		}
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
		return this.controlCache.get(getChunkStreamId(regionX, regionZ)) != 0;
	}

	private int[] getChunkInformation(final int regionX, final int regionZ) {
		if (outOfBounds(regionX, regionZ))
			return INVALID;

		int idx = getChunkStreamId(regionX, regionZ);
		final int streamInfo = this.controlCache.get(idx);
		if (streamInfo == 0)
			return NO_CHUNK_INFORMATION;

		final int sectorNumber = streamInfo >> SECTOR_NUMBER_SHIFT;
		final int numberOfSectors = streamInfo & SECTOR_COUNT_MASK;
		return new int[] { sectorNumber, numberOfSectors, this.compressionVersion[idx] };
	}

	private void flushControl() throws Exception {
		if (this.dirtyControl) {
			writeSectors(0, this.controlSectors, SECTOR_SIZE * 2);
			this.dirtyControl = false;
		}
	}

	private void setChunkInformation(final int streamId, final int sectorNumber,
			final int sectorCount, final byte streamVersion) throws IOException {
		final int info = sectorNumber << SECTOR_NUMBER_SHIFT | sectorCount;
		if (info != this.controlCache.get(streamId)) {
			this.controlCache.put(streamId, info);
			this.dirtyControl = true;
		}
		this.compressionVersion[streamId] = streamVersion;
	}

	private void setChunkTimestamp(final int streamId, final int value) throws IOException {
		if (value != this.controlCache.get(streamId + CHUNKS_IN_REGION)) {
			this.controlCache.put(streamId, value);
			this.dirtyControl = true;
		}
	}

	private void setCompressionVersion(final int streamId, final byte value) {
		this.compressionVersion[streamId] = value;
	}

	@SuppressWarnings("unused")
	private int[] analyzeSectors() {
		final int lastUsedSector = this.sectorUsed.previousSetBit(this.sectorUsed.length());
		int gapSectors = 0;
		for (int i = 0; i < lastUsedSector; i++)
			if (!this.sectorUsed.get(i))
				gapSectors++;
		return new int[] { this.sectorsInFile, lastUsedSector, gapSectors };
	}

	public void close() throws Exception {
		// final int[] result = analyzeSectors();
		// logger.info(String.format("'%s': sectors %d, last sector %d, gaps %d
		// (%d%%)", this.name, result[0], result[1],
		// result[2], result[2] * 100 / result[1]));
		if (this.channel != null) {
			preRead.invalidateAll();
			flushControl();
			this.channel.close();
			this.channel = null;
		}
	}

	@Override
	public String toString() {
		return this.name;
	}
}
