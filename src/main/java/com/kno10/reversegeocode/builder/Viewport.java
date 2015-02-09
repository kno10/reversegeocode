package com.kno10.reversegeocode.builder;

/**
 * Viewport information.
 * 
 * @author Erich Schubert
 */
public class Viewport {
	/** Viewport */
	double xcover, xshift, ycover, yshift;

	/** Index / image size */
	int width, height;

	/** Scaling factors */
	double xscale, yscale;

	/**
	 * Constructor.
	 * 
	 * @param xcover
	 *            Width of viewport in degree.
	 * @param ycover
	 *            Height of viewport in degree.
	 * @param xshift
	 *            Rotation offset (usually: +180)
	 * @param yshift
	 *            Rotation offset
	 * @param resolution
	 *            Resolution in degrees per pixel
	 */
	public Viewport(double xcover, double ycover, double xshift,
			double yshift, double resolution) {
		this.xcover = xcover;
		this.ycover = ycover;
		this.xshift = xshift;
		this.yshift = yshift;
		double mult = 1. / resolution; // 1/degree resolution.
		this.width = (int) Math.ceil(xcover * mult);
		this.height = (int) Math.ceil(ycover * mult);
		this.xscale = width / xcover; // approx. 1. / mult
		this.yscale = height / ycover; // approx. 1. / mult
	}

	/**
	 * Project longitude.
	 * 
	 * @param lon
	 *            Longitude
	 * @return Projected coordinate
	 */
	public double projLon(float lon) {
		return (lon + xshift) * xscale;
	}

	/**
	 * Project latitude.
	 * 
	 * @param lat
	 *            Latitude
	 * @return Projected coordinate
	 */
	public double projLat(float lat) {
		return (lat + yshift) * yscale;
	}
}