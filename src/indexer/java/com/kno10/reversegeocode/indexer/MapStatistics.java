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
import java.util.Locale;

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

  /** Convert bytes to MB */
  private static final double MB = 1024. * 1024.;

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
      System.out.format(Locale.ROOT, "Map size:\t%d x %d\n", width, height);
      System.out.format(Locale.ROOT, "Longitude cover:\t%.1f\t%.1f\n", -xshift, xcover - xshift);
      System.out.format(Locale.ROOT, "Latitude cover:\t%.1f\t%.1f\n", -yshift, ycover - yshift);
      System.out.format(Locale.ROOT, "Total size:\t%d\t(%.3f MB)\n", filesize, filesize / MB);
      System.out.format(Locale.ROOT, "Size of map data:\t%d\t(%.3f MB)\n", mapsize, mapsize / MB);
      System.out.format(Locale.ROOT, "Average bytes per row:\t%.1f\n", mapsize / (double) height);
      System.out.format(Locale.ROOT, "Row compression factor:\t%.1f\n", width * (double) height / (double) mapsize);
      System.out.format(Locale.ROOT, "Metadata entries:\t%d\n", numentries);
      System.out.format(Locale.ROOT, "Size of metadata:\t%d\t(%.3f MB)\n", metasize, metasize / MB);
      System.out.format(Locale.ROOT, "Average bytes per meta:\t%.1f\n", metasize / (double) numentries);
      System.out.format(Locale.ROOT, "Total bytes per pixel:\t%f\n", filesize / (width * (double) height));
    }
  }

  public static void main(String[] args) {
    try {
      for(String arg : args) {
        stats(arg);
      }
    }
    catch(IOException e) {
      e.printStackTrace();
    }
  }
}
