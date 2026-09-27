import hmac
import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse
from .service import public_job


def create_server(address, service, token, lessons=None, public_video_playback=False, accounts=None):
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

        def bearer(self):
            value = self.headers.get("Authorization", "")
            return value[7:] if value.startswith("Bearer ") else ""

        def lesson_identity(self):
            if hmac.compare_digest(self.bearer(), token):
                return "admin", None
            owner = accounts.authenticate(self.bearer()) if accounts else None
            if owner:
                return "account", owner
            self.respond(401, {"error": "Sign in to access lessons"})
            return None, None

        def json_body(self):
            if self.headers.get("Transfer-Encoding"):
                raise ValueError("Chunked requests are not supported")
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 1024:
                raise ValueError("Expected a JSON body of at most 1024 bytes")
            body = json.loads(self.rfile.read(size))
            if not isinstance(body, dict):
                raise ValueError("Expected a JSON object")
            return body

        def account_request(self, method, path):
            if not accounts:
                self.respond(503, {"error": "Accounts are unavailable"})
                return
            try:
                if path == "/api/account/session" and method == "GET":
                    result = accounts.session(self.bearer())
                elif path == "/api/account/signout" and method == "POST":
                    accounts.signout(self.bearer())
                    result = {"ok": True}
                else:
                    body = self.json_body()
                    if path == "/api/account/signup" and method == "POST":
                        accounts.signup(body.get("email"), body.get("password"), body.get("displayName"))
                        result = {"ok": True}
                    elif path == "/api/account/verify" and method == "POST":
                        result = accounts.verify(body.get("email"), body.get("code"))
                    elif path == "/api/account/signin" and method == "POST":
                        result = accounts.signin(body.get("email"), body.get("password"))
                    elif path == "/api/account/profile" and method == "PUT":
                        result = accounts.update_name(self.bearer(), body.get("displayName"))
                    else:
                        self.respond(404, {"error": "Not found"})
                        return
                self.respond(200, result)
            except (ValueError, UnicodeError) as error:
                self.respond(400, {"error": str(error) if not isinstance(error, json.JSONDecodeError) else "Invalid JSON"})
            except Exception:
                self.respond(503, {"error": "Account service is temporarily unavailable"})

        def do_GET(self):
            path = urlparse(self.path).path
            if path == "/api/account/session":
                self.account_request("GET", path)
                return
            if public_video_playback:
                public_lesson = re.fullmatch(r"/api/public/lessons/((?:en|de)-[a-z0-9-]+)/video", path)
                if public_lesson:
                    row = service.database.get(public_lesson[1])
                    video = public_job(row) if row and row["status"] == "completed" else None
                    if video:
                        video["videoUrl"] = "/api/public" + video["videoUrl"]
                    self.respond(200, {"video": video})
                    return
                public_media = re.fullmatch(r"/api/public/media/([a-f0-9]{32})\.mp4", path)
                if public_media:
                    with service.database.connect() as db:
                        row = db.execute("SELECT 1 FROM lesson_videos WHERE id=? AND status='completed'", (public_media[1],)).fetchone()
                    if not row:
                        self.respond(404, {"error": "Video file not found"})
                        return
                    self.serve_video(public_media[1])
                    return
            lesson_path = path in ("/api/categories", "/api/catalog") or bool(re.fullmatch(r"/api/lesson-jobs/[a-f0-9]{32}", path))
            role, owner = self.lesson_identity() if lesson_path else ("admin", None) if self.authorized() else (None, None)
            if role is None:
                return
            if lessons and path == "/api/categories":
                self.respond(200, lessons.categories())
                return
            if lessons and path == "/api/catalog":
                self.respond(200, lessons.catalog())
                return
            job = re.fullmatch(r"/api/lesson-jobs/([a-f0-9]{32})", path)
            if lessons and job:
                self.respond(200, {"job": lessons.get(job[1], owner) if role == "account" else lessons.get(job[1])})
                return
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
            path = urlparse(self.path).path
            if path in ("/api/account/signup", "/api/account/verify", "/api/account/signin", "/api/account/signout"):
                self.account_request("POST", path)
                return
            role, owner = self.lesson_identity() if path == "/api/lesson-jobs" else ("admin", None) if self.authorized() else (None, None)
            if role is None:
                return
            match = re.fullmatch(r"/api/lessons/((?:en|de)-[a-z0-9-]+)/video", path)
            if not match and not (lessons and path == "/api/lesson-jobs"):
                self.respond(404, {"error": "Not found"})
                return
            try:
                body = self.json_body()
                if lessons and path == "/api/lesson-jobs":
                    if not isinstance(body, dict) or set(body) != {"id", "language", "category", "level"} or not all(isinstance(value, str) for value in body.values()):
                        raise ValueError("Expected id, language, category, and level")
                    job = lessons.create(body["id"], body["language"], body["category"], body["level"], owner_id=owner)
                    self.respond(202 if job["status"] in ("pending", "processing") else 200, {"job": job})
                    return
                if not isinstance(body, dict) or set(body) - {"retry"} or type(body.get("retry", False)) is not bool:
                    raise ValueError("Expected {\"retry\": true} or {}")
                row = service.generate(match[1], retry=body.get("retry", False))
                self.respond(200 if row["status"] in ("completed", "failed") else 202, {"video": public_job(row)})
            except (ValueError, UnicodeError) as error:
                self.respond(400, {"error": str(error) if not isinstance(error, json.JSONDecodeError) else "Invalid JSON"})
            except Exception:
                self.respond(503, {"error": "Video service is temporarily unavailable"})

        def do_PUT(self):
            path = urlparse(self.path).path
            if path == "/api/account/profile":
                self.account_request("PUT", path)
            else:
                self.respond(404, {"error": "Not found"})

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
