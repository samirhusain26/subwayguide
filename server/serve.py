#!/usr/bin/env python3
"""Local subway board API. Does not store rider preferences or send them upstream."""
import argparse
import json
import logging
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from context_feeds import notice_state_for, weather_for
from transit import get_arrivals

CATALOG = Path(__file__).with_name("stations.json")
REFRESH_INTERVAL = 7 * 86400


class BoardServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, catalog, request_handler=None):
        self.slots = threading.BoundedSemaphore(8)
        self.install_directory(catalog)
        super().__init__(address, request_handler or BoardRequest)


    def process_request(self, request, client_address):
        request.settimeout(8)
        if not self.slots.acquire(blocking=False):
            try:
                request.settimeout(0.25)
                request.sendall(b"HTTP/1.0 503 Busy\r\nRetry-After: 5\r\nContent-Length: 0\r\n\r\n")
            except OSError:
                pass
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()

    def install_directory(self, catalog):
        stations = {entry["id"]: entry for entry in catalog["stations"]}
        self.snapshot = (catalog, stations)


def refresh_periodically(server, stop):
    from refresh_catalog import refresh, download, fetch, GTFS_URL
    while not stop.is_set():
        try:
            age = time.time() - CATALOG.stat().st_mtime
            if age >= REFRESH_INTERVAL:
                catalog = refresh(fetch(), download(GTFS_URL), CATALOG)
                server.install_directory(catalog)
        except Exception:
            logging.exception("Station refresh failed; keeping the previous directory")
        stop.wait(6 * 3600)


class BoardRequest(BaseHTTPRequestHandler):
    server_version = "SubwayBoard/1"

    def log_message(self, fmt, *args):
        # Do not record riders' station selections or LAN addresses.
        pass

    def reply(self, status, data):
        body = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        try:
            self.dispatch()
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            logging.exception("Board request failed")
            self.reply(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": "Service could not complete the request"})

    def dispatch(self):
        path = urlsplit(self.path)
        directory, stations = self.server.snapshot
        if path.path == "/v1/stations":
            return self.reply(200, directory)
        if path.path != "/v1/board":
            return self.reply(404, {"error": "Unknown endpoint"})
        try:
            query = parse_qs(path.query, max_num_fields=3, strict_parsing=True)
            station_id, route, direction = (query[k][0] for k in ("station", "route", "direction"))
        except (KeyError, ValueError):
            return self.reply(400, {"error": "Specify station, route and direction"})
        station = stations.get(station_id)
        if not station or route not in station["routes"] or direction not in station["directions"]:
            return self.reply(400, {"error": "Unknown station, route or direction"})
        supported = station.get("routeDirections", {}).get(route)
        if supported is not None and direction not in supported:
            return self.reply(400, {"error": "Trains cannot be boarded in this direction"})
        self.reply(200, make_board(station, route, direction, stations))


def make_board(station, route, direction, stations):
    """Build the same schema used by the HTTP service, for embedded callers."""
    board = get_arrivals(station, route, direction, stations)
    now = int(time.time())
    # Optional lookups have isolated caches and never gate a train prediction.
    weather = weather_for(station["lat"], station["lon"])
    notices_ready, notice = notice_state_for(route, station["id"], direction, now)
    if notice and notice["preventsBoarding"]:
        board = dict(board, status="disrupted", arrivals=[])
    return dict(board, serverTime=now, weather=weather, noticesReady=notices_ready,
                alert=notice["text"] if notice else None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1", help="Use 0.0.0.0 to accept connections from home Wi-Fi")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--refresh-catalog", action="store_true",
                        help="Refresh the stored MTA station catalog weekly in the background")
    args = parser.parse_args()
    directory = json.loads(CATALOG.read_text(encoding="utf-8"))
    with BoardServer((args.host, args.port), directory) as app:
        print(f"Subway board running on port {args.port}; {len(app.snapshot[1])} stations.", flush=True)
        stop = threading.Event()
        if args.refresh_catalog:
            threading.Thread(target=refresh_periodically, args=(app, stop), daemon=True).start()
        try:
            app.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            stop.set()


if __name__ == "__main__":
    main()
