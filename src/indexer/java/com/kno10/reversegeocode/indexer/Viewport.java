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
   * @param xcover Width of viewport in degree.
   * @param ycover Height of viewport in degree.
   * @param xshift Rotation offset (usually: +180)
   * @param yshift Rotation offset
   * @param resolution Resolution in degrees per pixel
   */
  public Viewport(double xcover, double ycover, double xshift, double yshift, double resolution) {
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
   * Slice constructor.
   *
   * @param existing Existing viewport
   * @param x X start (in pixels!)
   * @param y Y start (in pixels!)
   * @param w Width (in pixels!)
   * @param h Height (in pixels!)
   */
  public Viewport(Viewport existing, int x, int y, int w, int h) {
    this.width = Math.min(existing.width - x, w);
    this.height = Math.min(existing.height - y, h);
    this.xcover = existing.xcover * width / (double) existing.width;
    this.ycover = existing.ycover * height / (double) existing.height;
    this.xscale = existing.xscale;
    this.yscale = existing.yscale;
    this.xshift = existing.xshift - x / xscale;
    this.yshift = existing.yshift - y / yscale;
  }

  /**
   * Project longitude.
   *
   * @param lon Longitude
   * @return Projected coordinate
   */
  public double projLon(float lon) {
    return (lon + xshift) * xscale;
  }

  /**
   * Project latitude.
   *
   * @param lat Latitude
   * @return Projected coordinate
   */
  public double projLat(float lat) {
    return (lat + yshift) * yscale;
  }

  @Override
  public String toString() {
    return "Viewport [xcover=" + xcover + ", xshift=" + xshift + ", ycover=" + ycover + ", yshift=" + yshift + ", width=" + width + ", height=" + height + ", xscale=" + xscale + ", yscale=" + yscale + "]";
  }
}
