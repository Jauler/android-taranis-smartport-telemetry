# Terrain data

The application can work with an offline terrain model. Nothing is downloaded while flying:
the data is one file, the *terrain pack* (`*.tpk`), which is either bundled into the APK or
copied to the phone by hand.

With a terrain pack present the application shows:

| Readout | Meaning |
|---|---|
| Height above ground | Altitude of the aircraft above the ground right below it (AGL). |
| Ground elevation | Elevation above sea level of the ground right below the aircraft. |
| Elevation with trees and buildings | Elevation above sea level of the top of the highest tree or building right below the aircraft: the ground elevation plus the height of what stands on it. |
| Altitude limit | The altitude, as shown by the altitude sensor, at which the aircraft reaches the height above ground limit (120 m by default). If the ground below the aircraft is 30 m higher than the takeoff point, the limit reads 150 m. |
| Min line of sight altitude | The lowest altitude, as shown by the altitude sensor, at which nothing (ground, trees, buildings) stands between the aircraft and the receiving antenna. |

Above the height limit the two height readouts turn red. Below the line of sight altitude that
readout turns red. The readouts are ordinary sensors and can be moved or hidden in
*Settings > Sensor display settings*. Without a terrain pack they are hidden.

## How the values are calculated

* The altitude sensor normally reports the height above the takeoff point. The application
  takes the position of the aircraft at the moment of arming as the takeoff point (the first
  received position if the armed state is unknown) and looks up the ground elevation there.
  *Set takeoff point at UAV location* in the map menu overrides it. Some flight controllers
  report altitude above sea level instead (e.g. Betaflight over CRSF); switch
  *Settings > Terrain > Altitude sensor shows* to *Altitude above sea level* for those.
* The receiving antenna is assumed to be where the phone is (where the aircraft took off if
  the phone has no location fix, and always when a log is replayed, as the phone may be
  anywhere by then), at *Receiving antenna height above ground* (set it to the height of a
  repeater above the pilot when one is used).
* The line between the antenna and the aircraft is sampled every half cell of the tree and
  building layer (10 m). For every sample the
  altitude needed to look over it from the antenna is extrapolated to the aircraft position;
  the highest of them is the result. Earth curvature is included (4/3 Earth radius, as usual
  for radio links).
* A radio link needs more than the bare straight line: with *Radio link frequency* (5.8 GHz by
  default) every obstacle is raised by 60 % of the radius of the first Fresnel zone at its
  place, `0.6 * sqrt(wavelength * d1 * d2 / (d1 + d2))`. That is about 6 m for an obstacle
  2 km from the antenna on a 9 km link at 5.8 GHz and 14 m at 900 MHz. What the data cannot
  tell is how much trees only weaken a signal instead of blocking it; *Clearance above
  obstacles* adds a fixed margin on top.
* Trees a few meters from the pilot would shade the whole horizon, so obstacles closer than
  *Ignore obstacles near the antenna* (50 m by default) are skipped.
* The line of sight readout does not turn red while the aircraft is closer
  than *Line of sight warning distance* (200 m by default) to the home point (the takeoff point,
  or the antenna if that is not known): on the ground and while climbing out the obstacles
  around the pilot would trigger it all the time. The readout itself is still shown.

## Installing a pack

* **In the application:** get the pack file onto the phone (download it, send it over,
  copy it by USB to e.g. *Download*), then tap *Settings > Terrain > Terrain data* and pick
  the file. It is copied into place and checked; the same entry replaces or removes the
  installed pack. The application itself downloads nothing.
* **Bundled:** put the file to `app/src/main/assets/terrain/terrain.tpk` before building. It is
  stored uncompressed in the APK and read in place. The file is not kept in git (the pack of
  Lithuania is about 140 MB); a build without it works, just without the terrain features
  until a pack is installed.
* **By hand:** copy any `*.tpk` to `Android/data/<application id>/files/terrain/` on the phone.

An installed pack takes precedence over the bundled one.

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
* `analyze_flight.py PACK FLIGHT.csv` - runs the same calculations over a flight recorded by the
  application as CSV and prints them next to the recorded link quality: a quick way to see
  whether a lost link was a blocked line of sight.
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
