package com.kno10.reversegeocode.converter;

/*
 * Copyright (C) 2015, Erich Schubert
 * Ludwig-Maximilians-Universität München
 * Lehr- und Forschungseinheit für Datenbanksysteme
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.util.Iterator;

import com.gs.collections.api.map.primitive.MutableIntObjectMap;
import com.gs.collections.api.map.primitive.MutableShortLongMap;
import com.gs.collections.impl.map.mutable.primitive.IntObjectHashMap;
import com.gs.collections.impl.map.mutable.primitive.ShortLongHashMap;

/**
 * Hierarchical hash map, long to long.
 * 
 * This hash-map conserves memory by using Goldman-Sachs collections internally
 * and by using a prefix compression.
 * 
 * Note: this implementation only allows 48 bit keys. Furthermore, this approach
 * is mostly beneficial when there are a lot of keys that have the same prefix,
 * e.g. because the ids are given out sequentially. If your keys are randomly
 * chosen, this data structure will be much less efficient!
 * 
 * @author Erich Schubert
 */
class LongLongHierarchicalMap {
	/**
	 * Default shifting size.
	 */
	public static final int DEFAULT_SHIFT = 16;

	// Chunked maps.
	MutableIntObjectMap<MutableShortLongMap> topmap = new IntObjectHashMap<MutableShortLongMap>();

	// Size and bitmask for subset
	final int shift, mask;

	final long maxid;

	/**
	 * Default constructor: allows up to 48 bit of ids.
	 */
	public LongLongHierarchicalMap() {
		this(DEFAULT_SHIFT);
	}

	/**
	 * Full constructor.
	 * 
	 * @param shift
	 *            Number of bits to use for the inner hashmap.
	 */
	public LongLongHierarchicalMap(int shift) {
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
		MutableShortLongMap chunk = topmap.get(prefix);
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
	public long getIfAbsent(long id, long notfound) {
		int prefix = (int) (id >>> shift);
		if (maxid > 0L && id >= maxid) {
			throw new RuntimeException(
					"Too large node ids for this memory layout.");
		}
		MutableShortLongMap chunk = topmap.get(prefix);
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
	public void put(long id, long val) {
		int prefix = (int) (id >>> shift);
		MutableShortLongMap chunk = topmap.get(prefix);
		if (chunk == null) {
			chunk = new ShortLongHashMap();
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
		for (Iterator<MutableShortLongMap> it = topmap.iterator(); it.hasNext();) {
			size += it.next().size();
		}
		return size;
	}
}