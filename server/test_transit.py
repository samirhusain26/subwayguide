"""Offline tests using real GTFS-realtime wire messages and a controlled clock."""
import io
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

if __package__:
    from . import transit
else:
    import transit

gtfs = transit.gtfs
NOW = 1_800_000_000
STATION = {"id": "116", "name": "125 St", "lat": 40.816, "lon": -73.958,
           "routes": ["1"], "directions": {"N": "Uptown", "S": "Downtown"}}
STATIONS = {"116": STATION, "142": {"name": "South Ferry", "directions": {"N": "Uptown"}},
            "137": {"name": "Chambers St", "directions": {"S": "Downtown", "N": "Uptown"}}}


def snapshot():
    feed = gtfs.FeedMessage()
    feed.header.gtfs_realtime_version = "1.0"
    feed.header.timestamp = NOW
    return feed


def trip(feed, train="train", route="1", stops=None):
    entity = feed.entity.add(id=str(len(feed.entity)))
    update = entity.trip_update
    update.trip.trip_id = train
    update.trip.start_date = "20270115"
    update.trip.route_id = route
    if stops is None:
        stops = [("116S", 60, 61), ("142S", 600, None)]
    for platform, arrival, departure in stops:
        stop = update.stop_time_update.add(stop_id=platform)
        if arrival is not None:
            stop.arrival.time = NOW + arrival
        if departure is not None:
            stop.departure.time = NOW + departure
    return update


class TransitTest(unittest.TestCase):
    def setUp(self):
        self.feed = snapshot()
        self.wall = NOW
        self.clock = 0
        slots = {url: transit._CachedFeed() for url in transit._CACHE}
        self.patch(transit, "_CACHE", slots)
        self.patch(transit.time, "time", side_effect=lambda: self.wall)
        self.patch(transit.time, "monotonic", side_effect=lambda: self.clock)
        self.network = self.patch(transit, "urlopen", side_effect=self.respond)

    def patch(self, obj, field, *args, **kwargs):
        patcher = patch.object(obj, field, *args, **kwargs)
        self.addCleanup(patcher.stop)
        return patcher.start()

    def respond(self, req, timeout):
        self.assertIn(req.full_url, transit._CACHE)
        self.assertGreater(timeout, 0)
        return io.BytesIO(self.feed.SerializeToString())

    def board(self, route="1", direction="S", station=STATION):
        return transit.get_arrivals(station, route, direction, STATIONS)

    def test_platform_route_branch_order_origin_and_dwell(self):
        trip(self.feed, "branch", stops=[("116S", 100, 101), ("137S", 190, None)])
        trip(self.feed, "origin", stops=[("116S", None, 60), ("142S", 600, None)])
        trip(self.feed, "at-platform", stops=[("116S", -5, 10), ("142S", 600, 600)])
        trip(self.feed, "north", stops=[("116N", 20, 21), ("101N", 300, None)])
        trip(self.feed, "other-route", route="2")
        trip(self.feed, "lookalike", stops=[("1160S", 2, 3), ("142S", 100, None)])
        trip(self.feed, "already-departed", stops=[("116S", -6, -5), ("142S", 80, None)])
        result = self.board()
        self.assertEqual((result["status"], result["feedTime"]), ("ok", NOW))
        self.assertEqual([(a["time"], a["destination"]) for a in result["arrivals"]], [
            (NOW + 10, "South Ferry"), (NOW + 60, "South Ferry"), (NOW + 100, "Chambers St")])
        self.assertEqual(len({r["id"] for r in result["arrivals"]}), 3)

    def test_not_boardable(self):
        trip(self.feed, "ending", stops=[("115S", 10, 10), ("116S", 20, 20)])
        canceled = trip(self.feed, "canceled")
        canceled.trip.schedule_relationship = canceled.trip.CANCELED
        trip(self.feed, "removed")
        self.feed.entity[-1].is_deleted = True
        skipped = trip(self.feed, "skipped").stop_time_update[0]
        skipped.schedule_relationship = skipped.SKIPPED
        no_data = trip(self.feed, "no-eta").stop_time_update[0]
        no_data.schedule_relationship = no_data.NO_DATA
        downstream = trip(self.feed, "last-served").stop_time_update[-1]
        downstream.schedule_relationship = downstream.SKIPPED
        trip(self.feed, "delays-only", stops=[("116S", None, None), ("142S", 200, None)])
        self.assertEqual(self.board(), {"status": "ok", "feedTime": NOW, "arrivals": []})

    def test_partial_or_unknown_tail_uses_direction(self):
        trip(self.feed, "partial", stops=[("116S", 1, 2), ("137S", 50, 50)])
        trip(self.feed, "unknown", stops=[("116S", 3, 4), ("999S", 50, None)])
        tail = trip(self.feed, "skipped-tail", stops=[
            ("116S", 5, 6), ("137S", 50, 50), ("142S", 99, None)]).stop_time_update[-1]
        tail.schedule_relationship = tail.SKIPPED
        self.assertEqual([a["destination"] for a in self.board()["arrivals"]], ["Downtown"] * 3)

    def test_terminal_at_a_shared_station(self):
        church = {"name": "Church Av", "directions": {"N": "Northbound", "S": "Southbound"},
                  "routeDirections": {"G": ["N"], "F": ["N", "S"]}}
        stations = dict(STATIONS, F27=church)
        station = dict(STATION, id="F25")
        for route in ("G", "F"):
            trip(self.feed, route=route, stops=[("F25S", 60, 61), ("F27S", 180, 180)])
        g = transit.get_arrivals(station, "G", "S", stations)
        f = transit.get_arrivals(station, "F", "S", stations)
        self.assertEqual(g["arrivals"][0]["destination"], "Church Av")
        self.assertEqual(f["arrivals"][0]["destination"], "Downtown")

    def test_held_and_origin_trains(self):
        stalled = trip(self.feed, "stalled")
        moving = trip(self.feed, "moving")
        trip(self.feed, "origin", stops=[("116S", None, 20), ("142S", 400, None)])
        trip(self.feed, "late-origin", stops=[
            ("115S", None, -91), ("116S", 40, 40), ("142S", 400, None)])
        for update, lag in ((stalled, 91), (moving, 90)):
            car = self.feed.entity.add(id="vehicle-" + update.trip.trip_id).vehicle
            car.trip.CopyFrom(update.trip)
            car.timestamp = NOW - lag
        rows = self.board()["arrivals"]
        self.assertEqual([r["id"].split("/")[-1] for r in rows], ["origin", "moving"])

    def vehicle(self, update, raw, lag):
        car = self.feed.entity.add(id="car-" + str(len(self.feed.entity))).vehicle
        car.trip.CopyFrom(update.trip)
        car.trip.trip_id = raw
        car.timestamp = NOW - lag
        return car

    def delay(self, raw, route):
        alert = self.feed.entity.add(id="delay-" + str(len(self.feed.entity))).alert
        alert.header_text.translation.add(text="Train delayed")
        selector = alert.informed_entity.add(route_id=route)
        selector.trip.trip_id = raw
        return selector

    def test_120_second_old_suffixless_vehicle_suppresses_116s_prediction(self):
        held = trip(self.feed, "060000_1..S08R")
        self.vehicle(held, "060000_1..S", 120)
        trip(self.feed, "060100_1..S03R", stops=[("116S", 100, 101), ("142S", 700, None)])
        self.assertEqual([r["time"] for r in self.board()["arrivals"]], [NOW + 100])

    def test_delay_selector_changes_path_without_losing_the_trip(self):
        for raw, selector in (("060000_1..S08R", "060000_1..S"),
                              ("060000_1..S", "060000_1..S08R"),
                              ("060000_1..S08R", "060000_1..S09R")):
            with self.subTest(raw=raw, selector=selector):
                self.feed = snapshot()
                trip(self.feed, raw)
                trip(self.feed, "060100_1..S")
                self.delay(selector, "1")
                self.clock += transit._TTL
                self.assertEqual([r["id"].split("/")[-1] for r in self.board()["arrivals"]],
                                 ["060100_1..S"])
                del self.feed.entity[-1]
                self.clock += transit._TTL
                self.assertEqual(len(self.board()["arrivals"]), 2)

    def test_ambiguous_held_signal_only_suppresses_overlapping_trips(self):
        for condition in ("stalled", "delay"):
            with self.subTest(condition=condition):
                self.feed = snapshot()
                a = trip(self.feed, "060000_1..S08R")
                trip(self.feed, "060000_1..S19R")
                other_day = trip(self.feed, "060000_1..S")
                other_day.trip.start_date = "20270116"
                trip(self.feed, "060000_1..S", route="2")
                if condition == "stalled":
                    self.vehicle(a, "060000_1..S", 120)
                    self.vehicle(a, a.trip.trip_id, 2)
                    self.vehicle(a, "060000_1..S", 1)
                else:
                    self.delay("060000_1..S", "1").trip.start_date = a.trip.start_date
                self.clock += transit._TTL
                row, = self.board()["arrivals"]
                self.assertIn("/20270116/", row["id"])
                self.assertEqual(len(self.board("2")["arrivals"]), 1)

    def test_two_vehicle_aliases_cannot_overwrite_a_stall_on_a_bare_update(self):
        update = trip(self.feed, "060000_1..S")
        self.vehicle(update, "060000_1..S08R", 120)
        self.vehicle(update, "060000_1..S09R", 1)
        self.assertEqual(self.board()["arrivals"], [])

    def test_explicit_variant_identifies_one_of_two_known_paths(self):
        first = trip(self.feed, "060000_1..S08R")
        trip(self.feed, "060000_1..S09R", stops=[("116S", 100, 100), ("142S", 600, None)])
        self.vehicle(first, first.trip.trip_id, 120)
        row, = self.board()["arrivals"]
        self.assertEqual((row["time"], row["id"]), (NOW + 100, ""))

    def test_positive_aliased_movement_must_identify_one_trip(self):
        stops = [("115S", None, -120), ("116S", 80, 80), ("142S", 600, None)]
        first = trip(self.feed, "060000_1..S08R", stops=stops)
        self.vehicle(first, "060000_1..S", 10)
        self.assertEqual(len(self.board()["arrivals"]), 1)
        trip(self.feed, "060000_1..S09R", stops=stops)
        self.clock += transit._TTL
        self.assertEqual(self.board()["arrivals"], [])

    def test_aliases_and_all_feed_urls(self):
        cases = [("6", "6X", "610", ""), ("7", "7X", "710", ""),
                 ("F", "FX", "F24", "-bdfm"), ("SIR", "SI", "S15", "-si"),
                 ("S", "GS", "901", ""), ("S", "FS", "D26", "-bdfm"),
                 ("S", "H", "H04", "-ace"), ("G", "G", "G22", "-g"),
                 ("J", "J", "J20", "-jz"), ("Q", "Q", "Q03", "-nqrw"),
                 ("L", "L", "L08", "-l")]
        for selected, actual, parent, suffix in cases:
            with self.subTest(selected=selected, parent=parent):
                self.feed = snapshot()
                trip(self.feed, route=actual, stops=[(parent + "S", 15, 16), ("142S", 600, None)])
                self.clock += transit._TTL
                result = self.board(selected, station=dict(STATION, id=parent))
                self.assertEqual(len(result["arrivals"]), 1)
                self.assertEqual(result["arrivals"][0]["onward"], "142S")
                self.assertEqual(result["arrivals"][0]["route"], actual)
                self.assertEqual(self.network.call_args.args[0].full_url, transit._BASE + suffix)
        self.assertEqual(len(transit._CACHE), 8)

    def test_mta_delay_alert_only_suppresses_its_trip(self):
        for train in ("held", "following", "old-alert", "other-day"):
            trip(self.feed, train)
        for train in ("held", "old-alert", "other-day", ""):
            alert = self.feed.entity.add(id="delay-" + train).alert
            alert.header_text.translation.add(text="Train delayed")
            selector = alert.informed_entity.add(route_id="1")
            if train:
                selector.trip.trip_id = train
                selector.trip.route_id = "1"
            if train == "old-alert":
                alert.active_period.add(end=NOW - 1)
            if train == "other-day":
                selector.trip.start_date = "20270114"
        self.assertEqual({r["id"].split("/")[-1] for r in self.board()["arrivals"]},
                         {"following", "old-alert", "other-day"})

    def test_express_selection_only_shows_express_trips(self):
        for route in ("6", "6X", "7X"):
            trip(self.feed, route=route, stops=[("610S", 30, 30), ("640S", 300, None)])
        station = dict(STATION, id="610")
        express = self.board("6X", station=station)["arrivals"]
        all_sixes = self.board("6", station=station)["arrivals"]
        self.assertEqual(len(all_sixes), 2)
        self.assertEqual([r["id"].split("/")[0] for r in express], ["6X"])

    def test_reuses_snapshot_then_reconciles_full_feed(self):
        trip(self.feed)
        self.assertEqual(len(self.board()["arrivals"]), 1)
        self.feed.ClearField("entity")
        self.board("2")
        self.board(direction="N")
        self.assertEqual(self.network.call_count, 1)
        self.clock = transit._TTL
        self.assertEqual(self.board()["arrivals"], [])
        self.assertEqual(self.network.call_count, 2)

    def test_path_suffix_can_disappear_and_return_as_eta_changes(self):
        ids = []
        for raw, eta in (("021150_1..S08R", 100), ("021150_1..S", 133), ("021150_1..S08R", 119)):
            self.feed = snapshot()
            trip(self.feed, raw, stops=[("116S", eta, eta), ("142S", 600, None)])
            self.clock += transit._TTL
            row, = self.board()["arrivals"]
            self.assertEqual(row["time"], NOW + eta)
            ids.append(row["id"])
        self.assertTrue(ids[0])
        self.assertEqual(len(set(ids)), 1)

    def test_service_date_route_origin_direction_and_start_distinguish_runs(self):
        for raw in ("021150_6..S08R", "021200_6..S08R", "021150_6..N08R"):
            trip(self.feed, raw, route="6")
        trip(self.feed, "021150_6..S08R", route="6").trip.start_date = "20270116"
        trip(self.feed, "021150_6..S08R", route="6").trip.start_time = "03:32:00"
        trip(self.feed, "021150_6..S08R", route="6X")
        ids = [r["id"] for r in self.board("6")["arrivals"]]
        self.assertEqual(len(ids), 6)
        self.assertEqual(len(set(ids)), 6)
        self.assertNotIn("", ids)

    def test_collision_keeps_rows_and_disables_chime_even_after_a_gap(self):
        for raw, eta in (("021150_1..S08R", 60), ("021150_1..S19R", 80), ("021200_1..S", 120)):
            trip(self.feed, raw, stops=[("116S", eta, eta), ("142S", 600, None)])
        rows = self.board()["arrivals"]
        self.assertEqual([r["time"] for r in rows], [NOW + 60, NOW + 80, NOW + 120])
        self.assertEqual([r["id"] for r in rows[:2]], ["", ""])
        self.assertTrue(rows[2]["id"])
        self.feed.ClearField("entity")
        self.clock += transit._TTL
        self.assertFalse(self.board()["arrivals"])
        self.clock += transit._TTL
        trip(self.feed, "021150_1..S")
        row, = self.board()["arrivals"]
        self.assertEqual(row["id"], "")

    def test_collision_outside_selected_platform_and_undated_trip(self):
        trip(self.feed, "021150_1..S08R")
        trip(self.feed, "021150_1..S09R", stops=[("115S", 20, 20), ("142S", 600, None)])
        trip(self.feed, "021200_1..S").trip.ClearField("start_date")
        self.assertEqual([r["id"] for r in self.board()["arrivals"]], ["", ""])

    def test_duplicate_or_missing_descriptor_still_keeps_every_arrival(self):
        for train in ("021150_1..S", "021150_1..S", ""):
            trip(self.feed, train)
        rows = self.board()["arrivals"]
        self.assertEqual([(r["id"], r["time"]) for r in rows], [("", NOW + 60)] * 3)

    def test_trickling_body_exceeds_total_deadline_and_cache_cools_down(self):
        test = self
        class Trickle(io.BytesIO):
            reads = 0
            def read1(self, size):
                self.reads += 1
                test.clock += 1
                return b"x"
        stream = Trickle()
        self.network.side_effect = lambda *args, **kwargs: stream
        self.assertEqual(self.board()["status"], "unavailable")
        self.assertTrue(stream.closed)
        self.assertEqual(stream.reads, transit._FETCH_BUDGET)
        self.assertEqual(self.board()["status"], "unavailable")
        self.assertEqual(self.network.call_count, 1)

    def test_stalled_headers_cannot_hold_waiters_or_start_duplicate_fetches(self):
        trip(self.feed)
        initial = self.board()
        self.clock += transit._TTL
        entered, resume = threading.Event(), threading.Event()
        refresh_done = threading.Event()
        def stalled(*args, **kwargs):
            entered.set()
            resume.wait(timeout=5)
            return self.respond(*args, **kwargs)
        self.network.side_effect = stalled
        # Simulate expiration in Event.wait without making the test sleep.
        test = self
        class DeadlineEvent:
            def set(self):
                refresh_done.set()
            def wait(self, timeout):
                test.assertTrue(entered.wait(timeout=2))
                test.assertLessEqual(timeout, transit._FETCH_BUDGET)
                test.clock += timeout
                return False
        self.patch(transit, "Event", DeadlineEvent)
        try:
            for _ in range(2):
                self.assertEqual(self.board(), dict(initial, status="stale"))
            self.assertEqual(self.network.call_count, 2)
        finally:
            resume.set()
            self.assertTrue(refresh_done.wait(timeout=2))
        self.assertEqual(self.board(), dict(initial, status="stale"))
        self.assertEqual(self.network.call_count, 2)

    def test_offline_and_single_flight_across_clients(self):
        # All callers reach the same cold cache together.
        barrier = threading.Barrier(8)
        self.network.side_effect = TimeoutError
        def request(i):
            barrier.wait(timeout=5)
            return self.board("1" if i % 2 else "2")
        with ThreadPoolExecutor(8) as pool:
            results = list(pool.map(request, range(8)))
        self.assertEqual(results, [{"status": "unavailable", "feedTime": None, "arrivals": []}] * 8)
        self.assertEqual(self.network.call_count, 1)
        self.network.side_effect = self.respond
        self.clock += transit._TTL
        self.assertEqual(self.board()["status"], "ok")

    def test_stale_age_failure_and_expiration(self):
        trip(self.feed, stops=[("116S", 110, 111), ("142S", 300, None)])
        self.board()
        self.clock += transit._TTL
        self.wall += 20
        self.network.side_effect = OSError("offline")
        self.assertEqual(self.board()["status"], "stale")
        self.assertEqual(self.board()["feedTime"], NOW)
        self.network.side_effect = self.respond
        self.clock += transit._TTL
        self.wall = NOW + 60
        self.assertEqual(self.board()["status"], "ok")
        self.wall += 1
        self.assertEqual(len(self.board()["arrivals"]), 1)
        self.assertEqual(self.board()["status"], "stale")
        self.wall = NOW + 120
        self.assertEqual(self.board()["status"], "stale")
        self.wall = NOW + 121
        self.assertEqual(self.board(), {"status": "unavailable", "feedTime": NOW, "arrivals": []})

    def test_malformed_missing_differential_and_future(self):
        differential = snapshot()
        differential.header.incrementality = gtfs.FeedHeader.DIFFERENTIAL
        future = snapshot()
        future.header.timestamp += 500
        for payload in (b'bad wire', b'', b'\x12\x00', differential.SerializeToString(),
                        future.SerializeToString(), b'0' * (transit._MAX_BYTES + 1)):
            with self.subTest(length=len(payload)):
                self.network.side_effect = lambda *a, **kw: io.BytesIO(payload)
                self.clock += transit._TTL
                self.assertEqual(self.board(), {"status": "unavailable", "feedTime": None, "arrivals": []})

    def test_bad_refresh_keeps_previous_snapshot(self):
        trip(self.feed)
        initial = self.board()
        self.feed.header.timestamp -= 1
        self.feed.ClearField("entity")
        self.clock += transit._TTL
        self.assertEqual(self.board(), dict(initial, status="stale"))
        self.network.side_effect = lambda *a, **kw: io.BytesIO(b"truncated")
        self.clock += transit._TTL
        self.assertEqual(self.board(), dict(initial, status="stale"))

    def test_future_header_then_clock_rollback(self):
        self.feed.header.timestamp = NOW + 1
        self.assertEqual(self.board(), {"status": "unavailable", "feedTime": None, "arrivals": []})
        self.clock += transit._TTL
        self.feed.header.timestamp = NOW
        trip(self.feed)
        initial = self.board()
        self.clock += transit._TTL
        self.feed.header.timestamp = NOW + 1
        self.assertEqual(self.board(), dict(initial, status="stale"))
        self.wall = NOW - 1
        self.assertEqual(self.board(), {"status": "unavailable", "feedTime": NOW, "arrivals": []})

    def test_invalid_selectors_never_fetch(self):
        self.assertEqual(self.board("https://invalid")["status"], "unavailable")
        self.assertEqual(self.board(direction="bad")["status"], "unavailable")
        self.network.assert_not_called()


if __name__ == "__main__":
    unittest.main()
