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

package org.blockartistry.nbt;

/**
 * Improvements over the Vanilla NBTSizeTracker:
 * 
 * + The static readUTF() no longer performs calculations
 * if the size tracker is noop.  Prior it would do the
 * calculation and throw the results away.
 *
 */
public class NBTSizeTracker {
	
	public static final NBTSizeTracker field_152451_a = new NBTSizeTracker();
	
	private final boolean isNull;
	private final long field_152452_b;
	private long field_152453_c;
	
	private NBTSizeTracker() {
		field_152452_b = 0;
		isNull = true;
	}
	
	public NBTSizeTracker(long p_i1203_1_) {
		field_152452_b = p_i1203_1_;
		isNull = false;
	}

	public boolean isNoop() {
		return isNull;
	}
	
	public void func_152450_a(long p_152450_1_) {
		if(isNull)
			return;
		
		field_152453_c += p_152450_1_ / 8L;
		if (field_152453_c > field_152452_b) {
			throw new RuntimeException("Tried to read NBT tag that was too big; tried to allocate: "
					+ field_152453_c + "bytes where max allowed: " + field_152452_b);
		}
	}

	public static void readUTF(NBTSizeTracker tracker, String data) {
		
		if(tracker.isNoop())
			return;
		
		tracker.func_152450_a(16L);
		if (data == null) {
			return;
		}
		int len = data.length();
		int utflen = 0;
		for (int i = 0; i < len; i++) {
			int c = data.charAt(i);
			if ((c >= 1) && (c <= 127)) {
				utflen++;
			} else if (c > 2047) {
				utflen += 3;
			} else {
				utflen += 2;
			}
		}
		tracker.func_152450_a(8 * utflen);
	}
}
