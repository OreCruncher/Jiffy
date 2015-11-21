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

package org.blockartistry.world;

import com.google.common.primitives.Longs;

import net.minecraft.world.ChunkPosition;

public class ChunkCoordIntPair {
	
	/** The X position of this Chunk Coordinate Pair */
	public final int chunkXPos;
	/** The Z position of this Chunk Coordinate Pair */
	public final int chunkZPos;
	@SuppressWarnings("unused")
	private static final String __OBFID = "CL_00000133";

	private final long cantor;

	public ChunkCoordIntPair(final int chunkX, final int chunkZ) {
		this.chunkXPos = chunkX;
		this.chunkZPos = chunkZ;
		this.cantor = chunkXZ2Int(chunkX, chunkZ);
	}

	/**
	 * converts a chunk coordinate pair to an long (suitable for hashing)
	 * Uses the Cantor Pairing method to generate the result.
	 */
	private final static long BASE = 31000000 >> 4;
	public static long chunkXZ2Int(final int chunkX, final int chunkZ) {
		// Takes advantage of the fact that coords are really bounded at
		// 30,000,000 blocks.  It is used to offset the coordinate values
		// to make them positive so that the Cantor will work.
		final long x = (long)chunkX + BASE;
		final long z = (long)chunkZ + BASE;
		return ((x + z) * (x + z + 1)) / 2 + z;
	}

	public long cantor() {
		return this.cantor;
	}
	
	public int hashCode() {
		return Longs.hashCode(this.cantor);
	}

	public boolean equals(final Object anObject) {
		if(this == anObject)
			return true;
		
		if(!(anObject instanceof ChunkCoordIntPair))
			return false;

		return this.cantor == ((ChunkCoordIntPair)anObject).cantor;
	}

	public int getCenterXPos() {
		return (this.chunkXPos << 4) + 8;
	}

	public int getCenterZPosition() {
		return (this.chunkZPos << 4) + 8;
	}

	public ChunkPosition func_151349_a(int p_151349_1_) {
		return new ChunkPosition(this.getCenterXPos(), p_151349_1_, this.getCenterZPosition());
	}

	public String toString() {
		return "[" + this.chunkXPos + ", " + this.chunkZPos + "]";
	}
}