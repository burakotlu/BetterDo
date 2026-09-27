import json
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from server.accounts import AccountService
from server.config import Config
from server.database import VideoDatabase
from server.http_api import create_server
from server.lessons import LessonService
from server.media import VideoMedia
from server.providers.mock import MockVideoGenerationProvider
from server.service import VideoService


class Mailer:
    def __init__(self):
        self.messages = []

    def send_code(self, address, code):
        self.messages.append((address, code))


class AccountTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.db = VideoDatabase(Path(self.directory.name) / "data.db")
        self.mailer = Mailer()
        self.accounts = AccountService(self.db, "s" * 40, self.mailer)

    def account(self, email="a@example.com"):
        self.accounts.signup(email, "password123", "Learner")
        return self.accounts.verify(email, self.mailer.messages[-1][1])

    def test_email_verification_sessions_and_signout(self):
        self.accounts.signup("A@Example.com", "password123", "Learner")
        self.assertEqual("a@example.com", self.mailer.messages[0][0])
        with self.assertRaisesRegex(ValueError, "Confirm your email"):
            self.accounts.signin("a@example.com", "password123")
        for _ in range(2):
            with self.assertRaisesRegex(ValueError, "Invalid or expired"):
                self.accounts.verify("a@example.com", "00000000" if self.mailer.messages[0][1] != "00000000" else "11111111")
        with self.db.connect() as db:
            self.assertEqual(2, db.execute("SELECT code_attempts FROM accounts").fetchone()[0])
        session = self.accounts.verify("a@example.com", self.mailer.messages[0][1])
        token = session["access_token"]
        self.assertEqual(session["user"]["id"], self.accounts.authenticate(token))
        self.assertEqual("New name", self.accounts.update_name(token, "New name")["display_name"])
        self.assertEqual("New name", self.accounts.session(token)["user"]["display_name"])
        self.accounts.signout(token)
        self.assertIsNone(self.accounts.authenticate(token))
        self.assertTrue(self.accounts.signin("a@example.com", "password123")["user"]["verified"])

    def test_rejected_signins_are_rate_limited(self):
        self.account()
        for _ in range(8):
            with self.assertRaisesRegex(ValueError, "Invalid email or password"):
                self.accounts.signin("a@example.com", "wrongpassword")
        with self.assertRaisesRegex(ValueError, "Too many attempts"):
            self.accounts.signin("a@example.com", "password123")

    def test_account_session_authorizes_own_lessons_but_not_video_generation(self):
        first = self.account()
        second = self.account("b@example.com")
        config = Config(data_dir=Path(self.directory.name), token="t" * 40)
        video = VideoService(config, self.db, MockVideoGenerationProvider(), VideoMedia(config.data_dir, "mock"))
        lessons = LessonService(self.db, "test-model", "http://localhost:11434")
        http = create_server(("127.0.0.1", 0), video, config.token, lessons, accounts=self.accounts)
        thread = threading.Thread(target=http.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join, 2)
        self.addCleanup(http.server_close)
        self.addCleanup(http.shutdown)
        base = "http://127.0.0.1:{}".format(http.server_port)

        def call(path, token=None, body=None, method=None):
            headers = {"Authorization": "Bearer " + token} if token else {}
            data = json.dumps(body).encode() if body is not None else None
            with urlopen(Request(base + path, data=data, headers=headers, method=method)) as response:
                return json.load(response)

        call("/api/account/signup", body={"email": "c@example.com", "password": "password123", "displayName": "Third"})
        third = call("/api/account/verify", body={"email": "c@example.com", "code": self.mailer.messages[-1][1]})
        self.assertEqual("Third", call("/api/account/session", third["access_token"])["user"]["display_name"])
        self.assertEqual("Updated", call("/api/account/profile", third["access_token"], {"displayName": "Updated"}, "PUT")["display_name"])
        self.assertEqual("Updated", call("/api/account/signin", body={"email": "c@example.com", "password": "password123"})["user"]["display_name"])
        call("/api/account/signout", third["access_token"], method="POST")
        with self.assertRaises(HTTPError):
            call("/api/account/session", third["access_token"])

        with self.assertRaises(HTTPError) as unauthorized:
            call("/api/categories")
        self.assertEqual(401, unauthorized.exception.code)
        self.assertTrue(call("/api/categories", first["access_token"])["generationEnabled"])
        identity = "f" * 32
        job = {"id": identity, "language": "en", "category": "travel", "level": "A1"}
        self.assertEqual("pending", call("/api/lesson-jobs", first["access_token"], job)["job"]["status"])
        self.assertIsNone(call("/api/lesson-jobs/" + identity, second["access_token"])["job"])
        with self.assertRaises(HTTPError) as conflict:
            call("/api/lesson-jobs", second["access_token"], job)
        self.assertEqual(400, conflict.exception.code)
        with self.assertRaises(HTTPError) as video_error:
            call("/api/lessons/en-sneeze/video", first["access_token"], {})
        self.assertEqual(401, video_error.exception.code)


if __name__ == "__main__":
    unittest.main()
