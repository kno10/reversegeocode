package com.kno10.reversegeocode.builder;

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
	public Viewport(double xcover, double ycover, double xshift, double yshift,
			double resolution) {
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