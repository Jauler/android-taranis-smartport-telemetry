# Terrain data

The application can work with an offline terrain model. Nothing is downloaded while flying:
the data is one file, the *terrain pack* (`*.tpk`), which is either bundled into the APK or
copied to the phone by hand.

With a terrain pack present the application shows:

| Readout | Meaning |
|---|---|
| Height above ground | Altitude of the aircraft above the ground right below it (AGL). |
| Ground elevation | Elevation above sea level of the ground right below the aircraft. |
| Altitude limit | The altitude, as shown by the altitude sensor, at which the aircraft reaches the height above ground limit (120 m by default). If the ground below the aircraft is 30 m higher than the takeoff point, the limit reads 150 m. |

Above the height limit the two height readouts turn red and there is a voice warning. The
readouts are ordinary sensors and can be moved or hidden in *Settings > Sensor display
settings*. Without a terrain pack they are hidden.

## How the values are calculated

* The altitude sensor normally reports the height above the takeoff point. The application
  takes the position of the aircraft at the moment of arming as the takeoff point (the first
  received position if the armed state is unknown) and looks up the ground elevation there.
  *Set takeoff point at UAV location* in the map menu overrides it. Some flight controllers
  report altitude above sea level instead (e.g. Betaflight over CRSF). This is detected from
  the first altitude of the flight (about zero or about the terrain elevation); if the guess
  is wrong, e.g. after connecting in mid-flight, pick the right one in
  *Settings > Terrain > Altitude sensor shows*.

## Installing a pack

* **Bundled:** put the file to `app/src/main/assets/terrain/terrain.tpk` before building. It is
  stored uncompressed in the APK and read in place. The file is not kept in git (the pack of
  Lithuania is about 140 MB); a build without it works, just without the terrain features.
* **By hand:** copy any `*.tpk` to `Android/data/<application id>/files/terrain/` on the phone.
  It takes precedence over the bundled one.

## Building the pack of Lithuania

Python 3.9+ with `numpy rasterio pyproj requests` is needed, about 8 GB of disk space and
4 GB of downloads.

    cd tools/terrain
    python build_lithuania.py WORKDIR all ../../app/src/main/assets/terrain/terrain.tpk

Every step is cached in `WORKDIR` and can be rerun on its own (`fetch-dtm`, `grid-chm`, ...).
The tree and building layer is prepared at 10 m and stored at 20 m (every cell holds the
highest object inside it); add `--obj-cell 10` to keep the full resolution at the price of a
pack about twice as large.

| Layer | Cell | Source | Licence |
|---|---|---|---|
| Ground (`dtm`) | 20 m | GDR50LT relief grid served by the geoportal.lt elevation service. Interpolated from 1:50 000 contours, so real detail is coarser than the grid. | CC BY 4.0, © Nacionalinė žemės tarnyba prie Aplinkos ministerijos |
| Trees and buildings (`obj`) | 20 m | Tree heights: Meta / World Resources Institute global canopy height v2 (estimated from satellite imagery). Buildings: Microsoft Global ML Building Footprints; footprints without a height estimate get 8 m. | CC BY 4.0 (canopy), ODbL (buildings) |
| Populated areas (`pop`) | 20 m | GRPK built-up territories ("Užstatytos teritorijos": urbanised and industrial territories, sports grounds), classified into settlements and isolated buildings by their density. | CC BY 4.0, © Nacionalinė žemės tarnyba prie Aplinkos ministerijos |

These attributions have to accompany the pack wherever it is distributed.

**Accuracy.** This is a modelled surface, not a measured one. Tree heights are typically a few
meters off and tall trees tend to be underestimated; many buildings only have the default
height; masts, chimneys and power lines are not included at all. The national LiDAR point
clouds (Lidar_DR_LT on geoportal.lt) would give a true surface model, but they can only be
ordered with a personal account and are terabytes in size; rasters made from them can be
dropped into the same pipeline in place of the canopy/building steps.

## Tools

All in `tools/terrain`:

* `inspect_pack.py PACK [LAT LON [LAT2 LON2 [ANTENNA_HEIGHT]]]` - what the pack contains, the
  terrain at a point, or the line of sight between two points.
* `make_demo_log.py OUT.log [--csv FLIGHT.csv]` - writes a telemetry log (a synthetic flight, or
  the track of a recorded CSV) which can be replayed in the application to see the terrain
  readouts without flying.
* `make_test_pack.py` - generates the small pack and the expected values used by the unit test
  `TerrainModelTest`.

## File format

See the header of [tools/terrain/tpk.py](../tools/terrain/tpk.py). In short: a header with the
parameters of a Transverse Mercator grid, then named raster layers, each cut into square tiles
which are deflate-compressed independently so that the application can read any place without
loading the rest.
