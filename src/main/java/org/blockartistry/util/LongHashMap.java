package org.blockartistry.util;

/**
 * Replacement LongHashMap.  Improves on the Vanilla version by:
 * 
 * + Default size of the map is 4K entries.  This is what the
 * 1.8.x Vanilla Minecraft codebase does.
 * 
 * + Salt the key with a prime number when hashing to improve
 * key distribution.
 * 
 * + Order the Entries by key value when inserting into the
 * collision chain.  Used to perform escapes early when
 * searching/adding.
 * 
 * + "Unroll" method calls for performance.  Though isolating
 * reusable code in separate methods is a good idea, it can
 * hurt performance.
 * 
 * + Cache calculated values that are stable to avoid extra
 * instruction cycles when running (e.g. mask).
 * 
 * + Cleaned up chain iteration.  Easier to read and a little
 * more efficient.
 *
 */
public class LongHashMap {

	private final static Entry FREE = null;
	private final static Object NO_VALUE = null;
	private final static float DEFAULT_FILL = 0.75F;

	/** the array of all elements in the hash */
	private Entry[] hashArray;

	/** The number of active elements in the hash map */
	private int numHashElements;

	/**
	 * Threshold at which the map size will be increased and values rehashed.
	 * Expensive operation so getting the initial size of the map "correct" the
	 * first time is best overall.
	 */
	private int threshold;

	/**
	 * The ratio of used entries to map size before the map size is increased
	 * and a rehash occurs.
	 */
	private float fillFactor;

	/**
	 * Quick mask to calculate the index into the array from a hash. Use of this
	 * mask assumes the array length is in pow2 form.
	 */
	private int mask;
	private int size;

	public LongHashMap() {
		this(4096, DEFAULT_FILL);
	}

	public LongHashMap(final int size) {
		this(size, DEFAULT_FILL);
	}

	public LongHashMap(final int size, final float fillFactor) {
		this.size = arraySize(size, fillFactor);
		this.fillFactor = fillFactor;
		this.hashArray = new Entry[this.size];
		this.threshold = (int) (this.size * this.fillFactor);
		this.numHashElements = 0;
		this.mask = this.size - 1;
	}

	/**
	 * Returns an index into the array based on the key provided.
	 * http://planetmath.org/goodhashtableprimes
	 * 
	 * Prime < 4K table size.
	 */
	private final static int TERM = 3079;
	private static int getHashedKey(long key) {
		// This is pretty darn good- keep!
		final int repack = (int) (key * TERM ^ key >>> 32);
		return repack ^ repack >>> 20 ^ repack >>> 12 ^ repack >>> 7 ^ repack >>> 4;
	}

	public int getNumHashElements() {
		return this.numHashElements;
	}

	/**
	 * get the value from the map given the key
	 */
	public Object getValueByKey(final long key) {
		Entry current = this.hashArray[getHashedKey(key) & this.mask];
		while (current != FREE) {
			// Assume that the object is in the list so check
			// for equivalence before the escape check.
			if (current.key == key)
				return current.value;
			if (key < current.key)
				return NO_VALUE;
			current = current.next;
		}
		return NO_VALUE;
	}

	public boolean containsItem(final long key) {
		Entry current = this.hashArray[getHashedKey(key) & this.mask];
		while (current != FREE) {
			// Assume that the object is in the list so check
			// for equivalence before the escape check.
			if (current.key == key)
				return true;
			if (key < current.key)
				return false;
			current = current.next;
		}
		return false;
	}

	/**
	 * Add a key-value pair.
	 */
	public void add(final long key, final Object value) {
		final int k = getHashedKey(key) & this.mask;
		Entry current = this.hashArray[k];
		Entry previous = FREE;

		while (current != FREE) {
			// Assume that the object is not in the list so
			// check the escape before equivalence.
			if (key < current.key)
				break;

			if (current.key == key) {
				current.value = value;
				return;
			}

			previous = current;
			current = current.next;
		}

		// If we get here it isn't in the list
		final Entry newEntry = new Entry(key, value, current);
		this.numHashElements++;
		if (previous == FREE) {
			// Set the head
			this.hashArray[k] = newEntry;
		} else {
			// Insert into the list
			previous.next = newEntry;
		}

		// Do we need to rehash?
		if (this.numHashElements > this.threshold)
			rehashTable();
	}

	/**
	 * Increases the capacity of the table and rehashes existing entries into
	 * the new array.
	 */
	private void rehashTable() {
		final Entry[] oldTable = this.hashArray;
		this.size = oldTable.length << 1;
		this.hashArray = new Entry[this.size];
		this.mask = this.size - 1;
		this.threshold = (int) (this.size * this.fillFactor);
		this.numHashElements = 0;

		for (int i = 0; i < oldTable.length; i++) {
			Entry current = oldTable[i];
			while (current != FREE) {
				add(current.key, current.value);
				current = current.next;
			}
		}
	}

	/**
	 * calls the removeKey method and returns removed object
	 */
	public Object remove(final long key) {
		final int k = getHashedKey(key) & this.mask;
		Entry current = this.hashArray[k];
		Entry previous = FREE;

		while (current != FREE) {

			// Assume the object is in the list so check for
			// equivalence before escape.
			if (current.key == key) {
				--this.numHashElements;
				if (previous != FREE)
					previous.next = current.next;
				else
					this.hashArray[k] = current.next;
				return current.value;
			}

			if (key < current.key)
				return NO_VALUE;

			previous = current;
			current = current.next;
		}

		return NO_VALUE;
	}

	private static class Entry {
		/**
		 * the key as a long (for playerInstances it is the x in the most
		 * significant 32 bits and then y)
		 */
		final long key;

		/** the value held by the hash at the specified key */
		Object value;

		/** the next hashentry in the table */
		Entry next;

		Entry(final long key, final Object value, final Entry entryChain) {
			this.key = key;
			this.value = value;
			this.next = entryChain;
		}
	}

	public static long nextPowerOfTwo(long x) {
		if (x == 0)
			return 1;
		x--;
		x |= x >> 1;
		x |= x >> 2;
		x |= x >> 4;
		x |= x >> 8;
		x |= x >> 16;
		return (x | x >> 32) + 1;
	}

	public static int arraySize(final int expected, final float f) {
		final long s = Math.max(2, nextPowerOfTwo((long) Math.ceil(expected / f)));
		if (s > (1 << 30))
			throw new IllegalArgumentException(
					"Too large (" + expected + " expected elements with load factor " + f + ")");
		return (int) s;
	}
 }