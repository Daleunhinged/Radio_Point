#!/usr/bin/env python3
"""
BC Albers (EPSG:3005) forward projection in pure Python.

Why this exists: pyproj ships compiled DLLs, and on at least one of Dale's
Windows machines a Windows Application Control policy blocks them:

    ImportError: DLL load failed while importing _sync:
    An Application Control policy has blocked this file.

That is a machine security policy, not a broken install, and reinstalling
does not fix it. Anything in this toolchain that only needs a coordinate
transform should import from here instead of pyproj so it keeps working on
locked-down office machines. shapely has the same exposure - assume it may
also be blocked and prefer stdlib geometry where practical.

Accuracy: verified against pyproj 3.8.0 at the reference points in
REFERENCE below. Agreement is exact to 4 decimal places (sub-millimetre)
across BC's extent. Run `python bc_albers.py` to re-check.

Parameters are EPSG:3005: NAD83 / GRS80, Albers Equal Area Conic,
standard parallels 50.0 and 58.5, latitude of origin 45.0,
central meridian -126.0, false easting 1000000, false northing 0.
"""

import math

A = 6378137.0                  # GRS80 semi-major axis
F = 1 / 298.257222101          # GRS80 flattening
E2 = 2 * F - F * F
E = math.sqrt(E2)

LAT_1, LAT_2, LAT_0, LON_0 = 50.0, 58.5, 45.0, -126.0
X_0, Y_0 = 1000000.0, 0.0


def _q(phi):
    """Authalic latitude helper (Snyder 3-12)."""
    s = math.sin(phi)
    return (1 - E2) * (
        s / (1 - E2 * s * s)
        - (1 / (2 * E)) * math.log((1 - E * s) / (1 + E * s))
    )


def _m(phi):
    """Snyder 14-15."""
    s = math.sin(phi)
    return math.cos(phi) / math.sqrt(1 - E2 * s * s)


_p1, _p2, _p0 = map(math.radians, (LAT_1, LAT_2, LAT_0))
_m1, _m2 = _m(_p1), _m(_p2)
_q1, _q2, _q0 = _q(_p1), _q(_p2), _q(_p0)
_N = (_m1 * _m1 - _m2 * _m2) / (_q2 - _q1)
_C = _m1 * _m1 + _N * _q1
_RHO0 = A * math.sqrt(_C - _N * _q0) / _N


def to_albers(lon, lat):
    """WGS84/NAD83 lon,lat (degrees) -> BC Albers x,y (metres)."""
    phi = math.radians(lat)
    rho = A * math.sqrt(_C - _N * _q(phi)) / _N
    theta = _N * (math.radians(lon) - math.radians(LON_0))
    return X_0 + rho * math.sin(theta), Y_0 + _RHO0 - rho * math.cos(theta)


def bbox_to_albers(min_lon, min_lat, max_lon, max_lat):
    """
    Lat/lon box -> BC Albers (minx, miny, maxx, maxy).

    Projects all four corners rather than two, because Albers is conic:
    the box edges bow, so the extreme x and y come from different corners.
    The result is very slightly larger than the input box, which is the
    safe direction for a query filter.
    """
    xs, ys = [], []
    for lon in (min_lon, max_lon):
        for lat in (min_lat, max_lat):
            x, y = to_albers(lon, lat)
            xs.append(x)
            ys.append(y)
    return min(xs), min(ys), max(xs), max(ys)


# (lon, lat, expected_x, expected_y) from pyproj 3.8.0 / PROJ.
REFERENCE = [
    (-126.0, 45.0, 1000000.0000, 0.0000),          # projection origin
    (-123.0, 53.0, 1200862.7073, 892282.3895),
    (-120.0, 50.0, 1429659.6799, 572002.9182),     # east edge of zone 10
    (-124.0, 52.85, 1134403.4117, 873191.6019),    # Blackwater box SW
    (-122.4, 53.45, 1238451.4149, 944296.9204),    # Blackwater box NE
    (-128.6, 54.5, 832052.5186, 1058502.4462),     # NW BC
    (-119.5, 49.1, 1474540.7151, 475542.6246),     # SE BC
]


def selftest(tolerance_m=0.001):
    worst = 0.0
    for lon, lat, ex, ey in REFERENCE:
        x, y = to_albers(lon, lat)
        d = math.hypot(x - ex, y - ey)
        worst = max(worst, d)
        flag = "ok " if d <= tolerance_m else "FAIL"
        print(f"  {flag} {lon:>8},{lat:>6}  {x:12.4f},{y:12.4f}  err {d:.6f} m")
    print(f"\n  worst error: {worst:.6f} m (tolerance {tolerance_m} m)")
    return worst <= tolerance_m


if __name__ == "__main__":
    import sys
    print("BC Albers (EPSG:3005) self-test against PROJ reference values:\n")
    sys.exit(0 if selftest() else 1)
