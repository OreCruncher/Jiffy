package org.blockartistry.nbt;

/**
 * Improvements over the Vanilla NBTSizeTracker:
 * 
 * + The static readUTF() no longer performs calculations
 * if the size tracker is noop.  Prior it would do the
 * calculation and throw the results away.
 *
 */
public class NBTSizeTracker {
	
	public static final NBTSizeTracker field_152451_a = new NBTSizeTracker();
	
	private final boolean isNull;
	private final long field_152452_b;
	private long field_152453_c;
	
	private NBTSizeTracker() {
		field_152452_b = 0;
		isNull = true;
	}
	
	public NBTSizeTracker(long p_i1203_1_) {
		field_152452_b = p_i1203_1_;
		isNull = false;
	}

	public boolean isNoop() {
		return isNull;
	}
	
	public void func_152450_a(long p_152450_1_) {
		if(isNull)
			return;
		
		field_152453_c += p_152450_1_ / 8L;
		if (field_152453_c > field_152452_b) {
			throw new RuntimeException("Tried to read NBT tag that was too big; tried to allocate: "
					+ field_152453_c + "bytes where max allowed: " + field_152452_b);
		}
	}

	public static void readUTF(NBTSizeTracker tracker, String data) {
		
		if(tracker.isNoop())
			return;
		
		tracker.func_152450_a(16L);
		if (data == null) {
			return;
		}
		int len = data.length();
		int utflen = 0;
		for (int i = 0; i < len; i++) {
			int c = data.charAt(i);
			if ((c >= 1) && (c <= 127)) {
				utflen++;
			} else if (c > 2047) {
				utflen += 3;
			} else {
				utflen += 2;
			}
		}
		tracker.func_152450_a(8 * utflen);
	}
}
