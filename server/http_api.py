import hmac
import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse
from .service import public_job


def create_server(address, service, token):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(15)

        def log_message(self, format, *args):
            pass  # No tokens, media URLs, or arbitrary user input in access logs.

        def respond(self, code, data):
            body = json.dumps(data).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def authorized(self):
            supplied = self.headers.get("Authorization", "").encode("utf-8")
            if not hmac.compare_digest(supplied, ("Bearer " + token).encode("utf-8")):
                self.respond(401, {"error": "Video service access token is missing or invalid"})
                return False
            return True

        def do_GET(self):
            if not self.authorized():
                return
            path = urlparse(self.path).path
            match = re.fullmatch(r"/api/lessons/((?:en|de)-[a-z0-9-]+)/video", path)
            if match:
                self.respond(200, {"video": public_job(service.database.get(match[1]))})
                return
            media = re.fullmatch(r"/media/([a-f0-9]{32})\.mp4", path)
            if media:
                self.serve_video(media[1])
                return
            self.respond(404, {"error": "Not found"})

        def do_POST(self):
            if not self.authorized():
                return
            match = re.fullmatch(r"/api/lessons/((?:en|de)-[a-z0-9-]+)/video", urlparse(self.path).path)
            if not match:
                self.respond(404, {"error": "Not found"})
                return
            try:
                if self.headers.get("Transfer-Encoding"):
                    raise ValueError("Chunked requests are not supported")
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= 1024:
                    raise ValueError("Expected a JSON body of at most 1024 bytes")
                body = json.loads(self.rfile.read(size))
                if not isinstance(body, dict) or set(body) - {"retry"} or type(body.get("retry", False)) is not bool:
                    raise ValueError("Expected {\"retry\": true} or {}")
                row = service.generate(match[1], retry=body.get("retry", False))
                self.respond(200 if row["status"] in ("completed", "failed") else 202, {"video": public_job(row)})
            except (ValueError, UnicodeError) as error:
                self.respond(400, {"error": str(error) if not isinstance(error, json.JSONDecodeError) else "Invalid JSON"})
            except Exception:
                self.respond(503, {"error": "Video service is temporarily unavailable"})

        def serve_video(self, identity):
            path = service.media.directory / (identity + ".mp4")
            if not path.is_file():
                self.respond(404, {"error": "Video file not found"})
                return
            length = path.stat().st_size
            start, end = 0, length - 1
            byte_range = self.headers.get("Range")
            if byte_range:
                match = re.fullmatch(r"bytes=(\d*)-(\d*)", byte_range)
                if not match or not any(match.groups()):
                    self.respond(416, {"error": "Invalid byte range"})
                    return
                if match[1]:
                    start = int(match[1])
                    end = min(int(match[2]), end) if match[2] else end
                else:
                    start = max(0, length - int(match[2]))
                if start > end or start >= length:
                    self.respond(416, {"error": "Invalid byte range"})
                    return
            self.send_response(206 if byte_range else 200)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Cache-Control", "private, max-age=86400")
            self.send_header("Content-Length", str(end - start + 1))
            if byte_range:
                self.send_header("Content-Range", "bytes {}-{}/{}".format(start, end, length))
            self.end_headers()
            with path.open("rb") as video:
                video.seek(start)
                remaining = end - start + 1
                while remaining:
                    chunk = video.read(min(65536, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)

    return ThreadingHTTPServer(address, Handler)
