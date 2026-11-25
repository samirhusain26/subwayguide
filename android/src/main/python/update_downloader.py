"""Download the signed Subway Board release APK without initializing the board service."""
from __future__ import annotations

import os
import ssl
import time
from pathlib import Path
from urllib.error import URLError
from urllib.request import HTTPSHandler, HTTPRedirectHandler, Request, build_opener

URL = "https://github.com/samirhusain26/subwayguide/releases/latest/download/SubwayBoard.apk"
MAX_BYTES = 200 * 1024 * 1024
TIMEOUT_SECONDS = 25
DEADLINE_SECONDS = 600


class _HttpsRedirectHandler(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not newurl.lower().startswith("https://"):
            raise OSError("Update server redirected to an insecure URL")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(apk_path: str) -> int:
    """Download to apk_path atomically; a failed attempt never replaces a prior APK."""
    import certifi

    destination = Path(apk_path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_name(destination.name + ".part")
    partial.unlink(missing_ok=True)
    context = ssl.create_default_context(cafile=certifi.where())
    opener = build_opener(HTTPSHandler(context=context), _HttpsRedirectHandler())
    headers = {"Accept": "application/vnd.android.package-archive", "User-Agent": "SubwayBoard-Android"}
    request = Request(URL, headers=headers)
    try:
        response = opener.open(request, timeout=TIMEOUT_SECONDS)
        with response:
            if response.geturl().lower().split(":", 1)[0] != "https":
                raise OSError("Update download did not use HTTPS")
            declared = response.headers.get("Content-Length")
            expected = int(declared) if declared and declared.isdigit() else None
            if expected is not None and expected > MAX_BYTES:
                raise OSError("Update APK exceeds the download size limit")
            total = 0
            deadline = time.monotonic() + DEADLINE_SECONDS
            with partial.open("wb") as output:
                while True:
                    if time.monotonic() > deadline:
                        raise TimeoutError("Update download exceeded its time limit")
                    chunk = response.read(128 * 1024)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > MAX_BYTES:
                        raise OSError("Update APK exceeds the download size limit")
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
            if expected is not None and total != expected:
                raise OSError("Update download ended before completion")
        if total == 0:
            raise OSError("Update download was empty")
        os.replace(partial, destination)
        return total
    except URLError as error:
        if isinstance(error.reason, ssl.SSLCertVerificationError):
            raise OSError("Could not verify the update server. Check the phone’s date and time, then try again.") from error
        raise OSError("Could not reach the update server. Check Wi-Fi and try again.") from error
    finally:
        partial.unlink(missing_ok=True)
