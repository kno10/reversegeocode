package com.kno10.reversegeocode.builder;

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
 * TODO: use hierarchy information to always generate e.g. country information
 * 
 * @author Erich Schubert
 */
public class ParseOSM {
	File infile, oufile;

	MutableLongObjectMap<long[]> ways = new LongObjectHashMap<long[]>();

	LongIntHierarchicalMap nodemap = new LongIntHierarchicalMap();

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
			System.err.println("Runtime pass 1: " + (end - start) / 1000 + "s");
		}
		{
			long start = System.currentTimeMillis();
			InputStream input = new FileInputStream(infile);
			new BlockInputStream(input, new SecondPass()).process();
			input.close();
			long end = System.currentTimeMillis();
			System.err.println("Runtime pass 2: " + (end - start) / 1000 + "s");
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
			System.err.println("Runtime pass 3: " + (end - start) / 1000 + "s");
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
				System.err.println(this.ncounter
						+ " nodes skipped (first pass).");
			}
		}

		@Override
		protected void parseNodes(List<Node> nodes) {
			if (nodes.size() == 0) {
				return;
			}
			this.ncounter += nodes.size();
			if (this.ncounter % 10000000 == 0) {
				System.err.println(this.ncounter
						+ " nodes skipped (first pass).");
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
				// Check for closed ways
				if (nodes[0] == nodes[rcount]) {
					int kcount = w.getKeysCount();
					for (int i = 0; i < kcount; i++) {
						String key = getStringById(w.getKeys(i));
						// FIXME: also check for boundary=administrative
						if ("admin_level".equals(key) || "boundary".equals(key)) {
							nodes[rcount] |= 2;
							break;
						}
					}
				}
				ways.put(w.getId(), nodes);
				++wcounter;
				if (wcounter % 1000000 == 0) {
					System.err.println(wcounter + " ways read.");
				}
			}
		}

		@Override
		protected void parseRelations(List<Relation> rels) {
			for (Relation r : rels) {
				parseRelation(r);
				++this.rcounter;
				if (this.rcounter % 1000000 == 0) {
					System.err.println(rcounter + " relations read.");
				}
			}
		}

		protected void parseRelation(Relation r) {
			boolean keep = false;
			int kcount = r.getKeysCount();
			for (int i = 0; i < kcount; i++) {
				String key = getStringById(r.getKeys(i));
				// boundary=administrative
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
					System.err.println("Unknown way seen: " + id
							+ " in relation: " + r.getId());
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
			System.err.println(ncounter + " nodes, " + wcounter + " ways, "
					+ rcounter + " relations");
			Runtime rt = Runtime.getRuntime();
			long used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
			System.err.println("Memory consumption (before GC): " + used);
			System.gc();
			used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
			System.err.println("Memory consumption (after GC): " + used);
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
					assert (nodemap.containsKey(data[i]));
					ncounter++;
				}
			}
			used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
			System.err.println("Memory consumption (before GC): " + used);
			System.gc();
			used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
			System.err.println("Memory consumption (after GC): " + used);
			System.err.println(ncounter + " nodes, " + wcounter + " ways, "
					+ rcounter + " relations, nodes to collect: "
					+ nodemap.computeSize());
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
			if (this.ncounter % 10000000 == 0) {
				System.err.println(this.ncounter
						+ " nodes processed (second pass).");
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
			System.err.println(ncounter + " nodes, " + n2counter + " kept.");
			Runtime rt = Runtime.getRuntime();
			long used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
			System.err.println("Memory consumption (before GC): " + used);
			System.gc();
			used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
			System.err.println("Memory consumption (after GC): " + used);
			if (n2counter == 0) {
				throw new RuntimeException("No nodes were kept.");
			}
		}
	}

	public int encodeLonLat(double lon, double lat) {
		// Round to 0.01 precision (6 digits, 5 digits)
		int ilon = (int) Math.round(lon * 100);
		int ilat = (int) Math.round(lat * 100);
		// ilon is -18000 to 18000 (roughly). Shift by 32k:
		ilon += 0x8000;
		// ilat is -9000 to 9000 (roughly). Shift by 16k:
		ilat += 0x4000;
		assert (ilon >= 0 && ilat >= 0 && ilon < 0xFFFF && ilat < 0x8000);
		int val = (ilat << 16) | ilon;
		return val;
	}

	public double decodeLon(int val) {
		val &= 0xFFFF;
		val -= 0x8000;
		return val * 0.01;
	}

	public double decodeLat(int val) {
		val >>>= 16;
		val -= 0x4000;
		return val * 0.01;
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
				System.err.println(this.ncounter
						+ " nodes skipped (first pass).");
			}
		}

		@Override
		protected void parseNodes(List<Node> nodes) {
			if (nodes.size() == 0) {
				return;
			}
			this.ncounter += nodes.size();
			if (this.ncounter % 10000000 == 0) {
				System.err.println(this.ncounter
						+ " nodes skipped (first pass).");
			}
		}

		@Override
		protected void parseWays(List<Way> ws) {
			for (Way w : ws) {
				long[] data = ways.get(w.getId());
				if (data == null || (data[data.length - 1] & 0x3L) == 2L) {
					continue;
				}
				metadata.reset(w);
				if (metadata.accept()) {
					buf.delete(0, buf.length());
					metadata.append(buf);
					int rcount = w.getRefsCount();
					long id = 0; // Delta coded!
					int last = Integer.MAX_VALUE;
					for (int i = 0; i < rcount; i++) {
						id += w.getRefs(i);
						int cur = nodemap.getIfAbsent(id, Integer.MIN_VALUE);
						if (cur == Integer.MIN_VALUE) {
							++mnode;
							// System.err.println("Missing node: " + id);
							continue;
						}
						if (cur == last) {
							continue;
						}
						double lon = decodeLon(cur), lat = decodeLat(cur);
						buf.append(String.format(Locale.ROOT, "\t%.2f,%.2f",
								lon, lat));
						last = cur;
					}
					if (rcount > 1 && last == w.getRefs(0)) { // Closed.
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
					System.err.println(wcounter + " ways read.");
				}
			}
		}

		@Override
		protected void parseRelations(List<Relation> rels) {
			for (Relation r : rels) {
				parseRelation(r);
				++this.rcounter;
				if (this.rcounter % 1000000 == 0) {
					System.err.println(rcounter + " relations read.");
				}
			}
		}

		protected void parseRelation(Relation r) {
			try {
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
						System.err.println("Unknown way seen: " + id
								+ " in relation: " + r.getId());
						continue;
					}
					String role = getStringById(r.getRolesSid(i));
					if (!"outer".equals(role) && !"inner".equals(role)) {
						continue; // Unknown role
					}
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
					// System.err.println("Missing node: " + nid);
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
					// System.err.println("Missing node: " + nid);
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
					if (metadata.name.contains("Zaragoza")) {
						System.err.append(buf);
						System.err.println(": Could not close way starting at "
								+ first + " ending " + last + " (relation id "
								+ rid + ").");
					}
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
			System.err.println(ncounter + " nodes, " + wcounter + " ways, "
					+ rcounter + " relations");
			System.err.println("Missing nodes: " + mnode);
		}

		public class Metadata {
			String levl = null, name = null, inam = null, enam = null,
					wiki = null, wikd = null, boun = null, coun = null,
					plac = null;

			public void reset() {
				levl = null;
				name = null;
				boun = null;
				inam = null;
				wiki = null;
				wikd = null;
				coun = null;
				plac = null;
			}

			public void reset(Relation r) {
				reset();
				int kcount = r.getKeysCount();
				for (int i = 0; i < kcount; i++) {
					String key = getStringById(r.getKeys(i));
					String val = getStringById(r.getVals(i));
					updateMetadata(key, val);
				}
			}

			public void reset(Way r) {
				int kcount = r.getKeysCount();
				for (int i = 0; i < kcount; i++) {
					String key = getStringById(r.getKeys(i));
					String val = getStringById(r.getVals(i));
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
				case "int_name":
					inam = val;
					break;
				case "name:en":
				case "place_name:en":
					enam = val;
					inam = (inam == null) ? val : inam;
					break;
				// Country
				case "is_in:country_code":
					coun = val;
					break;
				case "addr:country":
				case "is_in:country":
					coun = (coun == null) ? val : coun;
					break;
				case "ISO3166-1":
				case "ISO3166-2":
				case "is_in:iso_3166_1":
				case "is_in:iso_3166_2":
					coun = (coun == null) ? val.substring(0, 2) : coun;
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
				buf.append('\t').append(enam == null ? name : enam);
				buf.append('\t').append(coun == null ? "" : coun);
				buf.append('\t').append(plac == null ? "" : plac);
				buf.append('\t').append(wiki == null ? "" : wiki);
				buf.append('\t').append(wikd == null ? "" : wikd);
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
