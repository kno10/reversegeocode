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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

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

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gs.collections.api.bag.primitive.MutableIntBag;
import com.gs.collections.api.iterator.IntIterator;
import com.gs.collections.api.map.primitive.IntObjectMap;
import com.gs.collections.api.map.primitive.MutableIntObjectMap;
import com.gs.collections.impl.bag.mutable.primitive.IntHashBag;
import com.gs.collections.impl.list.mutable.primitive.FloatArrayList;
import com.gs.collections.impl.map.mutable.primitive.IntObjectHashMap;
import com.gs.collections.impl.set.mutable.UnifiedSet;

/**
 * Build and encode the lookup index.
 * 
 * This is currently implemented using JavaFX to facilitate the polygon drawing.
 * For this reason, it needs to extend a JavaFX Application - this part of the
 * JavaFX API is just stupid...
 * 
 * TODO: make parameters configurable.
 * 
 * @author Erich Schubert
 */
public class BuildLayeredIndex extends Application {
	/** Class logger */
	private static final Logger LOG = LoggerFactory
			.getLogger(BuildLayeredIndex.class);

	/** Input and output file names */
	File infile, oufile, imfile;

	/** Pattern for matching coordinates */
	Pattern coordPattern = Pattern
			.compile("(?<=\t)(-?\\d+(?:\\.\\d*)),(-?\\d+(?:\\.\\d*))(?=\t|$)");

	/** Pattern for recognizing the level */
	Pattern levelPattern = Pattern.compile("(?<=\t)(\\d+)(?=\t)");

	/** Minimum and maximum level */
	private int minLevel = 2, maxLevel = 10;

	/** Entities read from the file */
	private ArrayList<UnifiedSet<Entity>> entities;

	/** Minimum size of objects to draw */
	double minsize;

	/** Viewport of the map */
	Viewport viewport;

	/**
	 * Constructor.
	 */
	public BuildLayeredIndex() {
		super();
	}

	@Override
	public void init() throws Exception {
		super.init();
		Map<String, String> named = getParameters().getNamed();
		String v = named.get("input");
		if (v == null) {
			throw new RuntimeException("Missing parameter --input");
		}
		this.infile = new File(v);
		v = named.get("output");
		if (v == null) {
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
		for (int i = 0; i < minLevel; i++) {
			entities.add(null);
		}
		for (int i = minLevel; i <= maxLevel; i++) {
			entities.add(new UnifiedSet<>());
		}

		// Viewport on map
		v = named.get("resolution");
		double resolution = v != null ? Double.valueOf(v) : 0.01;
		// TODO: make clipping configurable.
		this.viewport = new Viewport(360., 140., 180., 60., resolution);

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
		try (BufferedReader b = new BufferedReader(new InputStreamReader(
				new GZIPInputStream(new FileInputStream(infile))))) {
			long start = System.currentTimeMillis();
			String line = null;
			while ((line = b.readLine()) != null) {
				++lines;
				points.clear();
				bb.reset();

				String meta = null;
				lm.reset(line);
				if (!lm.find()) {
					LOG.warn("Line was not matched: {}", line);
					continue;
				}
				// We keep metadata 0-terminated as seperator!
				meta = line.substring(0, lm.end()) + '\0';
				int level = Integer.parseInt(lm.group(1));
				assert (!lm.find());
				m.reset(line);
				while (m.find()) {
					assert (m.start() >= lm.end());
					float lon = Float.parseFloat(m.group(1));
					float lat = Float.parseFloat(m.group(2));
					points.add(lon);
					points.add(lat);
					bb.update(lon, lat);
				}
				if (points.size() == 0) {
					LOG.warn("Line was not matched: {}", line);
					continue;
				}
				if (bb.size() < minsize) {
					continue;
				}
				Entity ent = new Entity(meta);
				if (level >= entities.size()) {
					// Level not used.
					continue;
				}
				UnifiedSet<Entity> levdata = entities.get(level);
				if (levdata == null) {
					// Level not used.
					continue;
				}
				Entity exist = levdata.get(ent);
				if (exist != null) {
					exist.bb.update(bb);
					exist.polys.add(points.toArray());
					++polycount;
				} else {
					levdata.add(ent);
					ent.bb = new BoundingBox(bb);
					ent.polys = new LinkedList<>();
					ent.polys.add(points.toArray());
					++polycount;
					++ecounter;
				}
			}

			long end = System.currentTimeMillis();
			LOG.info("Parsing time: {} ms", end - start);
			LOG.info("Read {} lines, kept {} entities, {} polygons", //
					lines, ecounter, polycount);

			render(stage);
		} catch (IOException e) {
			LOG.error("IO Error", e);
		}
		Platform.exit();
	}

	/**
	 * Render the polygons onto the "winner" map.
	 * 
	 * @param stage
	 *            Empty JavaFX stage used for rendering
	 */
	public void render(Stage stage) {
		final int blocksize = 1024;
		Group rootGroup = new Group();
		Scene scene = new Scene(rootGroup, blocksize, blocksize, Color.BLACK);
		WritableImage writableImage = null; // Buffer

		MutableIntObjectMap<String> meta = new IntObjectHashMap<>();
		meta.put(0, ""); // Note: deliberately not \0 terminated.
		int entnum = 1;

		int[][] winners = new int[viewport.height][viewport.width];
		int[][] winner = new int[viewport.height][viewport.width];
		long start = System.currentTimeMillis();
		for (int lev = minLevel; lev <= maxLevel; lev++) {
			if (entities.get(lev) == null) {
				continue;
			}
			LOG.info("Rendering level {}", lev);
			for (int y = 0; y < viewport.height; y++) {
				Arrays.fill(winner[y], 0);
			}

			// Sort by size.
			ArrayList<Entity> order = new ArrayList<>(entities.get(lev));
			Collections.sort(order);

			Path path = new Path();
			ObservableList<PathElement> elems = path.getElements();
			for (Entity e : order) {
				if (e.polys.size() <= 0) {
					continue;
				}

				// Area to inspect
				int xmin = Math.max(0,
						(int) Math.floor(viewport.projLon(e.bb.lonmin)) - 1);
				int xmax = Math.min(viewport.width,
						(int) Math.ceil(viewport.projLon(e.bb.lonmax)) + 1);
				int ymin = Math.max(0,
						(int) Math.ceil(viewport.projLat(e.bb.latmin)) - 1);
				int ymax = Math.min(viewport.height,
						(int) Math.floor(viewport.projLat(e.bb.latmax)) + 1);
				// System.out.format("%d-%d %d-%d; ", xmin, xmax, ymin, ymax);
				for (int x1 = xmin; x1 < xmax; x1 += blocksize) {
					int x2 = Math.min(x1 + blocksize, xmax);
					for (int y1 = ymin; y1 < ymax; y1 += blocksize) {
						int y2 = Math.min(y1 + blocksize, ymax);

						// Implementation note: we are drawing upside down.
						elems.clear();
						for (float[] f : e.polys) {
							assert (f.length > 1);
							elems.add(new MoveTo(viewport.projLon(f[0]) - x1,
									viewport.projLat(f[1]) - y1));
							for (int i = 2, l = f.length; i < l; i += 2) {
								elems.add(new LineTo(viewport.projLon(f[i])
										- x1, viewport.projLat(f[i + 1]) - y1));
							}
						}
						path.setStroke(Color.TRANSPARENT);
						path.setFill(Color.WHITE);
						path.setFillRule(FillRule.EVEN_ODD);

						rootGroup.getChildren().add(path);
						writableImage = scene.snapshot(writableImage);
						rootGroup.getChildren().remove(path);

						transferPixels(writableImage, x1, x2, y1, y2, //
								winner, entnum);
					}
				}
				// Note: we construct meta 0-terminated!
				meta.put(entnum, e.key);
				++entnum;
			}
			flatten(winners, winner, meta);
		}
		long end = System.currentTimeMillis();
		LOG.info("Rendering time: {} ms", end - start);

		buildIndex(meta, winners);
		if (imfile != null) {
			visualize(meta.size(), winners);
		}
	}

	/**
	 * Transfer pixels from the rendering buffer to the winner/alpha maps.
	 * 
	 * @param img
	 *            Rendering buffer
	 * @param x1
	 *            Left
	 * @param x2
	 *            Right
	 * @param y1
	 *            Bottom
	 * @param y2
	 *            Top
	 * @param winner
	 *            Output array
	 * @param c
	 *            Entity number
	 */
	public void transferPixels(WritableImage img, int x1, int x2, int y1,
			int y2, int[][] winner, int c) {
		PixelReader reader = img.getPixelReader();
		for (int y = y1, py = 0; y < y2; y++, py++) {
			for (int x = x1, px = 0; x < x2; x++, px++) {
				int col = reader.getArgb(px, py);
				int alpha = (col & 0xFF);
				// Always ignore cover less than 10%
				if (alpha < 0x19) {
					continue;
				}
				// Clip value range to positive bytes,
				alpha = alpha > 0x7F ? 0x7F : alpha;
				byte oldalpha = (byte) (winner[y][x] >>> 24);
				if (alpha == 0x7F || (alpha > 0 && alpha >= oldalpha)) {
					winner[y][x] = (alpha << 24) | c;
				}
			}
		}
	}

	/**
	 * Flatten multiple layers of "winners".
	 * 
	 * @param winners
	 *            Input layers
	 * @param winner
	 *            Output array
	 * @param ents
	 *            Entities
	 * @param meta
	 *            Reduce metadata
	 */
	private void flatten(int[][] winners, int[][] winner,
			MutableIntObjectMap<String> meta) {
		MutableIntObjectMap<MutableIntBag> parents = new IntObjectHashMap<>();
		for (int y = 0; y < viewport.height; y++) {
			for (int x = 0; x < viewport.width; x++) {
				int id = winner[y][x] & 0xFFFFFF; // top byte is alpha!
				if (id > 0) {
					parents.getIfAbsentPut(id, IntHashBag::new)//
							.add(winners[y][x]);
				}
			}
		}
		// Find the most frequent parent:
		parents.forEachKeyValue((i, b) -> {
			int best = -1, bcount = -1;
			for (IntIterator it = b.intIterator(); it.hasNext();) {
				int p = it.next(), c = b.occurrencesOf(p);
				if (c > bcount || (c == bcount && p < best)) {
					bcount = c;
					best = p;
				}
			}
			if (best > 0) {
				meta.put(i, meta.get(i) /* 0 terminated! *///
						+ meta.get(best) /* 0 terminated */);
			}
		});
		for (int y = 0; y < viewport.height; y++) {
			for (int x = 0; x < viewport.width; x++) {
				int id = winner[y][x] & 0xFFFFFF; // top byte is alpha!
				if (id > 0) {
					winners[y][x] = id;
				}
			}
		}
	}

	/**
	 * Build the output index file.
	 * 
	 * @param meta
	 *            Metadata
	 * @param winner
	 *            Winner array
	 */
	private void buildIndex(IntObjectMap<String> meta, int[][] winner) {
		int[] map = new int[meta.size()];
		// Scan pixels for used indexes.
		for (int y = 0; y < viewport.height; y++) {
			int[] row = winner[y];
			for (int x = 0; x < viewport.width; x++) {
				map[row[x]] = 1; // present
			}
		}
		// Enumerate used indexes.
		int c = 0;
		for (int i = 0; i < map.length; i++) {
			map[i] = (map[i] == 0) ? -1 : c++;
		}
		LOG.info("Number of used entities: {}", c);
		byte[] buffer = new byte[viewport.width * 8]; // Output buffer.

		try (DataOutputStream os = new DataOutputStream(//
				new FileOutputStream(oufile))) {
			// First prepare all the data, so that we can put
			// the final positions into the header table immediately.
			// Encode the rows
			byte[][] rows = new byte[viewport.height][];
			for (int y = 0; y < viewport.height; y++) {
				int len = encodeLine16(winner[y], map, buffer);
				rows[y] = Arrays.copyOf(buffer, len);
			}
			// Encode the metadata
			byte[][] metadata = new byte[c][];
			int c2 = 0;
			for (int i = 0; i < map.length; i++) {
				if (map[i] <= -1) {
					continue;
				}
				byte[] bytes = meta.get(i).getBytes("UTF-8");
				metadata[c2++] = bytes;
			}
			assert (c2 == c);

			// Part 1: HEADER
			// Write a "magic" header first.
			os.writeInt(0x6e0_6e0_01);
			// Write dimensions
			os.writeInt(viewport.width);
			os.writeInt(viewport.height);
			// Write coverage
			os.writeFloat((float) viewport.xcover);
			os.writeFloat((float) viewport.ycover);
			os.writeFloat((float) viewport.xshift);
			os.writeFloat((float) viewport.yshift);
			// Write the number of indexes
			os.writeInt(c);

			final int headersize = os.size();
			LOG.warn("Position of pixmap index: {}", headersize);
			// Position of first row in the data:
			final int firstpos = headersize + //
					((viewport.height + metadata.length + 1) << 2);
			int pos = firstpos;
			// Part 2: PIXMAP header
			// Write the row header table
			for (int y = 0; y < viewport.height; y++) {
				os.writeInt(pos);
				pos += rows[y].length;
				assert (pos > 0);
			}
			// Part 3: METADATA header
			// Write the metadata header table
			for (byte[] row : metadata) {
				os.writeInt(pos);
				pos += row.length;
				assert (pos > 0);
			}
			os.writeInt(pos); // End of line extra value.
			if (os.size() != firstpos) {
				throw new RuntimeException(
						"File construction logic is inconsistent. Expected: "
								+ firstpos + " position: " + os.size());
			}
			// Part 2: PIXMAP rows
			for (byte[] row : rows) {
				os.write(row, 0, row.length);
			}
			// Part 3: METADATA entries
			for (byte[] row : metadata) {
				os.write(row, 0, row.length);
			}
			// Ensure we are at the predicted position.
			if (pos != os.size()) {
				throw new RuntimeException(
						"File construction logic is inconsistent. Expected: "
								+ firstpos + " position: " + os.size());
			}
		} catch (IOException e) {
			LOG.error("IO error writing index.", e);
		}
	}

	/**
	 * Encode a line of the output image map.
	 * 
	 * @param winner
	 *            Image map
	 * @param map
	 *            Entity ID mapping
	 * @param buffer
	 *            Output buffer
	 * @return Length
	 */
	// TODO: develop even more compact RLEs for this use case.
	private int encodeLine16(int[] winner, int[] map, byte[] buffer) {
		int len = 0;
		// Perform a simple run-length encoding.
		for (int x = 0; x < winner.length;) {
			final int first = x;
			final int cur = winner[x++];
			while (x < winner.length && winner[x] == cur) {
				++x;
			}
			// Write value of map.
			len = writeUnsignedVarint(buffer, len, map[cur]);
			// Write repetition count - 1
			len = writeUnsignedVarint(buffer, len, x - first - 1);
		}
		return len;
	}

	/**
	 * Write a single varint.
	 * 
	 * @param buffer
	 *            Buffer to write to
	 * @param pos
	 *            Current position
	 * @param val
	 *            Value to write
	 * @return New position
	 */
	private static int writeUnsignedVarint(byte[] buffer, int pos, int val) {
		// Extra bytes have the high bit set
		while ((val & 0x7F) != val) {
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
	 * @param Maximum
	 *            color
	 * @param winner
	 *            Winners array
	 */
	public void visualize(int max, int[][] winner) {
		// Randomly assign colors for visualization:
		Random r = new Random();
		int[] cols = new int[max + 1];
		for (int i = 1; i < cols.length; i++) {
			cols[i] = r.nextInt(0x1000000) | 0xFF000000;
		}
		try {
			WritableImage writableImage = new WritableImage(viewport.width,
					viewport.height);
			PixelWriter writer = writableImage.getPixelWriter();
			for (int y = 0; y < viewport.height; y++) {
				// Note: visualization is drawn upside down.
				int[] row = winner[viewport.height - 1 - y];
				for (int x = 0; x < viewport.width; x++) {
					writer.setArgb(x, y, cols[row[x]]);
				}
			}
			ImageIO.write(SwingFXUtils.fromFXImage(writableImage, null), "png",
					imfile);
		} catch (IOException e) {
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

		/** Bounding box */
		BoundingBox bb;

		List<float[]> polys;

		public Entity(String key) {
			this.key = key;
		}

		@Override
		public int hashCode() {
			return key.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
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
	 * @param args
	 *            Parameters
	 */
	public static void main(String[] args) {
		launch(args);
	}
}
