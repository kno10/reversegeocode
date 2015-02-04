package com.kno10.reversegeocode.builder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.freedesktop.cairo.Context;
import org.freedesktop.cairo.Format;
import org.freedesktop.cairo.ImageSurface;
import org.gnome.gtk.Gtk;

import com.gs.collections.impl.list.mutable.primitive.FloatArrayList;
import com.gs.collections.impl.set.mutable.UnifiedSet;

public class BuildIndex {
	File infile, oufile;

	Pattern coordPattern = Pattern
			.compile("^(-?\\d+(?:\\.\\d*)),(-?\\d+(?:\\.\\d*))$");

	private UnifiedSet<Entity> entities;

	// Minimum size of objects to draw
	float minlw = .1f, minlh = .1f;

	public BuildIndex(String infile, String outfile) {
		super();
		this.infile = new File(infile);
		this.oufile = new File(outfile);
		this.entities = new UnifiedSet<>();
	}

	private void run() {
		Matcher m = coordPattern.matcher("");
		StringBuilder buf = new StringBuilder();
		FloatArrayList points = new FloatArrayList();
		BoundingBox bb = new BoundingBox();
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
				if (bb.width() < minlw || bb.height() < minlh) {
					continue;
				}
				Entity ent = new Entity(buf.toString());
				Entity exist = entities.get(ent);
				if (exist != null) {
					exist.bb.update(bb);
					exist.polys.add(points.toArray());
				} else {
					entities.add(ent);
					ent.bb = new BoundingBox(bb);
					ent.polys = new LinkedList<>();
					ent.polys.add(points.toArray());
				}
			}
		} catch (IOException e) {
			// FIXME: add logging.
			e.printStackTrace();
		}

		Gtk.init(new String[] {});
		ImageSurface surface = new ImageSurface(Format.ARGB32, 3600, 1800);
		Context ctx = new Context(surface);
		{
			double xcover = 360., xshift = 0;
			double ycover = 140., yshift = 80;
			double mult = 20; // 0.05 degree resolution.
			int width = (int) Math.ceil(xcover * mult), height = (int) Math
					.ceil(ycover * mult);
			double xscale = width / xcover, yscale = height / ycover;

			ctx.translate(xshift * xscale, yshift * yscale);
			ctx.scale(xscale, -yscale);
		}
	}

	public static class Entity {
		final String key;

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
		new BuildIndex(args[0], args[1]).run();
	}

}
