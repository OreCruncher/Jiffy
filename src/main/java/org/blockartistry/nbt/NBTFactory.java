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

import net.minecraftforge.common.util.Constants.NBT;

public final class NBTFactory {

	public static NBTBase getTag(final byte type) {

		switch (type) {
		case NBT.TAG_END:
			return new NBTTagEnd();
		case NBT.TAG_BYTE:
			return new NBTTagByte();
		case NBT.TAG_SHORT:
			return new NBTTagShort();
		case NBT.TAG_INT:
			return new NBTTagInt();
		case NBT.TAG_LONG:
			return new NBTTagLong();
		case NBT.TAG_FLOAT:
			return new NBTTagFloat();
		case NBT.TAG_DOUBLE:
			return new NBTTagDouble();
		case NBT.TAG_BYTE_ARRAY:
			return new NBTTagByteArray();
		case NBT.TAG_INT_ARRAY:
			return new NBTTagIntArray();
		case NBT.TAG_STRING:
			return new NBTTagString();
		case NBT.TAG_LIST:
			return new NBTTagList();
		case NBT.TAG_COMPOUND:
			return new NBTTagCompound();
		}

		return null;
	}

	//
	// Indicates whether the NBT object represents an immutable
	// type. This is to optimize copies and reduce on overhead.
	//
	public static boolean isImmutable(final byte type) {
		return !(type == NBT.TAG_BYTE_ARRAY || type == NBT.TAG_INT_ARRAY || type == NBT.TAG_COMPOUND
				|| type == NBT.TAG_LIST);
	}
}
