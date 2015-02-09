# Reverse-Geocoding Databases Derived from OpenStreetMap (OSM)

The data files in this folder are derived from OpenStreetMap.

OpenStreetMap data is © OpenStreetMap contributors.

For details on the license, consult the
[OpenStreetMap Copyright](http://www.openstreetmap.org/copyright),
[ODbL license text](http://opendatacommons.org/licenses/odbl/) reproduced
also in the file [LICENSE](LICENSE).

The following data files are currently provided:

## [osm-20150126-0.01.bin](osm-20150126-0.01.bin)

This data file has a resolution of 0.01 degree, and includes rather small
polygons (minimum bounding box size 4 pixels). This can sometimes be too
detailed for your usage. But on the other hand, it will recognize major
cities by name. It includes 38875 entities.

## [osm-20150126-0.02.bin](osm-20150126-0.02.bin)

This data file has the resolution reduced to 0.02 degree, with the same
bounding box threshold (4 pixels, but 4 times as large as in
the other file due to the decreased resolution).
Thus it provides a much coarser view on the data, containing 28699 entities.
It often does not even provide county level information.

## [osm-20150126-0.05.bin](osm-20150126-0.05.bin)

This data file has the resolution reduced to 0.05 degree, with 1 pixel minimum size.
It provides an even coarser view on the data, containing 35013 entities.
It often does not even provide county level information.
