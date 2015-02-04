package com.kno10.reversegeocode.builder;

import java.util.Iterator;

import com.gs.collections.api.map.primitive.MutableIntObjectMap;
import com.gs.collections.api.map.primitive.MutableShortIntMap;
import com.gs.collections.impl.map.mutable.primitive.IntObjectHashMap;
import com.gs.collections.impl.map.mutable.primitive.ShortIntHashMap;

/**
 * Hierarchical hash map, long to int.
 * 
 * This hash-map conserves memory by using Goldman-Sachs collections internally
 * and by using a prefix compression.
 * 
 * @author Erich Schubert
 */
class LongIntHierarchicalMap {
	/**
	 * Default shifting size.
	 */
	public static final int DEFAULT_SHIFT = 16;

	// Chunked maps.
	MutableIntObjectMap<MutableShortIntMap> topmap = new IntObjectHashMap<MutableShortIntMap>();

	// Size and bitmask for subset
	final int shift, mask;

	final long maxid;

	/**
	 * Default constructor: allows up to 48 bit of ids.
	 */
	public LongIntHierarchicalMap() {
		this(DEFAULT_SHIFT);
	}

	/**
	 * Full constructor.
	 * 
	 * @param shift
	 *            Number of bits to use for the inner hashmap.
	 */
	public LongIntHierarchicalMap(int shift) {
		super();
		this.shift = shift;
		this.mask = (1 << shift) - 1;
		this.maxid = 1L << (shift + 32);
	}

	/**
	 * Test if a key is present in the hashmap.
	 * 
	 * @param id
	 *            Key to test
	 * @return {@code true} when contained.
	 */
	public boolean containsKey(long id) {
		int prefix = (int) (id >>> shift);
		if (maxid > 0L && id >= maxid) {
			throw new RuntimeException(
					"Too large node ids for this memory layout, increase SHIFT.");
		}
		MutableShortIntMap chunk = topmap.get(prefix);
		return (chunk != null) && chunk.containsKey((short) (id & mask));
	}

	/**
	 * Get the value of a key
	 * 
	 * @param id
	 *            key to retrieve
	 * @param notfound
	 *            Value to return when the key is not found.
	 * @return Value stored, or {@code notfound}
	 */
	public int getIfAbsent(long id, int notfound) {
		int prefix = (int) (id >>> shift);
		if (maxid > 0L && id >= maxid) {
			throw new RuntimeException(
					"Too large node ids for this memory layout.");
		}
		MutableShortIntMap chunk = topmap.get(prefix);
		if (chunk == null) {
			return notfound;
		}
		return chunk.getIfAbsent((short) (id & mask), notfound);
	}

	/**
	 * Store a value in the map.
	 * 
	 * @param id
	 *            Key
	 * @param val
	 *            Value
	 */
	public void put(long id, int val) {
		int prefix = (int) (id >>> shift);
		MutableShortIntMap chunk = topmap.get(prefix);
		if (chunk == null) {
			chunk = new ShortIntHashMap();
			topmap.put(prefix, chunk);
		}
		chunk.put((short) (id & mask), val);
	}

	/**
	 * Compute the size of the map.
	 * 
	 * This is more expensive than your usual {@link size()} call, therefore we
	 * chose a different name. It would be trivial to add a counter to provide
	 * O(1) size.
	 * 
	 * @return Size, by aggregating over all maps.
	 */
	public int computeSize() {
		int size = 0;
		for (Iterator<MutableShortIntMap> it = topmap.iterator(); it.hasNext();) {
			size += it.next().size();
		}
		return size;
	}
}