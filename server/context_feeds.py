"""Optional station context. Board handlers read snapshots and never wait for network I/O."""
from __future__ import annotations

import json
import math
import re
import threading
import time
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from urllib.request import Request, urlopen

from google.transit import gtfs_realtime_pb2 as gtfs
if __package__:
    from .trip_data import direction_name
else:
    from trip_data import direction_name


_POINTS = "https://api.weather.gov/points/"
_ALERT_URL = "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/camsys%2Fsubway-alerts"
_HOURLY_URL = re.compile(r"https://api\.weather\.gov/gridpoints/[A-Z]{3}/\d+,\d+/forecast/hourly")
_MAX_ALERT_AGE = 3600  # MTA's alerts snapshot can stay unchanged between incidents.
STATIONS_PATH = Path(__file__).with_name("stations.json")


@dataclass
class _Entry:
    value: object = None
    expires: float = 0
    retry_at: float = 0
    loading: bool = False


class _Cache:
    """Demand-driven refresh with at most one submitted job per available worker."""

    def __init__(self, load, *, workers, capacity, refresh, expire, retry):
        self.load = load
        self.capacity = capacity
        self.refresh = refresh
        self.expire = expire
        self.retry = retry
        self.workers = workers
        self.pool = ThreadPoolExecutor(max_workers=workers, thread_name_prefix=load.__name__)
        self.lock = threading.Lock()
        self.entries = OrderedDict()
        self.busy = 0

    def get(self, key):
        with self.lock:
            now = time.monotonic()
            entry = self.entries.get(key)
            if entry is None:
                if self.busy == self.workers:
                    return None
                if len(self.entries) >= self.capacity:
                    victim = next((k for k, e in self.entries.items() if not e.loading), None)
                    if victim is None:
                        return None
                    del self.entries[victim]
                entry = self.entries[key] = _Entry()
            self.entries.move_to_end(key)
            if not entry.loading and now >= entry.retry_at and self.busy < self.workers:
                entry.loading = True
                self.busy += 1
                try:
                    self.pool.submit(self._update, key, entry)
                except RuntimeError:  # Executor is shutting down.
                    entry.loading = False
                    self.busy -= 1
            return entry.value if now < entry.expires else None

    def _update(self, key, entry):
        try:
            value = self.load(key, entry.value)
        except Exception:  # A provider failure leaves the previous expiry intact.
            with self.lock:
                entry.retry_at = time.monotonic() + self.retry
        else:
            with self.lock:
                now = time.monotonic()
                entry.value = value
                entry.expires = now + self.expire
                entry.retry_at = now + self.refresh
        finally:
            with self.lock:
                entry.loading = False
                self.busy -= 1


def _fetch(url, accept, limit):
    req = Request(url, headers={"User-Agent": "SubwayGuide/1.0 (station board)", "Accept": accept})
    deadline = time.monotonic() + 8
    with urlopen(req, timeout=4) as response:
        body = bytearray()
        while len(body) <= limit and time.monotonic() < deadline:
            chunk = response.read1(min(65536, limit + 1 - len(body)))
            if not chunk:
                return bytes(body)
            body.extend(chunk)
    raise ValueError("Context feed exceeded time or size limit")


def _properties(url):
    return json.loads(_fetch(url, "application/geo+json", 1024 * 1024))["properties"]


def _weather_kind(label, daytime):
    words = label.casefold()
    for kind, terms in (
        ("storm", ("thunder", "tornado", "tropical storm", "hurricane")),
        ("snow", ("snow", "sleet", "flurr", "ice pellet", "freezing rain", "wintry")),
        ("rain", ("rain", "shower", "drizzle")),
        ("fog", ("fog", "mist", "haze", "smoke")),
        ("cloud", ("cloud", "overcast")),
    ):
        if any(term in words for term in terms):
            return kind
    if ("clear" in words or "sunny" in words or words == "fair") and isinstance(daytime, bool):
        return "sun" if daytime else "night"
    return None


def _timestamp(value):
    dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if dt.utcoffset() is None:
        raise ValueError("Missing forecast timezone")
    return dt.timestamp()


@dataclass(frozen=True)
class _Weather:
    hourly_url: str
    mapped_at: float
    periods: tuple
    updated: float


def _load_weather(coords, previous):
    mapped_at = time.monotonic()
    if previous and mapped_at - previous.mapped_at < 86400:
        url, mapped_at = previous.hourly_url, previous.mapped_at
    else:
        url = _properties(_POINTS + coords)["forecastHourly"]
        if not _HOURLY_URL.fullmatch(url):
            raise ValueError("Unexpected NWS forecast URL")
    hourly = _properties(url)
    updated = _timestamp(hourly["updateTime"])
    now = time.time()
    if updated > now or now - updated >= 86400:
        raise ValueError("Unusable hourly forecast update time")
    if previous and updated < previous.updated:
        raise ValueError("Regressed hourly forecast update time")
    periods = []
    for period in hourly["periods"]:
        label = " ".join((period.get("shortForecast") or "").split())
        kind = _weather_kind(label, period.get("isDaytime"))
        if kind:
            start, end = _timestamp(period["startTime"]), _timestamp(period["endTime"])
            temperature = period.get("temperature")
            unit = period.get("temperatureUnit")
            fields = {}
            bounds = {"F": (-130, 140), "C": (-90, 60)}
            if type(temperature) in (int, float) and isinstance(unit, str) and unit in bounds:
                low, high = bounds[unit]
                if low <= temperature <= high:
                    fields = {"temperature": temperature, "temperatureUnit": unit}
            periods.append((start, end, kind, label, fields))
    return _Weather(url, mapped_at, tuple(periods), updated)


_weather_cache = _Cache(_load_weather, workers=2, capacity=64, refresh=900, expire=86400, retry=60)


def weather_for(lat, lon) -> dict[str, str | float] | None:
    """Return the NWS forecast for the current hour at the station, or None."""
    try:
        lat, lon = float(lat), float(lon)
    except (ValueError, TypeError, OverflowError):
        return None
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        return None
    weather = _weather_cache.get(f"{lat:.4f},{lon:.4f}")
    now = time.time()
    if weather and -60 <= now - weather.updated < 86400:
        for start, end, kind, label, fields in weather.periods:
            if start <= now < end:
                return {"kind": kind, "label": label, "expiresAt": min(end, weather.updated + 86400), **fields}
    return None


def _headline(alert):
    translations = alert.header_text.translation
    for language in ("en", "en-us", ""):
        for t in translations:
            if t.language.lower() != language:
                continue
            line = " ".join(t.text.split())
            if not line or "<" in line or ">" in line:
                continue
            line = re.sub(r"\[([A-Z0-9]{1,3})\]", r"\1", line)
            return line
    return None


# Target text must name the selected station as an object of the exclusion.
# MTA lists transfer/alternative stations in the very same set of selectors.
_AREA = re.compile(r"^in (?:upper |lower |the )?(manhattan|bronx|brooklyn|queens|staten island), ")
_SKIP = re.compile(r"(.+?) (?:skips?|bypasses|bypass|does not stop at|do not stop at|will not stop at) (.+)")
_NO_AT = re.compile(r"no (.+?) (?:service )?at (.+)")
_SUSPENDED_AT = re.compile(r"(.+?) (?:service is suspended|is suspended|are suspended) at (.+)")


def _subject_matches(subject, route, station, direction):
    label = direction_name(station, route, direction).casefold()
    prefixes = {"", "northbound " if direction == "N" else "southbound "}
    if label:
        for term in [label, *label.split(" / ")]:
            prefixes.update((term + " ", term + "-bound "))
    return any(subject == prefix + route + suffix
               for prefix in prefixes for suffix in ("", " train", " trains", " service"))


def _prevents_boarding(text, route, station, direction, no_service, names):
    line = text.casefold().removesuffix(".")
    area = _AREA.match(line)
    if area:
        if not station or area[1] != station["area"].casefold():
            return False
        line = line[area.end():]
    match = _SKIP.fullmatch(line) or _NO_AT.fullmatch(line) or _SUSPENDED_AT.fullmatch(line)
    if match:
        if not station:
            return False
        generic_subject = no_service and match[1] in ("service", "trains")
        if not (generic_subject or _subject_matches(match[1], route.casefold(), station, direction)):
            return False
        targets = re.split(r", (?:and )?| and ", match[2].removesuffix(" in both directions"))
        return station["name"].casefold() in targets and all(target in names for target in targets)
    if line.endswith(" station is closed"):
        return bool(station and line == station["name"].casefold() + " station is closed")
    suspended = re.fullmatch(r"(.+?) (?:(?:is|are) )?suspended", line)
    if suspended:
        if no_service and suspended[1] in ("service", "trains"):
            return True
        return bool(station and _subject_matches(suspended[1], route.casefold(), station, direction))
    # A narrow/conditional skip outside the supported grammar cannot turn every
    # station selector into a closure, even if an upstream effect is overly broad.
    if re.search(r"\b(?:skips?|skipping|bypass(?:es|ing)?|some|entrance|exit|not stop at|no longer stop at)\b", line):
        return False
    return bool(no_service and not line)


def _parse_alerts(data):
    feed = gtfs.FeedMessage.FromString(data)
    if (not feed.IsInitialized() or not feed.header.timestamp
            or feed.header.incrementality != gtfs.FeedHeader.FULL_DATASET):
        raise ValueError("Expected a complete, timestamped service alert snapshot")
    # This runs on the alert worker. Use the same local station names/direction
    # labels as the board, and pick up atomic catalog refreshes on the next feed.
    stations = json.loads(STATIONS_PATH.read_bytes())["stations"]
    catalog = {s["gtfsParentId"]: s for s in stations}
    names = {s["name"].casefold() for s in stations}
    by_stop = {}
    for entity in feed.entity:
        if entity.is_deleted or not entity.HasField("alert"):
            continue
        alert = entity.alert
        headline = _headline(alert)
        no_service = alert.HasField("effect") and alert.effect == gtfs.Alert.NO_SERVICE
        if not headline and not no_service:
            continue
        display = headline or "No service at this platform"
        if len(display) > 160:
            display = display[:159].rstrip() + "…"
        periods = tuple((p.start, p.end if p.HasField("end") else math.inf)
                        for p in alert.active_period)
        for s in alert.informed_entity:
            # Trip-specific selectors cannot be evaluated from a platform board.
            if (s.HasField("trip") or s.agency_id not in ("", "MTASBWY")
                    or (s.HasField("route_type") and s.route_type != 1)):
                continue
            route_only = bool(s.route_id and not s.stop_id)
            stop_only = bool(s.stop_id and not s.route_id)
            if route_only:
                # Expand only an explicit whole-line suspension, never prose that
                # merely mentions the line while describing one station.
                subject = re.fullmatch(r"(.+?) (?:(?:is|are) )?suspended",
                                       (headline or "").casefold().removesuffix("."))
                if not no_service or not subject:
                    continue
                # Rerouted trains may stop where this route has no scheduled pickup.
                targets = [(s.route_id, station, direction)
                           for station in stations for direction in ("N", "S")
                           if _subject_matches(subject[1], s.route_id.casefold(), station, direction)]
            elif stop_only:
                parent = s.stop_id[:-1] if s.stop_id.endswith(("N", "S")) else s.stop_id
                station = catalog.get(parent)
                # A stop-only selector can close a named station, but cannot
                # safely join with unrelated route-only selectors.
                if (station is None or not headline or
                        headline.casefold().removesuffix(".") != station.get("name", "").casefold() +
                        " station is closed"):
                    continue
                targets = [("*", station, direction) for direction in ("N", "S")]
            elif s.route_id and s.stop_id:
                parent = s.stop_id[:-1] if s.stop_id.endswith(("N", "S")) else s.stop_id
                station = catalog.get(parent)
                targets = [(s.route_id, station, direction) for direction in ("N", "S")]
            else:
                continue
            for route, station, direction in targets:
                if station is None:
                    continue
                dir_id = 0 if direction == "N" else 1
                if s.HasField("direction_id") and s.direction_id != dir_id:
                    continue
                if s.stop_id and not route_only and s.stop_id not in (station["gtfsParentId"],
                                                                          station["gtfsParentId"] + direction):
                    continue
                prevents = _prevents_boarding(headline or "", route, station, direction, no_service, names)
                # Whole-line and whole-station selectors provide the scope when
                # the explicit headline itself has no station object.
                if route_only or stop_only:
                    prevents = True
                priority = (not prevents, not no_service, not entity.id.startswith("lmm:alert:"), entity.id)
                by_stop.setdefault((route, station["gtfsParentId"], direction), []).append(
                    (priority, periods, display, prevents))
    return feed.header.timestamp, by_stop


def _load_alerts(key, previous):
    snapshot = _parse_alerts(_fetch(_ALERT_URL, "application/x-protobuf", 4 * 1024 * 1024))
    if snapshot[0] > time.time() + 60 or (previous and snapshot[0] < previous[0]):
        raise ValueError("Future or regressed service alert timestamp")
    return snapshot


_alert_cache = _Cache(_load_alerts, workers=1, capacity=1, refresh=60, expire=300, retry=30)


def notice_states_for(routes, stopId, direction, now) -> tuple[bool, dict[str, dict | None]]:
    """Evaluate routes against one alert snapshot for an atomic board decision."""
    if not isinstance(routes, (list, tuple)):
        return False, {}
    routes = tuple(dict.fromkeys(routes))
    if (not routes or any(not isinstance(route, str) or not route for route in routes)
            or not isinstance(stopId, str) or direction not in ("N", "S")
            or not isinstance(now, (float, int))):
        return False, {}
    parent = stopId
    if stopId.endswith(("N", "S")):
        if stopId[-1] != direction:
            return False, {}
        parent = stopId[:-1]
    if not parent:
        return False, {}
    snapshot = _alert_cache.get(_ALERT_URL)
    if not snapshot or not -60 <= now - snapshot[0] < _MAX_ALERT_AGE:
        return False, {route: None for route in routes}
    result = {}
    for route in routes:
        best = None
        candidates = (*snapshot[1].get((route, parent, direction), ()),
                      *snapshot[1].get(("*", parent, direction), ()))
        for priority, periods, headline, prevents in candidates:
            if periods and not any(start <= now < end for start, end in periods):
                continue
            if best is None or priority < best[0]:
                best = (priority, {"text": headline, "preventsBoarding": prevents})
        result[route] = best[1] if best else None
    return True, result


def notice_state_for(route, stopId, direction, now) -> tuple[bool, dict | None]:
    """Read (alerts ready, active notice) atomically for one route."""
    ready, notices = notice_states_for((route,), stopId, direction, now)
    return ready, notices.get(route)


def notice_for(route, stopId, direction, now) -> dict | None:
    """Return a matching notice, or None when none is known."""
    return notice_state_for(route, stopId, direction, now)[1]


def alert_for(route, stopId, direction, now) -> str | None:
    """Legacy text-only view of the active notice."""
    notice = notice_for(route, stopId, direction, now)
    return notice["text"] if notice else None
