import io
import json
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

from google.transit import gtfs_realtime_pb2 as gtfs

if __package__:
    from . import context_feeds as feeds
else:
    import context_feeds as feeds


NOW = 1704067200  # 2024-01-01 00:00 UTC


class AlertTests(unittest.TestCase):
    def setUp(self):
        self.feed = gtfs.FeedMessage()
        self.feed.header.gtfs_realtime_version = "2.0"
        self.feed.header.timestamp = NOW

    def alert(self, text="Service change", **selector):
        entity = self.feed.entity.add(id=str(len(self.feed.entity)))
        entity.alert.header_text.translation.add(text=text, language="en")
        entity.alert.informed_entity.add(**selector)
        return entity.alert

    def result(self, stop="116S", direction="S", route="1", now=NOW):
        snapshot = feeds._parse_alerts(self.feed.SerializeToString())
        with patch.object(feeds._alert_cache, "get", return_value=snapshot):
            return feeds.alert_for(route, stop, direction, now)

    def test_station_and_platform_with_direction(self):
        self.alert(route_id="1", agency_id="MTASBWY", stop_id="116", direction_id=0)
        self.assertIsNone(self.result())
        self.assertEqual(self.result("116", "N"), "Service change")
        self.assertIsNone(self.result("116N", "S"))
        self.assertIsNone(self.result("115", "N"))
        self.assertIsNone(self.result("116", "N", "2"))
        self.alert("Downtown delay", route_id="1", stop_id="116S", direction_id=1)
        self.assertEqual(self.result("116"), "Downtown delay")

    def test_parent_unspecified_direction_covers_both_platforms(self):
        self.alert(stop_id="116", route_id="1")
        for d in ("N", "S"):
            self.assertEqual(self.result("116" + d, d), "Service change")

    def test_partial_selectors_cannot_be_joined(self):
        a = self.alert(route_id="1")
        for selector in (
            dict(stop_id="116"), dict(route_id="1", stop_id="115"),
            dict(route_id="2", stop_id="116"), dict(route_id="1", stop_id="116N"),
            dict(route_id="1", stop_id="116", route_type=3),
            dict(route_id="1", stop_id="116", agency_id="MTA NYCT"),
            dict(route_id="1", stop_id="116", direction_id=2),
        ):
            a.informed_entity.add(**selector)
        a.informed_entity.add(route_id="1", stop_id="116").trip.trip_id = "single trip"
        self.assertIsNone(self.result())

    def test_active_periods_are_half_open_and_may_be_unbounded(self):
        a = self.alert(route_id="1", stop_id="116")
        a.active_period.add(end=NOW)
        a.active_period.add(start=NOW + 10)
        for offset, expected in ((-1, "Service change"), (0, None), (9, None), (10, "Service change")):
            with self.subTest(offset=offset):
                self.assertEqual(self.result(now=NOW + offset), expected)

    def test_plain_english_title_and_priority(self):
        self.alert("Ordinary notice", route_id="1", stop_id="116")
        a = self.alert("Suspended", route_id="1", stop_id="116", direction_id=1)
        a.effect = gtfs.Alert.NO_SERVICE
        a.header_text.Clear()
        a.header_text.translation.add(text="<b>Suspended</b>", language="en-html")
        a.header_text.translation.add(text="[1] trains\n suspended.", language="en")
        self.assertEqual(self.result(), "1 trains suspended.")
        self.feed.entity[1].is_deleted = True
        self.assertEqual(self.result(), "Ordinary notice")

    def test_ambiguous_markup_or_language(self):
        a = self.alert(route_id="1", stop_id="116")
        a.header_text.Clear()
        a.header_text.translation.add(text="<div>Delay</div>", language="en")
        a.header_text.translation.add(text="Retard", language="fr")
        self.assertIsNone(self.result())
        a.header_text.translation.add(text="x" * 200)
        self.assertEqual(self.result(), "x" * 159 + "…")

    def test_stale_future_missing_or_differential_snapshot(self):
        self.alert(route_id="1", stop_id="116")
        self.assertIsNone(self.result(now=NOW + feeds._MAX_ALERT_AGE))
        self.assertIsNone(self.result(now=NOW - 61))
        self.feed.header.incrementality = gtfs.FeedHeader.DIFFERENTIAL
        with self.assertRaises(ValueError):
            self.result()
        self.feed.header.ClearField("incrementality")
        self.feed.header.ClearField("timestamp")
        with self.assertRaises(ValueError):
            self.result()

    def notice(self, stop="116S", direction="S", route="1", now=NOW):
        snapshot = feeds._parse_alerts(self.feed.SerializeToString())
        with patch.object(feeds._alert_cache, "get", return_value=snapshot):
            return feeds.notice_for(route, stop, direction, now)

    def test_live_bypass_has_three_selectors_but_one_closed_platform(self):
        a = self.alert("Downtown [1] skips 125 St", route_id="1", stop_id="116", direction_id=1)
        for other in ("112", "117"):
            a.informed_entity.add(route_id="1", stop_id=other, direction_id=1)
        a.description_text.translation.add(language="en", text="Use nearby 116 St-Columbia University.")
        a.active_period.add(start=NOW, end=NOW + 2)
        a.active_period.add(start=NOW + 10, end=NOW + 20)
        text = "Downtown 1 skips 125 St"
        self.assertEqual(self.notice(), {"text": text, "preventsBoarding": True})
        self.assertEqual(self.result(), text)
        for other in ("112S", "117S"):
            self.assertEqual(self.notice(other), {"text": text, "preventsBoarding": False})
        self.assertIsNone(self.notice("116N", "N"))
        self.assertIsNone(self.notice(now=NOW - 1))
        self.assertIsNone(self.notice(now=NOW + 2))
        self.assertTrue(self.notice(now=NOW + 10)["preventsBoarding"])

    def test_multiple_named_stations_and_unlisted_transfer(self):
        a = self.alert("In Upper Manhattan, downtown [1] skips 125 St, 116 St-Columbia University, "
                       "Cathedral Pkwy (110 St) and 103 St", route_id="1", stop_id="116", direction_id=1)
        for stop in ("112", "117", "118", "119"):
            a.informed_entity.add(route_id="1", stop_id=stop, direction_id=1)
        for stop in ("116", "117", "118", "119"):
            self.assertTrue(self.notice(stop)["preventsBoarding"])
        self.assertFalse(self.notice("112")["preventsBoarding"])

    def test_exclusion_wins_over_other_active_notices_and_takes_end_boundary(self):
        self.alert("Delays", route_id="1", stop_id="116S")
        a = self.alert("Downtown [1] skips 125 St", route_id="1", stop_id="116")
        a.active_period.add(end=NOW + 1)
        self.assertTrue(self.notice()["preventsBoarding"])
        self.assertFalse(self.notice("116N", "N")["preventsBoarding"])
        self.assertEqual(self.notice(now=NOW + 1), {"text": "Delays", "preventsBoarding": False})

    def test_exact_station_route_direction_and_unconditional_action_required(self):
        for headline in ("Downtown [1] skips 125 St tomorrow", "Downtown [1] may skip 125 St",
                         "Some downtown [1] trains skip 125 St", "Downtown [2] skips 125 St",
                         "Downtown [1] skips 125 St North", "Downtown [1] skips 125 St except at night",
                         "Uptown [1] skips 125 St", "In Queens, downtown [1] skips 125 St",
                         "Use 125 St instead", "125 St station entrance is closed",
                         "Downtown [1] no longer skips 125 St", "[1] trains resume stopping at 125 St"):
            with self.subTest(headline=headline):
                self.feed.ClearField("entity")
                self.alert(headline, route_id="1", stop_id="116", direction_id=1)
                self.assertFalse(self.notice()["preventsBoarding"])

    def test_no_service_effect_and_explicit_contradictions(self):
        a = self.alert("Service suspended", route_id="1", stop_id="116", direction_id=1)
        a.effect = gtfs.Alert.NO_SERVICE
        self.assertTrue(self.notice()["preventsBoarding"])
        self.assertIsNone(self.notice("116N", "N"))
        self.assertIsNone(self.notice(route="2"))
        a.header_text.Clear()
        self.assertEqual(self.notice(), {"text": "No service at this platform", "preventsBoarding": True})
        a.header_text.translation.add(language="en", text="Downtown [1] skips 103 St")
        self.assertFalse(self.notice()["preventsBoarding"])

    def test_explicit_station_suspensions(self):
        for headline in ("No downtown [1] service at 125 St", "125 St station is closed",
                         "Downtown [1] service is suspended at 125 St", "[1] trains are suspended"):
            with self.subTest(headline=headline):
                self.feed.ClearField("entity")
                self.alert(headline, route_id="1", stop_id="116", direction_id=1)
                self.assertTrue(self.notice()["preventsBoarding"])

    def test_regressed_or_future_feed_cannot_replace_current_exclusion(self):
        with patch.object(feeds, "_fetch", return_value=b""), patch.object(feeds.time, "time", return_value=NOW):
            for timestamp in (NOW - 1, NOW + 61):
                with patch.object(feeds, "_parse_alerts", return_value=(timestamp, {})):
                    with self.assertRaises(ValueError):
                        feeds._load_alerts(feeds._ALERT_URL, (NOW, {}))

    def test_readiness_is_distinct_from_no_matching_notice(self):
        for snapshot, ready in ((None, False), ((NOW - feeds._MAX_ALERT_AGE, {}), False),
                                ((NOW + 61, {}), False), ((NOW, {}), True)):
            with patch.object(feeds._alert_cache, "get", return_value=snapshot) as cache:
                self.assertEqual(feeds.notice_state_for("1", "116S", "S", NOW), (ready, None))
                cache.assert_called_once()



class WeatherTests(unittest.TestCase):
    def test_kinds(self):
        cases = (("Mostly Sunny", True, "sun"), ("Mostly Clear", False, "night"),
                 ("Cloudy", False, "cloud"), ("Rain Showers", False, "rain"),
                 ("Rain And Snow", True, "snow"), ("Patchy Fog", True, "fog"),
                 ("Showers And Thunderstorms", True, "storm"), ("Windy", True, None),
                 ("Clear", None, None))
        for text, day, kind in cases:
            with self.subTest(text=text):
                self.assertEqual(feeds._weather_kind(text, day), kind)

    def test_point_lookup_hour_change_grid_reuse_and_expiry(self):
        url = "https://api.weather.gov/gridpoints/OKX/34,47/forecast/hourly"
        hourly = {"updateTime": "2023-12-31T23:00:00Z", "periods": [
            {"startTime": "2023-12-31T18:00:00-05:00", "endTime": "2024-01-01T00:00:00Z",
             "shortForecast": "Clear", "isDaytime": False, "temperature": "50", "temperatureUnit": "F"},
            {"startTime": "2024-01-01T00:00:00Z", "endTime": "2024-01-01T01:00:00Z",
             "shortForecast": "Chance Rain Showers", "isDaytime": False, "temperature": 54, "temperatureUnit": "F"},
        ]}
        points = {"forecastHourly": url}
        def upstream(endpoint, accept, limit):
            return json.dumps({"properties": hourly if endpoint == url else points}).encode()

        with patch.object(feeds, "_fetch", side_effect=upstream) as fetch:
            weather = feeds._load_weather("40.8156,-73.9584", None)
            self.assertEqual([call.args[0] for call in fetch.call_args_list],
                             [feeds._POINTS + "40.8156,-73.9584", url])
            feeds._load_weather("40.8156,-73.9584", weather)
            self.assertEqual(fetch.call_count, 3)
            with patch.object(feeds.time, "monotonic", return_value=weather.mapped_at + 86400):
                feeds._load_weather("40.8156,-73.9584", weather)
            self.assertEqual(fetch.call_count, 5)

        with patch.object(feeds._weather_cache, "get", return_value=weather) as cache:
            for offset, expect in ((-1, "night"), (0, "rain"), (3600, None)):
                with patch.object(feeds.time, "time", return_value=NOW + offset):
                    result = feeds.weather_for(40.815581, -73.958372)
                self.assertEqual(result and result["kind"], expect)
                if expect == "rain":
                    self.assertEqual(result["label"], "Chance Rain Showers")
                    self.assertEqual(result["temperature"], 54)
                    self.assertEqual(result["temperatureUnit"], "F")
                    self.assertEqual(result["expiresAt"], NOW + 3600)
                elif expect == "night":
                    self.assertNotIn("temperature", result)
                    self.assertNotIn("temperatureUnit", result)
            cache.assert_called_with("40.8156,-73.9584")
            with patch.object(feeds.time, "time", return_value=NOW), patch.object(
                    feeds._weather_cache, "get", return_value=feeds._Weather(
                        url, 0, weather.periods, NOW - 86400)):
                self.assertIsNone(feeds.weather_for(40, -74))

    def test_invalid_coords_or_hourly_url(self):
        with patch.object(feeds._weather_cache, "get") as cache:
            for lat, lon in ((None, 0), (91, 0), (40, -181), (float("nan"), 0)):
                self.assertIsNone(feeds.weather_for(lat, lon))
            cache.assert_not_called()
        with patch.object(feeds, "_properties", return_value={"forecastHourly": "http://elsewhere/"}) as get:
            with self.assertRaises(ValueError):
                feeds._load_weather("40,-74", None)
            self.assertEqual(get.call_count, 1)

    def test_download_timeout_agent_and_size_limit(self):
        for data in (b"1234", b"12345"):
            with patch.object(feeds, "urlopen", return_value=io.BytesIO(data)) as opener:
                if len(data) > 4:
                    with self.assertRaises(ValueError):
                        feeds._fetch(feeds._ALERT_URL, "application/x-protobuf", 4)
                else:
                    self.assertEqual(feeds._fetch(feeds._ALERT_URL, "application/x-protobuf", 4), data)
                req = opener.call_args.args[0]
                self.assertIn("SubwayGuide", req.get_header("User-agent"))
                self.assertEqual(opener.call_args.kwargs["timeout"], 4)


class CacheTests(unittest.TestCase):
    def test_concurrent_reads_do_not_wait_duplicate_or_queue(self):
        started, release = threading.Event(), threading.Event()
        calls = []
        def load(key, old):
            calls.append(key)
            started.set()
            release.wait(5)
            return "snapshot"
        cache = feeds._Cache(load, workers=1, capacity=3, refresh=30, expire=60, retry=10)
        try:
            self.assertIsNone(cache.get("current"))
            self.assertTrue(started.wait(1))
            with ThreadPoolExecutor(max_workers=8) as callers:
                results = list(callers.map(cache.get, ["current", "other"] * 80))
            self.assertEqual(results, [None] * len(results))
            self.assertEqual(list(cache.entries), ["current"])
        finally:
            release.set()
            cache.pool.shutdown()
        self.assertEqual(calls, ["current"])
        self.assertEqual(cache.get("current"), "snapshot")

    def test_stale_revalidation_failure_backoff_expiry_and_recovery(self):
        jobs = []
        results = iter(["first", TimeoutError(), TimeoutError(), "recovered"])
        def load(key, old):
            result = next(results)
            if isinstance(result, Exception):
                raise result
            return result
        cache = feeds._Cache(load, workers=1, capacity=1, refresh=5, expire=12, retry=4)
        with patch.object(cache.pool, "submit", side_effect=lambda fn, *a: jobs.append((fn, a))), \
                patch.object(feeds.time, "monotonic") as clock:
            for t, value, should_refresh in (
                (0, None, True), (4, "first", False), (5, "first", True),
                (8, "first", False), (9, "first", True), (12, None, False),
                (13, None, True), (14, "recovered", False),
            ):
                clock.return_value = t
                self.assertEqual(cache.get("station"), value)
                self.assertEqual(len(jobs), int(should_refresh))
                if jobs:
                    fn, args = jobs.pop()
                    fn(*args)
            self.assertIsNone(cache.get("other"))
            self.assertEqual(list(cache.entries), ["other"])
        cache.pool.shutdown()


if __name__ == "__main__":
    unittest.main()
