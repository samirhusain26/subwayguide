# Offline NYC map

The Android app reads the packaged atlas from `android/app/src/main/assets/nyc`. No map account, tile requests or location permission are needed. It uses different basemap resolutions for a neighborhood and a complete subway route. The route overlay and station labels are drawn at the screen resolution. The source schedule and its geographic shape are retained; each selected passenger pattern is clipped to its first and last served station when rendered, so extra shape geometry cannot extend past its passenger endpoints.

## Rebuild the atlas

Download the inputs below into one directory. Keep the full MTA archive under any filename and pass its path separately. The shipped GTFS feed identifies its version as `20261028-fall-2026-pick-with-long-term-supplements`.

| Input name | Source |
| --- | --- |
| GTFS ZIP | [MTA Subway static GTFS](https://rrgtfsfeeds.s3.amazonaws.com/gtfs_subway.zip) |
| `borough.geojson` | [NYC Department of City Planning borough boundaries clipped to shoreline](https://data.cityofnewyork.us/resource/gthc-hcne.geojson) |
| `parks.geojson` | [NYC Parks properties over 40 acres](https://data.cityofnewyork.us/resource/enfh-gkve.geojson?$where=acres%3E40&$limit=500) |
| `states.zip` | [2024 Census cartographic state boundaries at 1:500,000](https://www2.census.gov/geo/tiger/GENZ2024/shp/cb_2024_us_state_500k.zip) |
| `nyroads.zip`, `njroads.zip` | [2024 Census primary and secondary roads](https://www2.census.gov/geo/tiger/TIGER2024/PRISECROADS/), files for states 36 and 34 |
| `roads-<county>.zip` | [2024 Census roads](https://www2.census.gov/geo/tiger/TIGER2024/ROADS/), file `tl_2024_<county>_roads.zip` for each county in the script's REGION_COUNTIES |
| `water-<county>.zip` | [2024 Census area hydrography](https://www2.census.gov/geo/tiger/TIGER2024/AREAWATER/), file `tl_2024_<county>_areawater.zip` for each county in REGION_COUNTIES |

From the repository root, on the development Mac, run the following command with the input paths.

```sh
~/.local/bin/dev-storage run -- uv run --python 3.11 --with 'pyshp<3' --with Pillow python tools/build_nyc_map.py /path/to/map-inputs /path/to/gtfs_subway.zip
```

The generator checks that every selectable catalog platform and route has a scheduled pattern, validates all 256 detail tiles and 64 overview tiles at 512 × 512, then promotes the staged atlas only after every tile and schedule reference passes. It writes `manifest.json` with the GTFS version, GTFS SHA-256 and each input file digest. Staging and prior complete generations live in hidden `.nyc-atlas-*` directories at the repository root, outside APK assets. A process lock serializes generators; it removes only abandoned directories with its reserved staging prefix. If promotion is interrupted after moving the old atlas, the next run validates and restores the newest complete backup first. Keep old generations as recovery evidence and remove them only as an explicit cleanup. The atlas can differ from temporary diversions or a newly updated station catalog. A station missing from the map still has access to the ordinary arrivals board.

The checked-in historical atlas records the known GTFS version and hashes for `network.json` and every tile. Its original GTFS and map archives were not retained, so the manifest explicitly leaves their source hashes unrecorded. Newly generated atlases include the exact archive and source input hashes.

Patterns with matching destination and next-stop evidence are further compared with any downstream platform IDs reported for the actual train. When multiple scheduled patterns still fit, the map labels its path **Representative scheduled route** so the diagram does not imply a proven express/local stop sequence.
