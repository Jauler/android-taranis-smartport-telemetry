"""Writes a synthetic LTM telemetry log of a flight, for trying the terrain features in the
log player without flying. Copy the result to the TelemetryLogs directory of the phone.

    python make_demo_log.py demo_flight.log [LAT LON LAT2 LON2]
    python make_demo_log.py replay.log --csv FLIGHT.csv

With --csv the positions, altitudes and armed state of a flight recorded as CSV by the
application are replayed instead (other sensors are not carried over).

Without it the aircraft sits disarmed at the first point, arms, climbs to 100 m, flies towards
the second point, descends to 25 m on the way (losing the line of sight behind trees), then
climbs to 180 m above the takeoff point (above the 120 m limit) and stays there.
"""

import math
import struct
import sys


def frame(kind, payload):
    crc = 0
    for b in payload:
        crc ^= b
    return b"$T" + kind + payload + bytes([crc])


def gps(lat, lon, speed, altitude):
    return frame(b"G", struct.pack("<iiBiB", int(round(lat * 1e7)), int(round(lon * 1e7)),
                                   int(speed), int(round(altitude * 100)), (14 << 2) | 3))


def attitude(heading):
    return frame(b"A", struct.pack("<hhh", 0, 0, int(heading)))


def status(armed):
    # 15.6 V, 500 mAh, rssi, airspeed, armed + angle mode
    return frame(b"S", struct.pack("<HHBBB", 15600, 500, 220, 20, (1 if armed else 0) | (2 << 2)))


def origin(lat, lon):
    return frame(b"O", struct.pack("<iiiBB", int(round(lat * 1e7)), int(round(lon * 1e7)), 0, 1, 3))


def from_csv(path):
    import csv
    records = []
    first = None
    with open(path, encoding="utf-8", errors="replace") as f:
        for row in csv.DictReader(f):
            position = row.get("GPS", "").split()
            try:
                lat, lon = float(position[0]), float(position[1])
                altitude = float(row.get("Alt(m)") or 0)
                speed = float(row.get("GSpd(kmh)") or 0) / 3.6
                heading = float(next((v for k, v in row.items() if k and k.startswith("Hdg")), 0) or 0)
            except (ValueError, IndexError):
                continue
            if abs(lat) < 0.001 and abs(lon) < 0.001:
                continue
            if first is None:
                first = (lat, lon)
            records.append(gps(lat, lon, min(speed, 255), altitude) + attitude(heading) +
                           status(bool(row.get("FM", "").strip())) + origin(*first))
    # the log player drops up to the last percent of the file: repeat the last record
    records.extend(records[-1:] * max(200, len(records) // 40))
    return b"".join(records)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    if len(argv) >= 4 and argv[2] == "--csv":
        out = from_csv(argv[3])
        with open(argv[1], "wb") as f:
            f.write(out)
        print("wrote %s, %d bytes" % (argv[1], len(out)))
        return 0
    lat1, lon1, lat2, lon2 = (float(v) for v in argv[2:6]) if len(argv) >= 6 else (55.2600, 23.8500, 55.2880, 23.9750)
    heading = math.degrees(math.atan2((lon2 - lon1) * math.cos(math.radians(lat1)), lat2 - lat1)) % 360
    out = bytearray()

    def emit(lat, lon, speed, altitude, armed):
        out.extend(gps(lat, lon, speed, altitude))
        out.extend(attitude(heading if heading <= 180 else heading - 360))
        out.extend(status(armed))
        out.extend(origin(lat1, lon1))

    for _ in range(50):                      # on the ground, disarmed
        emit(lat1, lon1, 0, 0.0, False)
    for i in range(50):                      # climb out
        emit(lat1, lon1, 0, 100.0 * (i + 1) / 50, True)
    steps = 1500
    for i in range(steps + 1):
        t = i / steps
        if t < 0.35:
            altitude = 100.0
        elif t < 0.45:
            altitude = 100.0 - 75.0 * (t - 0.35) / 0.10
        elif t < 0.60:
            altitude = 25.0
        elif t < 0.75:
            altitude = 25.0 + 155.0 * (t - 0.60) / 0.15
        else:
            altitude = 180.0
        emit(lat1 + (lat2 - lat1) * t, lon1 + (lon2 - lon1) * t, 20, altitude, True)
    for _ in range(200):                     # the log player drops the tail of the file
        emit(lat2, lon2, 0, 180.0, True)
    with open(argv[1], "wb") as f:
        f.write(out)
    print("wrote %s, %d bytes" % (argv[1], len(out)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
