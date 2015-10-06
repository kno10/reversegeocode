package com.kno10.reversegeocode.converter;

import java.util.Collections;
import java.util.Iterator;

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

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;

/**
 * Hierarchical hash map, long to object.
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
 *
 * @param <T> Object type
 */
class LongObjectHierarchicalMap<T> {
  /**
   * Default shifting size.
   */
  public static final int DEFAULT_SHIFT = 16;

  // Chunked maps.
  Int2ObjectOpenHashMap<Short2ObjectOpenHashMap<T>> topmap = new Int2ObjectOpenHashMap<Short2ObjectOpenHashMap<T>>();

  // Size and bitmask for subset
  final int shift, mask;

  final long maxid;

  /**
   * Default constructor: allows up to 48 bit of ids.
   */
  public LongObjectHierarchicalMap() {
    this(DEFAULT_SHIFT);
  }

  /**
   * Full constructor.
   *
   * @param shift Number of bits to use for the inner hashmap.
   */
  public LongObjectHierarchicalMap(int shift) {
    super();
    this.shift = shift;
    this.mask = (1 << shift) - 1;
    this.maxid = 1L << (shift + 32);
  }

  /**
   * Test if a key is present in the hashmap.
   *
   * @param id Key to test
   * @return {@code true} when contained.
   */
  public boolean containsKey(long id) {
    int prefix = (int) (id >>> shift);
    if(maxid > 0L && id >= maxid) {
      throw new RuntimeException("Too large node ids for this memory layout, increase SHIFT.");
    }
    Short2ObjectOpenHashMap<T> chunk = topmap.get(prefix);
    return (chunk != null) && chunk.containsKey((short) (id & mask));
  }

  /**
   * Get the value of a key
   *
   * @param id key to retrieve
   * @return Value stored, or {@code null}
   */
  public T get(long id) {
    int prefix = (int) (id >>> shift);
    if(maxid > 0L && id >= maxid) {
      throw new RuntimeException("Too large node ids for this memory layout.");
    }
    Short2ObjectOpenHashMap<T> chunk = topmap.get(prefix);
    if(chunk == null) {
      return null;
    }
    return chunk.get((short) (id & mask));
  }

  /**
   * Get the value of a key
   *
   * @param id key to retrieve
   * @param notfound Value to return when the key is not found.
   * @return Value stored, or {@code notfound}
   */
  public T getOrDefault(long id, T notfound) {
    int prefix = (int) (id >>> shift);
    if(maxid > 0L && id >= maxid) {
      throw new RuntimeException("Too large node ids for this memory layout.");
    }
    Short2ObjectOpenHashMap<T> chunk = topmap.get(prefix);
    if(chunk == null) {
      return notfound;
    }
    return chunk.getOrDefault((short) (id & mask), notfound);
  }

  /**
   * Store a value in the map.
   *
   * @param id Key
   * @param val Value
   */
  public void put(long id, T val) {
    int prefix = (int) (id >>> shift);
    Short2ObjectOpenHashMap<T> chunk = topmap.get(prefix);
    if(chunk == null) {
      chunk = new Short2ObjectOpenHashMap<T>();
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
    for(Short2ObjectOpenHashMap<T> m : topmap.values()) {
      size += m.size();
    }
    return size;
  }

  /**
   * Iterate over all values.
   *
   * @return Value iterator
   */
  public Iterator<T> valueIterator() {
    return new ValueIterator<T>(topmap.values().iterator());
  }

  /**
   * Value iterator class.
   *
   * @author Erich Schubert
   *
   * @param <T> Data type
   */
  private static class ValueIterator<T> implements Iterator<T> {
    /** Outer iterator */
    private ObjectIterator<Short2ObjectOpenHashMap<T>> outer;

    /** Inner iterator */
    private Iterator<T> inner;

    /**
     * Constructor.
     *
     * @param outer Outer iterator
     */
    public ValueIterator(ObjectIterator<Short2ObjectOpenHashMap<T>> outer) {
      this.outer = outer;
      if(outer.hasNext()) {
        inner = outer.next().values().iterator();
      }
      else {
        inner = Collections.emptyIterator();
      }
    }

    @Override
    public boolean hasNext() {
      while(!inner.hasNext()) {
        if(!outer.hasNext()) {
          return false;
        }
        inner = outer.next().values().iterator();
      }
      assert(inner.hasNext());
      return true;
    }

    @Override
    public T next() {
      this.hasNext(); // Ensure we have a next element.
      return inner.next();
    }

    @Override
    public void remove() {
      inner.remove();
    }
  }
}
