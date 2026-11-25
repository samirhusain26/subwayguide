import json
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import urlopen
from unittest.mock import patch

from serve import BoardServer


class BoardHTTPTest(unittest.TestCase):
    def setUp(self):
        self.station = {"id":"116","name":"125 St","area":"Manhattan","lat":40.816,"lon":-73.96,
                        "routes":["1"],"directions":{"S":"Downtown"},"routeDirections":{"1":["S"]}}
        self.board = BoardServer(("127.0.0.1",0), {"stations":[self.station]})
        self.thread = threading.Thread(target=self.board.serve_forever, kwargs={"poll_interval":0.01})
        self.thread.start()
        self.addCleanup(self.close)
        self.base = "http://127.0.0.1:%s" % self.board.server_port

    def close(self):
        self.board.shutdown()
        self.thread.join()
        self.board.server_close()

    def read(self, path):
        with urlopen(self.base + path, timeout=3) as response:
            return json.load(response)

    def test_catalog_without_external_services(self):
        self.assertEqual(self.read("/v1/stations")["stations"], [self.station])

    def test_bad_selections_never_call_upstream(self):
        with patch("serve.get_arrivals") as upstream:
            for query in ("", "?station=116&direction=N&route=1","?station=116&direction=S&route=2",
                          "?station=H10&direction=N&route=1","?station=116&direction=S&route=1&route=2"):
                with self.assertRaises(HTTPError) as error:
                    self.read("/v1/board"+query)
                self.assertEqual(error.exception.code, 400)
            upstream.assert_not_called()

    def test_healthy_arrivals_without_weather(self):
        with patch("serve.get_arrivals",return_value={"status":"ok","feedTime":1,"arrivals":[]}), \
                patch("serve.weather_for",return_value=None), patch("serve.notice_state_for",return_value=(True,None)):
            result=self.read("/v1/board?station=116&direction=S&route=1")
            self.assertEqual(result["status"],"ok")
            self.assertIsNone(result["weather"])
            self.assertGreater(result["serverTime"],0)

    def test_active_platform_skip_suppresses_recommendations(self):
        with patch("serve.get_arrivals",return_value={"status":"ok","feedTime":1,"arrivals":[{"id":"one"}]}), \
                patch("serve.weather_for",return_value=None), \
                patch("serve.notice_state_for",return_value=(True,{"text":"Downtown 1 skips 125 St","preventsBoarding":True})):
            result=self.read("/v1/board?station=116&direction=S&route=1")
            self.assertEqual((result["status"],result["arrivals"]),("disrupted",[]))
            self.assertIn("skips",result["alert"])

    def test_scheduled_refresh_uses_validated_publisher(self):
        import serve
        stop = threading.Event()
        before = self.board.snapshot
        with patch("serve.CATALOG") as path, patch("refresh_catalog.fetch",return_value=[]), \
                patch("refresh_catalog.download",return_value=b"header-only"), \
                patch("refresh_catalog.refresh",side_effect=ValueError("unusable update")) as publish, \
                patch.object(stop, "wait", side_effect=lambda _: stop.set()), \
                self.assertLogs(level="ERROR"):
            path.stat.return_value.st_mtime = 1
            serve.refresh_periodically(self.board,stop)
            publish.assert_called_once_with([],b"header-only",path)
            self.assertIs(self.board.snapshot,before)
