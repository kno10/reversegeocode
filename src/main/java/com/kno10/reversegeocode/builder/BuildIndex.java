package com.kno10.reversegeocode.builder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
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
		String infile = "/nfs/multimedia/OpenStreetMap/20150126/administrative-polys.tsv.gz";
		this.infile = new File(infile);
		// this.oufile = new File(outfile);
		this.entities = new UnifiedSet<>();

		// Viewport on map
		xcover = 360.;
		xshift = 180;
		ycover = 140.;
		yshift = 80;
		double mult = 1. / 0.05; // 1/degree resolution.
		this.width = (int) Math.ceil(xcover * mult);
		this.height = (int) Math.ceil(ycover * mult);
		this.xscale = width / xcover; // approx. 1. / mult
		this.yscale = height / ycover; // approx. 1. / mult

		double pixel_minsize = 4; // Minimum number of pixels (BB)
		this.minsize = pixel_minsize / mult;
	}

	@Override
	public void start(Stage stage) throws Exception {
		Matcher m = coordPattern.matcher("");
		StringBuilder buf = new StringBuilder();
		FloatArrayList points = new FloatArrayList();
		BoundingBox bb = new BoundingBox();
		int polycount = 0;
		// Everybody just "loves" such Java constructs:
		try (BufferedReader b = new BufferedReader(new InputStreamReader(
				new GZIPInputStream(new FileInputStream(infile))))) {
			String line = null;
			while ((line = b.readLine()) != null) {
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

			System.err.println("Have " + entities.size() + " entities, "
					+ polycount + " polgons.");

			render(stage);
		} catch (IOException e) {
			// FIXME: add logging.
			e.printStackTrace();
		}
		Platform.exit();
	}

	public void render(Stage stage) {
		Group rootGroup = new Group();
		Scene scene = new Scene(rootGroup, width, height, Color.BLACK);
		WritableImage writableImage = null; // Buffer

		int[][] winner = new int[width][height];
		byte[][] alphas = new byte[width][height];

		long start = System.currentTimeMillis();
		// Sort by size.
		ArrayList<Entity> order = new ArrayList<>(entities);
		Collections.sort(order);
		final int div = order.size() / 10; // Logging
		int c = 0;
		for (Entity e : order) {
			++c;
			if (c % div == 0) {
				System.err.format("%d%%\n", (c * 100) / order.size());
			}
			if (e.polys.size() <= 0) {
				continue;
			}
			Path path = new Path();
			ObservableList<PathElement> elems = path.getElements();
			for (float[] f : e.polys) {
				assert (f.length > 1);
				elems.add(new MoveTo((f[0] + xshift) * xscale, (f[1] - yshift)
						* -yscale));
				for (int i = 2, l = f.length; i < l; i += 2) {
					elems.add(new LineTo((f[i] + xshift) * xscale,
							(f[i + 1] - yshift) * -yscale));
				}
			}
			path.setStroke(Color.TRANSPARENT);
			path.setFill(Color.WHITE);
			path.setFillRule(FillRule.EVEN_ODD);

			rootGroup.getChildren().add(path);

			// Area to inspect
			int xmin = Math.max(0,
					(int) Math.floor((e.bb.lonmin + xshift) * xscale));
			int xmax = Math.min(width - 1,
					(int) Math.ceil((e.bb.lonmax + xshift) * xscale));
			// Note: y axis is reversed!
			int ymax = Math.min(height - 1,
					(int) Math.floor((e.bb.latmin - yshift) * -yscale));
			int ymin = Math.max(0,
					(int) Math.ceil((e.bb.latmax - yshift) * -yscale));
			// System.out.format("%d-%d %d-%d; ", xmin, xmax, ymin, ymax);

			writableImage = scene.snapshot(writableImage);
			rootGroup.getChildren().remove(path);

			PixelReader reader = writableImage.getPixelReader();
			for (int y = ymin; y <= ymax; y++) {
				for (int x = xmin; x <= xmax; x++) {
					int col = reader.getArgb(x, y);
					int alpha = (col & 0xFF);
					// Ignore cover less than 10%
					alpha -= 0x19;
					// Clip value range to positive bytes,
					// i.E. always overwrite at a coverage of >=60%
					alpha = alpha > 0x7F ? 0x7F : alpha;
					if (alpha > 0 && alpha >= alphas[x][y]) {
						alphas[x][y] = (byte) alpha;
						winner[x][y] = c;
					}
				}
			}
		}
		long end = System.currentTimeMillis();
		System.err.println("Time: " + (end - start));
		visualize(order.size(), winner);
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
				for (int x = 0; x < width; x++) {
					writer.setArgb(x, y, cols[winner[x][y]]);
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
