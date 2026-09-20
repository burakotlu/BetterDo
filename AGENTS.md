# BetterDo development guide

Build a native Android daily language-learning app for a Turkish speaker.
English is the primary course; German has equal support in the same architecture.
The name is provisional. The product motto is “Kaydırmak yerine, bir kelime öğren.”
Do not add a web frontend or WebView shell. Use Kotlin and Jetpack Compose.

## Product rules
- Keep the daily lesson short: one everyday word, Turkish meaning, three natural
  examples, a short dialogue, and two quiz questions.
- Preserve TalkBack labels, readable contrast, large touch targets, and font scaling.
- Never silently reset saved progress. Keep progress isolated by language.
- Do not expose model credentials in frontend files or commit secrets.
- Say explicitly when a feature requires a server, external model, or installed voice.
- Treat generated content as plain text and validate it before publication.

## Architecture
- `app/`: native Android application; Kotlin, Jetpack Compose, Android TextToSpeech.
- `app/src/main/assets/lessons.json`: seed lessons, validated by `scripts/validate_content.py`.
- `scripts/generate_lesson.py`: optional local Ollama content agent; writes drafts.
- `scripts/publish_lesson.py`: explicitly accepts a reviewed draft into the catalog.
- `tests/`: Python standard-library tests for content and agent validation.

## Validation
Run `py -3 -m unittest discover -s tests -v` and
`py -3 scripts/validate_content.py` (use `python3` on Linux).
Run `./gradlew testDebugUnitTest lintDebug assembleDebug` (Windows: `gradlew.bat`).
Use Android Studio/emulator or a physical device to inspect both languages,
quiz feedback, review scheduling, process restart persistence, rotation, and speech.
Update the README when commands, data format, or user-visible behavior change.
Do not claim an APK build, device testing, model generation, or audible speech was
tested unless it was. Do not delegate to other agents unless explicitly requested.
