"""Android bridge around the existing board server and feed/cache logic."""
from __future__ import annotations

import json
import hmac
import os
import secrets
import ssl
import threading
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPSHandler, ProxyHandler, Request, build_opener, install_opener, urlopen

_lock = threading.RLock()
_server = None
_server_thread = None
_catalog_thread = None
_token = None
_catalog_path = None
_files_dir = None
_local_opener = None


def _initialize(catalog_json, files_dir):
    global _server, _server_thread, _catalog_thread, _token, _catalog_path, _files_dir, _local_opener
    with _lock:
        if _server is not None:
            return
        import certifi
        ca_file = certifi.where()
        os.environ["SSL_CERT_FILE"] = ca_file
        os.environ["REQUESTS_CA_BUNDLE"] = ca_file
        install_opener(build_opener(HTTPSHandler(context=ssl.create_default_context(cafile=ca_file))))
        _local_opener = build_opener(ProxyHandler({}))

        _files_dir = files_dir
        _catalog_path = Path(files_dir) / "stations.json"

        import context_feeds
        import refresh_catalog
        import serve
        from refresh_catalog import validate_catalog

        catalog = json.loads(catalog_json)
        validate_catalog(catalog)
        if _catalog_path.exists():
            try:
                cached_catalog = json.loads(_catalog_path.read_text(encoding="utf-8"))
                validate_catalog(cached_catalog)
                catalog = cached_catalog
            except (OSError, ValueError, json.JSONDecodeError):
                refresh_catalog.atomic_write(catalog, _catalog_path)
        else:
            refresh_catalog.atomic_write(catalog, _catalog_path)

        serve.CATALOG = _catalog_path
        context_feeds.STATIONS_PATH = _catalog_path
        refresh_catalog.OUT = _catalog_path

        class AuthenticatedRequest(serve.BoardRequest):
            def do_GET(self):
                supplied = self.headers.get("X-Subway-Token", "")
                if not hmac.compare_digest(supplied, self.server.auth_token):
                    return self.reply(403, {"error": "Forbidden"})
                super().do_GET()

        _token = secrets.token_urlsafe(32)
        _server = serve.BoardServer(("127.0.0.1", 0), catalog, AuthenticatedRequest)
        _server.auth_token = _token
        _server_thread = threading.Thread(target=_server.serve_forever,
                                          name="local-board-http", daemon=True)
        _server_thread.start()
        _catalog_thread = threading.Thread(target=serve.refresh_periodically,
                                           args=(_server, threading.Event()),
                                           name="station-catalog-refresh", daemon=True)
        _catalog_thread.start()


def _request(path, params=None):
    address = "http://127.0.0.1:%d%s" % (_server.server_address[1], path)
    if params:
        address += "?" + urlencode(params)
    request = Request(address, headers={"X-Subway-Token": _token, "Accept": "application/json"})
    try:
        with _local_opener.open(request, timeout=12) as response:
            return response.read().decode("utf-8")
    except HTTPError as error:
        body = error.read(65536).decode("utf-8", "replace")
        if error.code == 400:
            return json.dumps({"__httpStatus": 400, "error": body})
        return json.dumps({"__httpStatus": error.code, "error": body})


def initialize(catalog_json, files_dir):
    _initialize(catalog_json, files_dir)


def stations_json():
    if _server is None:
        raise RuntimeError("Local board service is not initialized")
    return _request("/v1/stations")


def board_json(station, route, direction):
    if _server is None:
        raise RuntimeError("Local board service is not initialized")
    return _request("/v1/board", {"station": station, "route": route, "direction": direction})
