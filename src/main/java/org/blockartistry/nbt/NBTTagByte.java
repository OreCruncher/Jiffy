package org.blockartistry.nbt;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.minecraftforge.common.util.Constants.NBT;

public class NBTTagByte extends NBTPrimitive {

	private byte data;

	NBTTagByte() {
	}

	public NBTTagByte(final byte value) {
		this.data = value;
	}

	@Override
	void write(final DataOutput stream) throws IOException {
		stream.writeByte(this.data);
	}

	@Override
	void func_152446_a(final DataInput stream, final int depth, final NBTSizeTracker tracker) throws IOException {
		tracker.func_152450_a(8L);
		this.data = stream.readByte();
	}

	public byte getId() {
		return NBT.TAG_BYTE;
	}

	public String toString() {
		return "" + this.data + "b";
	}

	public NBTBase copy() {
		return new NBTTagByte(this.data);
	}

	public boolean equals(final Object tag) {
		return super.equals(tag) && this.data == ((NBTTagByte) tag).data;
	}

	public int hashCode() {
		return super.hashCode() ^ this.data;
	}

	public long func_150291_c() {
		return this.data;
	}

	public int func_150287_d() {
		return this.data;
	}

	public short func_150289_e() {
		return (short) this.data;
	}

	public byte func_150290_f() {
		return this.data;
	}

	public double func_150286_g() {
		return this.data;
	}

	public float func_150288_h() {
		return this.data;
	}
}
