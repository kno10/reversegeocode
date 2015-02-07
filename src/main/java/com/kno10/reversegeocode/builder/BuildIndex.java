package com.kno10.reversegeocode.builder;

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

import com.gs.collections.impl.list.mutable.primitive.FloatArrayList;
import com.gs.collections.impl.set.mutable.UnifiedSet;

public class BuildIndex extends Application {
	File infile, oufile;

	Pattern coordPattern = Pattern
			.compile("^(-?\\d+(?:\\.\\d*)),(-?\\d+(?:\\.\\d*))$");

	private UnifiedSet<Entity> entities;

	// Minimum size of objects to draw
	double minsize;

	// Viewport
	double xcover, xshift, ycover, yshift;

	// Image size
	int width, height;

	// Scaling factors
	double xscale, yscale;

	public BuildIndex() {
		super();
		this.entities = new UnifiedSet<>();

		// Viewport on map
		xcover = 360.;
		xshift = 180;
		ycover = 140.;
		yshift = 60;
		double mult = 1. / 0.01; // 1/degree resolution.
		this.width = (int) Math.ceil(xcover * mult);
		this.height = (int) Math.ceil(ycover * mult);
		this.xscale = width / xcover; // approx. 1. / mult
		this.yscale = height / ycover; // approx. 1. / mult

		double pixel_minsize = 20; // Minimum number of pixels (BB)
		this.minsize = pixel_minsize / mult;
	}

	@Override
	public void init() throws Exception {
		super.init();
		List<String> unnamed = getParameters().getUnnamed();
		this.infile = new File(unnamed.get(0));
		this.oufile = new File(unnamed.get(1));
	}

	@Override
	public void start(Stage stage) throws Exception {
		Matcher m = coordPattern.matcher("");
		StringBuilder buf = new StringBuilder();
		FloatArrayList points = new FloatArrayList();
		BoundingBox bb = new BoundingBox();
		int polycount = 0, lines = 0;
		// Everybody just "loves" such Java constructs:
		try (BufferedReader b = new BufferedReader(new InputStreamReader(
				new GZIPInputStream(new FileInputStream(infile))))) {
			long start = System.currentTimeMillis();
			String line = null;
			while ((line = b.readLine()) != null) {
				++lines;
				buf.delete(0, buf.length()); // Reset the buffer
				points.clear();
				bb.reset();
				String[] cols = line.split("\t");
				int nummeta = 0;
				for (int i = 0; i < cols.length; i++) {
					if (m.reset(cols[i]).matches()) {
						float lon = Float.parseFloat(m.group(1));
						float lat = Float.parseFloat(m.group(2));
						points.add(lon);
						points.add(lat);
						bb.update(lon, lat);
					} else {
						assert (points.isEmpty());
						if (nummeta > 0) {
							buf.append('\t');
						}
						buf.append(cols[i]);
						++nummeta;
					}
				}
				if (bb.size() < minsize) {
					continue;
				}
				Entity ent = new Entity(buf.toString());
				Entity exist = entities.get(ent);
				if (exist != null) {
					exist.bb.update(bb);
					exist.polys.add(points.toArray());
					++polycount;
				} else {
					entities.add(ent);
					ent.bb = new BoundingBox(bb);
					ent.polys = new LinkedList<>();
					ent.polys.add(points.toArray());
					++polycount;
				}
			}

			long end = System.currentTimeMillis();
			System.err.println("Parsing time: " + (end - start) + " ms");
			System.err.println("Read " + lines + " lines, kept "
					+ entities.size() + " entities, " + polycount
					+ " polygons.");

			render(stage);
		} catch (IOException e) {
			// FIXME: add logging.
			e.printStackTrace();
		}
		Platform.exit();
	}

	public void render(Stage stage) {
		final int blocksize = 1024;
		Group rootGroup = new Group();
		Scene scene = new Scene(rootGroup, blocksize, blocksize, Color.BLACK);
		WritableImage writableImage = null; // Buffer

		int[][] winner = new int[height][width];
		byte[][] alphas = new byte[height][width];

		long start = System.currentTimeMillis();
		// Sort by size.
		ArrayList<Entity> order = new ArrayList<>(entities);
		Collections.sort(order);
		final int div = order.size() / 10; // Logging
		int c = 0;
		Path path = new Path();
		ObservableList<PathElement> elems = path.getElements();
		for (Entity e : order) {
			++c;
			if (c % div == 0) {
				System.err
						.format("Drawing %.0f%%\n", (c * 100.) / order.size());
			}
			if (e.polys.size() <= 0) {
				continue;
			}
			// Area to inspect
			int xmin = Math.max(0,
					(int) Math.floor((e.bb.lonmin + xshift) * xscale) - 1);
			int xmax = Math.min(width,
					(int) Math.ceil((e.bb.lonmax + xshift) * xscale) + 1);
			int ymin = Math.max(0,
					(int) Math.ceil((e.bb.latmin + yshift) * yscale) - 1);
			int ymax = Math.min(height,
					(int) Math.floor((e.bb.latmax + yshift) * yscale) + 1);
			// System.out.format("%d-%d %d-%d; ", xmin, xmax, ymin, ymax);
			for (int x1 = xmin; x1 < xmax; x1 += blocksize) {
				int x2 = Math.min(x1 + blocksize, xmax);
				for (int y1 = ymin; y1 < ymax; y1 += blocksize) {
					int y2 = Math.min(y1 + blocksize, ymax);

					// Implementation note: we are drawing upside down.
					elems.clear();
					for (float[] f : e.polys) {
						assert (f.length > 1);
						elems.add(new MoveTo((f[0] + xshift) * xscale - x1, //
								(f[1] + yshift) * yscale - y1));
						for (int i = 2, l = f.length; i < l; i += 2) {
							elems.add(new LineTo((f[i] + xshift) * xscale - x1, //
									(f[i + 1] + yshift) * yscale - y1));
						}
					}
					path.setStroke(Color.TRANSPARENT);
					path.setFill(Color.WHITE);
					path.setFillRule(FillRule.EVEN_ODD);

					rootGroup.getChildren().add(path);

					writableImage = scene.snapshot(writableImage);
					rootGroup.getChildren().remove(path);

					transferPixels(writableImage, x1, x2, y1, y2, winner, c,
							alphas);
				}
			}
		}
		long end = System.currentTimeMillis();
		System.err.println("Rendering time: " + (end - start) + " ms");

		buildIndex(order, winner);
		visualize(order.size(), winner);
	}

	public void transferPixels(WritableImage img, int x1, int x2, int y1,
			int y2, int[][] winner, int c, byte[][] alphas) {
		PixelReader reader = img.getPixelReader();
		for (int y = y1; y < y2; y++) {
			for (int x = x1; x < x2; x++) {
				int col = reader.getArgb(x - x1, y - y1);
				int alpha = (col & 0xFF);
				// Always ignore cover less than 10%
				if (alpha < 0x19) {
					continue;
				}
				// Clip value range to positive bytes,
				alpha = alpha > 0x7F ? 0x7F : alpha;
				if (alpha == 0x7F || (alpha > 0 && alpha >= alphas[y][x])) {
					alphas[y][x] = (byte) alpha;
					winner[y][x] = c;
				}
			}
		}
	}

	private void buildIndex(ArrayList<Entity> order, int[][] winner) {
		int[] map = new int[order.size() + 1];
		// Scan pixels for used indexes.
		for (int y = 0; y < height; y++) {
			int[] row = winner[y];
			for (int x = 0; x < width; x++) {
				map[row[x]] = 1;
			}
		}
		// Enumerate used indexes.
		int c = 0;
		for (int i = 0; i < map.length; i++) {
			map[i] = (map[i] == 0) ? -1 : c++;
		}
		System.err.println("Number of used entities: " + c);
		byte[] buffer = new byte[width * 8]; // Output buffer.

		if (c > 0x8000) {
			// In this case, you'll need to extend the file format below.
			throw new RuntimeException(
					"Current file version only allows 0x8000 entities.");
		}

		try (DataOutputStream os = new DataOutputStream(//
				new FileOutputStream(oufile))) {
			// Part 1: HEADER
			// Write a "magic" header first.
			os.writeInt(0x6e0_6e0_00);
			// Write dimensions
			os.writeShort(width);
			os.writeShort(height);
			// Write the number of indexes
			os.writeShort(c);

			// Part 2: PIXMAP rows
			// Encode the rows
			byte[][] rows = new byte[height][];
			for (int y = 0; y < height; y++) {
				int len = encodeLine(winner[y], map, buffer);
				rows[y] = Arrays.copyOf(buffer, len);
			}
			// Write the row header table
			for (int y = 0; y < height; y++) {
				os.writeShort(rows[y].length);
			}
			// Write the row header table
			for (byte[] row : rows) {
				os.write(row, 0, row.length);
			}
			rows = null;

			// Part 3: METADATA
			byte[][] metadata = new byte[c][];
			metadata[0] = "Earth".getBytes("UTF-8");
			int c2 = 1;
			for (int i = 1; i < map.length; i++) {
				if (map[i] > -1) {
					metadata[c2++] = order.get(i - 1).key.getBytes("UTF-8");
				}
			}
			assert (c2 == c);
			// Write the metadata header table
			for (int y = 0; y < height; y++) {
				os.writeShort(metadata[y].length);
			}
			// Write the metadata header table
			for (byte[] row : metadata) {
				os.write(row, 0, row.length);
			}
			metadata = null;
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// TODO: develop even more compact RLEs for this use case.
	private int encodeLine(int[] winner, int[] map, byte[] buffer) {
		int len = 0;
		// Perform a simple run-length encoding.
		for (int x = 0; x < winner.length; ++x) {
			final int cur = winner[x];
			int run = 0; // Run length - 1
			for (; run < 256 && x + 1 < winner.length && winner[x + 1] == cur; ++x) {
				++run;
			}
			int val = map[cur];
			assert (val <= 0x7FFF);
			if (run > 0) {
				buffer[len++] = (byte) (((val >>> 8) & 0xFF) | 0x80);
				buffer[len++] = (byte) (val & 0xFF);
				buffer[len++] = (byte) (run - 1);
			} else { // Note: high bit must not be set!
				buffer[len++] = (byte) ((val >>> 8) & 0xFF);
				buffer[len++] = (byte) (val & 0xFF);
			}
		}
		return len;
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
			WritableImage writableImage = new WritableImage(width, height);
			PixelWriter writer = writableImage.getPixelWriter();
			for (int y = 0; y < height; y++) {
				int[] row = winner[height - 1 - y]; // Note: upside down
				for (int x = 0; x < width; x++) {
					writer.setArgb(x, y, cols[row[x]]);
				}
			}
			ImageIO.write(SwingFXUtils.fromFXImage(writableImage, null), "png",
					new File("output.png"));
		} catch (IOException e) {
			// TODO Use logging
			e.printStackTrace();
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
	 * Simple bounding box class for 2d data.
	 * 
	 * Important note: boxes crossing the -180/+180 boundary are not supported!
	 * 
	 * @author Erich Schubert
	 */
	public static class BoundingBox {
		/** Bounding box */
		float lonmin, lonmax, latmin, latmax;

		/**
		 * Constructor.
		 */
		public BoundingBox() {
			super();
			reset();
		}

		/**
		 * Clone constructor.
		 */
		public BoundingBox(BoundingBox other) {
			super();
			lonmin = other.lonmin;
			lonmax = other.lonmax;
			latmin = other.latmin;
			latmax = other.latmax;
		}

		/**
		 * Test whether a point is inside the bounding box.
		 * 
		 * @param lon
		 *            Longitude
		 * @param lat
		 *            Latitude
		 * @return {@code true} when inside
		 */
		public boolean inside(float lon, float lat) {
			return lonmin <= lon && lon <= lonmax && //
					latmin <= lat && lat <= latmax;
		}

		/**
		 * Width of the bb in degree.
		 * 
		 * @return Width
		 */
		public float width() {
			return lonmax - lonmin;
		}

		/**
		 * Height of the bb in degree.
		 * 
		 * @return Height
		 */
		public float height() {
			return latmax - latmin;
		}

		/**
		 * Area (unprojected) of the bounding box.
		 * 
		 * @return Area
		 */
		public double size() {
			return (lonmax - lonmin) * (latmax - latmin);
		}

		/**
		 * Reset the bounding box.
		 */
		public void reset() {
			lonmin = Float.POSITIVE_INFINITY;
			lonmax = Float.NEGATIVE_INFINITY;
			latmin = Float.POSITIVE_INFINITY;
			latmax = Float.NEGATIVE_INFINITY;
		}

		/**
		 * Update the bounding box with new data.
		 * 
		 * @param lon
		 *            Longitude
		 * @param lat
		 *            Latitude
		 */
		public void update(float lon, float lat) {
			lonmin = lon < lonmin ? lon : lonmin;
			lonmax = lon > lonmax ? lon : lonmax;
			latmin = lat < latmin ? lat : latmin;
			latmax = lat > latmax ? lat : latmax;
		}

		/**
		 * Update the bounding box with new data.
		 * 
		 * @param other
		 *            Other bounding box
		 */
		public void update(BoundingBox other) {
			lonmin = other.lonmin < lonmin ? other.lonmin : lonmin;
			lonmax = other.lonmax > lonmax ? other.lonmax : lonmax;
			latmin = other.latmin < latmin ? other.latmin : latmin;
			latmax = other.latmax > latmax ? other.latmax : latmax;
		}
	}

	public static void main(String[] args) {
		launch(args);
	}
}
