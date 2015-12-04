package com.kno10.reversegeocode.indexer;

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

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

/**
 * Dump some basic statistics on an index.
 *
 * @author Erich Schubert
 */
public class MapStatistics {
  /** Number of header bytes total. */
  private static final int HEADER_SIZE = 32;

  /** Decoder */
  static final CharsetDecoder DECODER = Charset.forName("UTF-8").newDecoder();

  public static void stats(String filename) throws IOException {
    try (RandomAccessFile file = new RandomAccessFile(filename, "r")) {
      long filesize = file.length();
      MappedByteBuffer buffer = file.getChannel().map(MapMode.READ_ONLY, 0, filesize);
      int magic = buffer.getInt();
      if(magic != 0x6e06e001) {
        throw new IOException("Index file does not have the correct type or version.");
      }
      int width = buffer.getInt();
      int height = buffer.getInt();
      float xcover = buffer.getFloat();
      float ycover = buffer.getFloat();
      float xshift = buffer.getFloat();
      float yshift = buffer.getFloat();
      int numentries = buffer.getInt();
      assert (buffer.position() == HEADER_SIZE);

      int[] rowpos = new int[height + 1];
      for(int i = 0; i < height; i++) {
        rowpos[i] = buffer.getInt();
      }
      int[] metapos = new int[numentries + 1];
      for(int i = 0; i < numentries + 1; i++) {
        metapos[i] = buffer.getInt();
      }
      rowpos[height] = metapos[0];
      assert (metapos[numentries] == filesize);
      int mapsize = rowpos[height] - rowpos[0];
      int metasize = metapos[numentries] - metapos[0];
      System.out.println("Map size: " + width + " x " + height);
      System.out.println("Map area: lan " + -yshift + " to " + (ycover - yshift) + " lon " + -xshift + " to " + (xcover - xshift));
      System.out.println("Size of map data: " + mapsize);
      System.out.println("Average bytes per row: " + (mapsize / (double) height));
      System.out.println("Row compression factor: " + (width * (double) height / (double) mapsize));
      System.out.println("Metadata entries: " + numentries);
      System.out.println("Size of metadata: " + metasize);
      System.out.println("Average bytes per meta: " + (metasize / (double) numentries));
      System.out.println("Total bytes per pixel: " + (filesize / (width * (double) height)));
    }
  }

  public static void main(String[] args) {
    try {
      for (String arg : args) {
        stats(arg);
      }
    }
    catch(IOException e) {
      e.printStackTrace();
    }
  }
}
