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

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.AbstractIntComparator;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.stage.Stage;

/**
 * Build and encode the lookup index.
 *
 * This is currently implemented using JavaFX to facilitate the polygon drawing.
 * For this reason, it needs to extend a JavaFX Application - this part of the
 * JavaFX API is just stupid...
 *
 * This version is designed for even finer resolution, but thus needs to process
 * the data in multiple slices. These are then compressed using run length
 * encoding to conserve memory.
 *
 * TODO: make parameters configurable.
 *
 * @author Erich Schubert
 */
public class BuildLayeredIndexSliced extends Application {
  /** Class logger */
  private static final Logger LOG = LoggerFactory.getLogger(BuildLayeredIndexSliced.class);

  /** Input and output file names */
  File infile, oufile, imfile;

  /** Pattern for matching coordinates */
  Pattern coordPattern = Pattern.compile("(?<=\\t)(-?\\d+(?:\\.\\d*)?),(-?\\d+(?:\\.\\d*)?)(?=\\t|$)");

  /** Pattern for recognizing the level */
  Pattern levelPattern = Pattern.compile("(?<=\\t)(\\d+)(?=\\t)");

  /** Minimum and maximum level */
  private int minLevel = 2, maxLevel = 10;

  /** Entities read from the file */
  private ArrayList<ObjectOpenHashSet<Entity>> entities;

  /** Minimum size of objects to draw */
  double minsize;

  /** Viewport of the map */
  Viewport gviewport;

  /** Rendering block size, and slice size. */
  final int blocksize = 2048;

  /**
   * Constructor.
   */
  public BuildLayeredIndexSliced() {
    super();
  }

  @Override
  public void init() throws Exception {
    super.init();
    Map<String, String> named = getParameters().getNamed();
    String v = named.get("input");
    if(v == null) {
      throw new RuntimeException("Missing parameter --input");
    }
    this.infile = new File(v);
    v = named.get("output");
    if(v == null) {
      throw new RuntimeException("Missing parameter --output");
    }
    this.oufile = new File(v);
    v = named.get("vis");
    this.imfile = v != null ? new File(v) : null;

    v = named.get("minlevel");
    minLevel = v != null ? Integer.valueOf(v) : minLevel;
    v = named.get("maxlevel");
    maxLevel = v != null ? Integer.valueOf(v) : maxLevel;

    // Initialize entity level sets:
    this.entities = new ArrayList<>(maxLevel + 1);
    for(int i = 0; i < minLevel; i++) {
      entities.add(null);
    }
    for(int i = minLevel; i <= maxLevel; i++) {
      entities.add(new ObjectOpenHashSet<>());
    }

    // Viewport on map
    v = named.get("resolution");
    double resolution = v != null ? Double.valueOf(v) : 0.001;
    // TODO: make clipping configurable.
    this.gviewport = new Viewport(360., 140., 180., 60., resolution);
    // this.gviewport = new Viewport(360., 180., 180., 90., resolution);

    // Minimum size of bounding box.
    v = named.get("minsize");
    double pixel_minsize = v != null ? Double.valueOf(v) : 4;
    this.minsize = pixel_minsize * resolution;
  }

  @Override
  public void start(Stage stage) throws Exception {
    // Preallocate objects (will be reset and reused!)
    Matcher m = coordPattern.matcher(""), lm = levelPattern.matcher("");
    FloatArrayList points = new FloatArrayList();
    BoundingBox bb = new BoundingBox();

    int polycount = 0, lines = 0, ecounter = 0;
    // Everybody just "loves" such Java constructs...
    try (
        BufferedReader b = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(infile))))) {
      long start = System.currentTimeMillis();
      String line = null;
      while((line = b.readLine()) != null) {
        ++lines;
        points.clear();
        bb.reset();

        String meta = null;
        lm.reset(line);
        if(!lm.find()) {
          LOG.warn("No admin level found in polygon: {}", line);
          continue;
        }
        // We keep metadata 0-terminated as seperator!
        meta = line.substring(0, lm.end()) + '\0';
        int level = Integer.parseInt(lm.group(1));
        assert (!lm.find());
        m.reset(line);
        while(m.find()) {
          assert (m.start() >= lm.end());
          float lon = Float.parseFloat(m.group(1));
          float lat = Float.parseFloat(m.group(2));
          points.add(lon);
          points.add(lat);
          bb.update(lon, lat);
        }
        if(points.size() == 0) {
          LOG.warn("Polygon was empty: {}", line);
          continue;
        }
        if(bb.size() < minsize) {
          continue;
        }
        if(level >= entities.size()) {
          // Level not used.
          continue;
        }
        ObjectOpenHashSet<Entity> levdata = entities.get(level);
        if(levdata == null) {
          // Level not used.
          continue;
        }
        Entity exist = levdata.get(new Entity(meta, -1));
        if(exist != null) {
          exist.polys.add(points.toFloatArray());
          exist.bb.update(bb);
          ++polycount;
        }
        else {
          Entity ent = new Entity(meta, ++ecounter);
          levdata.add(ent);
          ent.bb = new BoundingBox(bb);
          ent.polys = new ArrayList<>(1);
          ent.polys.add(points.toFloatArray());
          ++polycount;
        }
      }

      long end = System.currentTimeMillis();
      LOG.info("Parsing time: {} ms", end - start);
      LOG.info("Read {} lines, kept {} entities, {} polygons", //
      lines, ecounter, polycount);

      if(ecounter >= 0x0100_0000) { // Top byte will be used for alpha below.
        throw new RuntimeException("Too many entities.");
      }

      render(stage);
    }
    catch(IOException e) {
      LOG.error("IO Error", e);
    }
    Platform.exit();
  }

  /**
   * Render the polygons onto the "winner" map.
   *
   * @param stage Empty JavaFX stage used for rendering
   */
  public void render(Stage stage) {
    Runtime rt = Runtime.getRuntime();
    Group rootGroup = new Group();
    Scene scene = new Scene(rootGroup, blocksize, blocksize, Color.BLACK);
    WritableImage writableImage = null; // Buffer

    // Parent entity counts:
    Int2ObjectOpenHashMap<Int2IntOpenHashMap> parents = new Int2ObjectOpenHashMap<>();

    /** Compressed storage */
    byte[][] comp = new byte[gviewport.height][];

    final int sliceheight = blocksize;
    final int numslices = (int) Math.ceil(gviewport.height / (double) sliceheight);
    int totalsize = 0;
    LOG.info("Rendering in {} slices.", numslices);
    int[][] winners = new int[sliceheight][gviewport.width];
    int[][] winner = new int[sliceheight][gviewport.width];
    long start = System.currentTimeMillis();
    for(int slicestart = 0, slicenum = 1; slicestart < gviewport.height; slicestart += sliceheight, slicenum++) {
      long sstart = System.currentTimeMillis();
      LOG.info("Rendering slice {} / {}", slicenum, numslices);
      for(int y = 0; y < sliceheight; y++) {
        Arrays.fill(winners[y], 0);
      }
      Viewport sviewport = new Viewport(gviewport, 0, slicestart, gviewport.width, sliceheight);
      for(int lev = minLevel; lev <= maxLevel; lev++) {
        if(entities.get(lev) == null) {
          continue;
        }
        long lstart = System.currentTimeMillis();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        LOG.info("Rendering level {} ({} entities, {} MB used)", lev, entities.get(lev).size(), used);
        for(int y = 0; y < sliceheight; y++) {
          Arrays.fill(winner[y], 0);
        }

        // Sort by size.
        ArrayList<Entity> order = new ArrayList<>(entities.get(lev));
        Collections.sort(order);

        Path path = new Path();
        ObservableList<PathElement> elems = path.getElements();
        int drawn = 0;
        for(Entity e : order) {
          if(e.polys.size() <= 0) {
            continue;
          }

          // Project bounding box:
          final int pxmin = (int) Math.floor(sviewport.projLon(e.bb.lonmin));
          final int pxmax = (int) Math.ceil(sviewport.projLon(e.bb.lonmax));
          if(pxmax < 0 || sviewport.width < pxmin) {
            continue;
          }
          final int pymin = (int) Math.ceil(sviewport.projLat(e.bb.latmin));
          final int pymax = (int) Math.floor(sviewport.projLat(e.bb.latmax));
          if(pymax < 0 || sviewport.height < pymin) {
            continue;
          }

          // Clip rendering area:
          int xmin = Math.max(0, pxmin - 1);
          int xmax = Math.min(sviewport.width, pxmax + 1);
          int ymin = Math.max(0, pymin - 1);
          int ymax = Math.min(sviewport.height, pymax + 1);

          // System.out.format("%d-%d %d-%d; ", xmin, xmax, ymin, ymax);
          for(int x1 = xmin; x1 < xmax; x1 += blocksize) {
            int x2 = Math.min(x1 + blocksize, xmax);
            for(int y1 = ymin; y1 < ymax; y1 += blocksize) {
              int y2 = Math.min(y1 + blocksize, ymax);

              // Implementation note: we are drawing upside down.
              elems.clear();
              for(float[] f : e.polys) {
                assert (f.length > 1);
                elems.add(new MoveTo(sviewport.projLon(f[0]) - x1, sviewport.projLat(f[1]) - y1));
                for(int i = 2, l = f.length; i < l; i += 2) {
                  elems.add(new LineTo(sviewport.projLon(f[i]) - x1, sviewport.projLat(f[i + 1]) - y1));
                }
              }
              path.setStroke(Color.TRANSPARENT);
              path.setFill(Color.WHITE);
              path.setFillRule(FillRule.EVEN_ODD);

              rootGroup.getChildren().add(path);
              writableImage = scene.snapshot(writableImage);
              rootGroup.getChildren().remove(path);

              transferPixels(writableImage, x1, x2, y1, y2, //
              winner, e.num);
            }
          }
          ++drawn;
        }
        if(drawn > 0) {
          flatten(winners, winner, parents, sviewport);
        }
        LOG.info("Level rendering time: {} ms {} entities", System.currentTimeMillis() - lstart, drawn);
      }
      int slicesize = compress(winners, comp, slicestart);
      totalsize += slicesize;
      LOG.info("Slice rendering time: {} ms, {} bytes compressed ({} aggregated)", System.currentTimeMillis() - sstart, slicesize, totalsize);
    }
    long end = System.currentTimeMillis();
    LOG.info("Rendering time: {} ms", end - start);

    buildIndex(parents, comp);
  }

  /**
   * Transfer pixels from the rendering buffer to the winner/alpha maps.
   *
   * @param img Rendering buffer
   * @param x1 Left
   * @param x2 Right
   * @param y1 Bottom
   * @param y2 Top
   * @param winner Output array
   * @param c Entity number
   */
  public void transferPixels(WritableImage img, int x1, int x2, int y1, int y2, int[][] winner, int c) {
    assert (c > 0);
    PixelReader reader = img.getPixelReader();
    for(int y = y1, py = 0; y < y2; y++, py++) {
      final int[] rowy = winner[y];
      for(int x = x1, px = 0; x < x2; x++, px++) {
        int col = reader.getArgb(px, py);
        int alpha = (col & 0xFF);
        // Always ignore cover less than 10%
        if(alpha < 0x19) {
          continue;
        }
        // Clip value range to positive bytes,
        // alpha = alpha > 0x7F ? 0x7F : alpha;
        int oldalpha = rowy[x] >>> 24;
        if(alpha == 0xFF || alpha >= oldalpha) {
          rowy[x] = (alpha << 24) | c;
        }
      }
    }
  }

  /**
   * Flatten multiple layers of "winners".
   *
   * @param winners Input layers
   * @param winner Output array
   * @param parents Parents map
   * @param meta Reduce metadata
   * @param viewport Viewport
   */
  private void flatten(int[][] winners, int[][] winner, Int2ObjectOpenHashMap<Int2IntOpenHashMap> parents, Viewport viewport) {
    // Count the most frequent parent for each entity.
    for(int y = 0; y < viewport.height; y++) {
      final int[] rowy = winner[y], outy = winners[y];
      for(int x = 0; x < viewport.width; x++) {
        int id = rowy[x] & 0x00FF_FFFF; // top byte is alpha!
        if(id == 0) {
          continue;
        }
        Int2IntOpenHashMap map = parents.get(id);
        if(map == null) {
          parents.put(id, map = new Int2IntOpenHashMap());
        }
        map.addTo(outy[x], 1);
      }
    }
    // Copy output entities to target array.
    for(int y = 0; y < viewport.height; y++) {
      final int[] rowy = winner[y], outy = winners[y];
      for(int x = 0; x < viewport.width; x++) {
        int id = rowy[x] & 0x00FF_FFFF; // top byte is alpha!
        if(id > 0) {
          outy[x] = id;
        }
      }
    }
  }

  /**
   * Compress the bitmap to reduce memory consumption.
   *
   * @param winner Winner array
   * @param comp Compressed storage array
   * @param slicestart Offset
   * @return Data size
   */
  private int compress(int[][] winner, byte[][] comp, int slicestart) {
    final int l1 = winner.length, l2 = comp.length;
    int totalsize = 0;
    byte[] buffer = new byte[gviewport.width * 8]; // Compression buffer
    for(int iy = 0, oy = slicestart; iy < l1 && oy < l2; iy++, oy++) {
      final int[] row = winner[iy];
      final int rl = row.length;
      int len = 0;
      // Perform a simple run-length encoding.
      for(int x = 0; x < rl;) {
        final int first = x;
        final int cur = row[x++];
        while(x < rl && row[x] == cur) {
          ++x;
        }
        // Write value of map.
        len = writeUnsignedVarint(buffer, len, cur);
        // Write repetition count - 1
        len = writeUnsignedVarint(buffer, len, x - first - 1);
      }
      comp[oy] = Arrays.copyOf(buffer, len);
      totalsize += len;
    }
    return totalsize;
  }

  /**
   * Build the output index file.
   *
   * @param parents Parent counters
   * @param rows Compressed index
   */
  private void buildIndex(Int2ObjectOpenHashMap<Int2IntOpenHashMap> parents, byte[][] rows) {
    // Build metadata first.
    Int2ObjectOpenHashMap<String> meta = new Int2ObjectOpenHashMap<>();
    meta.put(0, ""); // Note: deliberately not \0 terminated.
    for(ObjectOpenHashSet<Entity> levdata : entities) {
      if(levdata == null) {
        continue;
      }
      for(Entity e : levdata) {
        meta.put(e.num, e.key);
      }
    }
    // Find the most frequent parent of each entity:
    int[] cnt = new int[meta.size()];
    cnt[0] = Integer.MAX_VALUE;
    for(Int2ObjectMap.Entry<Int2IntOpenHashMap> en : parents.int2ObjectEntrySet()) {
      int best = -1, bcount = -1, total = 0;
      for(Int2IntMap.Entry p : en.getValue().int2IntEntrySet()) {
        final int c = p.getIntValue();
        total += c;
        if(c > bcount || (c == bcount && c < best)) {
          bcount = c;
          best = p.getIntKey();
        }
      }
      final int i = en.getIntKey();
      cnt[i] = total;
      if(best <= 0) {
        continue; // Top level.
      }
      meta.put(i, meta.get(i) /* 0 terminated! *///
      + meta.get(best) /* 0 terminated */);
    }

    int c = 0;
    int[] map = new int[meta.size()];
    String[] mmeta = new String[meta.size()];
    Arrays.fill(map, -1);
    // Enumerate used indexes.
    {
      int[] tmp = new int[meta.size()];
      for(int i = 0; i < tmp.length; i++) {
        tmp[i] = i;
      }
      // Indirect sort, descending:
      IntArrays.quickSort(tmp, 1, tmp.length, new AbstractIntComparator() {
        @Override
        public int compare(int k1, int k2) {
          return Integer.compare(cnt[k2], cnt[k1]);
        }
      });
      LOG.info("Debug: {} > {} >= {} > {}", cnt[tmp[0]], cnt[tmp[1]], cnt[tmp[2]], cnt[tmp[tmp.length - 1]]);
      for(int p : tmp) {
        if(cnt[p] <= 0) {
          break;
        }
        map[p] = c;
        mmeta[c] = meta.get(p);
        if (c <= 1) {
          LOG.info("Meta: {} {}", c, mmeta[c]);
        }
        c++;
      }
      LOG.info("Number of used entities: {}", c);
    }
    byte[] buffer = new byte[gviewport.width * 8]; // Output buffer.
    // Scan pixels for used indexes.
    int[] bpos = { 0 };

    try (DataOutputStream os = new DataOutputStream(//
    new FileOutputStream(oufile))) {
      // First prepare all the data, so that we can put
      // the final positions into the header table immediately.
      // Encode the rows
      for(int y = 0; y < gviewport.height; y++) {
        final byte[] row = rows[y];
        bpos[0] = 0;
        int len = recodeLine16(row, bpos, map, buffer);
        rows[y] = Arrays.copyOf(buffer, len);
      }
      // Encode the metadata
      byte[][] metadata = new byte[c][];
      for(int i = 0; i < c; i++) {
        metadata[i] = mmeta[i].getBytes("UTF-8");
      }

      // Part 1: HEADER
      // Write a "magic" header first.
      os.writeInt(0x6e0_6e0_01);
      // Write dimensions
      os.writeInt(gviewport.width);
      os.writeInt(gviewport.height);
      // Write coverage
      os.writeFloat((float) gviewport.xcover);
      os.writeFloat((float) gviewport.ycover);
      os.writeFloat((float) gviewport.xshift);
      os.writeFloat((float) gviewport.yshift);
      // Write the number of indexes
      os.writeInt(c);

      final int headersize = os.size();
      LOG.warn("Position of pixmap index: {}", headersize);
      // Position of first row in the data:
      final int firstpos = headersize + //
      ((gviewport.height + metadata.length + 1) << 2);
      int pos2 = firstpos;
      // Part 2: PIXMAP header
      // Write the row header table
      for(int y = 0; y < gviewport.height; y++) {
        os.writeInt(pos2);
        pos2 += rows[y].length;
        assert (pos2 > 0);
      }
      // Part 3: METADATA header
      // Write the metadata header table
      for(byte[] row : metadata) {
        os.writeInt(pos2);
        pos2 += row.length;
        assert (pos2 > 0);
      }
      os.writeInt(pos2); // End of line extra value.
      if(os.size() != firstpos) {
        throw new RuntimeException("File construction logic is inconsistent. Expected: " + firstpos + " position: " + os.size());
      }
      // Part 2: PIXMAP rows
      for(byte[] row : rows) {
        os.write(row, 0, row.length);
      }
      // Part 3: METADATA entries
      for(byte[] row : metadata) {
        os.write(row, 0, row.length);
      }
      // Ensure we are at the predicted position.
      if(pos2 != os.size()) {
        throw new RuntimeException("File construction logic is inconsistent. Expected: " + firstpos + " position: " + os.size());
      }
    }
    catch(IOException e) {
      LOG.error("IO error writing index.", e);
    }
    if(imfile != null) {
      visualize(meta.size(), rows);
    }
  }

  /**
   * Encode a line of the output image map.
   *
   * @param winner Image map
   * @param map Entity ID mapping
   * @param buffer Output buffer
   * @return Length
   */
  // TODO: develop even more compact RLEs for this use case.
  private int recodeLine16(byte[] row, int[] pos, int[] map, byte[] buffer) {
    final int l = row.length;
    int len = 0;
    // The row is already run-length encoded, but we need to perform the
    // translation mapping.
    for(pos[0] = 0; pos[0] < l;) {
      // Map the value:
      len = writeUnsignedVarint(buffer, len, map[readUnsignedVarint(row, pos)]);
      // Copy the repetition count:
      len = writeUnsignedVarint(buffer, len, readUnsignedVarint(row, pos));
    }
    return len;
  }

  /**
   * Read an unsigned integer.
   *
   * @param buffer Buffer to read from
   * @param pos Position array
   * @return Integer value
   */
  private static int readUnsignedVarint(byte[] buffer, int[] pos) {
    int val = 0;
    int bits = 0;
    int p = pos[0];
    while(true) {
      final int data = buffer[p++];
      val |= (data & 0x7F) << bits;
      if((data & 0x80) == 0) {
        pos[0] = p;
        return val;
      }
      bits += 7;
      if(bits > 35) {
        throw new RuntimeException("Variable length quantity is too long for expected integer.");
      }
    }
  }

  /**
   * Write a single varint.
   *
   * @param buffer Buffer to write to
   * @param pos Current position
   * @param val Value to write
   * @return New position
   */
  private static int writeUnsignedVarint(byte[] buffer, int pos, int val) {
    // Extra bytes have the high bit set
    while((val & 0x7F) != val) {
      buffer[pos++] = (byte) ((val & 0x7F) | 0x80);
      val >>>= 7;
    }
    // Last byte doesn't have high bit set
    buffer[pos++] = (byte) (val & 0x7F);
    return pos;
  }

  /**
   * Visualize the map.
   *
   * @param Maximum color
   * @param Comp Compressed winners array
   */
  public void visualize(int max, byte[][] comp) {
    LOG.warn("Producing visualization.");
    // Randomly assign colors for visualization:
    Random r = new Random();
    int[] cols = new int[max + 1];
    for(int i = 1; i < cols.length; i++) {
      cols[i] = r.nextInt(0x1000000) | 0xFF000000;
    }
    int[] pos = { 0 };
    try {
      WritableImage writableImage = new WritableImage(gviewport.width, gviewport.height);
      PixelWriter writer = writableImage.getPixelWriter();
      for(int y = 0; y < gviewport.height; y++) {
        // Note: visualization is drawn upside down.
        byte[] row = comp[gviewport.height - 1 - y];
        pos[0] = 0;
        for(int x = 0; x < gviewport.width && pos[0] < row.length;) {
          int col = readUnsignedVarint(row, pos);
          int cnt = readUnsignedVarint(row, pos) + 1;
          if(col >= 0) {
            for(int i = 0; i < cnt; i++, x++) {
              writer.setArgb(x, y, cols[col]);
            }
          }
          else {
            x += cnt;
          }
        }
      }
      ImageIO.write(SwingFXUtils.fromFXImage(writableImage, null), "png", imfile);
    }
    catch(IOException e) {
      LOG.error("IO error writing visualization.", e);
    }
  }

  /**
   * An entity on the map.
   *
   * @author Erich Schubert
   */
  public static class Entity implements Comparable<Entity> {
    /** Index key (description) */
    final String key;

    /** Integer ID */
    final int num;

    /** Bounding box */
    BoundingBox bb;

    /** Polygons */
    List<float[]> polys;

    public Entity(String key, int num) {
      this.key = key;
      this.num = num;
    }

    @Override
    public int hashCode() {
      return key.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if(this == obj) {
        return true;
      }
      if(obj == null || getClass() != obj.getClass()) {
        return false;
      }
      return key.equals(((Entity) obj).key);
    }

    /**
     * Order descending by size.
     */
    @Override
    public int compareTo(Entity o) {
      return Double.compare(o.bb.size(), bb.size());
    }
  }

  /**
   * Launch, as JavaFX application.
   *
   * @param args Parameters
   */
  public static void main(String[] args) {
    launch(args);
  }
}
