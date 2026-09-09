# Road maps: continuation guide

## Current result and user feedback

Three raster MBTiles packages were built and delivered on 2026-09-09: Nazko,
Blackwater, and Quesnel–Barkerville. All include Quesnel. Source snapshot dates,
rectangular extents, feature counts, file sizes and hashes are recorded in
[the original manifest](2026-09-09-manifest.json). Total: 11,783 PNG tiles,
zoom 8–14, 256 pixels, standard TMS rows in SQLite. Integrity and every PNG
were checked; all three overview images were visually reviewed.

Dale reports that **Blackwater.mbtiles imported successfully**, but needs more
forestry road detail. This does not establish complete archive rendering,
all zoom levels, restart persistence or offline behavior on both phones.

**Next target: C Road and its affiliated branches.** The user first said V Road,
then corrected it to C Road. Its location has NOT been established. Dale will
bring the original office project map, or a screenshot/junction/coordinates.
Do not guess its identity from the local name. Office software/file format is
also unconfirmed (the user called it “mapwell”).

## Source choice and map approach

- Downloaded OpenStreetMap geometry via Overpass; no raster tile scraping.
- Queried highway ways, river ways, simple water ways and place nodes. Blackwater
  timed out as a single query and was split longitudinally, then deduplicated by
  `(element type, OSM id)`. Snapshot times differ slightly across requests.
- Rendered locally with Pillow and Shapely, Web Mercator coordinates and a spatial
  index. Main roads are gold, local roads white, tracks/minor ways brown/grey,
  water blue. Background is neutral, not a land-cover classification.
- Label priority favours settlements over locality names. There is no terrain,
  contour, imagery, live closure, bridge condition or forestry-channel layer.
- Polygon relations were not fetched; complex lakes/rivers can be incomplete.
  Small paths/services/residential ways are suppressed at overview zooms. Data
  is queried by bounding box; returned ways may extend beyond it, and outer tile
  fringes are not complete maps of adjacent areas.
- OpenStreetMap is incomplete on forestry spurs. Zooming further cannot create
  missing geometry. Do not claim these maps contain all forestry roads.

BC Digital Road Atlas and Forest Tenure Road Section Lines were investigated
but **not included**. The catalogue API returned `Access Only` and a BC terms
URL for both records. An earlier conversational recommendation overstated their
open-data status. Review actual terms before using or redistributing these
layers; do not infer permission from public download access.

Sources:
- https://www.openstreetmap.org/copyright
- https://opendatacommons.org/licenses/odbl/1-0/
- https://overpass-api.de/api/interpreter
- https://operations.osmfoundation.org/policies/tiles/
- https://catalogue.data.gov.bc.ca/dataset/digital-road-atlas-dra-master-partially-attributed-roads
- https://catalogue.data.gov.bc.ca/dataset/forest-tenure-road-section-lines

Retain OSM attribution in raster maps and accompanying documentation. Derived
geographic databases remain ODbL. The renderer's MIT licence does not license
OSM or office data. Do not add proprietary office maps to Git without checking
permitted use and the user's intended sharing scope.

## Rebuild

From repository root (Python 3.12 used originally):

```sh
python -m venv .venv
. .venv/bin/activate
python -m pip install -r tools/road-maps/requirements.txt
python tools/road-maps/fetch.py
# If Blackwater.json was not produced because of a timeout:
python tools/road-maps/fetch_blackwater.py
python tools/road-maps/build_maps.py
python tools/road-maps/validate_maps.py
```

The renderer needs DejaVuSans.ttf at its configured Linux path. Change `FONT`
for other operating systems. Scripts resolve inputs relative to themselves.
Outputs appear in `tools/road-maps/Quesnel-Road-Maps/`. Fresh fetches use current
OSM data and are **not bit-identical** to the original snapshot. Fetch scripts
skip existing complete area files; deliberately remove/archive them before a
refresh. Do not keep retrying a busy public endpoint in a tight loop.

For an offline rebuild, copy the three raw area JSON files from the delivered
ZIP's `rebuild/` directory into `tools/road-maps/` and skip fetching. The original
ZIP also includes GeoJSON extracts, preview images and rebuild scripts. The
GeoJSON is GIS source material, not an importable RadioPoint route.

Original delivery: **Quesnel-Road-Maps.zip**, 34,429,994 bytes, in Dale's ChatGPT
files. [Owner-authenticated download](https://chatgpt.com/api/library/files/libfile_847ccb1d83688191b9c011ec6b4bb3ee/download).
This is not a public or GitHub-hosted asset; an agent without Dale's file access
must obtain the ZIP from Dale or fetch a new snapshot. Generated binaries and
large raw extracts are excluded from Git. Source scripts, extents, build recipe
and original validation manifest are retained here.

After rebuilding, copy `package-instructions.txt` and the renderer licence into
the delivery, review all previews, record the new source times/hashes, and ZIP
individual .mbtiles with the notices. Never describe a fresh rebuild as the
original validated binary.

## Android integration and contribution tasks

1. Get C Road's actual location and the office map. Inspect its format, CRS,
   georeferencing, layers, coverage and usage permissions. Determine whether it
   is a vector source, GeoPDF, raster image/GeoTIFF, or proprietary export.
2. Prefer verified line geometry for branch detail. If only a georeferenced
   map is available, investigate rendering it as a basemap. Do not manufacture
   road lines from a schematic screenshot or infer kilometre calibration.
3. Keep **basemap display** separate from **route geometry**. Current import
   accepts one continuous GPX segment/route or KML LineString, not a branching
   road network. Encounter estimates need the same ordered route on both
   phones. Supporting a whole branching network is a separate app change.
4. Existing screen: MAPS → Import offline map archive → choose an extracted
   .mbtiles file → select imported map. The top road banner imports GPX/KML.
   Do not select the delivery ZIP as a tile archive. See
   `app/src/main/java/com/radiopoint/app/map/CustomMapLoader.kt` and
   `app/src/main/java/com/radiopoint/app/ui/MainActivity.kt`.
5. Test zoom bounds/overzoom. Tiles stop at 14; the current provider may allow
   zooming beyond available tiles and display blanks. No automatic bounds fit
   or zoom-limit correction was implemented in this map task.
6. Check labels/readability, offline display, app restart persistence, imports
   on both phones and memory use before expanding resolution or region size.
7. Optional improvements: configurable CLI extents/output/font, bounded retry
   and checkpointing, polygon relations, visible scale/legend and stronger
   access/abandoned/construction styling. Current renderer is a working first
   pass, not a complete cartographic or routing engine.

Checkpoint each coherent change and update HANDOFF.md. Do not promise C Road
coverage until its location and source geometry are verified.
