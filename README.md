# Klock — Intelligent Android Alarm App

> **Direct Phone Download:** Open [`apk/Klock-v1.0.0.apk`](apk/Klock-v1.0.0.apk) on GitHub from your Android phone and tap **Download raw file** to install directly (~1.9 MB signed APK).

**Klock** is a lightweight, native Android alarm application built with Kotlin and Jetpack Compose. When an alarm rings, audio starts immediately and continues until the user correctly answers **five fresh multiple-choice questions** fetched live from the internet — with zero predefined static question bank and zero snooze/stop shortcuts.

---

## 1. Architecture & Separation of Concerns

Klock follows strict layered Clean Architecture without reflection-heavy DI frameworks so cold-start latency and heap usage stay minimal on **2 GB RAM** devices:

```
com.klock.app
├── core/
│   ├── device/       MemoryTierDetector (2 GB .. 16 GB RAM), OemCompatibilityAdvisor
│   └── security/     SecurityGuards (HTTPS-only, SSRF/redirect guards, SHA-256 fingerprints)
├── domain/
│   ├── model/        Pure Kotlin models (Alarm, AlarmQuestion, ActiveAlarmSession, enums)
│   └── repository/   Repository & QuestionEngine contracts
├── data/
│   ├── local/        Room database (klock.db; TRUNCATE journal on 2 GB RAM, WAL on 3 GB+)
│   └── repository/   Room, DataStore Preferences, Android Keystore AES256-GCM BYOK secret store
├── alarm/
│   ├── scheduler/    AlarmTimeCalculator (DST/timezone-safe ZonedDateTime) & AndroidAlarmScheduler
│   ├── audio/        AndroidAlarmAudioController (USAGE_ALARM MediaPlayer + PCM AudioTrack fallback)
│   ├── session/      AlarmSessionCoordinator (persistent 5-question lock & emergency override)
│   ├── service/      AlarmRingingService (WakeLock, immediate audio, ongoing fullScreenIntent)
│   ├── receiver/     AlarmTriggerReceiver & AlarmBootAndTimeReceiver (reboot/time/update recovery)
│   └── worker/       AlarmReconcileWorker (12h battery-aware schedule integrity check)
├── questions/
│   ├── adaptive/     AdaptivePlanner (Easy -> Hard progression + accuracy-weighted category mix)
│   ├── network/      LiveEducationalSourcesClient (bounded streaming HTTP + redirect checks)
│   ├── engine/       LiveQuestionEngine (Microsoft Learn, Wikipedia, Stack Exchange + optional BYOK AI)
│   └── validation/   AnswerValidator (deterministic SQL/code/numeric/prose evaluation; zero exec)
└── ui/
    ├── theme/        Braun/horological Paper & Obsidian theme (tabular numerals, 1dp hairlines)
    ├── components/   MechanicalLockBar, ProvenanceCitationBar, CodeWorkbenchBlock, Tactile controls
    ├── screens/      Alarm list, Alarm editor, Question history, Settings & OEM diagnostics
    ├── active/       ActiveAlarmActivity (lock-screen presentation, Back interception, Completion)
    └── viewmodel/    MainViewModel & ActiveAlarmViewModel (unidirectional StateFlow)
```

---

## 2. Categories, Live Question Engine & Provenance

### Active Question Categories (5)
- **SQL** (`GROUP BY`/`HAVING`, `LEFT JOIN`, `SELECT DISTINCT`, window functions `PARTITION BY`, indexing, ACID)
- **Python** (list slicing, comprehensions, iterators/generators, `dict` operations, output prediction)
- **Power BI** (DAX `CALCULATE`, `SUMX`, filter vs. row context, star schema, Power Query M, RLS)
- **Marketing** (Customer Acquisition Cost, conversion rate optimization, A/B testing, segmentation, funnel metrics)
- **Business** (Gross margin, ROI, NPV, EBITDA, unit economics, Porter's five forces, working capital)

### Live Sources & Provenance Badges
Every question carries an explicit `QuestionProvenance` badge and clickable canonical HTTPS source URL:
1. **`DIRECTLY RETRIEVED`**: Community-verified technical questions with accepted answers retrieved live from Stack Exchange v2.3 (`stackoverflow.com`, `dba.stackexchange.com`) with exact canonical question URLs.
2. **`SOURCE-GROUNDED · LIVE`**: Questions deterministically constructed from freshly fetched documentation and encyclopedia extracts from **Microsoft Learn** (`learn.microsoft.com`), **Wikipedia** (`en.wikipedia.org`), and **Stack Exchange** — covering Multiple Choice, SQL Queries, Python Output Prediction, DAX/Code Expressions, Numerical Calculations, Short Text, and Scenario Analysis.
3. **`AI · SOURCE-GROUNDED`** *(Optional)*: Users may optionally store their own Gemini API key in Settings (encrypted via Android Keystore `EncryptedSharedPreferences`). No private API keys are bundled in source code, `BuildConfig`, or the APK.

---

## 3. Compatibility, Memory Scaling & Honest Platform Limitations

- **Minimum Supported Android Version**: **Android 6.0 Marshmallow (`minSdk = 23`)** through **Android 15/16 (`targetSdk = 35`, `compileSdk = 36`)**. `coreLibraryDesugaring` provides full `java.time` DST-accurate alarm scheduling down to API 23.
- **2 GB to 16 GB RAM Scaling (`MemoryTierDetector`)**:
  - **2 GB Low-Memory Profile (`<= 2.7 GB` or `ActivityManager.isLowRamDevice`)**: `OkHttp` connection pool capped to 1 idle connection, max 2 concurrent fetches, hard `192 KB` per-response streaming byte cap (prevents OOM on large pages), Room `JournalMode.TRUNCATE`, bounded 200-entry history retention.
  - **Standard (`3–6 GB`) & High-Capacity (`8–16 GB`) Profiles**: Scales network concurrency (3 to 5), response caps (`384–512 KB`), Room `WRITE_AHEAD_LOGGING`, and history retention (400 to 800 items).

### Unavoidable Android & OEM Limitations
1. **Force-Stop Behavior**: On all Android versions, manually clicking *Force Stop* in System Settings cancels all `AlarmManager` PendingIntents until the user opens Klock again.
2. **Direct Boot (Pre-First-Unlock after Reboot)**: After a cold reboot on file-based encrypted devices (Android 7+), credential-encrypted Room databases unlock once the user enters their PIN/pattern for the first time (`BOOT_COMPLETED` immediately restores all alarms upon unlock).
3. **OEM Background & Lock-Screen Policies**:
   - **Xiaomi / Redmi / POCO (MIUI & HyperOS)**: Requires enabling *Autostart* and *Show on Lock screen* in App Info -> Other permissions for full-screen lock-screen presentation.
   - **Samsung (One UI)**: Adding Klock to *Never auto sleeping apps* prevents One UI from hibernating alarms if the app UI is not opened for several days.
   - **Android 14+ (`API 34+`)**: Full-screen lock-screen intents (`USE_FULL_SCREEN_INTENT`) can be toggled per-app by the user; Klock detects this at runtime and surfaces a one-tap Settings shortcut.
4. **Validation Scope**: Automated unit, MockWebServer, Strix security PoC, and Robolectric multi-device/Compose UI tests (`320dp` small screen, SDK 34, OEM profiles, 2 GB–16 GB RAM tiers) + release/debug APK builds were executed locally on JVM/Robolectric. Physical hardware speaker/OEM lock-screen testing requires an attached Android device (`adb devices` had no connected hardware in this build environment).

---

## 4. Build & Test Commands

```bash
# Compile Debug & Release APKs
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug assembleRelease

# Run Automated Unit, MockWebServer, Robolectric, Compose 320dp UI, and Strix Security Tests
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest
```
