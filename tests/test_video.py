import concurrent.futures
import json
import tempfile
import threading
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from server.config import Config
from server.database import VideoDatabase
from server.http_api import create_server
from server.media import VideoMedia
from server.providers.base import ProviderError, VideoRequest, VideoResult
from server.providers.heygen import HeyGenVideoGenerationProvider
from server.providers.mock import MockVideoGenerationProvider
from server.service import VideoService
from server.teaching import generate_teaching_script


class VideoTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.now = 1000000.0
        self.config = Config(data_dir=Path(self.directory.name), token="a" * 40)
        self.database = VideoDatabase(self.config.data_dir / "videos.db")
        self.provider = MockVideoGenerationProvider(lambda: self.now)
        self.media = VideoMedia(self.config.data_dir, "mock")
        self.service = VideoService(self.config, self.database, self.provider, self.media, lambda: self.now)

    def complete(self):
        self.service.tick()
        self.now += 6
        self.service.tick()

    def test_creation_is_async_and_mock_completes(self):
        row = self.service.generate("en-sneeze")
        self.assertEqual("pending", row["status"])
        self.assertIsNone(row["provider_job_id"])
        self.service.tick()
        self.assertEqual("processing", self.database.get("en-sneeze")["status"])
        self.now += 6
        self.service.tick()
        row = self.database.get("en-sneeze")
        self.assertEqual("completed", row["status"])
        self.assertEqual(20, row["duration"])
        self.assertTrue((self.media.directory / (row["id"] + ".mp4")).is_file())

    def test_concurrent_requests_create_one_job(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            ids = list(pool.map(lambda _: self.service.generate("en-sneeze")["id"], range(20)))
        self.assertEqual(1, len(set(ids)))
        with self.database.connect() as db:
            self.assertEqual(1, db.execute("SELECT COUNT(*) FROM generation_requests").fetchone()[0])

    def test_completed_job_is_reused_even_on_retry(self):
        first = self.service.generate("en-sneeze")
        self.complete()
        row = self.service.generate_daily_word_video("en-sneeze")
        self.assertEqual(first["id"], row["id"])
        self.assertEqual("completed", self.service.generate("en-sneeze", retry=True)["status"])

    def test_failed_request_and_safe_error(self):
        with patch.object(self.provider, "generate_talking_video", side_effect=RuntimeError("secret-provider-key")):
            self.service.generate("en-sneeze")
            with self.assertLogs("server.service") as logs:
                self.service.tick()
        row = self.database.get("en-sneeze")
        self.assertEqual("failed", row["status"])
        self.assertNotIn("secret-provider-key", row["error_message"] + str(logs.output))

    def test_transient_submission_reuses_idempotency_key(self):
        row = self.service.generate("en-sneeze")
        with patch.object(self.provider, "generate_talking_video", side_effect=ProviderError("Rate limited", True, 60)) as call:
            self.service.tick()
            self.service.tick()
            self.assertEqual(1, call.call_count)
            self.now += 60
            self.service.tick()
            self.assertEqual(call.call_args_list[0][0][1], call.call_args_list[1][0][1])
        self.assertEqual(row["id"], self.database.get("en-sneeze")["id"])

    def test_restart_resumes_provider_job(self):
        self.service.generate("en-sneeze")
        self.service.tick()
        saved = self.database.get("en-sneeze")["provider_job_id"]
        restarted = VideoService(self.config, VideoDatabase(self.database.path), self.provider, self.media, lambda: self.now)
        self.now += 6
        with patch.object(self.provider, "generate_talking_video", side_effect=AssertionError("Duplicate submission")):
            restarted.tick()
        self.assertEqual(saved, self.database.get("en-sneeze")["provider_job_id"])
        self.assertEqual("completed", self.database.get("en-sneeze")["status"])

    def test_invalid_lesson_does_not_consume_budget(self):
        with self.assertRaises(ValueError):
            self.service.generate("en-not-published")
        with self.database.connect() as db:
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM generation_requests").fetchone()[0])

    def test_global_limit_and_language_isolation(self):
        self.service.config = replace(self.config, daily_limit=2)
        self.service.generate("en-sneeze")
        self.service.generate("de-niesen")
        self.assertNotEqual(self.database.get("en-sneeze")["id"], self.database.get("de-niesen")["id"])
        with self.assertRaises(ValueError):
            self.service.generate("en-errand")
        self.assertIsNotNone(self.service.generate("en-sneeze"))

    def test_confirmed_provider_failure_requires_explicit_retry(self):
        first = self.service.generate("en-sneeze")
        self.service.tick()
        self.now += 6
        with patch.object(self.provider, "check_status", return_value=VideoResult("failed")):
            self.service.tick()
        self.assertEqual("failed", self.service.generate("en-sneeze")["status"])
        self.now += 31
        retried = self.service.generate("en-sneeze", retry=True)
        self.assertNotEqual(first["id"], retried["id"])
        self.assertIsNone(retried["provider_job_id"])
        with self.database.connect() as db:
            self.assertEqual(2, db.execute("SELECT COUNT(*) FROM generation_requests").fetchone()[0])

    def test_ambiguous_submission_cannot_be_retried_after_key_window(self):
        self.service.generate("en-sneeze")
        self.now += 3601
        self.service.tick()
        self.now += 31
        with self.assertRaisesRegex(ValueError, "expired"):
            self.service.generate("en-sneeze", retry=True)

    def test_mock_provider_lifecycle(self):
        job = self.provider.generate_talking_video(None, "key")
        self.assertEqual("pending", self.provider.check_status(job).status)
        self.now += 3
        self.assertEqual("processing", self.provider.check_status(job).status)
        self.now += 3
        self.assertEqual("mock://sample", self.provider.check_status(job).video_url)

    def test_script_preserves_target_language_and_rejects_long_content(self):
        script = generate_teaching_script("niesen", "to sneeze", ["Ich muss niesen.", "Er niest."], "A1")
        self.assertIn("Ich muss niesen.", script)
        self.assertIn("Try saying it yourself", script)
        with self.assertRaises(ValueError):
            generate_teaching_script("word", "long " * 100, ["Example one.", "Example two."], "A1")

    def test_heygen_contract_uses_fixed_teacher_captions_and_idempotency(self):
        provider = HeyGenVideoGenerationProvider("never-print")
        request = VideoRequest("Teaching script", "teacher", "voice", "en", "sneeze")
        with patch.object(provider, "_request", return_value={"video_id": "v_123"}) as call:
            self.assertEqual("v_123", provider.generate_talking_video(request, "same-key"))
            payload = call.call_args[0][1]
            self.assertEqual("9:16", payload["aspect_ratio"])
            self.assertEqual("teacher", payload["avatar_id"])
            self.assertEqual("default", payload["caption"]["style"])
            self.assertEqual("same-key", call.call_args[0][2])
        with patch.object(provider, "_request", return_value={"status": "completed", "captioned_video_url": "https://example.com/video.mp4", "duration": 23}):
            self.assertEqual(23, provider.check_status("v_123").duration)

    def test_http_authorization_async_job_and_range_playback(self):
        http = create_server(("127.0.0.1", 0), self.service, self.config.token)
        thread = threading.Thread(target=http.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(http.server_close)
        self.addCleanup(http.shutdown)
        base = "http://127.0.0.1:{}".format(http.server_port)
        endpoint = base + "/api/lessons/en-sneeze/video"
        with self.assertRaises(HTTPError) as failure:
            urlopen(Request(endpoint, data=b"{}"))
        self.assertEqual(401, failure.exception.code)
        self.assertIsNone(self.database.get("en-sneeze"))
        headers = {"Authorization": "Bearer " + self.config.token, "Content-Type": "application/json"}
        with urlopen(Request(endpoint, data=b"{}", headers=headers)) as response:
            self.assertEqual(202, response.status)
            self.assertEqual("pending", json.load(response)["video"]["status"])
        self.complete()
        with urlopen(Request(endpoint, headers=headers)) as response:
            video = json.load(response)["video"]
        self.assertEqual("completed", video["status"])
        with self.assertRaises(HTTPError):
            urlopen(base + video["videoUrl"])
        headers["Range"] = "bytes=0-31"
        with urlopen(Request(base + video["videoUrl"], headers=headers)) as response:
            self.assertEqual(206, response.status)
            self.assertEqual(32, len(response.read()))


if __name__ == "__main__":
    unittest.main()
