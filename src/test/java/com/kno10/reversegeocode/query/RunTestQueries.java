package com.kno10.reversegeocode.query;

import java.io.IOException;
import java.util.Locale;

/**
 * Simple class to perform a few test queries
 * 
 * @author Erich Schubert
 */
public class RunTestQueries {
	// Queries to run
	static float[][] data = new float[][] {//
	{ -73.9865812f, 40.7305991f }, // New York
			{ 11.5754815f, 48.1372719f }, // Munich
			{ 11.61406f, 48.06596f }, // Near Munich
			{ 0f, 0f }, // Deep sea
	};

	public static void main(String[] args) {
		try {
			ReverseGeocoder rgc = new ReverseGeocoder(args[0]);
			for (float[] f : data) {
				System.out.format(Locale.ROOT, "%8.4f %8.4f %s\n", f[0], f[1],
						rgc.lookup(f[0], f[1]));
			}
			rgc.close();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
	}
}
