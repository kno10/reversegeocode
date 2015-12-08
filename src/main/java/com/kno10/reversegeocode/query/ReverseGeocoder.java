package com.kno10.reversegeocode.query;

/* Copyright (c) 2015, Erich Schubert
 * Ludwig-Maximilians-Universität München
 * Lehr- und Forschungseinheit für Datenbanksysteme
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

/**
 * Class to perform a reverse geocode operation.
 *
 * Notice: this class is <em>not thread-safe</em>. Synchronize if necessary!
 *
 * @author Erich Schubert
 */
public class ReverseGeocoder implements AutoCloseable {
  /** Number of header bytes total. */
  private static final int HEADER_SIZE = 32;

  /** Decoder */
  private static final CharsetDecoder DECODER = Charset.forName("UTF-8").newDecoder();

  /** Empty array - no match */
  private static final String[] EMPTY = new String[0];

  /** File name */
  private File filename;

  /** Java file object */
  private RandomAccessFile file;

  /** Java memory map */
  private MappedByteBuffer buffer;

  /** Map size */
  private int width, height;

  /** Map extends */
  private float xscale, yscale, xshift, yshift;

  /** Number of entries in the map. */
  private int numentries;

  /** String cache, so we only have to decode UTF-8 once. */
  private String[][] cache;

  /**
   * Constructor.
   *
   * @param name Index file name
   * @throws IOException
   */
  public ReverseGeocoder(String name) throws IOException {
    this.filename = new File(name);
    reopen();
  }

  /**
   * Reopen the index file.
   *
   * @throws IOException
   */
  public void reopen() throws IOException {
    file = new RandomAccessFile(filename, "r");
    buffer = file.getChannel().map(MapMode.READ_ONLY, 0, file.length());
    int magic = buffer.getInt();
    if(magic != 0x6e06e001) {
      throw new IOException("Index file does not have the correct type or version.");
    }
    width = buffer.getInt();
    height = buffer.getInt();
    xscale = width / buffer.getFloat();
    yscale = height / buffer.getFloat();
    xshift = buffer.getFloat();
    yshift = buffer.getFloat();
    numentries = buffer.getInt();
    assert (buffer.position() == HEADER_SIZE);

    cache = new String[numentries][];
  }

  /**
   * Lookup a longitude and latitude coordinate pair.
   *
   * @param lon Longitude
   * @param lat Latitude
   * @return Decoded string
   */
  public String[] lookup(float lon, float lat) {
    return lookupEntry(lookupUncached(lon, lat));
  }

  /**
   * Lookup a longitude and latitude coordinate pair.
   *
   * @param lon Longitude
   * @param lat Latitude
   * @return Index
   */
  public int lookupUncached(float lon, float lat) {
    int x = (int) Math.floor((lon + xshift) * xscale);
    int y = (int) Math.floor((lat + yshift) * yscale);
    if(x < 0 || x >= width || y < 0 || y >= height) {
      return 0;
    }
    // Find the row position
    buffer.limit(buffer.capacity());
    buffer.position(HEADER_SIZE + (y << 2));
    int rowpos = buffer.getInt();
    // Seek to row
    buffer.position(rowpos);
    for(int i = 0; i <= x;) {
      int c = readUnsignedVarint(buffer);
      i += readUnsignedVarint(buffer) + 1;
      if(x < i) {
        return c;
      }
    }
    return 0;
  }

  /**
   * Read an unsigned integer.
   *
   * @param buffer Buffer to read from
   * @return Integer value
   */
  private static int readUnsignedVarint(ByteBuffer buffer) {
    int val = 0;
    int bits = 0;
    while(true) {
      final int data = buffer.get();
      val |= (data & 0x7F) << bits;
      if((data & 0x80) == 0) {
        return val;
      }
      bits += 7;
      if(bits > 35) {
        throw new RuntimeException("Variable length quantity is too long for expected integer.");
      }
    }
  }

  /**
   * Lookup an index to its metadata string.
   *
   * @param idx Index to lookup
   * @return String
   */
  public String[] lookupEntry(int idx) {
    if(idx < 0 || idx >= numentries) {
      return EMPTY;
    }
    return (cache[idx] != null) ? cache[idx] : //
    (cache[idx] = lookupEntryUncached(idx));
  }

  /**
   * Lookup an index entry, uncached.
   *
   * @param idx Index entry
   * @return Decoded data.
   */
  public String[] lookupEntryUncached(int idx) {
    if(idx < 0 || idx >= numentries) {
      return EMPTY;
    }
    // Find the row position
    buffer.limit(buffer.capacity());
    buffer.position(HEADER_SIZE + ((height + idx) << 2));
    int start = buffer.getInt(), endp = buffer.getInt();
    if(start == endp) {
      return EMPTY;
    }
    try {
      // Decode charset:
      buffer.position(start).limit(endp);
      CharBuffer decoded = DECODER.decode(buffer);
      // Count the number of 0-delimited entries
      int nummeta = 0, end = decoded.length();
      for(int i = 0; i < end; i++) {
        if(decoded.get(i) == '\0') {
          ++nummeta;
        }
      }
      String[] ret = new String[nummeta];
      for(int i = 0, j = 0, k = 0; i < end; i++) {
        if(decoded.get(i) == '\0') {
          ret[k++] = decoded.subSequence(j, i).toString();
          j = i + 1;
        }
      }
      return ret;
    }
    catch(CharacterCodingException e) {
      throw new RuntimeException("Invalid encoding in index for entry: " + idx, e);
    }
  }

  @SuppressWarnings("restriction")
  @Override
  public void close() throws IOException {
    cache = null;
    if(buffer != null) {
      // Restricted API, but e.g. the JMH benchmarks fail if we do not unmap.
      sun.misc.Cleaner cleaner = ((sun.nio.ch.DirectBuffer) buffer).cleaner();
      cleaner.clean();
      buffer = null;
    }
    if(file != null) {
      file.close();
      file = null;
    }
  }

  /**
   * @return The number of entries in the geocoder.
   */
  public int getNumberOfEntries() {
    return numentries;
  }
}
