"""Request-local views of the MTA's eight full-dataset subway feeds."""
from __future__ import annotations

import re
import time
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from http.client import HTTPException
from threading import Event, Lock, Thread
from urllib.parse import quote
from urllib.request import Request, urlopen

from google.protobuf.message import DecodeError
from google.transit import gtfs_realtime_pb2 as gtfs

_BASE = "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/nyct%2Fgtfs"
_GROUPS = {
    "": ("1", "2", "3", "4", "5", "6", "6X", "7", "7X", "GS"),
    "-ace": ("A", "C", "E", "H"),
    "-bdfm": ("B", "D", "F", "FX", "M", "FS"),
    "-g": ("G",),
    "-jz": ("J", "Z"),
    "-nqrw": ("N", "Q", "R", "W"),
    "-l": ("L",),
    "-si": ("SI",),
}
_FEED_FOR = {route: _BASE + suffix for suffix, routes in _GROUPS.items() for route in routes}
_EXPRESS = {"6": "6X", "7": "7X", "F": "FX"}
_TTL = 20
_TIMEOUT = 4
_FETCH_BUDGET = 8
_MAX_BYTES = 4 * 1024 * 1024
_FRESH_AGE = 60
_LAST_USABLE_AGE = 120
_STALL_AGE = 90
_PATH = re.compile(r"(-?[0-9]{6,7}_[A-Z0-9]{1,2}\.{1,2}[NS])[A-Z0-9]*")
_CONFLICT_TTL = 48 * 3600
_MAX_CONFLICTS = 4096


def _read_feed(url, deadline=None):
    if deadline is None:
        deadline = time.monotonic() + _FETCH_BUDGET
    req = Request(url, headers={"User-Agent": "SubwayGuide/1.0"})
    payload = bytearray()
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError("Subway feed exceeded deadline")
    with urlopen(req, timeout=min(_TIMEOUT, remaining)) as response:
        while time.monotonic() < deadline:
            chunk = response.read1(min(65536, _MAX_BYTES + 1 - len(payload)))
            if not chunk:
                break
            payload.extend(chunk)
            if len(payload) > _MAX_BYTES:
                raise ValueError("Oversized subway feed")
    if time.monotonic() >= deadline:
        raise TimeoutError("Subway feed exceeded deadline")
    feed = gtfs.FeedMessage.FromString(payload)
    if (not feed.IsInitialized() or feed.header.gtfs_realtime_version not in ("1.0", "2.0")
            or not feed.header.timestamp
            or feed.header.timestamp > time.time()
            or feed.header.incrementality != gtfs.FeedHeader.FULL_DATASET):
        raise ValueError("Invalid subway snapshot")
    return feed


@dataclass
class _CachedFeed:
    lock: Lock = field(default_factory=Lock)
    feed: gtfs.FeedMessage | None = None
    refresh_after: float = float("-inf")
    failed: bool = False
    inflight: Event | None = None
    deadline: float = 0
    # Replaced atomically with its feed, never mutated by readers.
    trips: dict = field(default_factory=dict)
    conflicts: dict = field(default_factory=dict)
    silence_until: float = 0

    def _index_trips(self, feed):
        ids = {}
        counts = Counter()
        by_origin = defaultdict(list)
        for entity in feed.entity:
            if entity.is_deleted or not entity.HasField("trip_update"):
                continue
            trip = entity.trip_update.trip
            if trip.schedule_relationship == trip.CANCELED:
                continue
            rider_id = _rider_id(trip)
            key = _trip_key(trip)
            ids[key] = rider_id
            if trip.trip_id:
                by_origin[_path_base(trip.trip_id)].append(key)
            if rider_id:
                counts[rider_id] += 1

        # Once two trips claim an ID, the survivor cannot safely reclaim it
        # after its peer disappears or the feed temporarily loses both trips.
        stamp = feed.header.timestamp
        conflicts = {k: expiry for k, expiry in self.conflicts.items() if expiry > stamp}
        conflicts.update({k: stamp + _CONFLICT_TTL for k, n in counts.items() if n > 1})
        silence_until = self.silence_until
        if len(conflicts) > _MAX_CONFLICTS:
            conflicts.clear()
            silence_until = stamp + _CONFLICT_TTL
        moving, held = _train_conditions(feed, by_origin)
        trips = {}
        for key, rider_id in ids.items():
            if key not in held:
                chime_id = rider_id if stamp >= silence_until and rider_id not in conflicts else ""
                trips[key] = (chime_id, key in moving)
        return trips, conflicts, silence_until

    def _refresh(self, url, done, deadline):
        accepted = None
        try:
            snapshot = _read_feed(url, deadline)
            if self.feed and snapshot.header.timestamp < self.feed.header.timestamp:
                raise ValueError("Out-of-order subway snapshot")
            accepted = (snapshot, *self._index_trips(snapshot))
        except (OSError, HTTPException, ValueError, DecodeError):
            pass  # Keep the last accepted full snapshot on refresh failure.
        finally:
            with self.lock:
                now = time.monotonic()
                self.failed = accepted is None or now >= deadline
                if not self.failed:
                    self.feed, self.trips, self.conflicts, self.silence_until = accepted
                self.refresh_after = now + _TTL
                self.inflight = None
                done.set()

    def get(self, url):
        with self.lock:
            now = time.monotonic()
            if self.inflight is None and now >= self.refresh_after:
                self.deadline = now + _FETCH_BUDGET
                self.inflight = Event()
                try:
                    Thread(target=self._refresh, args=(url, self.inflight, self.deadline),
                           name="mta-feed", daemon=True).start()
                except RuntimeError:
                    self.inflight = None
                    self.failed = True
                    self.refresh_after = now + _TTL
            done, deadline = self.inflight, self.deadline
        # DNS and HTTP header reads can outlive a socket inactivity timeout.
        # Even then callers share one finite wait and at most one worker per feed.
        if done is not None:
            done.wait(max(0, deadline - time.monotonic()))
        with self.lock:
            return self.feed, self.failed or self.inflight is not None, self.trips


_CACHE = {url: _CachedFeed() for url in set(_FEED_FOR.values())}


def _route_id(route, parent):
    if route == "SIR":
        return "SI"
    # In some rider-facing catalogs all three unrelated shuttles are called S.
    if route == "S":
        if parent in ("901", "902"):
            return "GS"
        if parent in ("S01", "S03", "S04", "D26"):
            return "FS"
        if parent.startswith("H"):
            return "H"
    return route


def _trip_key(trip):
    return trip.route_id, trip.start_date, trip.start_time, trip.trip_id


def _path_base(raw):
    # NYCT GTFS-RT reference p. 5: preserve origin time, line and direction.
    # The optional path suffix may disappear while this train is in service.
    match = _PATH.fullmatch(raw)
    return match[1] if match else raw


def _rider_id(trip):
    if not trip.trip_id or not re.fullmatch(r"[0-9]{8}", trip.start_date):
        return ""
    return "/".join(quote(part, safe=":") for part in
                    (trip.route_id, trip.start_date, trip.start_time, _path_base(trip.trip_id)))


def _matching_updates(by_origin, evidence):
    route, date, start, raw = evidence
    base = _path_base(raw)
    candidates = [key for key in by_origin.get(base, ())
                  if (not route or route == key[0])
                  and (not date or not key[1] or date == key[1])
                  and (not start or not key[2] or start == key[2])]
    # A full path can distinguish simultaneous explicit variants. A bare ID
    # cannot. Also retain bare updates as possible subjects of a full selector.
    if raw != base and any(key[3] == raw for key in candidates):
        return [key for key in candidates if key[3] in (raw, base)]
    return candidates


def _train_conditions(feed, by_origin):
    latest = {}
    held = set()
    for entity in feed.entity:
        if entity.is_deleted:
            continue
        if entity.HasField("vehicle"):
            vehicle = entity.vehicle
            if vehicle.trip.trip_id and 0 < vehicle.timestamp <= feed.header.timestamp:
                key = _trip_key(vehicle.trip)
                matches = _matching_updates(by_origin, key)
                if len(matches) == 1:
                    source = (matches[0], key, vehicle.vehicle.id)
                    latest[source] = max(latest.get(source, 0), vehicle.timestamp)
                elif feed.header.timestamp - vehicle.timestamp > _STALL_AGE:
                    held.update(matches)
        elif entity.HasField("alert"):
            alert = entity.alert
            if not any(t.text.strip().lower() == "train delayed" for t in alert.header_text.translation):
                continue
            if alert.active_period and not any(
                p.start <= feed.header.timestamp and (not p.end or feed.header.timestamp < p.end)
                for p in alert.active_period
            ):
                continue
            for selector in alert.informed_entity:
                trip = selector.trip
                if selector.route_id and trip.route_id and selector.route_id != trip.route_id:
                    continue
                if trip.trip_id:
                    evidence = (trip.route_id or selector.route_id, trip.start_date, trip.start_time, trip.trip_id)
                    held.update(_matching_updates(by_origin, evidence))

    # Fresh evidence from one source cannot clear a different source's stall.
    # Only uniquely attributable movement can validate an overdue origin.
    moving = set()
    for (target, _, _), stamp in latest.items():
        if feed.header.timestamp - stamp > _STALL_AGE:
            held.add(target)
        else:
            moving.add(target)
    return moving, held


def _serves(stop):
    return stop.schedule_relationship in (stop.SCHEDULED, stop.UNSCHEDULED)


def _destination(last, board_time, route, stations, fallback):
    # NYCT specifies an arrival at a terminal. A truncated list may instead
    # end at a through stop with a departure. Require corroboration in that case.
    platform = last.stop_id
    if (not _serves(last) or platform[-1:] not in ("N", "S")
            or last.arrival.time < board_time):
        return fallback
    terminal = stations.get(platform[:-1])
    if not terminal or not terminal.get("name"):
        return fallback
    # At a shared station other lines may continue past this route's terminal.
    route_directions = terminal.get("routeDirections", {}).get(route)
    directions = terminal.get("directions", {}) if route_directions is None else route_directions
    if last.departure.time and not (directions and platform[-1] not in directions):
        return fallback
    return terminal["name"]


def _arrivals(feed, now, station, route, direction, stations, trips):
    platform = station["id"] + direction
    fallback = station["directions"].get(direction) or {"N": "Northbound", "S": "Southbound"}[direction]
    arrivals = []
    for entity in feed.entity:
        if entity.is_deleted or not entity.HasField("trip_update"):
            continue
        update = entity.trip_update
        trip = update.trip
        actual_route = _route_id(trip.route_id, station["id"])
        if actual_route != route and actual_route != _EXPRESS.get(route):
            continue
        if trip.schedule_relationship == trip.CANCELED:
            continue
        key = _trip_key(trip)
        if key not in trips:
            continue
        rider_id, moving = trips[key]
        stops = update.stop_time_update
        if len(stops) < 2:
            continue
        # No vehicle position is expected before origin departure. An explicitly
        # overdue departure-only origin still makes its downstream ETAs suspect.
        first = stops[0]
        if (not moving and _serves(first) and not first.arrival.time
                and 0 < first.departure.time < feed.header.timestamp - _STALL_AGE):
            continue
        for i, stop in enumerate(stops):
            if stop.stop_id != platform or not _serves(stop):
                continue
            eta = stop.arrival.time
            if eta < now:
                eta = stop.departure.time
            if eta < now:
                continue
            # A terminal can carry a departure field, and skipped stops can
            # trail the last served stop. Require an actual onward stop.
            onward = next((later.stop_id for later in stops[i + 1:]
                if _serves(later) and later.stop_id[-1:] in ("N", "S")
                and later.stop_id[:-1] != station["id"]
                and max(later.arrival.time, later.departure.time) >= eta
            ), None)
            if onward:
                arrivals.append({"id": rider_id, "time": eta, "route": actual_route, "onward": onward,
                                 "destination": _destination(stops[-1], eta, actual_route, stations, fallback)})
                break
    return sorted(arrivals, key=lambda row: (row["time"], row["id"]))


def get_arrivals(station: dict, route: str, direction: str, stations_by_id: dict) -> dict:
    """Return all upcoming boardable predictions, with MTA header time in Unix seconds."""
    route = _route_id(route, station["id"])
    url = _FEED_FOR.get(route)
    result = {"status": "unavailable", "feedTime": None, "arrivals": []}
    if url is None or direction not in ("N", "S"):
        return result
    feed, failed, trips = _CACHE[url].get(url)
    if feed is None:
        return result
    now = int(time.time())
    stamp = feed.header.timestamp
    result["feedTime"] = stamp
    age = now - stamp
    if age < 0 or age > _LAST_USABLE_AGE:
        return result
    result["status"] = "stale" if failed or age > _FRESH_AGE else "ok"
    result["arrivals"] = _arrivals(feed, now, station, route, direction, stations_by_id, trips)
    return result
