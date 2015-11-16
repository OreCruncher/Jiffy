package org.blockartistry.nbt;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraftforge.common.util.Constants.NBT;

public class NBTTagByteArray extends NBTBase {

	private static final byte[] EMPTY = {};
	private byte[] byteArray;

	NBTTagByteArray() {
		this(EMPTY);
	}

	public NBTTagByteArray(final byte[] array) {
		this.byteArray = array;
	}

	@Override
	public void write(final DataOutput stream) throws IOException {
		stream.writeInt(this.byteArray.length);
		stream.write(this.byteArray);
	}

	@Override
	public void func_152446_a(final DataInput stream, final int depth, final NBTSizeTracker tracker) throws IOException {
		tracker.func_152450_a(32L);
		int j = stream.readInt();
		tracker.func_152450_a(8 * j);
		if(j == 0) {
			this.byteArray = EMPTY;
		} else {
			this.byteArray = new byte[j];
			stream.readFully(this.byteArray);
		}
	}

	public byte getId() {
		return NBT.TAG_BYTE_ARRAY;
	}

	public String toString() {
		return "[" + this.byteArray.length + " bytes]";
	}

	public NBTBase copy() {
		if(this.byteArray == EMPTY)
			return new NBTTagByteArray();
		
		final byte[] newArray = new byte[this.byteArray.length];
		System.arraycopy(this.byteArray, 0, newArray, 0, this.byteArray.length);
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
