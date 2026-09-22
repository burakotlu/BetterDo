# BetterDo development guide

Build a native Android daily language-learning app with an English interface.
English is the primary course; German has equal support in the same architecture.
The name is provisional. The product motto is “Learn a word instead of scrolling.”
Do not add a web frontend or WebView shell. Use Kotlin and Jetpack Compose.

## Product rules
- Use English for code identifiers, filenames, comments, documentation, UI labels,
  meanings, translations, usage notes, quiz instructions, and agent prompts.
  Preserve German target-language words, examples, dialogues, and answer choices.
- Keep the daily lesson short: one everyday word, an English definition, three natural
  examples, a short dialogue, and two quiz questions.
- One word is the daily minimum, not a cap. Offer extra published lessons and keep
  each word's progress and review schedule independent.
- Preserve TalkBack labels, readable contrast, large touch targets, and font scaling.
- Never silently reset saved progress. Keep progress isolated by language.
- Do not expose model credentials in frontend files or commit secrets.
- Say explicitly when a feature requires a server, external model, or installed voice.
- Treat generated content as plain text and validate it before publication.

## Architecture
- `app/`: native Android application; Kotlin, Jetpack Compose, Android TextToSpeech.
- `content/lessons.json`: independently published HTTPS catalog; never bundle it in the app APK.
- Live lessons now come from the authenticated service: category/level selection,
  asynchronous Ollama jobs, and `GET /api/catalog`. The static JSON is legacy reviewed
  content, not the normal source for new app lessons. Preserve existing cached lessons.
- `CatalogRepository` + `CatalogDatabase`: validate downloads and cache snapshots in SQLite.
  Keep retired lessons for progress/review and exclude them from new lesson selection.
  Failed downloads must preserve the last valid cache and all user progress.
- `app/src/androidTest/assets/`: test fixtures only, not production lesson data.
- `scripts/generate_lesson.py`: optional local Ollama content agent; writes drafts.
- `scripts/publish_lesson.py`: accepts a reviewed draft into the remote catalog.
  Publishing content must not require rebuilding the APK.
- `tests/`: Python standard-library tests for content and agent validation.
- `server/`: optional private video API, SQLite job queue, and worker. Provider keys
  stay here. Mock mode must work without paid services. See `server/README.md`.
- `server/lessons.py`: on-demand lesson generation and dynamic catalog; validate
  model output before storage and label it as AI-generated, not human-reviewed.
- Video generation is explicit, deduplicated, and budgeted. Completed videos are
  reused; opening a lesson only reads status. Never reset video jobs to retry an
  ambiguous paid submission without reconciling its provider ID/idempotency key.

## Validation
Run `py -3 -m unittest discover -s tests -v` and
`py -3 scripts/validate_content.py` (use `python3` on Linux).
Run `./gradlew testDebugUnitTest lintDebug assembleDebug` (Windows: `gradlew.bat`).
Use Android Studio/emulator or a physical device to inspect both languages,
quiz feedback, review scheduling, process restart persistence, rotation, and speech.
Update the README when commands, data format, or user-visible behavior change.
Do not claim an APK build, device testing, model generation, or audible speech was
tested unless it was. Do not delegate to other agents unless explicitly requested.
