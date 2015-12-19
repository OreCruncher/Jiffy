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

package org.blockartistry.util;

/**
 * Replacement algos for SIN_TABLE/cos in Minecraft's MathHelper routines. Use
 * the Riven method:
 * 
 * http://riven8192.blogspot.com/2009/08/fastmath-sincos-lookup-tables.html
 */
public class MathHelper {

	// Increasing this number increases precision, but increases
	// the size of the lookup table.  12 bits means 4K entries.
	// This size seems to do nicely.  Can probably do smaller, but
	// what would be the point?
	private static final int SIN_BITS = 12;
	private static final int SIN_MASK = ~(-1 << SIN_BITS);
	private static final int SIN_COUNT = SIN_MASK + 1;
	private static final float RAD_FULL = (float) (Math.PI * 2.0);
	private static final float RAD_TO_INDEX = SIN_COUNT / RAD_FULL;
	private static final float DEG_FULL = (float) (360.0);
	private static final float DEG_TO_INDEX = SIN_COUNT / DEG_FULL;
	private final static float COS_TO_SIN = (float) (Math.PI / 2.0);
	private static final float[] SIN_TABLE = new float[SIN_COUNT]; // , cos;

	static {

		for (int i = 0; i < SIN_COUNT; i++) {
			SIN_TABLE[i] = (float) Math.sin((i + 0.5f) / SIN_COUNT * RAD_FULL);
		}

		// Fix-up cardinals
		for (int i = 0; i < 360; i += 90) {
			SIN_TABLE[(int) (i * DEG_TO_INDEX) & SIN_MASK] = (float) Math.sin(i * Math.PI / 180.0);
		}
	}

	public static final float sin(final float rad) {
		return SIN_TABLE[(int) (rad * RAD_TO_INDEX) & SIN_MASK];
	}
	
	public static final float cos(final float rad) {
		return SIN_TABLE[(int) ((rad + COS_TO_SIN) * RAD_TO_INDEX) & SIN_MASK];
	}
	
	public static final double sin(final double rad) {
		final float tmp = (float)rad;
		return SIN_TABLE[(int) (tmp * RAD_TO_INDEX) & SIN_MASK];
	}

	public static final double cos(final double rad) {
		final float tmp = (float)rad;
		return SIN_TABLE[(int) ((tmp + COS_TO_SIN) * RAD_TO_INDEX) & SIN_MASK];
	}
	
	public static final double random() {
		return XorShiftRandom.shared.nextDouble();
	}
}
