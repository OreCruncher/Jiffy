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

/**
 * Replaces Vanilla's NBTTagList and improves on it with the following:
 * 
 * + Backing store is a single array of tags.
 * 
 */
public class NBTTagList extends NBTBase {

	private static final int SIZE_INCREMENT = 8;

	private NBTBase[] list = EMPTY_ELEMENTDATA;
	private int newIndex = 0;
	byte tagType = NBT.TAG_END;

	@Override
	void write(final DataOutput stream) throws IOException {

		if (newIndex == 0)
			tagType = NBT.TAG_END;
		else
			tagType = list[0].getId();

		stream.writeByte(tagType);
		stream.writeInt(newIndex);

		for (int i = 0; i < newIndex; i++)
			list[i].write(stream);
	}

	private void ensureCapacity(final int newObjects) {
		final int targetLength = newIndex + newObjects;
		if (targetLength <= list.length)
			return;

		int newLength = Math.max(list.length, SIZE_INCREMENT);
		for (; newLength < targetLength; newLength <<= 1)
			;
		
		final NBTBase[] newArray = new NBTBase[newLength];
		if(list != EMPTY_ELEMENTDATA)
			System.arraycopy(list, 0, newArray, 0, list.length);
		list = newArray;
	}
	
	private void directAllocate(final int newObjects) {
		list = (newObjects == 0) ? EMPTY_ELEMENTDATA : new NBTBase[newObjects];
		newIndex = 0;
	}

	@Override
	void func_152446_a(final DataInput stream, final int depth, final NBTSizeTracker tracker) throws IOException {
		complexityCheck(depth);

		tracker.func_152450_a(8L);
		this.tagType = stream.readByte();
		tracker.func_152450_a(32L);
		final int totalObjs = stream.readInt();

		directAllocate(totalObjs);
	
		for (int i = 0; i < totalObjs; i++) {
			tracker.func_152450_a(32L);
			NBTBase nbtbase = NBTFactory.getTag(this.tagType);
			nbtbase.func_152446_a(stream, depth + 1, tracker);
			appendTag(nbtbase);
		}
	}

	public byte getId() {
		return NBT.TAG_LIST;
	}

	public String toString() {
		final StringBuilder builder = new StringBuilder(128);
		builder.append('[');

		for (int i = 0; i < newIndex; i++)
			if (list[i] != null)
				builder.append(i).append(':').append(list[i].toString()).append(',');

		return builder.append(']').toString();
	}

	public void appendTag(final NBTBase tag) {
		if (tagType == NBT.TAG_END) {
			tagType = tag.getId();
		} else if (tagType != tag.getId()) {
			System.err.println("WARNING: Adding mismatching tag types to tag list");
			return;
		}

		ensureCapacity(1);
		list[newIndex++] = tag;
	}

	public void func_150304_a(final int index, final NBTBase tag) {

		if (index >= 0 && index < newIndex) {
			if (tagType == NBT.TAG_END) {
				tagType = tag.getId();
			} else if (tagType != tag.getId()) {
				System.err.println("WARNING: Adding mismatching tag types to tag list");
				return;
			}
			list[index] = tag;
		} else {
			System.err.println("WARNING: index out of bounds to set tag in tag list");
		}
	}

	public NBTBase removeTag(final int index) {
		if (index < 0 || index >= newIndex)
			return null;

		final NBTBase removedTag = list[index];

		// We want to copy the last element value
		// to the old slot to keep the list compact.
		list[index] = list[--newIndex];
		list[newIndex] = null;

		return removedTag;
	}

	public NBTTagCompound getCompoundTagAt(final int index) {
		if (tagType == NBT.TAG_COMPOUND && index >= 0 && index < newIndex)
			return (NBTTagCompound) list[index];
		return new NBTTagCompound();
	}

	public int[] func_150306_c(final int index) {
		if (tagType == NBT.TAG_INT_ARRAY && index >= 0 && index < newIndex)
			return ((NBTTagIntArray) list[index]).func_150302_c();
		return NBTTagIntArray.EMPTY;
	}

	public double func_150309_d(final int index) {
		if (tagType == NBT.TAG_DOUBLE && index >= 0 && index < newIndex)
			return ((NBTTagDouble) list[index]).func_150286_g();
		return 0.0D;
	}

	public float func_150308_e(final int index) {
		if (tagType == NBT.TAG_FLOAT && index >= 0 && index < newIndex)
			return ((NBTTagFloat) list[index]).func_150288_h();
		return 0.0F;
	}

	public String getStringTagAt(final int index) {
		if (tagType == NBT.TAG_STRING && index >= 0 && index < newIndex)
			return list[index].func_150285_a_();
		return NBTTagString.EMPTY;
	}

	public int tagCount() {
		return newIndex;
	}

	@Override
	public void freeze() {
		if (!NBTFactory.isImmutable(tagType))
			for (int i = 0; i < newIndex; i++)
				list[i].freeze();
	}

	@Override
	public NBTBase copy() {

		NBTTagList nbt = new NBTTagList();
		nbt.tagType = this.tagType;

		if (newIndex > 0) {
			nbt.ensureCapacity(newIndex);
			nbt.tagType = tagType;
			nbt.newIndex = newIndex;
			if (NBTFactory.isImmutable(tagType)) {
				System.arraycopy(list, 0, nbt.list, 0, list.length);
			} else {
				for (int i = 0; i < newIndex; i++)
					nbt.list[i] = list[i].copy();
			}
		}

		return nbt;
	}

	public boolean equals(final Object tag) {

		if (!super.equals(tag))
			return false;

		final NBTTagList nbt = (NBTTagList) tag;
		return this.tagType == nbt.tagType && Arrays.deepEquals(this.list, nbt.list);
	}

	public int hashCode() {
		return super.hashCode() ^ list.hashCode();
	}

	public int func_150303_d() {
		return this.tagType;
	}
}
