#!/usr/bin/env python3
"""
locate_c_road.py - identify which FTEN "C" section is Dale's C Road.

Method: pull Blackwater Rd centreline from DRA, chain its segments into one
ordered line, walk it to find the km 40-50 stretch, then pull every FTEN
road section named "C" nearby and measure each one's closest approach to
that stretch. The real C Road should have a junction inside it.

Because km 0 could be at either end of the assembled line, BOTH directions
are reported. Dale confirms which matches the posted signs.

    python locate_c_road.py
    python locate_c_road.py --km 35 55 --name-like C

Dependencies: standard library plus bc_albers.py. No pyproj, no shapely.

This downloads geometry even though it writes no map. Both exact records
say Access Only as of 2026-09-09. Resolve permission before live extraction -
see LICENSING-NOTES.md.
"""

import argparse
import json
import math
import sys
import urllib.parse
import urllib.request

from bc_albers import to_albers, bbox_to_albers

WFS = "https://openmaps.gov.bc.ca/geo/pub/{layer}/ows"
DRA = "WHSE_BASEMAPPING.DRA_DGTL_ROAD_ATLAS_MPAR_SP"
FTEN = "WHSE_FOREST_TENURE.FTEN_ROAD_SECTION_LINES_SVW"
TIMEOUT = 240
BBOX = (-124.60, 52.70, -122.30, 53.60)   # generous: Blackwater runs well west


def wfs(layer, cql, limit=10000):
    params = {
        "service": "WFS", "version": "2.0.0", "request": "GetFeature",
        "typeName": layer, "outputFormat": "application/json",
        "srsName": "EPSG:4326", "count": str(limit), "CQL_FILTER": cql,
    }
    url = WFS.format(layer=layer) + "?" + urllib.parse.urlencode(params)
    try:
        with urllib.request.urlopen(url, timeout=TIMEOUT) as r:
            data = json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        print(f"  HTTP {e.code}: {e.read().decode('utf-8','replace')[:400]}")
        return []
    except Exception as e:
        print(f"  request failed: {e}")
        return []
    feats = data.get("features", [])
    if len(feats) >= limit:
        print(f"  ** hit the {limit} cap - results truncated **")
    return feats


def lines_of(feat):
    """Yield each linestring of a feature as a list of (lon, lat)."""
    g = feat.get("geometry") or {}
    t, c = g.get("type"), g.get("coordinates") or []
    if t == "LineString":
        yield [(p[0], p[1]) for p in c]
    elif t == "MultiLineString":
        for part in c:
            yield [(p[0], p[1]) for p in part]


def to_xy(line):
    return [to_albers(lon, lat) for lon, lat in line]


def chain(segments, tol=60.0):
    """
    Greedily chain segments end-to-end into the longest continuous run.
    Returns (ordered_points, n_used, n_total, max_gap_bridged).

    tol is the endpoint snapping tolerance in metres. Provincial data has
    sub-metre to tens-of-metres gaps at tenure and tile boundaries.
    """
    segs = [s for s in segments if len(s) >= 2]
    if not segs:
        return [], 0, 0, 0.0

    def seglen(s):
        return sum(math.dist(s[i], s[i + 1]) for i in range(len(s) - 1))

    segs.sort(key=seglen, reverse=True)

    def grow(seed):
        """Chain outward from one seed index. Returns (pts, used_flags, gap)."""
        used = [False] * len(segs)
        pts = list(segs[seed])
        used[seed] = True
        gap = 0.0
        while True:
            head, tail = pts[0], pts[-1]
            best = None
            for i, s in enumerate(segs):
                if used[i]:
                    continue
                for end, pt, rev, at_tail in (
                    (tail, s[0], False, True), (tail, s[-1], True, True),
                    (head, s[0], True, False), (head, s[-1], False, False),
                ):
                    d = math.dist(end, pt)
                    if d <= tol and (best is None or d < best[0]):
                        best = (d, i, rev, at_tail)
            if not best:
                return pts, used, gap
            d, i, rev, at_tail = best
            s = list(reversed(segs[i])) if rev else list(segs[i])
            pts = pts + s if at_tail else s + pts
            used[i] = True
            gap = max(gap, d)

    # An isolated orphan can be the single longest segment, so seeding only
    # on the longest can return a one-segment "road". Try several seeds and
    # keep whichever produces the greatest total assembled length.
    best_result = None
    for seed in range(min(len(segs), 25)):
        pts, used, gap = grow(seed)
        total_len = sum(math.dist(pts[i], pts[i + 1]) for i in range(len(pts) - 1))
        if best_result is None or total_len > best_result[0]:
            best_result = (total_len, pts, used, gap)

    _, chain_pts, used, max_gap = best_result
    return chain_pts, sum(used), len(segs), max_gap


def cumulative(pts):
    out, run = [0.0], 0.0
    for i in range(len(pts) - 1):
        run += math.dist(pts[i], pts[i + 1])
        out.append(run)
    return out


def point_at_km(pts, cum, km):
    target = km * 1000.0
    if target > cum[-1]:
        return None
    for i in range(len(cum) - 1):
        if cum[i] <= target <= cum[i + 1]:
            span = cum[i + 1] - cum[i]
            f = 0.0 if span == 0 else (target - cum[i]) / span
            return (pts[i][0] + f * (pts[i + 1][0] - pts[i][0]),
                    pts[i][1] + f * (pts[i + 1][1] - pts[i][1]))
    return pts[-1]


def dist_point_seg(p, a, b):
    ax, ay = a; bx, by = b; px, py = p
    dx, dy = bx - ax, by - ay
    den = dx * dx + dy * dy
    if den == 0:
        return math.dist(p, a), 0.0
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / den))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy)), t


def closest_approach(cand_pts, road_pts, cum):
    """Min distance from candidate line to road, and the road km where."""
    best = (float("inf"), 0.0)
    for cp in cand_pts:
        for i in range(len(road_pts) - 1):
            d, t = dist_point_seg(cp, road_pts[i], road_pts[i + 1])
            if d < best[0]:
                seg = cum[i + 1] - cum[i]
                best = (d, (cum[i] + t * seg) / 1000.0)
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--km", nargs=2, type=float, default=[40.0, 50.0],
                    help="expected junction window in km (default 40 50)")
    ap.add_argument("--name-like", default="C",
                    help="FTEN ROAD_SECTION_NAME to match exactly (default C)")
    ap.add_argument("--road", default="Blackwater Rd",
                    help="DRA ROAD_NAME_FULL (default 'Blackwater Rd')")
    ap.add_argument("--tol", type=float, default=60.0,
                    help="endpoint chaining tolerance, metres (default 60)")
    args = ap.parse_args()

    x0, y0, x1, y1 = bbox_to_albers(*BBOX)
    box = f"BBOX(GEOMETRY,{x0:.1f},{y0:.1f},{x1:.1f},{y1:.1f})"

    print(f"1. Fetching DRA '{args.road}' ...")
    road_feats = wfs(DRA, f"{box} AND ROAD_NAME_FULL='{args.road}'")
    segs = [to_xy(l) for f in road_feats for l in lines_of(f)]
    print(f"   {len(road_feats)} features, {len(segs)} linestrings")
    if not segs:
        print("   Nothing matched. Check spelling against the DRA "
              "ROAD_NAME_FULL values from discover_roads.py.")
        return 1

    pts, used, total, gap = chain(segs, args.tol)
    cum = cumulative(pts)
    print(f"   chained {used}/{total} segments, largest gap bridged {gap:.1f} m")
    print(f"   assembled length: {cum[-1]/1000:.2f} km, {len(pts)} points")
    if used < total:
        print(f"   ** {total-used} segments did NOT connect within {args.tol} m.")
        print("      Raise --tol, or the road has genuine branches/gaps. **")

    lo, hi = args.km
    if cum[-1] / 1000 < hi:
        print(f"   ** assembled road is shorter than km {hi}; "
              f"window may be off the end **")

    print(f"\n2. Fetching FTEN sections named '{args.name_like}' ...")
    cands = wfs(FTEN, f"{box} AND ROAD_SECTION_NAME='{args.name_like}'")
    print(f"   {len(cands)} candidate features")
    if not cands:
        print("   None. Try --name-like with a different value.")
        return 1

    print(f"\n3. Closest approach to '{args.road}', both km directions:")
    print(f"   (target window: km {lo}-{hi})\n")
    rows = []
    for f in cands:
        p = f.get("properties", {})
        for line in lines_of(f):
            cp = to_xy(line)
            if len(cp) < 2:
                continue
            d, km_fwd = closest_approach(cp, pts, cum)
            km_rev = cum[-1] / 1000 - km_fwd
            rows.append((d, km_fwd, km_rev, p, cp))

    rows.sort(key=lambda r: r[0])
    for d, kf, kr, p, cp in rows[:15]:
        hit = "  <== IN WINDOW" if (lo <= kf <= hi or lo <= kr <= hi) else ""
        print(f"   {d:8.0f} m   km {kf:6.2f} fwd / {kr:6.2f} rev{hit}")
        print(f"      file={p.get('FOREST_FILE_ID')}  section={p.get('ROAD_SECTION_ID')}"
              f"  status={p.get('LIFE_CYCLE_STATUS_CODE')}")
        print(f"      client={p.get('CLIENT_NAME')}")
        print(f"      location={p.get('LOCATION')}")
        print(f"      length={p.get('ROAD_SECTION_LENGTH')}  "
              f"district={p.get('GEOGRAPHIC_DISTRICT_NAME')}")
        print()

    for km in (lo, hi):
        q = point_at_km(pts, cum, km)
        if q:
            print(f"   NOTE: km {km} forward is at Albers "
                  f"{q[0]:.0f},{q[1]:.0f} - check against a known landmark "
                  f"to confirm which end is km 0.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
