# Lesson video service

## Live AI lesson catalog

The service also supports category-based lesson creation, independent of the video
provider. Set `AI_LESSON_MODEL` to an installed Ollama model, `OLLAMA_BASE_URL` to
its trusted server URL (default `http://127.0.0.1:11434`), and optionally
`LESSON_DAILY_LIMIT` (default 30 new lesson requests per rolling 24 hours).
In Docker, localhost is the container: use your Ollama service name or
`http://host.docker.internal:11434` on Docker Desktop. Keep Ollama private; only the
authenticated BetterDo backend should expose generation to clients.

- `GET /api/categories`: supported topics, levels, and generation availability.
- `POST /api/lesson-jobs`: `{ "id": "<32 lowercase hex characters>", "language":
  "en", "category": "travel", "level": "A1" }`; returns immediately with a job.
- `GET /api/lesson-jobs/{id}`: pending/processing/completed/failed and the final lesson.
- `GET /api/catalog`: up to 500 newest generated lessons, in the existing catalog format.

All endpoints use the same service token. The worker reuses the existing Ollama
generator and its bounded repair loop. Jobs are idempotent by request ID; five jobs
may be active at once. A separate worker keeps slow model generation from blocking
video polling. Interrupted model calls become explicitly failed on restart; queued
jobs resume. Model errors never overwrite existing lessons or video jobs.

Migration `002_lessons.sql` adds `generated_lessons` and `lesson_jobs`, preserving
the version-1 video tables. Generated lessons have server-assigned IDs and a unique
language/word key. Video generation resolves these stored lessons directly. The
manually reviewed JSON catalog remains only a legacy video lookup source; it is not
needed to generate or serve new lessons. Shared private-service users see the same
generated catalog. Add account-specific access rules before a public multi-user launch.

AI lessons are marked as generated and validated for schema, language, level, and
duplicate words. They are not human-reviewed and can contain linguistic mistakes.
No model is installed automatically, and tests use controlled model responses.

This optional Python standard-library service complements the native Android app.
There was no existing hosted API, authentication system, or server database to
extend. The catalog and progress architecture remain unchanged. No OpenAI
integration is introduced: scripts use reviewed catalog definitions and examples.

## Local mock development

From the repository root, with Python 3.7+ (3.12 recommended):

```powershell
Copy-Item .env.example .env
py -3 -c "import secrets; print(secrets.token_urlsafe(32))"
# Put that generated value in VIDEO_API_TOKEN in .env.
py -3 -m server --env-file .env
```

If using the older Anaconda installation on Windows and sqlite3 reports a missing
DLL, activate its environment first. Do not install a third-party sqlite package.
On Linux/macOS use `python3` instead of `py -3`.

Run the Android debug app, open a lesson, scroll to **AI Video**, and open
**Service settings**. Use `http://10.0.2.2:8080` on the Android emulator, or
`http://127.0.0.1:8080` with `adb reverse tcp:8080 tcp:8080` on a USB device.
Enter your `VIDEO_API_TOKEN` as the service access token. The app remembers the
server URL, but holds the token only in memory; enter it again after process death.
Never enter the HeyGen API key into the app.

Press **Generate AI Video**. The job moves through pending and processing to
completed in roughly 5–10 seconds. Play the 20-second portrait sample. It is a
silent mock preview, not an AI teacher or a lesson-specific recording. It works
without Ollama, FFmpeg, a provider account, or external video hosting. Opening a
lesson never submits a paid generation. Leaving the screen stops client polling;
the server worker continues and the app retrieves the job when you return.

## Configuration

| Variable | Use |
| --- | --- |
| `AI_VIDEO_PROVIDER` | `mock` by default; `heygen` for real renders |
| `AI_VIDEO_API_KEY` | Provider secret; required only for HeyGen |
| `AI_TEACHER_AVATAR_ID` | One fixed HeyGen avatar **look** ID |
| `AI_TEACHER_VOICE_ID` | One fixed voice ID; select a voice supporting English and German |
| `VIDEO_API_TOKEN` | Required random private-service credential, at least 32 ASCII characters |
| `VIDEO_DAILY_LIMIT` | Global new-job limit over a rolling 24 hours; default 10 |
| `VIDEO_DATA_DIR` | Persistent SQLite database and finished MP4s; default `server-data` |
| `VIDEO_CATALOG_PATH` | Reviewed server catalog; default `content/lessons.json` |

`.env` is ignored by Git. `--env-file` loads literal values; pre-existing process
environment variables take precedence. No provider keys or IDs enter the APK.

## Enable HeyGen

1. Provision a HeyGen API account/key with video creation/read access. Choose a
   permitted avatar look and compatible multilingual voice in that account. Keep
   both IDs fixed to retain the same teacher across lessons.
2. Set the four `AI_*` values in your server environment. There are no default
   paid credentials and switching provider never happens automatically.
3. Install FFmpeg with `libx264` and `drawtext` plus a system font, or use the
   Docker image below. The service checks for FFmpeg before accepting real jobs.
4. Restart the service and generate a lesson. Verify its voice, German
   pronunciation, lip sync, captions, and actual duration before publishing it.
   No paid generation was needed for the automated tests.

The adapter uses [HeyGen create video](https://developers.heygen.com/reference/create-video)
and [get video](https://developers.heygen.com/reference/get-video), including a
stable idempotency key, 9:16 output, fixed avatar/voice, and burned-in captions.
FFmpeg archives the captioned MP4 and adds a persistent word/IPA heading, so the
finished file includes the teaching text when used outside the app. Provider CDN
URLs and duration are stored internally; clients play the archived authenticated
file instead of depending on an expiring provider URL.

The script includes the word, English definition, first two reviewed target-language
examples, and a repetition prompt. Scripts exceeding 70 words/900 characters are
rejected before submission instead of cutting sentences. The target is about
15–30 seconds; actual voice speed determines duration. IPA is displayed, not read
as literal speech. German target words/examples are preserved; explanations remain
English. Extra translation overlays are not enabled in this implementation.

## Architecture and API

`teaching.py` → `VideoService` → SQLite queue → background worker →
`VideoGenerationProvider` → `VideoMedia` → authenticated Android player.

- `POST /api/lessons/{lessonId}/video` with `{}` creates/reuses a job and returns
  immediately (202 while active). The client cannot supply scripts, avatars,
  provider URLs, or arbitrary words: lessons come from the server catalog.
- `GET /api/lessons/{lessonId}/video` returns `{ "video": null }` or the saved job.
- `POST` with `{ "retry": true }` explicitly retries a failed job after a
  30-second cooldown. Network uncertainty reuses the saved job/key. A provider
  reporting a terminal failed render allows a new, budgeted submission.
- `GET /media/{id}.mp4` supports byte ranges for seeking. All routes require
  `Authorization: Bearer <VIDEO_API_TOKEN>`; no token is placed in a URL.

`server/migrations/001_videos.sql` creates `lesson_videos` and a separate
`generation_requests` budget ledger, with SQLite `user_version=1`. A unique
lesson ID and immediate transactions prevent races across simultaneous clicks.
App `catalog.db` and `progress-v1.json` are untouched. Completed jobs are always
reused, even after catalog/provider configuration changes; automatic regeneration
is intentionally absent. Retired lessons with saved videos remain readable.

Requests use timeouts; transient provider failures/429 responses get bounded
backoff with the same submission key. Polling ends after 180 polls. Submissions
without a provider ID expire after one hour, inside HeyGen's documented
idempotency window. An operator must reconcile these ambiguous jobs in the provider
dashboard before any manual reset. Never delete jobs to casually retry: it loses
deduplication evidence. Restart resumes persisted jobs; a process lock prevents
multiple workers for the same data directory. Changing provider with active jobs
requires restoring that provider to finish them. Logs omit secrets/provider bodies.

To add another provider, implement `VideoGenerationProvider` in `providers/`,
including idempotent submission and normalized statuses, and register it in
`providers/__init__.py` and the configuration allowlist. No Android changes are
needed. A future scheduler can call `service.generate_daily_word_video(lesson_id)`;
it uses the same validation, deduplication, and budget. No cron is installed.

## Deployment

GitHub Actions builds the Android app and tests the service; GitHub raw content
hosting **does not run this backend**. Deploy it separately on a machine with
persistent storage. Keep its catalog current with reviewed published content.

```sh
docker build -f server/Dockerfile -t betterdo-video .
docker run --env-file .env -e VIDEO_DATA_DIR=/data \
  -p 127.0.0.1:8080:8080 -v betterdo-video:/data betterdo-video
```

Use one service instance per data volume. Back up both `videos.db` (using SQLite's
backup API) and `media/`; disk usage grows with generated videos. Put an HTTPS
reverse proxy with request/connection limits in front of the service for remote
access; the standard-library HTTP listener is intended for local or proxied
private use. Release APKs require HTTPS. Configure egress to the provider/CDN.
The server validates public HTTPS downloads and bounds file sizes, but network
egress controls should also exclude internal destinations.

This is private, shared-catalog authorization, not a multi-user account system.
Everyone with the service token can request videos within the global budget.
Do not embed that token in a public APK. A public consumer deployment needs user
authentication and per-account quotas before distributing generation access.
No deployment, paid render, or social-media publication happens automatically.

## Verification

```powershell
py -3 -m unittest discover -s tests -v
py -3 scripts/validate_content.py
py -3 -m compileall -q server scripts tests
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

Tests cover job creation, concurrent deduplication, validation, budget limits,
restart recovery, provider failure/retries, the mock lifecycle, API authentication,
range downloads, and Android UI status transitions. Real voice quality and lip
sync require manually testing a configured paid provider.
