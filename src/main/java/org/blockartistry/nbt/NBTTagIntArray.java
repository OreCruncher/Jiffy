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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

import net.minecraftforge.common.util.Constants.NBT;

public class NBTTagIntArray extends NBTBase {

	public final static int[] EMPTY = new int[0];
	
	private int[] intArray;

	NBTTagIntArray() {
	}

	public NBTTagIntArray(final int[] array) {
		intArray = array;
	}

	@Override
	void write(final DataOutput stream) throws IOException {
		stream.writeInt(this.intArray.length);
		for (int i = 0; i < this.intArray.length; i++) {
			stream.writeInt(this.intArray[i]);
		}
	}

	@Override
	void func_152446_a(final DataInput stream, final int depth, final NBTSizeTracker tracker) throws IOException {
		tracker.func_152450_a(32L);
		final int j = stream.readInt();
		tracker.func_152450_a(32 * j);
		this.intArray = new int[j];
		for (int k = 0; k < j; k++) {
			this.intArray[k] = stream.readInt();
		}
	}

	public byte getId() {
		return NBT.TAG_INT_ARRAY;
	}

	public String toString() {
		final StringBuilder builder = new StringBuilder(128);
		builder.append('[');

		for (int j = 0; j < intArray.length; j++)
			builder.append(intArray[j]).append(',');

		return builder.append(']').toString();
	}

	@Override
	public void freeze() {
		final int[] newArray = new int[intArray.length];
		System.arraycopy(intArray, 0, newArray, 0, intArray.length);
		intArray = newArray;
	}
	
	public NBTBase copy() {
		final int[] newArray = new int[intArray.length];
		System.arraycopy(intArray, 0, newArray, 0, intArray.length);
		return new NBTTagIntArray(newArray);
	}

	public boolean equals(final Object tag) {
		return super.equals(tag) && Arrays.equals(this.intArray, ((NBTTagIntArray) tag).intArray);
	}

	public int hashCode() {
		return super.hashCode() ^ Arrays.hashCode(this.intArray);
	}

	public int[] func_150302_c() {
		return this.intArray;
	}
}
