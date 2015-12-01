/*
 * This file is part of Jiffy, licensed under the MIT License (MIT).
 *
 * Copyright (c) OreCruncher
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.blockartistry.util;

import java.util.function.Predicate;

/**
 * Replacement LongHashMap. Improves on the Vanilla version by:
 * 
 * + Default size of the map is 4K entries. This is what the 1.8.x Vanilla
 * Minecraft codebase does.
 * 
 * + Salt the key with a prime number when hashing to improve key distribution.
 * 
 * + Order the Entries by key value when inserting into the collision chain.
 * Used to perform escapes early when searching/adding.
 * 
 * + "Unroll" method calls for performance. Though isolating reusable code in
 * separate methods is a good idea, it can hurt performance.
 * 
 * + Cache calculated values that are stable to avoid extra instruction cycles
 * when running (e.g. mask).
 * 
 * + Cleaned up chain iteration. Easier to read and a little more efficient.
 * 
 * + removal method that takes a predicate to ease the process of iterating
 * through the map collection to remove items that meet criteria (BiomeCache).
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
	
	/**
	 * Size of the Entry array.
	 */
	private int size;

	/**
	 * Initializes a LongHashMap with 4K entries with a fill factor of 0.75.
	 */
	public LongHashMap() {
		this(2048, DEFAULT_FILL);
	}

	/**
	 * Initializes a LongHashMap with the specified size using a fill factor
	 * of 0.75.
	 */
	public LongHashMap(final int size) {
		this(size, DEFAULT_FILL);
	}

	/**
	 * Initializes a LongHashMap based on the expected size and fill factor.
	 */
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

	private static int getHashedKey(final long key) {
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

	/**
	 * Indicates whether an object is in the map with the associated
	 * key value.
	 * 
	 * @param key Key value to check for
	 * @return true if a KVP is contained in the map, false otherwise
	 */
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
	 * Adds a new key/value pair to the map, or updates an
	 * existing entry matched based on the provided key.
	 * 
	 * @param key Key part of the key/value pair
	 * @param value Value part of the key/value pair
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
	 * Removes the object associated with the provided key.
	 * 
	 * @param key The key for the value to remove
	 * @return Object that has been removed, or null if not found
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

	/**
	 * Remove map entries that satisfy the provided Predicate. Passing in null
	 * will remove all entries.
	 */
	@SuppressWarnings("unchecked")
	public <T> void removeAll(final Predicate<T> pred) {
		if (pred == null) {
			this.numHashElements = 0;
			this.hashArray = new Entry[this.size];
		} else {
			final int entryCount = this.size;
			for (int i = 0; i < entryCount; i++) {
				Entry current = this.hashArray[i];
				Entry previous = FREE;
				while (current != FREE) {
					if (pred.test((T) current.value)) {
						this.numHashElements--;
						if (previous == FREE)
							this.hashArray[i] = current.next;
						else
							previous.next = current.next;
						// Don't advance previous - we just
						// removed the current node.
					} else {
						previous = current;
					}
					current = current.next;
				}
			}
		}
	}

	private static class Entry {
		/**
		 * The key as a long (for playerInstances it is the x in the most
		 * significant 32 bits and then y)
		 */
		final long key;

		/**
		 * The value held by the hash at the specified key
		 */
		Object value;

		/**
		 * The next hashentry in the chain
		 */
		Entry next;

		Entry(final long key, final Object value, final Entry entryChain) {
			this.key = key;
			this.value = value;
			this.next = entryChain;
		}
	}

	/**
	 *  From MathHelper - for some reason it is marked client side :\
	 */
    private static int roundUpToPowerOfTwo(final int value)
    {
        int j = value - 1;
        j |= j >> 1;
        j |= j >> 2;
        j |= j >> 4;
        j |= j >> 8;
        j |= j >> 16;
        return j + 1;
    }

    /**
     * Size the array to a pow2 value factoring in the fill factor.  This
     * may return a larger size value than expected because of the fill
     * factor.
     */
	private static int arraySize(final int expected, final float f) {
		final long s = Math.max(2, roundUpToPowerOfTwo((int) Math.ceil(expected / f)));
		if (s > (1 << 30))
			throw new IllegalArgumentException(
					"Too large (" + expected + " expected elements with load factor " + f + ")");
		return (int) s;
	}
}