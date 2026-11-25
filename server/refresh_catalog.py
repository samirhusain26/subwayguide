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
import urllib.error
import urllib.request
import zipfile
from collections import defaultdict
from pathlib import Path

URL = "https://data.ny.gov/resource/39hk-dx4f.json?$limit=2000"
GTFS_URL = "https://rrgtfsfeeds.s3.amazonaws.com/gtfs_subway.zip"
OUT = Path(__file__).with_name("stations.json")
BOROUGHS = {"M": "Manhattan", "Bx": "Bronx", "Bk": "Brooklyn", "Q": "Queens", "SI": "Staten Island"}
ROUTE_FAMILIES = {
    "": {"1", "2", "3", "4", "5", "6", "6X", "7", "7X", "GS"},
    "-ace": {"A", "C", "E", "H"}, "-bdfm": {"B", "D", "F", "FX", "M", "FS"},
    "-g": {"G"}, "-jz": {"J", "Z"}, "-nqrw": {"N", "Q", "R", "W"},
    "-l": {"L"}, "-si": {"SI"},
}


def download(url: str, timeout: float = 60) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "SubwayGuide/1.0 (station catalog refresh)"})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return response.read()


def fetch(url: str = URL, timeout: float = 30) -> list[dict]:
    req = urllib.request.Request(url, headers={"User-Agent": "SubwayGuide/1.0 (station catalog refresh)"})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        rows = json.load(response)
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


def build(rows: list[dict], gtfs: bytes) -> dict:
    with zipfile.ZipFile(io.BytesIO(gtfs)) as archive:
        def read_table(name):
            with archive.open(name) as raw:
                return list(csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8-sig", newline="")))
        stops = read_table("stops.txt")
        trips = read_table("trips.txt")
        stop_map = {s["stop_id"]: s for s in stops}
        trip_map = {t["trip_id"]: t for t in trips}
        # Only retain each trip's final sequence number. This pass streams the
        # potentially multi-million-row stop_times table instead of materializing it.
        last_sequence: dict[str, int] = {}
        with archive.open("stop_times.txt") as raw:
            for st in csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8-sig", newline="")):
                trip, seq = st.get("trip_id", ""), st.get("stop_sequence", "")
                if trip and seq.isdigit():
                    last_sequence[trip] = max(last_sequence.get(trip, -1), int(seq))
        service: dict[str, dict[str, set[str]]] = defaultdict(lambda: defaultdict(set))
        headsigns: dict[str, dict[str, dict[str, set[str]]]] = defaultdict(
            lambda: defaultdict(lambda: defaultdict(set)))
        # A second streaming pass records only boardable, non-terminal stops.
        with archive.open("stop_times.txt") as raw:
            for st in csv.DictReader(io.TextIOWrapper(raw, encoding="utf-8-sig", newline="")):
                trip_id, stop_id = st.get("trip_id", ""), st.get("stop_id", "")
                seq = st.get("stop_sequence", "")
                if not trip_id or not seq.isdigit() or int(seq) >= last_sequence.get(trip_id, -1):
                    continue
                if st.get("pickup_type", "0") not in ("", "0"):
                    continue
                stop = stop_map.get(stop_id, {})
                parent = stop.get("parent_station") or stop_id[:-1]
                direction = stop_id[-1:]  # directional GTFS child convention
                trip = trip_map.get(trip_id, {})
                route = _route_id(trip.get("route_id", ""), parent)
                if parent and route and direction in ("N", "S"):
                    service[parent][route].add(direction)
                    headsign = trip.get("trip_headsign", "").strip()
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

    for st in stops:
        parent = st.get("parent_station", "")
        if parent in grouped:
            stop_id = st.get("stop_id", "")
            direction = stop_id[-1:] if stop_id.endswith(("N", "S")) else ""
            grouped[parent]["platforms"].append({"stop_id": stop_id, "name": st.get("stop_name", ""), "direction": direction})

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


def validate_catalog(catalog: dict, previous: dict | None = None) -> None:
    stations = catalog.get("stations")
    if not isinstance(stations, list):
        raise ValueError("Catalog validation failed: stations must be a list")
    ids = [str(s.get("id", "")) for s in stations]
    if len(set(ids)) != len(ids) or len(ids) < 450:
        raise ValueError(f"Catalog validation failed: expected >=450 unique stations, got {len(set(ids))}")
    route_station_counts: dict[str, int] = defaultdict(int)
    pairs = 0
    for station in stations:
        try:
            lat, lon = float(station["lat"]), float(station["lon"])
        except (KeyError, TypeError, ValueError):
            raise ValueError(f"Catalog validation failed: missing coordinates for {station.get('id')}")
        if not (40.4 <= lat <= 41.1 and -74.3 <= lon <= -73.6):
            raise ValueError(f"Catalog validation failed: coordinates outside NYC bounds for {station.get('id')}")
        rd = station.get("routeDirections")
        if not isinstance(rd, dict) or station.get("routes") != sorted(rd, key=lambda r: (not r.isdigit(), r)):
            raise ValueError(f"Catalog validation failed: invalid routes for {station.get('id')}")
        platform_dirs = {p.get("direction") for p in station.get("platforms", [])
                         if p.get("direction") in ("N", "S") and str(p.get("stop_id", "")).endswith(p["direction"])}
        for route, directions in rd.items():
            if not isinstance(directions, list) or not directions:
                raise ValueError(f"Catalog validation failed: empty boardable directions for {station.get('id')} {route}")
            route_station_counts[route] += 1
            for direction in directions:
                if direction not in ("N", "S") or direction not in platform_dirs:
                    raise ValueError(f"Catalog validation failed: no directional platform for {station.get('id')} {route} {direction}")
                if not str(station.get("directions", {}).get(direction, "")).strip():
                    raise ValueError(f"Catalog validation failed: no direction label for {station.get('id')} {route} {direction}")
                pairs += 1
    active = set(route_station_counts)
    for family, routes in ROUTE_FAMILIES.items():
        family_counts = [route_station_counts[r] for r in routes if r in route_station_counts]
        if not family_counts or sum(family_counts) < 10:
            raise ValueError(f"Catalog validation failed: insufficient boardable coverage for route family {family or 'IRT/7/GS'}")
    if pairs < 900:
        raise ValueError(f"Catalog validation failed: only {pairs} boardable route/direction pairs")
    if previous and isinstance(previous.get("stations"), list):
        old = previous["stations"]
        old_ids = {str(s.get("id")) for s in old}
        old_pairs = sum(len(ds) for s in old for ds in s.get("routeDirections", {}).values())
        # One intentional removal is acceptable; larger baseline regressions need investigation.
        if len(ids) < len(old_ids) * 0.95 or pairs < old_pairs * 0.95:
            raise ValueError(f"Catalog validation failed: large regression from baseline ({len(ids)}/{len(old_ids)} stations, {pairs}/{old_pairs} pairs)")


def refresh(rows: list[dict], gtfs: bytes, path: Path = OUT) -> dict:
    catalog = build(rows, gtfs)
    previous = None
    if path.exists():
        try:
            candidate = json.loads(path.read_text(encoding="utf-8"))
            validate_catalog(candidate)
            previous = candidate
        except (OSError, ValueError, json.JSONDecodeError):
            previous = None
    validate_catalog(catalog, previous)
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
    args = parser.parse_args()
    catalog = refresh(fetch(args.url), download(GTFS_URL))
    print(f"Wrote {len(catalog['stations'])} stations to {OUT}")


if __name__ == "__main__":
    main()
