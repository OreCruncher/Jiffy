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
import java.io.IOException;

/**
 * Replaces Minecraft's RegionFileCache. It improves on Minecraft's
 * implementation in the following ways:
 * 
 * + The cache is LRU based. When the number of cached RegionFiles exceeds the
 * cache size the oldest access RegionFile is closed and evicted. Minecraft's
 * implementation purges the entire list when the cache size exceeded which in
 * turn forces a reload of RegionFiles.
 * 
 * + Locking of the region cache is more fine grained which in turn will reduce
 * contention between multiple threads.
 *
 */
public class RegionFileCache {
	
	private static final int CACHE_SIZE = 256;
	private static final float HASHTABLE_LOAD_FACTOR = 0.75f;
	
	private static final RegionFileLRU regionsByFilename = new RegionFileLRU(CACHE_SIZE, HASHTABLE_LOAD_FACTOR);

	public static RegionFile createOrLoadRegionFile(final File saveDir, final int blockX, final int blockZ) {
		return createOrLoadRegionFile(saveDir.getPath(), blockX, blockZ);
	}
	
	public static RegionFile createOrLoadRegionFile(final String saveDir, final int blockX, final int blockZ) {

		final int X = blockX >> 5;
		final int Z = blockZ >> 5;

		// Just use the path provided for the save dir.  Since save directories are unique it
		// serves the purpose.  Goal is to minimize the amount of computation creating the key
		// and the amount of time it spends evaluating for a match in the underlying cache.
		final RegionFileKey key = new RegionFileKey(saveDir, X, Z);

		synchronized (regionsByFilename) {
			RegionFile regionfile = regionsByFilename.get(key);
			if (regionfile == null) {
				final File file = new File(saveDir, "region");
				file.mkdirs();
				final File file1 = new File(file,
						new StringBuilder(64).append("r.").append(X).append('.').append(Z).append(".mca").toString());
				regionfile = new RegionFile(file1);
				regionsByFilename.put(key, regionfile);
			}
			return regionfile;
		}
	}

	public static void clearRegionFileReferences() {
		synchronized (regionsByFilename) {

			for (final RegionFile rf : regionsByFilename.values()) {
				try {
					rf.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}

			regionsByFilename.clear();
		}
	}

	public static DataInputStream getChunkInputStream(final File saveDir, final int blockX, final int blockZ) {
		return getChunkInputStream(saveDir.getPath(), blockX, blockZ);
	}
	
	public static DataInputStream getChunkInputStream(final String saveDir, final int blockX, final int blockZ) {
		final RegionFile regionfile = createOrLoadRegionFile(saveDir, blockX, blockZ);
		return regionfile.getChunkDataInputStream(blockX & 31, blockZ & 31);
	}

	public static DataOutputStream getChunkOutputStream(final File saveDir, final int blockX, final int blockZ) {
		return getChunkOutputStream(saveDir.getPath(), blockX, blockZ);
	}
	
	public static DataOutputStream getChunkOutputStream(final String saveDir, final int blockX, final int blockZ) {
		final RegionFile regionfile = createOrLoadRegionFile(saveDir, blockX, blockZ);
		return regionfile.getChunkDataOutputStream(blockX & 31, blockZ & 31);
	}
}
