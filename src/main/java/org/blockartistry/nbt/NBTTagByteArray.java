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

public class NBTTagByteArray extends NBTBase {

	public static final byte[] EMPTY = new byte[0];
	
	private byte[] byteArray;

	NBTTagByteArray() {
	}

	public NBTTagByteArray(final byte[] array) {
		byteArray = array;
	}

	@Override
	void write(final DataOutput stream) throws IOException {
		stream.writeInt(this.byteArray.length);
		stream.write(this.byteArray);
	}

	@Override
	void func_152446_a(final DataInput stream, final int depth, final NBTSizeTracker tracker) throws IOException {
		tracker.func_152450_a(32L);
		int j = stream.readInt();
		tracker.func_152450_a(8 * j);
		this.byteArray = new byte[j];
		stream.readFully(this.byteArray);
	}

	public byte getId() {
		return NBT.TAG_BYTE_ARRAY;
	}

	public String toString() {
		return "[" + this.byteArray.length + " bytes]";
	}
	
	public void freeze() {
		final byte[] newArray = new byte[byteArray.length];
		System.arraycopy(byteArray, 0, newArray, 0, byteArray.length);
		byteArray = newArray;
	}

	public NBTBase copy() {
		final byte[] newArray = new byte[byteArray.length];
		System.arraycopy(byteArray, 0, newArray, 0, byteArray.length);
		return new NBTTagByteArray(newArray);
	}

	public boolean equals(final Object tag) {
		return super.equals(tag) && Arrays.equals(this.byteArray, ((NBTTagByteArray) tag).byteArray);
	}

	public int hashCode() {
		return super.hashCode() ^ Arrays.hashCode(this.byteArray);
	}

	public byte[] func_150292_c() {
		return this.byteArray;
	}
}
