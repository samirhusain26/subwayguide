#!/usr/bin/env python3
"""Refresh the bundled NYC subway station catalog from NY State Socrata.

Only Python's standard library is required. A successful refresh validates the
entire response and atomically replaces stations.json; failures preserve the
last good catalog.
"""
from __future__ import annotations

import argparse
import json
import csv
import io
import os
import tempfile
import urllib.request
import zipfile
import time
from collections import defaultdict
from contextlib import contextmanager
from pathlib import Path
from typing import BinaryIO

URL = "https://data.ny.gov/resource/39hk-dx4f.json?$limit=2000"
GTFS_URL = "https://rrgtfsfeeds.s3.amazonaws.com/gtfs_subway.zip"
OUT = Path(__file__).with_name("stations.json")
BOROUGHS = {"M": "Manhattan", "Bx": "Bronx", "Bk": "Brooklyn", "Q": "Queens", "SI": "Staten Island"}
_MAX_ZIP_BYTES = 32 * 1024 * 1024
_MAX_DIRECTORY_BYTES = 2 * 1024 * 1024
_MAX_TOTAL_SECONDS = 60
_MAX_TABLE_BYTES = {"stops.txt": 10 * 1024 * 1024, "trips.txt": 32 * 1024 * 1024,
                    "stop_times.txt": 256 * 1024 * 1024}
_MAX_TABLE_ROWS = {"stops.txt": 15000, "trips.txt": 100000, "stop_times.txt": 6000000}
ROUTE_FAMILIES = {
    "": {"1", "2", "3", "4", "5", "6", "6X", "7", "7X", "GS"},
    "-ace": {"A", "C", "E", "H"}, "-bdfm": {"B", "D", "F", "FX", "M", "FS"},
    "-g": {"G"}, "-jz": {"J", "Z"}, "-nqrw": {"N", "Q", "R", "W"},
    "-l": {"L"}, "-si": {"SI"},
}


def _copy_bounded(response, destination, limit: int, deadline: float):
    size = 0
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Catalog download exceeded total deadline")
        chunk = response.read1(min(65536, limit + 1 - size))
        if not chunk:
            break
        size += len(chunk)
        if size > limit:
            raise ValueError("Catalog download exceeded byte limit")
        destination.write(chunk)
    if time.monotonic() > deadline:
        raise TimeoutError("Catalog download exceeded total deadline")


def _bounded_read(response, limit: int, deadline: float) -> bytes:
    with io.BytesIO() as output:
        _copy_bounded(response, output, limit, deadline)
        return output.getvalue()


@contextmanager
def download(url: str, directory: Path, timeout: float = 60):
    deadline = time.monotonic() + min(timeout, _MAX_TOTAL_SECONDS)
    req = urllib.request.Request(url, headers={"User-Agent": "SubwayGuide/1.0 (station catalog refresh)"})
    with tempfile.TemporaryFile(dir=directory) as output:
        with urllib.request.urlopen(req, timeout=min(timeout, 5)) as response:
            _copy_bounded(response, output, _MAX_ZIP_BYTES, deadline)
        output.seek(0)
        yield output


def fetch(url: str = URL, timeout: float = 30) -> list[dict]:
    deadline = time.monotonic() + min(timeout, _MAX_TOTAL_SECONDS)
    req = urllib.request.Request(url, headers={"User-Agent": "SubwayGuide/1.0 (station catalog refresh)"})
    with urllib.request.urlopen(req, timeout=min(timeout, 5)) as response:
        rows = json.loads(_bounded_read(response, _MAX_DIRECTORY_BYTES, deadline))
    if not isinstance(rows, list) or len(rows) < 400:
        raise ValueError(f"Expected complete station directory, received {len(rows) if isinstance(rows, list) else 'non-list'} records")
    return rows


def _route_id(route: str, parent: str) -> str:
    # Match server/transit.py route resolution for the legacy shared "S" label.
    if route == "SIR":
        return "SI"
    if route == "S":
        if parent in ("901", "902"):
            return "GS"
        if parent in ("S01", "S03", "S04", "D26"):
            return "FS"
        if parent.startswith("H"):
            return "H"
    return route


def build(rows: list[dict], gtfs: bytes | BinaryIO) -> dict:
    with zipfile.ZipFile(io.BytesIO(gtfs) if isinstance(gtfs, bytes) else gtfs) as archive:
        infos = {info.filename: info for info in archive.infolist()}
        if sum(info.file_size for info in infos.values()) > sum(_MAX_TABLE_BYTES.values()):
            raise ValueError("Catalog GTFS archive exceeds expanded size limit")
        for name, limit in _MAX_TABLE_BYTES.items():
            if name not in infos or infos[name].file_size > limit:
                raise ValueError(f"Catalog GTFS table missing or oversized: {name}")
        def read_table(name):
            with archive.open(name) as raw:
                for count, row in enumerate(csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8-sig", newline="")), 1):
                    if count > _MAX_TABLE_ROWS[name]:
                        raise ValueError(f"Catalog GTFS table exceeds row limit: {name}")
                    yield row
        stop_map = {s["stop_id"]: (s.get("parent_station", ""), s.get("stop_name", "")) for s in read_table("stops.txt")}
        trip_map = {t["trip_id"]: (t["route_id"], t.get("trip_headsign", "").strip()) for t in read_table("trips.txt")}
        # Only retain each trip's final sequence number. This pass streams the
        # potentially multi-million-row stop_times table instead of materializing it.
        last_sequence: dict[str, int] = {}
        with archive.open("stop_times.txt") as raw:
            for row_count, st in enumerate(csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8-sig", newline="")), 1):
                if row_count > _MAX_TABLE_ROWS["stop_times.txt"]:
                    raise ValueError("stop_times.txt exceeds row limit")
                trip, seq = st.get("trip_id", ""), st.get("stop_sequence", "")
                if trip in trip_map and seq.isdigit():
                    last_sequence[trip] = max(last_sequence.get(trip, -1), int(seq))
        service: dict[str, dict[str, set[str]]] = defaultdict(lambda: defaultdict(set))
        headsigns: dict[str, dict[str, dict[str, set[str]]]] = defaultdict(
            lambda: defaultdict(lambda: defaultdict(set)))
        # A second streaming pass records only boardable, non-terminal stops.
        with archive.open("stop_times.txt") as raw:
            for row_count, st in enumerate(csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8-sig", newline="")), 1):
                if row_count > _MAX_TABLE_ROWS["stop_times.txt"]:
                    raise ValueError("stop_times.txt exceeds row limit")
                trip_id, stop_id = st.get("trip_id", ""), st.get("stop_id", "")
                seq = st.get("stop_sequence", "")
                if not trip_id or not seq.isdigit() or int(seq) >= last_sequence.get(trip_id, -1):
                    continue
                if st.get("pickup_type", "0") not in ("", "0"):
                    continue
                parent = stop_map.get(stop_id, ("", ""))[0] or stop_id[:-1]
                direction = stop_id[-1:]  # directional GTFS child convention
                route_id, headsign = trip_map.get(trip_id, ("", ""))
                route = _route_id(route_id, parent)
                if parent and route and direction in ("N", "S"):
                    service[parent][route].add(direction)
                    if headsign:
                        headsigns[parent][route][direction].add(headsign)

    grouped: dict[str, dict] = {}
    for row in rows:
        sid = str(row.get("gtfs_stop_id", "")).strip()
        name = str(row.get("stop_name", "")).strip()
        if not sid or not name:
            continue
        station = grouped.setdefault(sid, {
            "id": sid, "name": name, "area": BOROUGHS.get(row.get("borough"), "NYC"),
            "routes": [], "directions": {}, "routeDirections": {}, "lat": None, "lon": None,
            "platforms": [], "gtfsParentId": sid, "line": str(row.get("line", "")).strip(),
        })
        line = str(row.get("line", "")).strip()
        if line and line != station["line"]:
            station["line"] = ", ".join(sorted(set(filter(None, station["line"].split(", "))) | {line}))
        try:
            station["lat"] = float(row.get("gtfs_latitude"))
            station["lon"] = float(row.get("gtfs_longitude"))
        except (ValueError, TypeError):
            pass
        for direction, label_key in (("N", "north_direction_label"), ("S", "south_direction_label")):
            label = str(row.get(label_key, "")).strip()
            if label and label.lower() != "last stop":
                station["directions"][direction] = label

    for stop_id, (parent, stop_name) in stop_map.items():
        if parent in grouped:
            direction = stop_id[-1:] if stop_id.endswith(("N", "S")) else ""
            grouped[parent]["platforms"].append({"stop_id": stop_id, "name": stop_name, "direction": direction})

    result = []
    for sid, station in grouped.items():
        directions_with_platform = {p["direction"] for p in station["platforms"] if p.get("direction") in ("N", "S")}
        rd = service.get(sid, {})
        station["routeDirections"] = {
            route: sorted(ds & directions_with_platform)
            for route, ds in sorted(rd.items()) if ds & directions_with_platform
        }
        station["routes"] = sorted(station["routeDirections"], key=lambda r: (not r.isdigit(), r))
        valid_dirs = {d for ds in station["routeDirections"].values() for d in ds}
        station["directions"] = {d: label for d, label in station["directions"].items() if d in valid_dirs}
        # Directory "Last Stop" is not a usable direction label. Use scheduled
        # trip headsigns from boardable departures as a deterministic fallback.
        for direction in valid_dirs:
            if direction not in station["directions"]:
                choices = sorted({h for route in station["routeDirections"]
                                  for h in headsigns.get(sid, {}).get(route, {}).get(direction, set())})
                if choices:
                    station["directions"][direction] = choices[0]
        labeled = set(station["directions"])
        station["routeDirections"] = {
            route: [direction for direction in dirs if direction in labeled]
            for route, dirs in station["routeDirections"].items()
            if any(direction in labeled for direction in dirs)
        }
        station["routes"] = sorted(station["routeDirections"], key=lambda r: (not r.isdigit(), r))
        station["platforms"] = sorted(station["platforms"], key=lambda p: (p["direction"], p["stop_id"]))
        station["serviceAvailable"] = bool(station["routeDirections"])
        if station["lat"] is not None and station["lon"] is not None:
            result.append(station)
    result.sort(key=lambda s: (s["name"].casefold(), s["area"], s["id"]))
    return {"stations": result}


def validate_catalog(catalog: dict, previous: dict | None = None,
                     retired_routes: set[str] | None = None) -> None:
    if not isinstance(catalog, dict):
        raise ValueError("Catalog validation failed: catalog must be an object")
    stations = catalog.get("stations")
    if not isinstance(stations, list) or any(not isinstance(s, dict) for s in stations):
        raise ValueError("Catalog validation failed: stations must be a list of objects")
    ids = [str(s.get("id", "")) for s in stations]
    if len(set(ids)) != len(ids) or len(ids) < 450:
        raise ValueError(f"Catalog validation failed: expected >=450 unique stations, got {len(set(ids))}")
    route_station_counts: dict[str, int] = defaultdict(int)
    pairs = 0
    for station in stations:
        if not station.get("id") or not isinstance(station.get("directions"), dict):
            raise ValueError("Catalog validation failed: malformed station record")
        try:
            lat, lon = float(station["lat"]), float(station["lon"])
        except (KeyError, TypeError, ValueError):
            raise ValueError(f"Catalog validation failed: missing coordinates for {station.get('id')}")
        if not (40.4 <= lat <= 41.1 and -74.3 <= lon <= -73.6):
            raise ValueError(f"Catalog validation failed: coordinates outside NYC bounds for {station.get('id')}")
        rd = station.get("routeDirections")
        if (not isinstance(rd, dict) or any(not isinstance(route, str) for route in rd)
                or not isinstance(station.get("routes"), list)
                or station.get("routes") != sorted(rd, key=lambda r: (not r.isdigit(), r))):
            raise ValueError(f"Catalog validation failed: invalid routes for {station.get('id')}")
        platforms = station.get("platforms")
        if not isinstance(platforms, list) or any(not isinstance(p, dict) for p in platforms):
            raise ValueError(f"Catalog validation failed: invalid platforms for {station.get('id')}")
        platform_ids = {str(p.get("stop_id", "")) for p in platforms}
        platform_dirs = {d for d in ("N", "S") if str(station["id"]) + d in platform_ids}
        for route, directions in rd.items():
            if not isinstance(directions, list) or not directions:
                raise ValueError(f"Catalog validation failed: empty boardable directions for {station.get('id')} {route}")
            route_station_counts[route] += 1
            for direction in directions:
                if direction not in ("N", "S") or direction not in platform_dirs or str(station["id"]) + direction not in platform_ids:
                    raise ValueError(f"Catalog validation failed: no directional platform for {station.get('id')} {route} {direction}")
                label = station.get("directions", {}).get(direction)
                if not isinstance(label, str) or not label.strip():
                    raise ValueError(f"Catalog validation failed: no direction label for {station.get('id')} {route} {direction}")
                pairs += 1
    active = set(route_station_counts)
    for family, routes in ROUTE_FAMILIES.items():
        family_counts = [route_station_counts[r] for r in routes if r in route_station_counts]
        if (not family_counts or sum(family_counts) < 10) and not set(routes) <= (retired_routes or set()):
            raise ValueError(f"Catalog validation failed: insufficient boardable coverage for route family {family or 'IRT/7/GS'}")
    if pairs < 900:
        raise ValueError(f"Catalog validation failed: only {pairs} boardable route/direction pairs")
    if previous:
        if not isinstance(previous, dict) or not isinstance(previous.get("stations"), list) or any(
                not isinstance(s, dict) for s in previous["stations"]):
            raise ValueError("Catalog validation failed: malformed previous catalog")
        old = previous["stations"]
        old_routes = {route for s in old for route in s.get("routeDirections", {})}
        lost_routes = old_routes - active - (retired_routes or set())
        if lost_routes:
            raise ValueError("Catalog validation failed: previously served routes disappeared: " +
                             ", ".join(sorted(lost_routes)))
        old_ids = {str(s.get("id")) for s in old}
        old_pairs = sum(len(ds) for s in old for ds in s.get("routeDirections", {}).values())
        # One intentional removal is acceptable; larger baseline regressions need investigation.
        if len(ids) < len(old_ids) * 0.95 or pairs < old_pairs * 0.95:
            raise ValueError(f"Catalog validation failed: large regression from baseline ({len(ids)}/{len(old_ids)} stations, {pairs}/{old_pairs} pairs)")


def refresh(rows: list[dict], gtfs: bytes | BinaryIO, path: Path = OUT,
            retired_routes: set[str] | None = None) -> dict:
    catalog = build(rows, gtfs)
    previous = None
    if path.exists():
        try:
            candidate = json.loads(path.read_text(encoding="utf-8"))
            validate_catalog(candidate)
            previous = candidate
        except (OSError, ValueError, json.JSONDecodeError):
            previous = None
    validate_catalog(catalog, previous, retired_routes)
    atomic_write(catalog, path)
    return catalog


def atomic_write(catalog: dict, path: Path = OUT) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=".stations.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(catalog, f, ensure_ascii=False, separators=(",", ":"))
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        os.chmod(name, 0o644)
        os.replace(name, path)
    except BaseException:
        try:
            os.unlink(name)
        except FileNotFoundError:
            pass
        raise


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default=URL, help=argparse.SUPPRESS)
    parser.add_argument("--retire-route", action="append", default=[], metavar="ROUTE",
                        help="Explicitly accept retirement of a previously served route")
    args = parser.parse_args()
    rows = fetch(args.url)
    with download(GTFS_URL, OUT.parent) as gtfs:
        catalog = refresh(rows, gtfs, retired_routes=set(args.retire_route))
    print(f"Wrote {len(catalog['stations'])} stations to {OUT}")


if __name__ == "__main__":
    main()
