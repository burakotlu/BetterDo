import copy
import json
import sqlite3
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import Mock
from server.database import VideoDatabase
from server.lessons import LessonService
from server.config import Config
from server.media import VideoMedia
from server.providers.mock import MockVideoGenerationProvider
from server.service import VideoService


class LessonServiceTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.db = VideoDatabase(Path(self.directory.name) / "service.db")
        self.fixture = json.loads(Path("app/src/androidTest/assets/lessons.json").read_text(encoding="utf-8"))["lessons"][0]
        self.level = self.fixture["level"]
        self.generator = Mock(side_effect=lambda *args, **kwargs: copy.deepcopy(self.fixture))
        self.service = LessonService(self.db, "test-model", "http://localhost:11434", generator=self.generator)

    def create(self, identity="a" * 32):
        return self.service.create(identity, "en", "travel", self.level)

    def test_async_generation_populates_live_catalog(self):
        job = self.create()
        self.assertEqual("pending", job["status"])
        self.generator.assert_not_called()
        self.service.tick()
        done = self.service.get(job["id"])
        self.assertEqual("completed", done["status"])
        self.assertTrue(done["lesson"]["id"].startswith("en-generated-"))
        self.assertEqual("Travel", done["lesson"]["category"])
        self.assertIn("AI-generated", done["lesson"]["context"])
        self.assertEqual([done["lesson"]], self.service.catalog()["lessons"])
        self.assertEqual([], self.service.catalog("de")["lessons"])

    def test_duplicate_clicks_and_reconnect_reuse_job(self):
        with ThreadPoolExecutor(max_workers=8) as pool:
            jobs = list(pool.map(lambda _: self.create(), range(12)))
        self.assertEqual(1, len({job["id"] for job in jobs}))
        self.service.tick()
        self.assertEqual("completed", self.create()["status"])
        self.assertEqual(1, self.generator.call_count)

    def test_invalid_model_output_never_enters_catalog(self):
        self.generator.side_effect = lambda *args, **kwargs: {"word": "invalid"}
        self.create()
        self.service.tick()
        self.assertEqual("failed", self.service.get("a" * 32)["status"])
        self.assertEqual([], self.service.catalog()["lessons"])

    def test_model_outage_is_safe_and_preserves_existing_lessons(self):
        self.create()
        self.service.tick()
        self.create("b" * 32)
        self.generator.side_effect = RuntimeError("secret error body")
        self.service.tick()
        failed = self.service.get("b" * 32)
        self.assertNotIn("secret", failed["error"])
        self.assertEqual(1, len(self.service.catalog()["lessons"]))

    def test_limits_and_idempotency_conflicts(self):
        self.service.limit = 1
        self.create()
        with self.assertRaises(ValueError):
            self.create("b" * 32)
        with self.assertRaises(ValueError):
            self.service.create("a" * 32, "de", "travel", self.level)
        with self.assertRaises(ValueError):
            self.service.create("c" * 32, "en", "arbitrary prompt", self.level)

    def test_interrupted_jobs_fail_without_erasing_catalog(self):
        self.create()
        with self.db.connect() as db:
            db.execute("UPDATE lesson_jobs SET status='processing'")
        self.service.recover()
        self.assertEqual("failed", self.service.get("a" * 32)["status"])

    def test_duplicate_generated_words_are_not_published(self):
        self.create()
        self.service.tick()
        self.create("b" * 32)
        self.service.tick()
        self.assertEqual("failed", self.service.get("b" * 32)["status"])
        self.assertEqual(1, len(self.service.catalog()["lessons"]))

    def test_migration_preserves_existing_video_data(self):
        path = Path(self.directory.name) / "legacy.db"
        db = sqlite3.connect(str(path))
        db.executescript(Path("server/migrations/001_videos.sql").read_text())
        db.execute("INSERT INTO generation_requests VALUES ('keep',123)")
        db.commit()
        db.close()
        upgraded = VideoDatabase(path)
        with upgraded.connect() as db:
            self.assertEqual(2, db.execute("PRAGMA user_version").fetchone()[0])
            self.assertEqual("keep", db.execute("SELECT id FROM generation_requests").fetchone()[0])

    def test_disabled_generation_is_explicit(self):
        self.service.model = ""
        self.assertFalse(self.service.categories()["generationEnabled"])
        with self.assertRaisesRegex(ValueError, "AI_LESSON_MODEL"):
            self.create()

    def test_generated_lesson_can_request_video_without_static_catalog(self):
        self.create()
        self.service.tick()
        lesson = self.service.get("a" * 32)["lesson"]
        config = Config(data_dir=Path(self.directory.name), catalog=Path(self.directory.name) / "missing.json")
        videos = VideoService(config, self.db, MockVideoGenerationProvider(), VideoMedia(config.data_dir, "mock"))
        self.assertEqual("pending", videos.generate(lesson["id"])["status"])
