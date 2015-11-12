package org.blockartistry.nbt;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.minecraftforge.common.util.Constants.NBT;

public class NBTTagEnd extends NBTBase {
	
	public static final NBTTagEnd END = new NBTTagEnd();

	@Override
	void write(final DataOutput stream) throws IOException {
	}

	@Override
	void func_152446_a(final DataInput stream, final int depth, final NBTSizeTracker tracker) throws IOException {
	}

	@Override
	public boolean isImmutable() {
		return true;
	}
	
	@Override
	public void freeze() {
		
	}
	
	public byte getId() {
		return NBT.TAG_END;
	}

	public String toString() {
		return "END";
	}

	public NBTBase copy() {
		return END;
	}
}
