#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Build an offline map pack for the Moto Nav plugin.

A pack is one MBTiles file: a whole region at the zooms you plan with, plus a
narrow corridor at the zooms you actually ride with. That split is the entire
idea, and it is what turns seventeen gigabytes into a few hundred megabytes
without losing any detail where the wheels are.

Tiles are read from a tile server you run yourself. That is not a detail: bulk
downloading from OpenStreetMap's own servers is against their tile usage policy,
and a pack is by definition thousands of requests. Run the standard stack
locally from a Geofabrik extract and this reads from localhost, where there is
no policy to break and no rate to respect.

    docker run -v /path/italy-isole-latest.osm.pbf:/data/region.osm.pbf \\
        -v osm-data:/data/database/ overv/openstreetmap-tile-server import
    docker run -p 8080:80 -v osm-data:/data/database/ \\
        -d overv/openstreetmap-tile-server run

Then, for a ride:

    python mappack.py --region sicilia --gpx giro.gpx --out sicilia.mbtiles
    python mappack.py --region sicilia --gpx giro.gpx --out sicilia.mbtiles --push

--dry-run answers "how big will this be" before spending a night on it.
"""

import argparse
import math
import os
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ElementTree
from concurrent.futures import ThreadPoolExecutor

# Regions worth naming, so the common case is one word rather than four numbers.
REGIONS = {
    "sicilia": (12.35, 36.60, 15.70, 38.35),
    "sardegna": (8.10, 38.85, 9.85, 41.30),
    "calabria": (15.60, 37.90, 17.25, 40.15),
    "puglia": (14.90, 39.75, 18.55, 42.25),
    "campania": (13.75, 39.95, 15.85, 41.55),
    "toscana": (9.65, 42.20, 12.40, 44.50),
    "lazio": (11.40, 40.75, 14.05, 42.85),
    "piemonte": (6.60, 44.05, 9.25, 46.50),
    "lombardia": (8.45, 44.65, 11.45, 46.65),
    "trentino": (10.35, 45.65, 12.50, 47.10),
}

TILE_PIXELS = 256

# What one PNG of the standard style weighs, on average, over a mixed region.
# Only used to answer "how big" before anything is downloaded.
AVERAGE_TILE_KB = 18.0


# ---------------------------------------------------------------------------
# geometry
# ---------------------------------------------------------------------------

def tile_x(longitude, zoom):
    return (longitude + 180.0) / 360.0 * (2 ** zoom)


def tile_y(latitude, zoom):
    radians = math.radians(max(-85.05, min(85.05, latitude)))
    return (1.0 - math.log(math.tan(radians) + 1.0 / math.cos(radians)) / math.pi) / 2.0 * (2 ** zoom)


def meters_per_pixel(latitude, zoom):
    return 156543.03392 * math.cos(math.radians(latitude)) / (2 ** zoom)


def read_gpx(path):
    """Every point of a GPX, whatever tag it hides behind and whatever namespace."""
    points = []
    for element in ElementTree.parse(path).getroot().iter():
        tag = element.tag.rsplit("}", 1)[-1].lower()
        if tag in ("trkpt", "rtept", "wpt"):
            try:
                points.append((float(element.get("lat")), float(element.get("lon"))))
            except (TypeError, ValueError):
                pass
    return points


def region_tiles(bbox, zoom):
    west, south, east, north = bbox
    x0 = int(math.floor(tile_x(west, zoom)))
    x1 = int(math.ceil(tile_x(east, zoom)))
    y0 = int(math.floor(tile_y(north, zoom)))
    y1 = int(math.ceil(tile_y(south, zoom)))
    limit = 1 << zoom
    for x in range(max(0, x0), min(limit, x1 + 1)):
        for y in range(max(0, y0), min(limit, y1 + 1)):
            yield (zoom, x, y)


def corridor_tiles(points, zoom, half_width_m):
    """
    Every tile within half_width_m of the line through points.

    Walked rather than buffered: the track is densified to a step smaller than a
    tile, and around each sample a small square of tiles is taken. Crude, and
    exactly right - the answer only has to be a superset of what the screen will
    ask for, and a set makes the overlap free.
    """
    if not points:
        return set()

    keys = set()
    latitude = sum(p[0] for p in points) / len(points)
    tile_m = meters_per_pixel(latitude, zoom) * TILE_PIXELS
    step_m = max(20.0, tile_m / 3.0)
    radius = int(math.ceil(half_width_m / tile_m)) + 1
    limit = 1 << zoom

    def add(lat, lon):
        cx = int(tile_x(lon, zoom))
        cy = int(tile_y(lat, zoom))
        for x in range(cx - radius, cx + radius + 1):
            for y in range(cy - radius, cy + radius + 1):
                if 0 <= x < limit and 0 <= y < limit:
                    keys.add((zoom, x, y))

    previous = points[0]
    add(previous[0], previous[1])
    for current in points[1:]:
        dy = (current[0] - previous[0]) * 111132.0
        dx = (current[1] - previous[1]) * 111132.0 * math.cos(math.radians(previous[0]))
        span = math.hypot(dx, dy)
        if span > step_m:
            for i in range(1, int(span / step_m) + 1):
                fraction = i * step_m / span
                add(
                    previous[0] + (current[0] - previous[0]) * fraction,
                    previous[1] + (current[1] - previous[1]) * fraction,
                )
        add(current[0], current[1])
        previous = current
    return keys


def parse_zooms(text):
    if "-" in text:
        low, high = text.split("-", 1)
        return list(range(int(low), int(high) + 1))
    return [int(text)]


def human_size(megabytes):
    if megabytes < 1024:
        return "%.0f MB" % megabytes
    return "%.1f GB" % (megabytes / 1024.0)


# ---------------------------------------------------------------------------
# the pack
# ---------------------------------------------------------------------------

def open_pack(path, name):
    database = sqlite3.connect(path)
    database.execute("PRAGMA journal_mode=OFF")
    database.execute("PRAGMA synchronous=OFF")
    database.execute(
        "CREATE TABLE IF NOT EXISTS tiles ("
        "zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)"
    )
    database.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS tile_index "
        "ON tiles (zoom_level, tile_column, tile_row)"
    )
    database.execute("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
    database.execute("DELETE FROM metadata")
    database.executemany(
        "INSERT INTO metadata (name, value) VALUES (?, ?)",
        [("name", name), ("format", "png"), ("type", "baselayer"), ("version", "1")],
    )
    database.commit()
    return database


def existing_keys(database):
    rows = database.execute(
        "SELECT zoom_level, tile_column, tile_row FROM tiles").fetchall()
    # Stored TMS, wanted XYZ: undo the flip so the two sets can be compared.
    return set((z, x, (1 << z) - 1 - y) for z, x, y in rows)


def build_arguments():
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--region", help="a named region; see the list in the source")
    parser.add_argument("--bbox", help="west,south,east,north in degrees")
    parser.add_argument("--gpx", action="append", default=[],
                        help="a route to cover in detail; may be repeated")
    parser.add_argument("--out", default="pack.mbtiles")
    parser.add_argument("--overview-zoom", default="7-13",
                        help="zooms covering the whole region (default 7-13)")
    parser.add_argument("--corridor-zoom", default="14-17",
                        help="zooms covering the routes (default 14-17)")
    parser.add_argument("--corridor-width", type=float, default=2000.0,
                        help="corridor width in metres (default 2000)")
    parser.add_argument("--source", default="http://localhost:8080/tile/{z}/{x}/{y}.png")
    parser.add_argument("--workers", type=int, default=16)
    parser.add_argument("--dry-run", action="store_true",
                        help="count and size it, download nothing")
    parser.add_argument("--push", action="store_true",
                        help="copy into the plugin's pack folder over adb")
    parser.add_argument("--package", default="com.zetaforge.app")
    return parser


def main():
    parser = build_arguments()
    args = parser.parse_args()

    if args.bbox:
        bbox = tuple(float(v) for v in args.bbox.split(","))
    elif args.region:
        key = args.region.lower()
        if key not in REGIONS:
            parser.error("unknown region %r; known: %s"
                         % (args.region, ", ".join(sorted(REGIONS))))
        bbox = REGIONS[key]
    else:
        bbox = None

    if not bbox and not args.gpx:
        parser.error("give --region or --bbox, or --gpx, or both")

    wanted = set()
    if bbox:
        for zoom in parse_zooms(args.overview_zoom):
            wanted |= set(region_tiles(bbox, zoom))
    for path in args.gpx:
        points = read_gpx(path)
        if not points:
            print("! %s has no points" % path, file=sys.stderr)
            continue
        for zoom in parse_zooms(args.corridor_zoom):
            wanted |= corridor_tiles(points, zoom, args.corridor_width / 2.0)

    by_zoom = {}
    for zoom, _, _ in wanted:
        by_zoom[zoom] = by_zoom.get(zoom, 0) + 1

    print("zoom |      tiles |    approx")
    print("-----+------------+----------")
    for zoom in sorted(by_zoom):
        print("%4d | %10s | %9s" % (
            zoom,
            "{:,}".format(by_zoom[zoom]),
            human_size(by_zoom[zoom] * AVERAGE_TILE_KB / 1024.0),
        ))
    print("-----+------------+----------")
    print("     | %10s | %9s" % (
        "{:,}".format(len(wanted)),
        human_size(len(wanted) * AVERAGE_TILE_KB / 1024.0),
    ))

    if args.dry_run:
        return 0

    database = open_pack(args.out, os.path.splitext(os.path.basename(args.out))[0])
    have = existing_keys(database)
    todo = sorted(wanted - have)
    if have:
        print("\n%s already in %s, %s to fetch"
              % ("{:,}".format(len(have)), args.out, "{:,}".format(len(todo))))
    if not todo:
        print("nothing to fetch")
        database.close()
        # Still worth pushing: the usual reason for asking again is that the
        # pack is complete and only needs to reach the phone.
        return push(args.out, args.package) if args.push else 0

    print("\nfetching from %s" % args.source)
    fetched = fetch_all(database, todo, args)
    database.execute("VACUUM")
    database.close()

    if args.push:
        return push(args.out, args.package)
    if fetched:
        print("\ncopy it to the phone by running the same command with --push")
    return 0


def fetch_all(database, todo, args):
    """
    Download every missing tile and write it into the pack.

    Writes happen on this thread in batches: SQLite is happiest with one writer,
    and batching turns hundreds of thousands of commits into a few hundred.
    """
    def fetch(key):
        zoom, x, y = key
        url = args.source.format(z=zoom, x=x, y=y)
        request = urllib.request.Request(
            url, headers={"User-Agent": "zetaforge-mappack/1.0"})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return key, response.read()
        except (urllib.error.URLError, OSError):
            return key, None

    pending = []
    done = failed = total_bytes = 0
    started = time.time()

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for key, data in pool.map(fetch, todo):
            if data:
                zoom, x, y = key
                # TMS numbers rows from the south pole; the fetch used XYZ.
                pending.append((zoom, x, (1 << zoom) - 1 - y, sqlite3.Binary(data)))
                done += 1
                total_bytes += len(data)
            else:
                failed += 1

            if len(pending) >= 2000:
                database.executemany(
                    "INSERT OR REPLACE INTO tiles VALUES (?, ?, ?, ?)", pending)
                database.commit()
                pending = []

            seen = done + failed
            if seen % 500 == 0:
                elapsed = max(1e-3, time.time() - started)
                rate = seen / elapsed
                left = (len(todo) - seen) / rate if rate else 0
                sys.stdout.write("\r  %s/%s   %.0f tiles/s   %s left     " % (
                    "{:,}".format(seen), "{:,}".format(len(todo)), rate,
                    time.strftime("%H:%M:%S", time.gmtime(left))))
                sys.stdout.flush()

    if pending:
        database.executemany(
            "INSERT OR REPLACE INTO tiles VALUES (?, ?, ?, ?)", pending)
        database.commit()

    print("\n%s tiles written, %s failed, %.1f MB"
          % ("{:,}".format(done), "{:,}".format(failed), total_bytes / 1048576.0))
    if failed and not done:
        print("nothing came back - is the tile server running at %s ?" % args.source,
              file=sys.stderr)
    return done


def push(path, package):
    """
    Into the plugin's own storage, which no shell can write to directly.

    adb push lands files owned by the shell user in a place the app cannot read,
    so the pack goes via a world readable staging path and is copied the rest of
    the way by the app's own uid.
    """
    name = os.path.basename(path)
    staged = "/data/local/tmp/" + name
    target = "/data/data/%s/files/motonav/packs/" % package
    steps = [
        ["adb", "push", path, staged],
        ["adb", "shell", "run-as", package, "mkdir", "-p", target],
        ["adb", "shell", "run-as", package, "cp", staged, target + name],
        ["adb", "shell", "rm", "-f", staged],
    ]
    for step in steps:
        print("$ " + " ".join(step))
        if subprocess.call(step) != 0:
            print("failed", file=sys.stderr)
            return 1
    print("\ninstalled as %s" % (target + name))
    return 0


if __name__ == "__main__":
    sys.exit(main())
