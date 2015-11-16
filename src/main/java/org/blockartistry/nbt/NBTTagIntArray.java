package org.blockartistry.nbt;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraftforge.common.util.Constants.NBT;

public class NBTTagIntArray extends NBTBase {

	private static final int[] EMPTY = {};
	
	private int[] intArray;

	NBTTagIntArray() {
		this(EMPTY);
	}

	public NBTTagIntArray(final int[] array) {
		this.intArray = array;
	}

	@Override
	public void write(final DataOutput stream) throws IOException {
		stream.writeInt(this.intArray.length);
		for (int i = 0; i < this.intArray.length; i++) {
			stream.writeInt(this.intArray[i]);
		}
	}

	@Override
	public void func_152446_a(final DataInput stream, final int depth, final NBTSizeTracker tracker) throws IOException {
		tracker.func_152450_a(32L);
		final int j = stream.readInt();
		tracker.func_152450_a(32 * j);
		if(j == 0) {
			this.intArray = EMPTY;
		} else {
			this.intArray = new int[j];
			for (int k = 0; k < j; k++) {
				this.intArray[k] = stream.readInt();
			}
		}
	}

	public byte getId() {
		return NBT.TAG_INT_ARRAY;
	}

	public String toString() {
		final StringBuilder builder = new StringBuilder(128);
		builder.append('[');

		for (int j = 0; j < this.intArray.length; j++)
			builder.append(this.intArray[j]).append(',');

		return builder.append(']').toString();
	}

	public NBTBase copy() {
		if(this.intArray == EMPTY)
			return new NBTTagIntArray();
		
		final int[] newArray = new int[this.intArray.length];
		System.arraycopy(this.intArray, 0, newArray, 0, this.intArray.length);
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
