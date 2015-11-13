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

public final class RegionFileKey {

	private final int chunkX;
	private final int chunkZ;
	private final String dir;

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
