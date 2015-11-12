package org.blockartistry.nbt;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.minecraft.util.MathHelper;
import net.minecraftforge.common.util.Constants.NBT;

public class NBTTagFloat extends NBTPrimitive {
	
	private float data;

	NBTTagFloat() {
	}

	public NBTTagFloat(final float value) {
		this.data = value;
	}

	@Override
	void write(final DataOutput stream) throws IOException {
		stream.writeFloat(this.data);
	}

	@Override
	void func_152446_a(final DataInput stream, final int depth, final NBTSizeTracker tracker) throws IOException {
		tracker.func_152450_a(32L);
		this.data = stream.readFloat();
	}

	public byte getId() {
		return NBT.TAG_FLOAT;
	}

	public String toString() {
		return "" + this.data + "f";
	}

	public NBTBase copy() {
		return new NBTTagFloat(this.data);
	}

	public boolean equals(final Object tag) {
		return super.equals(tag) && this.data == ((NBTTagFloat) tag).data;
	}

	public int hashCode() {
		return super.hashCode() ^ Float.floatToIntBits(this.data);
	}

	public long func_150291_c() {
		return (long) this.data;
	}

	public int func_150287_d() {
		return MathHelper.floor_float(this.data);
	}

	public short func_150289_e() {
		return (short) (MathHelper.floor_float(this.data) & 0xFFFF);
	}

	public byte func_150290_f() {
		return (byte) (MathHelper.floor_float(this.data) & 0xFF);
	}

	public double func_150286_g() {
		return this.data;
	}

	public float func_150288_h() {
		return this.data;
	}
}
