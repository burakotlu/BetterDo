# BetterDo · Android

**Learn a word instead of scrolling.**

A native Android app for daily English and German practice. The interface,
documentation, definitions, and teaching notes are in English. German words,
examples, and dialogues stay in German, with English translations.

Built with Kotlin and Jetpack Compose. The provisional app name and motto are
defined in `app/src/main/res/values/strings.xml`.

## Features

- A daily minimum of one word per language, with optional extra lessons and no daily cap.
- IPA pronunciation, an English definition, and usage notes for every word.
- Three examples, a short conversation, and two quiz questions per lesson.
- Normal and slow pronunciation using Android TextToSpeech.
- Review intervals of 1, 3, 7, 14, 30, and 60 days.
- Separate word libraries, search, review schedules, and streaks for each language.
- Lessons downloaded over HTTPS and cached locally in SQLite; no bundled lesson catalog.
- Development instructions in `AGENTS.md` and an optional Python/Ollama content agent.

The first download requires internet access. Downloaded lessons work offline.
English example translations that duplicate the original sentence are hidden.

A daily word stays selected for the rest of the day. The next day selects the
first unseen published word, or the earliest review when all words have been seen.
After practicing a word, select **Learn another word** to open the next unseen
published lesson. Extra words have their own saved progress and review dates.
The daily goal counts distinct words practiced, including reviews and words marked
**Review tomorrow**; repeating the same word does not increase this count.
Each language has its own goal. When all published words have been explored, the
app offers the word library and explains that more content must be published.
Answer both quiz questions correctly to enable **Mark as learned**. Repeated
practice on the same day does not inflate the streak or review interval.
Scheduling follows the phone's local calendar; changing its clock affects dates.

## Run with Android Studio

1. Open this repository in Android Studio.
2. Select **JDK 17** as the Gradle JDK and install **Android SDK 35** in SDK Manager.
3. Complete Gradle sync.
4. Select an Android 8.0 (API 26) or newer device/emulator and press Run.

The project pins AGP 8.9.2, Gradle 8.11.1, Kotlin 2.1.20, and the Compose compiler
plugin. See the [AGP requirements](https://developer.android.com/build/releases/agp-8-9-0-release-notes)
and [Compose setup guide](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler).

Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Linux/macOS:

```sh
bash ./gradlew testDebugUnitTest lintDebug assembleDebug
```

Android Studio writes the SDK location to `local.properties`; alternatively, set
`ANDROID_HOME`. Machine-specific paths are not committed.
The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Download an APK from GitHub

The **Android checks and APK** workflow validates content, runs unit tests and
lint, and builds the debug APK. It then runs UI and remote-content tests on an
API 35 emulator. Content-only changes do not trigger an APK build.

Open a successful run on [GitHub Actions](https://github.com/burakotlu/BetterDo/actions),
download **BetterDo-debug-apk**, and extract the APK from the ZIP. This is a
development build, not a Play Store release. Android may ask you to allow
installation from the app you use to open the APK.

## Publish lessons without rebuilding the app

The current source is `content/lessons.json`, served over HTTPS by GitHub.
This is a separately published catalog, not a hosted PostgreSQL/API service.
The phone's SQLite database stores validated catalog snapshots and retired lessons
needed for reviews. Room is not currently used. Existing progress remains in
`progress-v1.json` and is preserved when the catalog changes.

The app checks for updates at startup, when the calendar day changes, and when
resuming with data older than six hours. **Refresh lessons** requests an update
manually. A failed request or invalid response keeps the previous valid cache.
Lesson IDs are permanent. Retired lessons remain available for existing reviews
but are excluded from new daily selections. A published catalog may be empty or
contain only one language; the app displays an empty state for unavailable lessons.

After reviewing an agent-generated draft:

```powershell
py -3 scripts/publish_lesson.py drafts/en-NEW-WORD.json --reviewed
py -3 scripts/validate_content.py
git add content/lessons.json
git commit -m "Publish new language lesson"
git push origin main
```

No APK rebuild is needed. Content-only commits run **Lesson catalog checks**.
GitHub caching may delay visibility; refresh again later if needed. Published
content must not contain user progress, credentials, or other private information.

A future API or another HTTPS host can serve the same
`{ "version": 1, "lessons": [...] }` format. Configure its address at build time:

```powershell
.\gradlew.bat assembleDebug -PcatalogUrl=https://example.com/api/lessons
```

Changing the server address requires a new build; changing lessons does not.
Downloads are limited to 2 MB. Release builds only allow HTTPS. Local HTTP is
enabled for specific debug test hosts; test fixtures never enter the application APK.

## Speech and local storage

Install an English/German voice pack in Android's text-to-speech settings if the
app reports one is missing. Offline pronunciation depends on the selected speech
engine and its installed voices. The app does not request microphone access or
record speech. Playback stops when the app leaves the foreground.

Progress is written atomically to private app storage. Uninstalling the app or
clearing its data removes that progress. There is no cross-device sync; automatic
Android backup is disabled. Unreadable progress is not silently reset or overwritten.
After updating an older installation, refresh lessons online to replace cached
teaching notes with the current English content.

## Development agent

`AGENTS.md` describes the Android product scope, English language convention,
accessibility requirements, content format, and validation commands.
Example task: “Follow AGENTS.md and add daily reminders with notification
permission handling, a user-selected time, cancellation, and tests.”
The file itself does not start a background service or an autonomous coding process.

## Content generation agent

The optional Python tool connects to an installed **Ollama** model on your computer,
using the [Ollama Chat API](https://docs.ollama.com/api/chat). No model runs on the
phone, and the app does not need Ollama to download published lessons.

Install Ollama, download a model, and start its local service. Replace `MODEL_NAME`
with an installed model name from `ollama list`:

```powershell
py -3 scripts/generate_lesson.py --language en --topic "at the bakery" --model MODEL_NAME
py -3 scripts/generate_lesson.py --language de --topic "at the train station" --model MODEL_NAME
```

The agent produces JSON, validates its structure and duplicate words, and attempts
repairs up to three times. It writes new files to `drafts/` without overwriting
existing drafts. Validation does not establish linguistic accuracy: review the
English definitions, translations, IPA, natural usage, and quiz answers before
publishing. Automatic daily cloud generation, reminders, and accounts are not included.

## Tests and project layout

- `app/src/test/`: scheduling, streaks, persistence, language isolation, remote
  updates, offline cache, and invalid downloads.
- `app/src/androidTest/`: language selection, quizzes, completion, Activity
  recreation, word library, and downloading new content with the same APK.
- `app/src/androidTest/assets/lessons.json`: test fixtures only.
- `content/lessons.json`: published lesson data, separate from the application.
- `tests/`: content validation, draft publishing, and mocked agent repair loops.
- `scripts/`: content tools using only the Python standard library.

```powershell
py -3 scripts/validate_content.py
py -3 -m unittest discover -s tests -v
```

Content tools support Python 3.7+; a current Python 3 release is recommended.
On Windows, use `py -3` if `python` points to Python 2. On Linux/macOS use `python3`.
Emulator tests do not verify pronunciation quality on a physical device.

Created by Burak Otlu.
