import json
import io
import copy
import sys
import tempfile
import unittest
from pathlib import Path
import zipfile
from collections import Counter
from types import SimpleNamespace
from unittest.mock import patch

import refresh_catalog as refresh_module
import serve


CATALOG = json.loads(Path(__file__).with_name("stations.json").read_text(encoding="utf-8"))["stations"]


class CatalogTests(unittest.TestCase):
    def test_every_route_direction_is_labeled_and_boardable(self):
        stations = {station["id"]: station for station in CATALOG}
        self.assertEqual(len(CATALOG), len(stations))
        allowed, rejected = Counter(), Counter()
        handler = SimpleNamespace(server=SimpleNamespace(snapshot=(None, stations)))
        responses = []
        handler.reply = lambda status, data: responses.append(status)
        with patch.object(serve, "make_board", return_value={}) as board:
            for station in CATALOG:
                station_id = station["id"]
                self.assertEqual(set(station["routes"]), set(station["routeDirections"]))
                platforms = {(p["direction"], p["stop_id"]) for p in station["platforms"]}
                for route, directions in station["routeDirections"].items():
                    self.assertTrue(directions, (station_id, route))
                    self.assertEqual(len(directions), len(set(directions)))
                    self.assertTrue(set(directions) <= {"N", "S"})
                    for direction in ("N", "S"):
                        with self.subTest(station=station_id, route=route, direction=direction):
                            handler.path = f"/v1/board?station={station_id}&route={route}&direction={direction}"
                            serve.BoardRequest.dispatch(handler)
                            if direction in directions:
                                allowed[route] += 1
                                self.assertIn((direction, station_id + direction), platforms)
                                self.assertTrue(station["directions"].get(direction))
                                self.assertEqual(responses.pop(), 200)
                                board.assert_called_once_with(station, route, direction, stations)
                            else:
                                rejected[route] += 1
                                self.assertEqual(responses.pop(), 400)
                                board.assert_not_called()
                            board.reset_mock()
        self.assertTrue(allowed)
        self.assertTrue(rejected)

    def test_official_borough_codes_and_station_line(self):
        areas = {station["area"] for station in CATALOG}
        self.assertTrue({"Manhattan", "Bronx", "Brooklyn", "Queens", "Staten Island"} <= areas)
        for station in CATALOG:
            self.assertTrue(station["line"], station["id"])

    def test_125_street_default(self):
        station = next(s for s in CATALOG if s["id"] == "116")
        self.assertEqual((station["name"], station["area"]), ("125 St", "Manhattan"))
        self.assertEqual(station["routeDirections"]["1"], ["N", "S"])
        self.assertEqual(station["directions"]["S"], "Downtown")
        self.assertIn("116S", {p["stop_id"] for p in station["platforms"]})

    def test_hudson_yards_direction_uses_departure_headsign(self):
        station = next(s for s in CATALOG if s["id"] == "726")
        self.assertEqual(station["routeDirections"].get("7"), ["N"])
        self.assertIn("Flushing", station["directions"]["N"])

    def test_single_station_removal_is_allowed_against_baseline(self):
        candidate = {"stations": CATALOG[1:]}
        refresh_module.validate_catalog(candidate, {"stations": CATALOG})

    def test_catalog_validation_rejects_wrong_shape_and_wrong_child_id(self):
        with self.assertRaises(ValueError):
            refresh_module.validate_catalog([])
        candidate = copy.deepcopy({"stations": CATALOG})
        station = next(s for s in candidate["stations"] if s["routeDirections"])
        route, directions = next(iter(station["routeDirections"].items()))
        direction = directions[0]
        platform = next(p for p in station["platforms"] if p["direction"] == direction)
        platform["stop_id"] = "not-the-consumer-child" + direction
        with self.assertRaisesRegex(ValueError, "no directional platform"):
            refresh_module.validate_catalog(candidate)

    def test_refresh_rejects_total_route_loss_unless_explicitly_retired(self):
        candidate = copy.deepcopy({"stations": CATALOG})
        for station in candidate["stations"]:
            station["routeDirections"].pop("GS", None)
            station["routes"] = sorted(station["routeDirections"], key=lambda r: (not r.isdigit(), r))
        with self.assertRaisesRegex(ValueError, "routes disappeared: GS"):
            refresh_module.validate_catalog(candidate, {"stations": CATALOG})
        refresh_module.validate_catalog(candidate, {"stations": CATALOG}, {"GS"})

    def test_header_only_gtfs_cannot_replace_last_good_catalog(self):
        directory_rows = [
            {"gtfs_stop_id": s["id"], "stop_name": s["name"], "borough": {
                "Brooklyn": "Bk", "Bronx": "Bx", "Manhattan": "M", "Queens": "Q", "Staten Island": "SI"
            }[s["area"]], "line": s["line"], "gtfs_latitude": str(s["lat"]),
             "gtfs_longitude": str(s["lon"]), "north_direction_label": s["directions"].get("N", "Last Stop"),
             "south_direction_label": s["directions"].get("S", "Last Stop")}
            for s in CATALOG
        ]
        truncated = io.BytesIO()
        with zipfile.ZipFile(truncated, "w") as z:
            z.writestr("stops.txt", "stop_id,parent_station,stop_name\n")
            z.writestr("trips.txt", "route_id,trip_id,direction_id,trip_headsign\n")
            z.writestr("stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type\n")
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "stations.json"
            original = Path(__file__).with_name("stations.json").read_bytes()
            path.write_bytes(original)
            with self.assertRaises(ValueError):
                refresh_module.refresh(directory_rows, truncated.getvalue(), path)
            self.assertEqual(path.read_bytes(), original)

    def test_embedded_startup_replaces_valid_json_wrong_shape_cache(self):
        bridge_path = Path(__file__).resolve().parents[1] / "android" / "src" / "main" / "python"
        sys.path.insert(0, str(bridge_path))
        try:
            import embedded_board
            import refresh_catalog as catalog_module
            with tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / "stations.json"
                path.write_text("[]", encoding="utf-8")
                bundled = json.dumps({"stations": CATALOG})
                result = embedded_board._catalog_from_cache(
                    bundled, path, catalog_module, refresh_module.validate_catalog)
                self.assertEqual(result["stations"][0]["id"], CATALOG[0]["id"])
                self.assertEqual(json.loads(path.read_text(encoding="utf-8")), result)
        finally:
            sys.path.remove(str(bridge_path))
            sys.modules.pop("embedded_board", None)

    def test_catalog_download_enforces_byte_and_deadline_bounds(self):
        import time
        payload = io.BytesIO(b"12345")
        self.assertEqual(refresh_module._bounded_read(payload, 5, time.monotonic() + 1), b"12345")
        with self.assertRaisesRegex(ValueError, "byte limit"):
            refresh_module._bounded_read(io.BytesIO(b"123456"), 5, time.monotonic() + 1)
        with patch.object(refresh_module.time, "monotonic", return_value=10):
            with self.assertRaisesRegex(TimeoutError, "deadline"):
                refresh_module._bounded_read(io.BytesIO(b"x"), 5, 9)

    def test_missing_gtfs_table_is_rejected(self):
        partial = io.BytesIO()
        with zipfile.ZipFile(partial, "w") as z:
            z.writestr("stops.txt", "stop_id,parent_station,stop_name\n")
            z.writestr("trips.txt", "route_id,trip_id,direction_id,trip_headsign\n")
        with self.assertRaises(ValueError):
            refresh_module.build([], partial.getvalue())


if __name__ == "__main__":
    unittest.main()
