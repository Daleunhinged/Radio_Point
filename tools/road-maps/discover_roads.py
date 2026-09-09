#!/usr/bin/env python3
"""
discover_roads.py - find out what BC actually calls a road before extracting it.

This prints attributes and does not build maps. The current WFS request can
also download geometry because it does not specify propertyName. It asks the BC Geographic
Warehouse which road features fall inside a lat/lon box and prints the
distinct attribute values, so you can see the real filter values instead of
guessing at names. Read-only; writes nothing.

Intended use: identifying C Road and its branches off Blackwater Road.
Dale reports the junction sits between km 40 and km 50 on Blackwater.

    python discover_roads.py
    python discover_roads.py --bbox -124.0 52.85 -122.4 53.45
    python discover_roads.py --layer FTEN --grep blackwater

Dependencies: none beyond the Python standard library. This is deliberate.
pyproj and shapely ship compiled DLLs which a Windows Application Control
policy blocks on at least one of Dale's machines; the projection lives in
bc_albers.py in pure Python for the same reason.

LICENSING - READ BEFORE REDISTRIBUTING ANYTHING DERIVED FROM THIS:
See LICENSING-NOTES.md in this directory. Both exact catalogue records were rechecked on 2026-09-09 and say Access Only.
Their linked policy requires written permission for reproduction. This
script only PRINTS attribute values; its response may contain geometry. Do not ship
derived geometry from these layers until the terms are confirmed.
"""

import argparse
import collections
import json
import sys
import urllib.parse
import urllib.request

from bc_albers import bbox_to_albers

WFS = "https://openmaps.gov.bc.ca/geo/pub/{layer}/ows"
TIMEOUT = 180

LAYERS = {
    "DRA": "WHSE_BASEMAPPING.DRA_DGTL_ROAD_ATLAS_MPAR_SP",
    "FTEN": "WHSE_FOREST_TENURE.FTEN_ROAD_SECTION_LINES_SVW",
}

# Blackwater corridor west of Quesnel (min_lon, min_lat, max_lon, max_lat).
DEFAULT_BBOX = (-124.00, 52.85, -122.40, 53.45)

# NOTE: FTEN_ROAD_SEGMENT_POLY_SVW is NOT a road centreline. BC's own
# metadata says it is the application centreline buffered 37.5 m either side
# and "does not represent the actual road on the ground". Do not use it.


def fetch(layer, bbox, limit):
    x0, y0, x1, y1 = bbox_to_albers(*bbox)
    params = {
        "service": "WFS",
        "version": "2.0.0",
        "request": "GetFeature",
        "typeName": layer,
        "outputFormat": "application/json",
        "srsName": "EPSG:4326",
        "count": str(limit),
        "CQL_FILTER": f"BBOX(GEOMETRY,{x0:.1f},{y0:.1f},{x1:.1f},{y1:.1f})",
    }
    url = WFS.format(layer=layer) + "?" + urllib.parse.urlencode(params)
    try:
        with urllib.request.urlopen(url, timeout=TIMEOUT) as r:
            raw = r.read().decode("utf-8", "replace")
            print(f"  HTTP {r.status}")
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")[:800]
        print(f"  HTTP {e.code}\n  {body}")
        return None
    except Exception as e:
        print(f"  request failed: {e}")
        return None

    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        print("  server did not return JSON. First 800 chars:")
        print("  " + raw[:800])
        return None


def report(tag, layer, bbox, limit, grep, max_distinct):
    print("=" * 72)
    print(f"{tag}: {layer}")
    print("=" * 72)

    gj = fetch(layer, bbox, limit)
    if gj is None:
        return

    feats = gj.get("features", [])
    print(f"  features returned: {len(feats)}")
    if len(feats) >= limit:
        print(f"  ** hit the {limit} cap - results truncated, "
              f"shrink --bbox or raise --limit **")
    if not feats:
        print("  Nothing here. Widen --bbox, or the layer name may be wrong.")
        return

    keys = sorted({k for f in feats for k in f.get("properties", {})})
    print(f"  attributes: {', '.join(keys)}")

    for field in keys:
        vals = collections.Counter(
            str(f["properties"].get(field)) for f in feats
        )
        for empty in ("None", "", " "):
            vals.pop(empty, None)
        if not vals or len(vals) > max_distinct:
            continue
        if max(len(v) for v in vals) > 60:
            continue
        print(f"\n  --- {field} ({len(vals)} distinct) ---")
        for val, n in vals.most_common(25):
            print(f"      {n:5d}  {val}")

    print(f"\n  --- matches for {grep!r} ---")
    hits = collections.Counter()
    for f in feats:
        for k, v in f["properties"].items():
            if grep in str(v).lower():
                hits[f"{k} = {v}"] += 1
    if hits:
        for h, n in hits.most_common(60):
            print(f"      {n:5d}  {h}")
    else:
        print("      none by name.")
        print("      Expected for C Road - forestry branches are usually")
        print("      filed under a road permit ID, not a local name. Use the")
        print("      km 40-50 junction to identify it visually in QGIS.")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bbox", nargs=4, type=float, metavar=("MINLON", "MINLAT", "MAXLON", "MAXLAT"),
                    default=list(DEFAULT_BBOX))
    ap.add_argument("--layer", choices=sorted(LAYERS) + ["ALL"], default="ALL")
    ap.add_argument("--grep", default="blackwater",
                    help="substring to hunt for, lowercase (default: blackwater)")
    ap.add_argument("--limit", type=int, default=6000)
    ap.add_argument("--max-distinct", type=int, default=300,
                    help="skip fields with more distinct values than this")
    args = ap.parse_args()

    bbox = tuple(args.bbox)
    print(f"bbox (WGS84 lon/lat): {bbox}")
    print(f"bbox (BC Albers)    : "
          f"{tuple(round(v) for v in bbox_to_albers(*bbox))}\n")

    chosen = LAYERS if args.layer == "ALL" else {args.layer: LAYERS[args.layer]}
    for tag, layer in chosen.items():
        report(tag, layer, bbox, args.limit, args.grep.lower(), args.max_distinct)
        print()

    print("Data queried from the BC Geographic Warehouse. Confirm licence "
          "terms before redistributing anything derived from it - see "
          "LICENSING-NOTES.md.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
