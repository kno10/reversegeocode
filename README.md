# Simple (but fast) reverse Geocoding with OpenStreetMap data

The goal of this project is to build a primitive but fast reverse geocoding
(coordinates to location lookup) system.

The basic principle is simple:
- extract polygons of each administrative region on OpenStreetMap (OSM)
- build a lookup map that can be stored reasonably
- to geocode coordinates, look them up in the map

However, things with OSM are not as easy as one may expect:
- OSM is a *lot* of data. It may use up all your memory easily.
  We are talking of 2.7 *billion* nodes for the planet file,
  and 270 million ways and 3.1 million relations.
  32 bits are not enough to store the IDs.
- The dump file is not well suited for random access. Instead, you
  need to process it in sequence. There are optimized data structures in
  the PBF file format that exploit delta compression; and strings are shared
  via a dictionary - and trust me, you don't want to process the XML dump
  using a DOM parser either.

My first prototypes on the full data always were running out of memory; reducing
the data set via Osmosis did not work, as it led to missing ways. So I needed
to carefully build this in Java, to conserve memory. Osmosis reads and writes data
multiple times (to large temporary files) - I decided to design my approach around
reading the input data multiple times instead, even if this means re-reading data
unnecessarily, at the benefit of not having to write large temporary files.

## OSM Data Extraction

We do a multi-pass process.

1. In the first pass, we ignore all nodes (the majority of the data).
We remember all ways and all relations we are interested in,
but no additional metadata to conserve memory.
2. We then build an index of the nodes we will need, and forget ways that we did not use.
3. In the second pass, we look at the nodes, but only keep those that we are
interested in. Since we only need a subset, this should fit into memory now
(at least if you have a machine with a lot of memory, like I do.)
4. In the third pass (since ways or nodes might be out of sequence), we
then can output the polygons for each relation, along with some metadata.

On my system, the each pass takes about 2 minutes (reading from a network share;
likely a lot faster if I had stored the source file on my SSD).

### Data Structures

We use Goldman-Sachs collections to conserve memory. These classes are excellent
hashmaps for *primitive* data types. For nodes, we also use a two level hashmap
with prefix compression, since node ids were given in sequence not randomly (and thus
have a lot of common prefixes - in particular, the first 20+ bits of each id are usually 0).

Since our desired output resolution is much less than 0.01 degree, we also encode
each coordinate approximately using a single integer.

### Implementation Notes

While osmosis --used-way --used-node did not work for me with tag filters, it
apparently worked just fine without. Using these filters can reduce the
planet file substantially, to about 13% of the planet file. This is worth
doing as a preprocessing step. It reduces the node count from 2.1 billion to
"just" 460 million, the number of ways to 17 million (the number of relations
remains unchanged, obviously). This way, 8 GB of RAM should be enough.

As of now, you *will* need to use a Debian Linux system.
Some of the libraries are not available on Maven central, so I had to put
system paths (have a look at the pom.xml, for what you need).

## Index construction

The index essentially is a large pixmap referencing metadata from OSM.

### Rendering

Rendering is currently done via JavaFX, so you will also need to have a UI for
building an index. Unfortunately, this is also rather slow (10-30 minutes,
depending on the desired resolution and number of polygons to render). However,
we needed an API that can render polygons with the even-odd rule and antialiasing,
and the java-gnome Cairo API wouldn't allow us access the resulting bitmaps without
writing them to disk as PNG.

The JavaFX renderer has a texture limit of 8192x8192 - if we want a higher resolution,
we need to draw multiple patches, and combine them.

### File Format

The file format is designed to be low-level, compact and efficient. It is meant to
be used via read-only shared memory mapping, to make best use of operating
system caching capabilities. The compression is less than what you could obtain with
PNG encoding or GZIP, but it allows skipping over data without decoding it into
application memory.

1. 4 bytes: magic header that identifies the file format. Currently,
this is the code 0x6e06e000, and I will increment the last byte on format changes.
2. 2 bytes: width of the map
3. 2 bytes: height of the map
4. 2 bytes: number of entities (max 0x8000)
5. height * 2 bytes: length of each row
6. x bytes for each row, as listed before (row encoding: see below)
7. nument * 2 bytes: length of metadata entries
8. x bytes for each metadata

Each row is encoded using a run-length encoding. Either 2 or 3 bytes compose a run.
The highest bit indicates repetitions, the remaining 15 bit indicate the entity ID.
If the highest bit was set, the pixel is repeated 1+n times, where n is the following
byte.

Metadata is stored similarly: first a block gives the length of each entry; then the
serialized entries are given. Entries are UTF-8 encoded, are *not* 0 terminated, and
may include tab characters to separate columns. The exact column layout is not
specified in this file format.

## Visualization

The index construction will also produce a .png viusalizing the map.
