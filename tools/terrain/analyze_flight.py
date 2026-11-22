"""Runs the terrain calculations of the app over a flight recorded as CSV (the OpenTX-style
CSV written by the application itself) and prints them next to the recorded link quality.

    python analyze_flight.py PACK FLIGHT.csv [--msl | --relative] [--antenna HEIGHT]
                             [--ignore-near METERS] [--every SECONDS]

By default the altitude column is taken as above sea level when the aircraft sits well above
zero before moving (Betaflight over CRSF), otherwise as relative to the takeoff point. The
antenna is assumed to stand at the first recorded position.
"""

import csv
import math
import sys

from pyproj import Transformer

from make_test_pack import Reference


def number(value, default=float("nan")):
    try:
        return float(value)
    except ValueError:
        return default


def load(path):
    rows = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for r in csv.DictReader(f):
            gps = r.get("GPS", "").split()
            if len(gps) != 2:
                continue
            lat, lon = number(gps[0]), number(gps[1])
            if not (abs(lat) > 0.001 and abs(lon) > 0.001):
                continue
            h, m, s = r["Time"].split(":")
            rows.append(dict(
                t=int(h) * 3600 + int(m) * 60 + float(s), time=r["Time"][:8], lat=lat, lon=lon,
                alt=number(r.get("Alt(m)", "")), tqly=number(r.get("TQly(%)", "")),
                rqly=number(r.get("RQly(%)", "")), rss=max(number(r.get("1RSS(dB)", ""), -200),
                                                         number(r.get("2RSS(dB)", ""), -200)),
                trss=number(r.get("TRSS(dB)", "")), dist=number(r.get("Dist(m)", "")),
                mode=r.get("FM", "")))
    return rows


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    options = argv[3:]

    def option(name, default):
        return float(options[options.index(name) + 1]) if name in options else default

    antenna_height = option("--antenna", 2.0)
    ignore_near = option("--ignore-near", 50.0)
    every = option("--every", 15.0)

    ref = Reference(argv[1])
    to_grid = Transformer.from_crs("EPSG:4326", "EPSG:3346", always_xy=True)
    rows = load(argv[2])
    if not rows:
        print("no positions in the log")
        return 1

    first = rows[0]
    ax, ay = to_grid.transform(first["lon"], first["lat"])
    takeoff_ground = ref.ground(ax, ay)
    if "--msl" in options:
        msl = True
    elif "--relative" in options:
        msl = False
    else:
        msl = first["alt"] > 30.0
    reference = 0.0 if msl else takeoff_ground
    print("takeoff %.6f, %.6f: terrain %.1f m, first altitude in the log %.1f m -> altitude is %s" % (
        first["lat"], first["lon"], takeoff_ground, first["alt"],
        "above sea level" if msl else "relative to takeoff"))
    print("antenna %.0f m above ground at the takeoff point, obstacles closer than %.0f m ignored" % (
        antenna_height, ignore_near))
    print()
    print("time      dist   alt  ground   AGL  limit  minLOS  margin  populated  1/2RSS TRSS TQly  mode")

    last_print = -1e9
    stats = []
    for r in rows:
        x, y = to_grid.transform(r["lon"], r["lat"])
        ground = ref.ground(x, y)
        los = ref.line_of_sight(ax, ay, antenna_height, x, y, ignore_near, 0.0)
        if math.isnan(ground) or los is None:
            continue
        r["agl"] = r["alt"] + reference - ground
        r["limit"] = 120.0 + ground - reference
        r["min_los"] = los[0] - reference
        r["margin"] = r["alt"] - r["min_los"]
        r["obstacle"] = los[2]
        r["los_distance"] = los[1]
        r["pop"] = ref.populated(x, y, 0.0, 2)
        stats.append(r)
        if r["t"] - last_print >= every:
            last_print = r["t"]
            print("%s %5.0f %5.0f  %6.1f %5.0f  %5.0f  %6.0f  %6.0f  %-9s  %5.0f %4.0f %4.0f  %s" % (
                r["time"], los[1], r["alt"], ground, r["agl"], r["limit"], r["min_los"], r["margin"],
                {2: "INSIDE", 1: "near", 0: "-", -1: "?"}[r["pop"]], r["rss"], r["trss"], r["tqly"], r["mode"]))

    flying = [r for r in stats if r["agl"] > 5.0 and r["los_distance"] > 200.0]
    if not flying:
        return 0
    print()
    print("in flight: %d samples, max distance %.0f m, max AGL %.0f m, above the 120 m limit %.0f%% of the time" % (
        len(flying), max(r["los_distance"] for r in flying), max(r["agl"] for r in flying),
        100.0 * sum(r["agl"] > 120.0 for r in flying) / len(flying)))
    clear = [r for r in flying if r["margin"] >= 0]
    blocked = [r for r in flying if r["margin"] < 0]
    for name, part in (("line of sight free", clear), ("line of sight blocked", blocked)):
        if part:
            print("%-22s %5d samples: mean downlink quality %5.1f%%, mean downlink RSSI %6.1f dB, quality below 50%% in %4.1f%%" % (
                name, len(part), sum(r["tqly"] for r in part) / len(part),
                sum(r["trss"] for r in part) / len(part),
                100.0 * sum(r["tqly"] < 50 for r in part) / len(part)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
