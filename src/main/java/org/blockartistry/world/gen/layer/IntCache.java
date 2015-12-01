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

package org.blockartistry.world.gen.layer;

/**
 * Disabling caching allows for about 13% better performance of
 * BiomeCache block initialization.  The overhead of maintaining
 * the allocation lists is more expensive than what was trying to
 * be saved.  Bottom line is to stay out of the way of the heap
 * manager and GC because it is more efficient.
 * 
 * NOTE: This caching may benefit less capable systems.  But
 * keep in mind this core mod is targeting server systems and
 * not clients.  Tests were performed on an i7 using Java 8.
 */
public class IntCache {
	public static int[] getIntCache(final int requestSize) {
		return new int[requestSize];
	}

	public static void resetIntCache() {
	}

	public static String getCacheSizes() {
		return "Integer array caching disabled";
	}
}