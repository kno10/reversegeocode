package com.kno10.reversegeocode.builder;

/**
 * Simple bounding box class for 2d data, longitude, latitude format.
 * 
 * Important note: boxes crossing the -180/+180 boundary are not supported!
 * 
 * @author Erich Schubert
 */
public class BoundingBox {
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