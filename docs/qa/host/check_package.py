"""Check the locally built APK with its build interpreter (CPython 3.11, -I -S)."""
import argparse
import hashlib
import importlib.metadata
import importlib.util
import io
import json
import marshal
import struct
import sys
import tempfile
import time
import zipfile
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import ProxyHandler, build_opener

ROOT = Path(__file__).resolve().parents[3]


def check(apk_path, work, live):
    with zipfile.ZipFile(apk_path) as apk:
        seed = apk.read("assets/stations.json")
        assert seed == (ROOT / "server/stations.json").read_bytes(), "outdated APK station directory"
        atlas = ROOT / "android/app/src/main/assets/nyc"
        tiles = {f"8-{x}-{y}.png" for x in range(16) for y in range(16)}
        tiles.update(f"64-{x}-{y}.png" for x in range(-4, 4) for y in range(-3, 5))
        assert {p.name for p in atlas.glob("*.png")} == tiles, "incomplete source atlas"
        manifest = json.loads((atlas / "manifest.json").read_bytes())
        assert set(manifest["files"]) == tiles | {"network.json"}, "incomplete atlas manifest"
        for name, digest in manifest["files"].items():
            assert hashlib.sha256(apk.read("assets/nyc/" + name)).hexdigest() == digest, name
        packaged = {key.removeprefix("assets/nyc/") for key in apk.namelist() if key.startswith("assets/nyc/")}
        assert packaged == {p.name for p in atlas.iterdir() if p.is_file()}, "APK atlas inventory differs"
        for file in sorted((ROOT / "android/app/src/main/assets").glob("*.json")):
            assert apk.read("assets/" + file.name) == file.read_bytes(), f"outdated configuration: {file.name}"
        directions = (ROOT / "server/trip_data/trip_direction_overrides.json").read_bytes()
        assert apk.read("assets/trip_direction_overrides.json") == directions, "outdated Android directions"
        network = json.loads(apk.read("assets/nyc/network.json"))
        corridor = ["M11", "M12", "M13", "M14", "M16", "M18"]
        for index, station in enumerate(corridor):
            mappings = json.loads(directions)[station]["directions"]["Manhattan"]
            for route in ("J", "M"):
                platform = station + mappings[route]
                onward = {stops[i + 1][:-1] for _, _, stops in network["routes"][route]
                          for i, stop in enumerate(stops[:-1]) if stop == platform}
                if index + 1 < len(corridor):
                    expected = corridor[index + 1]
                else:
                    expected = "D21" if route == "M" else "M19"
                assert expected in onward, f"invalid {route} boarding direction at {station}"
        for file in atlas.iterdir():
            assert apk.read("assets/nyc/" + file.name) == file.read_bytes(), f"outdated map asset: {file.name}"
        for abi, machine in (("armeabi-v7a", 40), ("arm64-v8a", 183)):
            header = apk.read(f"lib/{abi}/libpython3.11.so")[:20]
            assert header[:4] == b"\x7fELF" and struct.unpack_from("<H", header, 18)[0] == machine
        for name in ("app", "requirements-common"):
            with zipfile.ZipFile(io.BytesIO(apk.read(f"assets/chaquopy/{name}.imy"))) as inner:
                inner.extractall(work / "modules")

    modules = work / "modules"
    assert (modules / "trip_data/trip_direction_overrides.json").read_bytes() == directions, "outdated service directions"
    for name in ("context_feeds", "embedded_board", "refresh_catalog", "serve", "transit", "trip_data/__init__", "update_downloader"):
        shipped = (modules / f"{name}.pyc").read_bytes()
        assert shipped[:4] == importlib.util.MAGIC_NUMBER, "run with the Python 3.11 build interpreter"
        code = marshal.loads(shipped[16:])
        source_dir = "android/src/main/python" if name in ("embedded_board", "update_downloader") else "server"
        source = (ROOT / source_dir / f"{name}.py").read_bytes()
        assert compile(source, code.co_filename, "exec") == code, f"outdated APK module: {name}"

    sys.path.insert(0, str(modules))
    for name, version in {"certifi": "2026.11.20", "tzdata": "2026.6", "protobuf": "6.33.6",
                          "gtfs-realtime-bindings": "1.0.0"}.items():
        assert importlib.metadata.version(name) == version, f"unexpected packaged {name} version"
    import certifi
    import embedded_board
    import serve
    from google.transit import gtfs_realtime_pb2

    for module in (certifi, embedded_board, serve, gtfs_realtime_pb2):
        assert Path(module.__file__).is_relative_to(modules)
    embedded_board.initialize(seed.decode(), str(work / "app-files"))
    try:
        stations = json.loads(embedded_board.stations_json())["stations"]
        assert stations == json.loads(seed)["stations"]
        assert json.loads(embedded_board.board_json("116", "Q", "N"))["__httpStatus"] == 400
        public_url = f"http://127.0.0.1:{embedded_board._server.server_port}/v1/stations"
        try:
            build_opener(ProxyHandler({})).open(public_url, timeout=2).close()
        except HTTPError as error:
            assert error.code == 403
            error.close()
        else:
            raise AssertionError("private service accepted an unauthenticated request")

        with patch.object(serve, "get_arrivals", return_value={
            "status": "ok", "feedTime": 1, "arrivals": [{"id": "sample", "time": 600}]
        }), patch.object(serve, "notice_states_for", return_value=(True, {"1": None})), patch.object(
            serve, "weather_for", return_value=None
        ):
            sample = json.loads(embedded_board.board_json("116", "1", "S"))
            assert sample["arrivals"][0]["id"] == "sample" and sample["noticesReady"]
        print(f"Packaged modules, ARM runtimes, {len(stations)} stations and private board API passed.")

        if live:
            for _ in range(12):
                board = json.loads(embedded_board.board_json("116", "1", "S"))
                if board["noticesReady"] and board.get("weather"):
                    break
                time.sleep(1)
            assert board["feedTime"] and board["status"] in ("ok", "stale", "disrupted")
            print("Live downtown 1:", board["status"], "notices ready:", board["noticesReady"],
                  "weather:", (board.get("weather") or {}).get("kind"), "arrivals:", len(board["arrivals"]))
    finally:
        embedded_board._server.shutdown()
        embedded_board._server.server_close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", help="also check live MTA and NWS data")
    parser.add_argument("apk", type=Path, nargs="?", default=ROOT / "dist/SubwayBoard.apk")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as folder:
        check(args.apk, Path(folder), args.live)
