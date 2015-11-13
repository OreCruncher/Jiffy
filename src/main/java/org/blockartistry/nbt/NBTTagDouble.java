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

import net.minecraft.util.MathHelper;
import net.minecraftforge.common.util.Constants.NBT;

public class NBTTagDouble extends NBTPrimitive {

	private double data;

	NBTTagDouble() {
	}

	public NBTTagDouble(final double value) {
		this.data = value;
	}

	@Override
	void write(final DataOutput stream) throws IOException {
		stream.writeDouble(this.data);
	}

	@Override
	void func_152446_a(final DataInput stream, final int depth, final NBTSizeTracker tracker) throws IOException {
		tracker.func_152450_a(64L);
		this.data = stream.readDouble();
	}

	public byte getId() {
		return NBT.TAG_DOUBLE;
	}

	public String toString() {
		return "" + this.data + "d";
	}

	public NBTBase copy() {
		return new NBTTagDouble(this.data);
	}

	public boolean equals(final Object tag) {
		return super.equals(tag) && this.data == ((NBTTagDouble) tag).data;
	}

	public int hashCode() {
		final long l = Double.doubleToLongBits(this.data);
		return super.hashCode() ^ (int) (l ^ l >>> 32);
	}

	public long func_150291_c() {
		return (long) Math.floor(this.data);
	}

	public int func_150287_d() {
		return MathHelper.floor_double(this.data);
	}

	public short func_150289_e() {
		return (short) (MathHelper.floor_double(this.data) & 0xFFFF);
	}

	public byte func_150290_f() {
		return (byte) (MathHelper.floor_double(this.data) & 0xFF);
	}

	public double func_150286_g() {
		return this.data;
	}

	public float func_150288_h() {
		return (float) this.data;
	}
}
