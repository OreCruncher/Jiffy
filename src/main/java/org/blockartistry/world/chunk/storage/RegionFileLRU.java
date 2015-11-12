/* This file is part of Restructured, licensed under the MIT License (MIT).
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

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.world.ChunkCoordIntPair;

/**
 * Implements a cache of RegionFiles that evicts oldest RegionFile from the
 * cache when it's size is exceeded.  It improves on the Vanilla approach:
 * 
 * + Uses eviction to remove least used items rather than dumping the entire
 * region cache when it gets full.
 * 
 * + Parameters of eviction are eldest item if the cache is full, or the
 * eldest region is considered idle.  The latter check occurs whenever a new
 * region file is added to the cache.
 * 
 * + The key into the cache is not a computed string.  Vanilla version
 * constantly recalculates a key whenever a cache query is made.  This version
 * uses a key object that uses the X,Z integer primitives for comparison.  Also,
 * the string portion of the key is made up of a stable string meaning that it
 * can hit an reference equivalence match.
 */
@SuppressWarnings("serial")
public class RegionFileLRU extends LinkedHashMap<RegionFileLRU.RegionFileKey, RegionFile> {

	private static final Logger logger = LogManager.getLogger();

	// Key class for the LRU.  Technically the key is not a ChunkCoordIntPair so from
	// an OOP design standpoint is a no no.  But, in this case base class does have
	// useful functionality for the key thus it is used.
	public static class RegionFileKey extends ChunkCoordIntPair {
		
		private final String dir;
		
		public RegionFileKey(final String dir, final int regionX, final int regionZ) {
			super(regionX, regionZ);
			this.dir = dir;
		}
		
		@Override
		public boolean equals(final Object anObj) {
			// Compare the coordinates before the string.  Usually cheaper
			// to do the primitive compares than the string comparison.
			return super.equals(anObj) && dir.equals(((RegionFileKey)anObj).dir);
		}
	}
	
	private final int cacheSize;
	
	public RegionFileLRU(final int cacheSize, final float hashtableLoadFactor) {
		super((int) Math.ceil(cacheSize / hashtableLoadFactor) + 1, hashtableLoadFactor, true);
		
		this.cacheSize = cacheSize;
	}

	@Override
	protected boolean removeEldestEntry(final Map.Entry<RegionFileKey, RegionFile> eldest) {

		final RegionFile rf = eldest.getValue();

		// If we have space in the cache and the file is not
		// idle, keep.
		if (size() <= cacheSize && !rf.isIdle())
			return false;

		// Make sure the file is closed
		try {
			rf.close();
		} catch (IOException e) {
			logger.error("Unable to close region file '" + eldest.getKey() + "'", e);
		}

		// Remove it
		return true;
	}
}
