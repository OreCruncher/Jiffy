/*
 * This file is part of Jiffy, licensed under the MIT License (MIT).
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

package org.blockartistry.world.biome;

import java.util.function.Predicate;

import org.blockartistry.util.LongHashMap;

import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;

/**
 * Changes:
 * 
 * + Use removeAll() with predicate to remove entries from the map rather than
 * maintaining a separate list.
 * 
 * + Bumped the clean interval from 7.5 seconds to 10 seconds.
 * 
 * + Use ChunkCoordIntPair.chunkXZ2Int() rather than private method of encoding.
 *
 */
public class BiomeCache {

	// Time in nanoseconds.  Immune to system time changes, DST, etc.
	private final static long CLEAN_INTERVAL = 10000000000L; // 10 seconds
	private final static long EXPIRE_THRESHOLD = 30000000000L; // 30 seconds

	/** Reference to the WorldChunkManager */
	private final WorldChunkManager chunkManager;
	/** The last time this BiomeCache was cleaned, in nanoseconds. */
	private long lastCleanupTime;
	/**
	 * The map of keys to BiomeCacheBlocks. Keys are based on the chunk x, z
	 * coordinates as (x | z << 32).
	 */
	private LongHashMap cacheMap = new LongHashMap();

	public BiomeCache(final WorldChunkManager manager) {
		this.chunkManager = manager;
		this.lastCleanupTime = System.nanoTime();
	}

	/**
	 * Returns a biome cache block at location specified.
	 */
	public BiomeCache.Block getBiomeCacheBlock(int blockX, int blockZ) {
		blockX >>= 4;
		blockZ >>= 4;
		final long k = ChunkCoordIntPair.chunkXZ2Int(blockX, blockZ);
		BiomeCache.Block block = (BiomeCache.Block) this.cacheMap.getValueByKey(k);

		if (block == null) {
			block = new BiomeCache.Block(blockX, blockZ);
			this.cacheMap.add(k, block);
		}
		
		block.lastAccessTime = System.nanoTime();
		return block;
	}

	/**
	 * Returns the BiomeGenBase related to the x, z position from the cache.
	 */
	public BiomeGenBase getBiomeGenAt(final int blockX, final int blockZ) {
		return this.getBiomeCacheBlock(blockX, blockZ).getBiomeGenAt(blockX, blockZ);
	}

	/**
	 * Removes BiomeCacheBlocks from this cache that haven't been accessed in at
	 * least 30 seconds.
	 */
	public void cleanupCache() {
		final long i = System.nanoTime();
		if ((i - this.lastCleanupTime) > CLEAN_INTERVAL) {
			this.lastCleanupTime = i;
			this.cacheMap.removeAll(new RemoveOldEntries(i));
		}
	}

	/**
	 * Returns the array of cached biome types in the BiomeCacheBlock at the
	 * given location.
	 */
	public BiomeGenBase[] getCachedBiomes(final int blockX, final int blockZ) {
		return this.getBiomeCacheBlock(blockX, blockZ).biomes;
	}

	private static class RemoveOldEntries implements Predicate<Block> {
		private final long timeMark;
		public RemoveOldEntries(final long mark) {
			this.timeMark = mark;
		}
		// Return true to cause a removal of the entry
		// in the map
		@Override
		public boolean test(final Block t) {
			return (timeMark - t.lastAccessTime) > EXPIRE_THRESHOLD;
		}
	}

	public class Block {
		/** An array of chunk rainfall values saved by this cache. */
		public float[] rainfallValues = new float[256];
		/** The array of biome types stored in this BiomeCacheBlock. */
		public BiomeGenBase[] biomes = new BiomeGenBase[256];
		/** The x coordinate of the BiomeCacheBlock. */
		public int xPosition;
		/** The z coordinate of the BiomeCacheBlock. */
		public int zPosition;
		/** The last time this BiomeCacheBlock was accessed, in milliseconds. */
		public long lastAccessTime;

		public Block(int p_i1972_2_, int p_i1972_3_) {
			this.xPosition = p_i1972_2_;
			this.zPosition = p_i1972_3_;
			BiomeCache.this.chunkManager.getRainfall(this.rainfallValues, p_i1972_2_ << 4, p_i1972_3_ << 4, 16, 16);
			BiomeCache.this.chunkManager.getBiomeGenAt(this.biomes, p_i1972_2_ << 4, p_i1972_3_ << 4, 16, 16, false);
		}

		/**
		 * Returns the BiomeGenBase related to the x, z position from the cache
		 * block.
		 */
		public BiomeGenBase getBiomeGenAt(int p_76885_1_, int p_76885_2_) {
			return this.biomes[p_76885_1_ & 15 | (p_76885_2_ & 15) << 4];
		}
	}
}