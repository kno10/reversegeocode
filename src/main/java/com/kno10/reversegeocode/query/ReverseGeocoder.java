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
	private static final int HEADER_SIZE = 26;

	/** Decoder */
	static final CharsetDecoder DECODER = Charset.forName("UTF-8").newDecoder();

	/** File name */
	File filename;

	/** Java file object */
	RandomAccessFile file;

	/** Java memory map */
	MappedByteBuffer buffer;

	/** Map size */
	int width, height;

	/** Map extends */
	float xscale, yscale, xshift, yshift;

	/** Number of entries in the map. */
	int numentries;

	/** Position of map and metadata in the file. */
	int metaoffset;

	/** String cache, so we only have to decode UTF-8 once. */
	String[] cache;

	/**
	 * Constructor.
	 * 
	 * @param name
	 *            Index file name
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
		if (magic != 0x6e06e000) {
			throw new IOException(
					"Index file does not have the correct type or version.");
		}
		width = buffer.getShort() & 0xFFFF;
		height = buffer.getShort() & 0xFFFF;
		xscale = width / buffer.getFloat();
		yscale = height / buffer.getFloat();
		xshift = buffer.getFloat();
		yshift = buffer.getFloat();
		numentries = buffer.getShort() & 0xFFFF;
		assert (buffer.position() == HEADER_SIZE);
		// Read the row indexes, to compute the position of the metadata
		int sum = 0;
		for (int i = 0; i < height; i++) {
			sum += buffer.getShort() & 0xFFFF; // Length of the encoded row
		}
		assert (buffer.position() == HEADER_SIZE + height * 2);
		metaoffset = buffer.position() + sum;

		cache = new String[numentries];
	}

	/**
	 * Lookup a longitude and latitude coordinate pair.
	 * 
	 * @param lon
	 *            Longitude
	 * @param lat
	 *            Latitude
	 * @return Decoded string
	 */
	public String lookup(float lon, float lat) {
		return lookupEntry(lookupUncached(lon, lat));
	}

	/**
	 * Lookup a longitude and latitude coordinate pair.
	 * 
	 * @param lon
	 *            Longitude
	 * @param lat
	 *            Latitude
	 * @return Index
	 */
	public int lookupUncached(float lon, float lat) {
		int x = (int) Math.floor((lon + xshift) * xscale);
		int y = (int) Math.floor((lat + yshift) * yscale);
		if (x < 0 || x >= width || y < 0 || y >= height) {
			return 0;
		}
		// Find the row position
		buffer.limit(buffer.capacity());
		buffer.position(HEADER_SIZE);
		int sum = 0;
		for (int i = 0; i < y; i++) {
			sum += buffer.getShort() & 0xFFFF;
		}
		// Seek to row
		buffer.position(HEADER_SIZE + height * 2 + sum);
		for (int i = 0; i <= x;) {
			int c = buffer.getShort() & 0xFFFF;
			int l = (buffer.get() & 0xFF) + 1;
			i += l;
			if (x < i) {
				return c;
			}
		}
		return 0;
	}

	/**
	 * Lookup an index to its metadata string.
	 * 
	 * @param idx
	 *            Index to lookup
	 * @return String
	 */
	public String lookupEntry(int idx) {
		if (idx < 0 || idx >= numentries) {
			return null;
		}
		if (cache[idx] != null) {
			return cache[idx];
		}
		return cache[idx] = lookupEntryUncached(idx);
	}

	/**
	 * Lookup an index entry, uncached.
	 * 
	 * @param idx
	 *            Index entry
	 * @return Decoded data.
	 */
	public String lookupEntryUncached(int idx) {
		// Find the row position
		buffer.limit(buffer.capacity());
		buffer.position(metaoffset);
		int sum = 0;
		for (int i = 0; i < idx; i++) {
			sum += buffer.getShort() & 0xFFFF;
		}
		int l = buffer.getShort() & 0xFFFF;
		// Compute offsets for UTF-8 encoded string.
		int p = metaoffset + numentries * 2 + sum;
		buffer.position(p);
		buffer.limit(p + l);
		// Decode
		try {
			return DECODER.decode(buffer).toString();
		} catch (CharacterCodingException e) {
			throw new RuntimeException("Invalid encoding in index.", e);
		}
	}

	@Override
	public void close() throws IOException {
		cache = null;
		buffer = null;
		if (file != null) {
			file.close();
		}
		file = null;
	}
}
