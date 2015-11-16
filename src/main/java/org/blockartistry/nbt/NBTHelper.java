package org.blockartistry.nbt;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagEnd;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.common.util.Constants.NBT;

public class NBTHelper {

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
	
	public static void complexityCheck(final int depth) {
		if (depth > 512) {
			throw new RuntimeException("Tried to read NBT tag with too high complexity, depth > 512");
		}
	}
}
