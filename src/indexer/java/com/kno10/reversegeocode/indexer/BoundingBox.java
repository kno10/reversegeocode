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