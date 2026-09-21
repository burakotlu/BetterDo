import json
import logging
import threading
import time
import uuid
from dataclasses import asdict
from scripts.validate_content import load_catalog
from .providers.base import VideoRequest, ProviderError
from .teaching import generate_teaching_script

logger = logging.getLogger(__name__)


class VideoService:
    def __init__(self, config, database, provider, media, clock=time.time):
        self.config, self.database, self.provider, self.media = config, database, provider, media
        self.clock = clock
        self.worker_lock = threading.Lock()

    def generate_daily_word_video(self, lesson_id):
        """Scheduler entry point. Same validation, deduplication and limits as HTTP."""
        return self.generate(lesson_id)

    def generate(self, lesson_id, retry=False):
        now = self.clock()
        with self.database.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            old = db.execute("SELECT * FROM lesson_videos WHERE lesson_id=?", (lesson_id,)).fetchone()
            if old and (old["status"] != "failed" or not retry):
                return dict(old)
            # Reuse ambiguous submissions; only confirmed render failures get a new paid job.
            if old:
                if old["provider"] != self.config.provider:
                    raise ValueError("Restore the job's provider configuration before retrying")
                if now - old["updated_at"] < 30:
                    raise ValueError("Wait 30 seconds before retrying")
                if not old["provider_job_id"] and now - old["created_at"] > 3600:
                    raise ValueError("Submission expired. Ask the server operator to reconcile this job before retrying")
                if old["failure_kind"] == "provider_failed":
                    self._reserve(db, now)
                    db.execute("UPDATE lesson_videos SET id=?, provider_job_id=NULL, created_at=? WHERE id=?",
                               (uuid.uuid4().hex, now, old["id"]))
                db.execute("UPDATE lesson_videos SET status='pending', attempts=0, polls=0, next_check=?, updated_at=?, error_message=NULL, failure_kind=NULL WHERE lesson_id=?",
                           (now, now, lesson_id))
            else:
                lessons = load_catalog(self.config.catalog)["lessons"]
                lesson = next((item for item in lessons if item["id"] == lesson_id), None)
                if lesson is None:
                    raise ValueError("This lesson is not in the server's published catalog")
                if len(lesson["word"]) > 48 or len(lesson["pronunciation"]) > 70:
                    raise ValueError("Word or pronunciation is too long for a vertical video")
                script = generate_teaching_script(lesson["word"], lesson["meaning"],
                                                  [item["text"] for item in lesson["examples"]], lesson["level"])
                self._reserve(db, now)
                request = VideoRequest(script, self.config.avatar, self.config.voice, lesson["language"], lesson["word"])
                identity = uuid.uuid4().hex
                db.execute("INSERT INTO lesson_videos (id,lesson_id,provider,status,request,script,pronunciation,next_check,created_at,updated_at) VALUES (?,?,?,'pending',?,?,?,?,?,?)",
                           (identity, lesson_id, self.config.provider, json.dumps(asdict(request)), script,
                            lesson["pronunciation"], now, now, now))
            return dict(db.execute("SELECT * FROM lesson_videos WHERE lesson_id=?", (lesson_id,)).fetchone())

    def _reserve(self, db, now):
        count = db.execute("SELECT COUNT(*) FROM generation_requests WHERE created_at>?", (now - 86400,)).fetchone()[0]
        if count >= self.config.daily_limit:
            raise ValueError("The server's rolling 24-hour video limit has been reached")
        db.execute("INSERT INTO generation_requests VALUES (?, ?)", (uuid.uuid4().hex, now))

    def _update(self, identity, **values):
        values["updated_at"] = self.clock()
        with self.database.connect() as db:
            db.execute("UPDATE lesson_videos SET " + ",".join(key + "=?" for key in values) + " WHERE id=?",
                       list(values.values()) + [identity])

    def tick(self):
        """One worker per database; HTTP handlers only insert/read jobs."""
        with self.worker_lock:
            with self.database.connect() as db:
                rows = db.execute("SELECT * FROM lesson_videos WHERE status IN ('pending','processing') AND next_check<=? ORDER BY created_at LIMIT 10",
                                  (self.clock(),)).fetchall()
            for row in rows:
                self._advance(dict(row))

    def _advance(self, row):
        identity = row["id"]
        try:
            if row["provider"] != self.config.provider:
                raise ProviderError("The server's selected provider differs from this saved job.")
            if self.clock() - row["created_at"] > 3600 and not row["provider_job_id"]:
                raise ProviderError("Submission expired. Operator reconciliation is required.")
            request = VideoRequest(**json.loads(row["request"]))
            if not row["provider_job_id"]:
                self._update(identity, status="processing")
                job_id = self.provider.generate_talking_video(request, identity)
                self._update(identity, provider_job_id=job_id, status="processing", attempts=0, next_check=self.clock() + 2)
                return
            if self.clock() - row["created_at"] > 3600 and self.clock() - row["updated_at"] > 900:
                raise ProviderError("Video generation timed out. Try checking this job again.")
            result = self.provider.check_status(row["provider_job_id"])
            if result.status == "completed":
                self._update(identity, source_url=result.video_url)
                url = self.media.finish(identity, result, request, row["pronunciation"])
                self._update(identity, status="completed", video_url=url, duration=result.duration, error_message=None)
            elif result.status == "failed":
                self._update(identity, status="failed", failure_kind="provider_failed",
                             error_message="Video generation failed. Trying again submits a new video and may use paid allowance.")
                logger.warning("Video job %s failed at the provider", identity)
            elif result.status in ("pending", "processing"):
                # A bounded number of polls also stops a provider that never finishes.
                if row["polls"] >= 180:
                    raise ProviderError("Video generation timed out. Try checking this job again.")
                self._update(identity, status="processing", attempts=0, polls=row["polls"] + 1, next_check=self.clock() + (2 if self.config.provider == "mock" else 20))
            else:
                raise ProviderError("Invalid video provider status.")
        except ProviderError as error:
            if error.transient and row["attempts"] < 5:
                self._update(identity, attempts=row["attempts"] + 1, next_check=self.clock() + max(error.retry_after, 2 ** row["attempts"]))
            else:
                self._update(identity, status="failed", error_message=str(error))
                logger.warning("Video job %s failed (%s)", identity, type(error).__name__)
        except Exception as error:
            self._update(identity, status="failed", error_message="Video processing failed. Try again or contact the server operator.")
            # Never log provider bodies, URLs, request headers, scripts, or credentials.
            logger.warning("Video job %s failed (%s)", identity, type(error).__name__)


def public_job(row):
    if row is None:
        return None
    return {"id": row["id"], "lessonId": row["lesson_id"], "provider": row["provider"],
            "status": row["status"], "videoUrl": row["video_url"], "script": row["script"],
            "pronunciation": row["pronunciation"], "aspectRatio": "9:16", "duration": row["duration"],
            "errorMessage": row["error_message"], "createdAt": row["created_at"], "updatedAt": row["updated_at"]}
