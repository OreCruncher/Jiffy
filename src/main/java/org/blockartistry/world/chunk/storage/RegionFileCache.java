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
 * + Loading cache will evict oldest entry when it becomes full, or will evict
 * after a specified time period.
 * 
 */
public class RegionFileCache {

	private static final Logger logger = LogManager.getLogger("RegionFileCache");

	private static final int CACHE_SIZE = 256;
	
	// In minutes.  Looks like Minecraft/Forge does a big flush every
	// 4 minutes or so when standing there.  The exipry time is a bit
	// longer than that in case of tardiness for some reason.
	private static final int EXPIRY_TIME = 6;

	/**
	 * Key object for indexing into the cache.  Keeps components as native
	 * as possible to speed up comparisons.
	 */
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
			return dir + " [" + chunkX + ", " + chunkZ + "]";
		}
	}

	/**
	 * Routine that is called when there is a cache miss in order to
	 * initialize the value for the given key.
	 */
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

	/**
	 * Listener that receives notification when a RegionFile is evicted from the
	 * cache. It ensures that the RegionFile is properly closed out.
	 */
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
			.maximumSize(CACHE_SIZE).expireAfterAccess(EXPIRY_TIME, TimeUnit.MINUTES)
			.removalListener(new RegionFileEviction()).build(new RegionFileLoader());

	public static RegionFile createOrLoadRegionFile(final File saveDir, final int blockX, final int blockZ)
			throws ExecutionException {
		return createOrLoadRegionFile(saveDir.getPath(), blockX, blockZ);
	}

	public static RegionFile createOrLoadRegionFile(final String saveDir, final int blockX, final int blockZ)
			throws ExecutionException {
		// Just use the path provided for the save dir. Since save directories
		// are unique it serves the purpose. Goal is to minimize the amount of
		// computation creating the key and the amount of time it spends
		// evaluating for a match in the underlying cache.
		final RegionFileKey key = new RegionFileKey(saveDir, blockX >> 5, blockZ >> 5);
		return regionsByFilename.get(key);
	}

	public static void clearRegionFileReferences() {
		// Evicts all current entries.  During the process
		// of eviction the unload callback is made and the
		// files are closed.
		regionsByFilename.invalidateAll();
	}

	public static DataInputStream getChunkInputStream(final File saveDir, final int blockX, final int blockZ)
			throws Exception {
		return getChunkInputStream(saveDir.getPath(), blockX, blockZ);
	}

	public static DataInputStream getChunkInputStream(final String saveDir, final int blockX, final int blockZ)
			throws Exception {
		final RegionFile regionfile = createOrLoadRegionFile(saveDir, blockX, blockZ);
		return regionfile.getChunkDataInputStream(blockX & 31, blockZ & 31);
	}

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
