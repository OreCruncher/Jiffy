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
