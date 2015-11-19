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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

/**
 * Replaces Minecraft's RegionFileCache. It improves on Minecraft's
 * implementation in the following ways:
 * 
 * + Use a loading cache that provides better concurrency support.
 *
 * + Policy set to evict entries based on when they were last accessed.
 * 
 * + Key into the cache is no longer a computed String. Makes use of the fact
 * that the save directory can be considered immutable, and the coordinates are
 * primitives.
 * 
 * + No cache size limit. The number of entries in the cache will float based on
 * demand and expiration policy.
 */
public class RegionFileCache {

	private static final Logger logger = LogManager.getLogger("RegionFileCache");

	// Looks like Minecraft/Forge does a big flush every
	// 45 seconds or so when standing there. The expiration time is a bit
	// longer than that in case of tardiness for some reason. Note that
	// the expiration is based on the time of last access (read or write).
	private static final int EXPIRATION_TIME = 3;
	private static final TimeUnit EXPIRATION_UNIT = TimeUnit.MINUTES;

	// Number of entries to initialize the cache with
	private static final int INITIAL_CAPACITY = 64;
	
	// Higher the concurrency number, the more parallel the cache can be.
	// Use 8 because ThreadedFileIOBase, ChunkIOExecutor, and the Server
	// thread total about 8.
	private static final int CONCURRENCY = 8;

	// Key into the cache. Makes use of the fact that the save directory
	// for the region file can be considered immutable, and the coordinates
	// are primitives. Most efficient if the save directory is an interned
	// string.
	private static final class RegionFileKey {

		public final int chunkX;
		public final int chunkZ;
		public final String dir;

		public RegionFileKey(final String dir, final int regionX, final int regionZ) {
			this.chunkX = regionX;
			this.chunkZ = regionZ;
			this.dir = dir;
		}

		@Override
		public int hashCode() {
			final int i = 1664525 * this.chunkX + 1013904223;
			final int j = 1664525 * (this.chunkZ ^ -559038737) + 1013904223;
			return i ^ j;
		}

		@Override
		public boolean equals(final Object anObj) {
			if (this == anObj)
				return true;

			final RegionFileKey obj = (RegionFileKey) anObj;
			return chunkX == obj.chunkX && chunkZ == obj.chunkZ && dir.equals(obj.dir);
		}

		@Override
		public String toString() {
			return new StringBuilder().append(dir).append('[').append(chunkX).append(',').append(chunkZ).append(']')
					.toString();
		}
	}

	// Used by the cache when an entry is not found when queried. It
	// will load the RegionFile from disk, or create if needed.
	private static final class RegionFileLoader extends CacheLoader<RegionFileKey, RegionFile> {

		@Override
		public RegionFile load(RegionFileKey key) throws Exception {
			final File file = new File(key.dir, "region");
			file.mkdirs();
			final File file1 = new File(file, new StringBuilder(64).append("r.").append(key.chunkX).append('.')
					.append(key.chunkZ).append(".mca").toString());
			logger.debug("Loading '" + key.toString() + "'");
			return new RegionFile(file1);
		}
	}

	// Routine used when a RegionFile is evicted from the cache. Current
	// cause of eviction is expiration. When this occurs the RegionFile
	// needs to be closed out.
	private static final class RegionFileEviction implements RemovalListener<RegionFileKey, RegionFile> {
		@Override
		public void onRemoval(RemovalNotification<RegionFileKey, RegionFile> notification) {
			try {
				logger.debug("Unloading '" + notification.getKey() + "'");
				notification.getValue().close();
			} catch (final Exception ex) {
				logger.error("Error unloading '" + notification.getKey() + "'", ex);
			}
		}
	}

	private static final LoadingCache<RegionFileKey, RegionFile> regionsByFilename = CacheBuilder.newBuilder()
			.expireAfterAccess(EXPIRATION_TIME, EXPIRATION_UNIT).removalListener(new RegionFileEviction())
			.initialCapacity(INITIAL_CAPACITY).concurrencyLevel(CONCURRENCY).build(new RegionFileLoader());

	@Deprecated
	public static RegionFile createOrLoadRegionFile(final File saveDir, final int blockX, final int blockZ)
			throws ExecutionException {
		return createOrLoadRegionFile(saveDir.getPath(), blockX, blockZ);
	}

	public static RegionFile createOrLoadRegionFile(final String saveDir, final int blockX, final int blockZ)
			throws ExecutionException {
		return regionsByFilename.get(new RegionFileKey(saveDir, blockX >> 5, blockZ >> 5));
	}

	public static void clearRegionFileReferences() {
		// Evicts all current entries. During the process
		// of eviction the onRemoval() callback is made and the
		// files are closed.
		regionsByFilename.invalidateAll();
	}

	@Deprecated
	public static DataInputStream getChunkInputStream(final File saveDir, final int blockX, final int blockZ)
			throws Exception {
		return getChunkInputStream(saveDir.getPath(), blockX, blockZ);
	}

	public static DataInputStream getChunkInputStream(final String saveDir, final int blockX, final int blockZ)
			throws Exception {
		final RegionFile regionfile = createOrLoadRegionFile(saveDir, blockX, blockZ);
		return regionfile.getChunkDataInputStream(blockX & 31, blockZ & 31);
	}

	@Deprecated
	public static DataOutputStream getChunkOutputStream(final File saveDir, final int blockX, final int blockZ)
			throws ExecutionException {
		return getChunkOutputStream(saveDir.getPath(), blockX, blockZ);
	}

	public static DataOutputStream getChunkOutputStream(final String saveDir, final int blockX, final int blockZ)
			throws ExecutionException {
		final RegionFile regionfile = createOrLoadRegionFile(saveDir, blockX, blockZ);
		return regionfile.getChunkDataOutputStream(blockX & 31, blockZ & 31);
	}
}
