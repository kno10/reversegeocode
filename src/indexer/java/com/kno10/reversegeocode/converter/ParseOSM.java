package com.kno10.reversegeocode.converter;

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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gs.collections.api.map.primitive.MutableLongObjectMap;
import com.gs.collections.impl.map.mutable.primitive.LongObjectHashMap;

import crosby.binary.BinaryParser;
import crosby.binary.Osmformat.DenseNodes;
import crosby.binary.Osmformat.HeaderBlock;
import crosby.binary.Osmformat.Node;
import crosby.binary.Osmformat.Relation;
import crosby.binary.Osmformat.Relation.MemberType;
import crosby.binary.Osmformat.Way;
import crosby.binary.file.BlockInputStream;

/**
 * Since I have machines with 32 GB RAM, I did not perform a whole lot of
 * optimizations yet. You could implement this with multiple passes to save
 * memory, e.g. by first processing relations only to identify ways to keep,
 * then scanning for ways only, and then reading matching nodes in the last
 * pass.
 * 
 * Theoretically, you could use Osmosis --used-way --used-node for this, but
 * this produced incomplete results for me, unfortunately.
 * 
 * This version reduces the precision to 0.01 degree, about 1113 meter at
 * equator. At this precision, coordinates can be stored as short values with
 * fixed precision (14 and 15 bit). This allows using more compact data
 * structures; and polygons can be well simplified by removing duplicate points,
 * which makes rendering faster.
 * 
 * Second and third passes could be merged, assuming that the file is correctly
 * ordered (nodes first). But the two minutes extra don't matter much.
 * 
 * @author Erich Schubert
 */
public class ParseOSM {
	/** Class logger */
	private static final Logger LOG = LoggerFactory.getLogger(ParseOSM.class);

	/** Input and output files */
	File infile, oufile;

	/** Buffer storing all ways */
	MutableLongObjectMap<long[]> ways = new LongObjectHashMap<long[]>();

	/** Map containing node positions */
	LongIntHierarchicalMap nodemap = new LongIntHierarchicalMap();

	/**
	 * Constructor
	 * 
	 * @param inname
	 *            Input file name
	 * @param outname
	 *            Output file name
	 */
	public ParseOSM(String inname, String outname) {
		super();
		this.infile = new File(inname);
		this.oufile = new File(outname);
	}

	public void process() throws IOException {
		{
			long start = System.currentTimeMillis();
			InputStream input = new FileInputStream(infile);
			new BlockInputStream(input, new FirstPass()).process();
			input.close();
			long end = System.currentTimeMillis();
			LOG.info("Runtime pass 1: {} s", (end - start) / 1000);
		}
		{
			long start = System.currentTimeMillis();
			InputStream input = new FileInputStream(infile);
			new BlockInputStream(input, new SecondPass()).process();
			input.close();
			long end = System.currentTimeMillis();
			LOG.info("Runtime pass 2: {} s", (end - start) / 1000);
		}
		{
			long start = System.currentTimeMillis();
			InputStream input = new FileInputStream(infile);
			FileWriter output = new FileWriter(oufile);
			new BlockInputStream(input, new ThirdPass(output)).process();
			input.close();
			output.flush();
			output.close();
			long end = System.currentTimeMillis();
			LOG.info("Runtime pass 3: {} s", (end - start) / 1000);
		}
	}

	class FirstPass extends BinaryParser {
		int ncounter = 0, wcounter = 0, rcounter = 0;

		@Override
		protected void parseDense(DenseNodes nodes) {
			if (nodes.getIdCount() == 0) {
				return;
			}
			this.ncounter += nodes.getIdCount();
			if (this.ncounter % 10000000 == 0) {
				LOG.info("{} nodes skipped (first pass).", this.ncounter);
			}
		}

		@Override
		protected void parseNodes(List<Node> nodes) {
			if (nodes.size() == 0) {
				return;
			}
			this.ncounter += nodes.size();
			if (this.ncounter % 10000000 == 0) {
				LOG.info("{} nodes skipped (first pass).", this.ncounter);
			}
		}

		@Override
		protected void parseWays(List<Way> ws) {
			for (Way w : ws) {
				int rcount = w.getRefsCount();
				// Keep only the data we use.
				long[] nodes = new long[rcount + 1];
				long id = 0; // Delta coded!
				for (int i = 0; i < rcount; i++) {
					id += w.getRefs(i);
					nodes[i] = id;
				}
				// Closed ways only:
				if (nodes[0] == nodes[rcount - 1]) {
					int kcount = w.getKeysCount();
					for (int i = 0; i < kcount; i++) {
						String key = getStringById(w.getKeys(i));
						// TODO: be more selective in this pass already?
						if ("admin_level".equals(key) || "boundary".equals(key)) {
							nodes[rcount] |= 2;
							break;
						}
					}
				}
				ways.put(w.getId(), nodes);
				++wcounter;
				if (wcounter % 1000000 == 0) {
					LOG.info("{} ways read (first pass).", wcounter);
				}
			}
		}

		@Override
		protected void parseRelations(List<Relation> rels) {
			for (Relation r : rels) {
				parseRelation(r);
				++this.rcounter;
				if (this.rcounter % 1000000 == 0) {
					LOG.info("{} relations read (first pass).", rcounter);
				}
			}
		}

		protected void parseRelation(Relation r) {
			boolean keep = false;
			int kcount = r.getKeysCount();
			for (int i = 0; i < kcount; i++) {
				String key = getStringById(r.getKeys(i));
				// TODO: be more selective in this pass already?
				if ("admin_level".equals(key)) {
					keep = true;
					break;
				}
			}
			if (!keep) {
				return;
			}
			int mcount = r.getMemidsCount();
			long id = 0; // Delta coded!
			for (int i = 0; i < mcount; i++) {
				id += r.getMemids(i);
				if (r.getTypes(i) != MemberType.WAY) {
					continue;
				}
				long[] nodes = ways.get(id);
				if (nodes == null) {
					LOG.info("Unknown way seen: {} in relation: {}", id,
							r.getId());
					continue;
				}
				// Mark as needed.
				nodes[nodes.length - 1] |= 1;
			}
		}

		@Override
		protected void parse(HeaderBlock header) {
			// Nothing to do.
		}

		@Override
		public void complete() {
			LOG.info("{} nodes, {} ways, {} relations", //
					ncounter, wcounter, rcounter);
			Runtime rt = Runtime.getRuntime();
			long used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
			LOG.info("Memory consumption (before GC): {} MB", used);
			System.gc();
			used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
			LOG.info("Memory consumption (after GC): {} MB", used);
			LOG.info("Shrinking memory use.");
			// Remove ways which are not needed, mark nodes that we do need.
			ncounter = 0;
			Iterator<long[]> it = ways.iterator();
			while (it.hasNext()) {
				final long[] data = it.next();
				final int l = data.length - 1;
				if (data[l] == 0L) {
					it.remove();
					--wcounter;
					continue;
				}
				for (int i = 0; i < l; i++) {
					nodemap.put(data[i], Integer.MIN_VALUE);
					ncounter++;
				}
			}
			used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
			LOG.info("Memory consumption (before GC): {} MB", used);
			System.gc();
			used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
			LOG.info("Memory consumption (after GC): {} MB", used);
			LOG.info("{} nodes, {} ways, {} relations, nodes to collect: {}",
					ncounter, wcounter, rcounter, nodemap.computeSize());
			if (nodemap.computeSize() == 0) {
				throw new RuntimeException("No nodes to keep.");
			}
		}
	}

	class SecondPass extends BinaryParser {
		int ncounter = 0, n2counter = 0;

		@Override
		protected void parseDense(DenseNodes nodes) {
			long lastId = 0, lastLat = 0, lastLon = 0;
			for (int i = 0; i < nodes.getIdCount(); i++) {
				lastId += nodes.getId(i);
				lastLat += nodes.getLat(i);
				lastLon += nodes.getLon(i);
				double lon = parseLon(lastLon), lat = parseLat(lastLat);
				processNode(lastId, lon, lat);
			}
		}

		@Override
		protected void parseNodes(List<Node> nodes) {
			for (Node n : nodes) {
				double lon = parseLon(n.getLon()), lat = parseLat(n.getLat());
				processNode(n.getId(), lon, lat);
			}
		}

		protected void processNode(long id, double lon, double lat) {
			++ncounter;
			if (ncounter % 10000000 == 0) {
				LOG.info("{} nodes processed (second pass).", ncounter);
			}
			if (!nodemap.containsKey(id)) {
				return;
			}
			++n2counter;
			nodemap.put(id, encodeLonLat(lon, lat));
		}

		@Override
		protected void parseWays(List<Way> ws) {
			// Ignore
		}

		@Override
		protected void parseRelations(List<Relation> rels) {
			// Ignore
		}

		@Override
		protected void parse(HeaderBlock header) {
			// Nothing to do.
		}

		@Override
		public void complete() {
			LOG.info("{} nodes, {} kept.", ncounter, n2counter);
			Runtime rt = Runtime.getRuntime();
			long used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
			LOG.info("Memory consumption (before GC): {} MB", used);
			System.gc();
			used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
			LOG.info("Memory consumption (after GC): {} MB", used);
			if (n2counter == 0) {
				throw new RuntimeException("No nodes were kept.");
			}
		}
	}

	public int encodeLonLat(double lon, double lat) {
		// Round to 0.01 precision (6 digits, 5 digits)
		int ilon = (int) Math.round((lon + 180) * 100);
		int ilat = (int) Math.round((lat + 90) * 100);
		assert (ilon >= 0 && ilat >= 0 && ilon < 0xFFFF && ilat < 0x8000);
		int val = (ilat << 16) | ilon;
		return val;
	}

	public double decodeLon(int val) {
		val &= 0xFFFF;
		return (val * 0.01) - 180.;
	}

	public double decodeLat(int val) {
		val >>>= 16;
		return (val * 0.01) - 90.;
	}

	class ThirdPass extends BinaryParser {
		int ncounter = 0, wcounter = 0, rcounter = 0;

		int mnode = 0;

		StringBuilder buf = new StringBuilder();

		ArrayList<long[]> fragments = new ArrayList<>();

		Appendable output;

		Metadata metadata = new Metadata();

		public ThirdPass(Appendable output) {
			this.output = output;
		}

		@Override
		protected void parseDense(DenseNodes nodes) {
			if (nodes.getIdCount() == 0) {
				return;
			}
			this.ncounter += nodes.getIdCount();
			if (this.ncounter % 10000000 == 0) {
				LOG.info("{} nodes skipped (third pass).", ncounter);
			}
		}

		@Override
		protected void parseNodes(List<Node> nodes) {
			if (nodes.size() == 0) {
				return;
			}
			this.ncounter += nodes.size();
			if (this.ncounter % 10000000 == 0) {
				LOG.info("{} nodes skipped (third pass).", ncounter);
			}
		}

		@Override
		protected void parseWays(List<Way> ws) {
			for (Way w : ws) {
				long[] data = ways.get(w.getId());
				if (data == null) {
					continue;
				}
				metadata.reset(w);
				if (metadata.accept()) {
					buf.delete(0, buf.length());
					metadata.append(buf);
					final int rcount = w.getRefsCount();
					long id = 0; // Delta coded!
					int last = Integer.MAX_VALUE;
					int wp = 0;
					for (int i = 0; i < rcount; i++) {
						id += w.getRefs(i);
						int cur = nodemap.getIfAbsent(id, Integer.MIN_VALUE);
						if (cur == Integer.MIN_VALUE) {
							++mnode;
							// LOG.info("Missing node: {}", id);
							continue;
						}
						if (cur == last) {
							continue;
						}
						double lon = decodeLon(cur), lat = decodeLat(cur);
						buf.append(String.format(Locale.ROOT, "\t%.2f,%.2f",
								lon, lat));
						wp++;
						last = cur;
					}
					if (wp > 1 && id == w.getRefs(0)) { // Closed.
						buf.append('\n');
						try {
							output.append(buf);
						} catch (IOException e) { // Can't throw IO here.
							throw new RuntimeException(e);
						}
					}
				}
				++wcounter;
				if (wcounter % 1000000 == 0) {
					LOG.info("{} ways read (third pass).", wcounter);
				}
			}
		}

		@Override
		protected void parseRelations(List<Relation> rels) {
			for (Relation r : rels) {
				parseRelation(r);
				++this.rcounter;
				if (this.rcounter % 1000000 == 0) {
					LOG.info("{} relations read (third pass).", rcounter);
				}
			}
		}

		protected void parseRelation(Relation r) {
			try {
				boolean errors = false;
				metadata.reset(r);
				if (!metadata.accept()) {
					return;
				}
				fragments.clear();
				buf.delete(0, buf.length());
				metadata.append(buf);
				int trunk = buf.length(); // For re-truncating
				int mcount = r.getMemidsCount();
				long id = 0; // Delta coded!
				for (int i = 0; i < mcount; i++) {
					id += r.getMemids(i);
					if (r.getTypes(i) != MemberType.WAY) {
						continue;
					}
					long[] nodes = ways.get(id);
					if (nodes == null) {
						if (!errors) {
							LOG.info(
									"Unknown way seen: {} in relation: {} ({})",//
									id, r.getId(), metadata.name);
						}
						errors = true;
						continue;
					}
					// NOTE: ignoring the "role" for now, used inconsistently
					// Note: last value is used for flags.
					if (nodes[0] == nodes[nodes.length - 2]) {
						int t2 = buf.length();
						appendToBuf(nodes, 0);
						if (buf.length() > t2) {
							buf.append('\n');
							output.append(buf);
							buf.delete(trunk, buf.length());
						}
					} else {
						fragments.add(nodes);
					}
				}
				if (fragments.size() > 0) {
					// Clear flags:
					for (long[] way : fragments) {
						way[way.length - 1] = 0L;
					}
					ringAssignment(fragments, r.getId(), trunk);
				}
			} catch (IOException e) { // Can't throw IO here.
				throw new RuntimeException(e);
			}
		}

		public void appendToBuf(long[] nodes, int start) {
			int last = start == 0 ? Integer.MIN_VALUE //
					: nodemap.getIfAbsent(nodes[start - 1], Integer.MIN_VALUE);
			for (int j = start, end = nodes.length - 1; j < end; j++) {
				long nid = nodes[j];
				int cur = nodemap.getIfAbsent(nid, Integer.MIN_VALUE);
				if (cur == Integer.MIN_VALUE) {
					// buf.append("\tmissing");
					++mnode;
					// LOG.info("Missing node: {}", nid);
					continue;
				}
				if (cur == last) {
					continue;
				}
				double lon = decodeLon(cur), lat = decodeLat(cur);
				buf.append(String.format(Locale.ROOT, "\t%.2f,%.2f", lon, lat));
				last = cur;
			}
		}

		public void appendToBufReverse(long[] nodes) {
			int last = nodemap.getIfAbsent(nodes[nodes.length - 2],
					Integer.MIN_VALUE);
			for (int j = nodes.length - 3; j >= 0; j--) {
				long nid = nodes[j];
				int cur = nodemap.getIfAbsent(nid, Integer.MIN_VALUE);
				if (cur == Integer.MIN_VALUE) {
					++mnode;
					// LOG.info("Missing node: {}", nid);
					continue;
				}
				if (cur == last) {
					continue;
				}
				double lon = decodeLon(cur), lat = decodeLat(cur);
				buf.append(String.format(Locale.ROOT, "\t%.2f,%.2f", lon, lat));
				last = cur;
			}
		}

		protected void ringAssignment(Collection<long[]> fragments, long rid,
				int trunk) throws IOException {
			boolean errors = false;
			for (long[] l : fragments) {
				if (l[l.length - 1] != 0L) {
					continue; // Already assigned.
				}
				l[l.length - 1] = 1; // Mark assigned.
				long first = l[0], last = l[l.length - 2];
				buf.delete(trunk, buf.length());
				int t2 = buf.length();
				appendToBuf(l, 0);
				if (ringAssignment(fragments, rid, first, last)) {
					if (buf.length() > t2) {
						buf.append('\n');
						output.append(buf);
					}
				} else {
					buf.delete(trunk, buf.length());
					if (!errors) {
						LOG.debug(
								"Could not close way {} - {} in relation {} ({})",
								first, last, rid, metadata.name);
					}
					errors = true;
				}
			}
		}

		private boolean ringAssignment(Collection<long[]> fragments, long rid,
				long stop, long cur) {
			int trunk = buf.length();
			for (long[] l : fragments) {
				if (l[l.length - 1] != 0L) {
					continue; // Already assigned.
				}
				long first = l[0], last = l[l.length - 2];
				l[l.length - 1] = 1; // Mark assigned.
				if (first == cur) {
					appendToBuf(l, 1);
					if (last == stop
							|| ringAssignment(fragments, rid, stop, last)) {
						return true;
					} // else undo
					buf.delete(trunk, buf.length());
				} else if (last == cur) {
					appendToBufReverse(l);
					if (first == stop
							|| ringAssignment(fragments, rid, stop, first)) {
						return true;
					} // else undo
					buf.delete(trunk, buf.length());
				}
				l[l.length - 1] = 0; // Mark unassigned.
			}
			return false;
		}

		@Override
		protected void parse(HeaderBlock header) {
			// Nothing to do.
		}

		@Override
		public void complete() {
			LOG.info("{} nodes, {} ways, {} relations", //
					ncounter, wcounter, rcounter);
			LOG.info("Missing nodes: {}", mnode);
		}

		public class Metadata {
			String levl = null, name = null, inam = null, wiki = null,
					wikd = null, boun = null, plac = null, osid = null;

			public void reset() {
				levl = null;
				name = null;
				inam = null;
				wiki = null;
				wikd = null;
				boun = null;
				plac = null;
				osid = null;
			}

			public void reset(Relation r) {
				reset();
				osid = "r" + r.getId();
				int kcount = r.getKeysCount();
				for (int i = 0; i < kcount; i++) {
					String key = getStringById(r.getKeys(i));
					String val = getStringById(r.getVals(i));
					updateMetadata(key, val);
				}
			}

			public void reset(Way w) {
				reset();
				osid = "w" + w.getId();
				int kcount = w.getKeysCount();
				for (int i = 0; i < kcount; i++) {
					String key = getStringById(w.getKeys(i));
					String val = getStringById(w.getVals(i));
					updateMetadata(key, val);
				}
			}

			public void updateMetadata(String key, String val) {
				if (key == null || val == null) {
					return;
				}
				switch (key) {
				case "admin_level":
					levl = val;
					break;
				case "name":
				case "place_name":
					name = val;
					break;
				case "name:en":
				case "place_name:en":
					inam = val;
					break;
				case "int_name":
					inam = (inam == null) ? val : inam;
					break;
				// Place is a type information
				case "place":
				case "de:place":
				case "boundary_type":
					plac = val;
					break;
				case "boundary":
					boun = val;
					break;
				// Wikipedia and WikiData
				case "wikipedia":
					wiki = val;
					break;
				case "wikipedia:en":
					wiki = (wiki == null) ? val : wiki;
					break;
				case "wikidata":
					wikd = val;
					break;
				}
			}

			public boolean accept() {
				return levl != null && name != null
						&& "administrative".equals(boun);
			}

			public void append(StringBuilder buf) {
				buf.append(name);
				buf.append('\t').append(inam == null ? name : inam);
				buf.append('\t').append(plac == null ? "" : plac);
				buf.append('\t').append(wiki == null ? "" : wiki);
				buf.append('\t').append(wikd == null ? "" : wikd);
				buf.append('\t').append(osid);
				buf.append('\t').append(levl);
			}
		}
	}

	public static void main(String[] args) {
		try {
			new ParseOSM(args[0], args[1]).process();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
