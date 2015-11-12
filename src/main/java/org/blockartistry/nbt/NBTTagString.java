package org.blockartistry.nbt;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.minecraftforge.common.util.Constants.NBT;

public class NBTTagString extends NBTBase {

	public static final String EMPTY = "";
	
	private String data;

	public NBTTagString() {
		this.data = EMPTY;
	}

	public NBTTagString(final String text) {
		this.data = text;
		if (text == null) {
			throw new IllegalArgumentException("Empty string not allowed");
		}
	}

	@Override
	void write(final DataOutput stream) throws IOException {
		stream.writeUTF(this.data);
	}

	@Override
	void func_152446_a(final DataInput stream, final int depth, final NBTSizeTracker tracker) throws IOException {
		this.data = stream.readUTF();
		NBTSizeTracker.readUTF(tracker, this.data);
	}

	public byte getId() {
		return NBT.TAG_STRING;
	}

	public String toString() {
		return "\"" + this.data + "\"";
	}

	@Override
	public boolean isImmutable() {
		return true;
	}

	@Override
	public void freeze() {
	}
	
	public NBTBase copy() {
		return new NBTTagString(this.data);
	}

	public boolean equals(final Object tag) {
		if(!super.equals(tag))
			return false;
		
		final NBTTagString nbt = (NBTTagString) tag;
		return ((this.data == null) && (nbt.data == null))
				|| ((this.data != null) && (this.data.equals(nbt.data)));
	}

	public int hashCode() {
		return super.hashCode() ^ this.data.hashCode();
	}

	public String func_150285_a_() {
		return this.data;
	}
}
