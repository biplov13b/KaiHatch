# Contributing to KaiHatch

Thank you for your interest in contributing to **KaiHatch**. This guide outlines how to report issues, propose improvements, build and test the project, and work safely with its archive extraction and storage code.

---

## Reporting Bugs & Requesting Features

- **Bug Reports:** Open a [Bug Report](https://github.com/biplov13b/KaiHatch/issues/new?template=bug_report.yml) with clear reproduction steps, device/Android version details, archive format and size, extraction mode (`Standard Mode` or `Hatch Mode`), and any relevant logs.
  - **Privacy & Security Warning:** Never attach private archives, personal files, passwords, or sensitive data to public issues.
- **Feature Requests:** Open a [Feature Request](https://github.com/biplov13b/KaiHatch/issues/new?template=feature_request.yml) describing the use case, the problem it solves, and the proposed behavior.
- **Security Vulnerabilities:** Do **not** open a public issue for security or data-loss vulnerabilities. Follow the instructions in [SECURITY.md](SECURITY.md).

---

## Development Setup & Building

### Prerequisites

- **JDK:** Java 17 (used by CI and Gradle 9.3.1)
- **Android SDK:** `compileSdk 36` (minor API level 1), `minSdk 24`, `targetSdk 36`, Android Build-Tools `36.0.0`
- **Gradle:** Gradle `9.3.1` via the included Gradle wrapper (`./gradlew` / `gradlew.bat`)

### Debug Keystore

The `debug` build type uses a `debug.keystore` file in the repository root (`storePassword = "android"`, `keyAlias = "androiddebugkey"`, `keyPassword = "android"`). If `debug.keystore` is not yet present locally, generate one in the project root before building:

```bash
keytool -genkeypair \
  -v \
  -keystore debug.keystore \
  -storepass android \
  -alias androiddebugkey \
  -keypass android \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

Do not commit `debug.keystore` or any release signing keys.

### Build Commands

Build the debug APK:

```bash
./gradlew :app:assembleDebug
```

Run the local JVM and Robolectric unit test suite:

```bash
./gradlew :app:testDebugUnitTest
```

Run a specific test class:

```bash
./gradlew :app:testDebugUnitTest --tests com.biplov.kaihatch.ExampleRobolectricTest
```

> **Note on `ExtractionBaselineAndFaultInjectionTest`:** The regression suite includes an intentional fault-injection test (`faultInjection_largeDeflateTailSwap_tornWriteAndCrash_mustRecoverValidSha256OnResume`) that documents the known mid-`slot 0` torn-write limitation in large-`DEFLATE` tail-swap extraction. Do not delete or mask this test unless your change specifically resolves that architectural limitation with full verification.

---

## Project Structure & Conventions

- **Language & UI:** 100% Kotlin and Jetpack Compose with Material 3 (`com.biplov.kaihatch.ui.*`).
- **Package Structure (`app/src/main/java/com/biplov/kaihatch/`):**
  - `engine/` — Core archive parsing (`ZipParser`), extraction (`KaiHatchEngine`), compression (`HatchCompressEngine`), I/O buffering (`CommonsCompressBufferStrategy`), and storage simulation (`SpaceLabEngine`).
  - `data/` — Storage Access Framework (SAF) helpers, POSIX file descriptor integration (`StorageHelper`), crash recovery journals (`ResumeJournalManager`), history persistence (`HistoryRepository`), and foreground job execution (`ArchiveJobRunner`, `ArchiveForegroundService`).
  - `model/` — Immutable data models for archive metadata, extraction/compression progress, and theme preferences.
  - `ui/` — `HatchViewModel`, screens (`ui/screens/`), reusable Material 3 components (`ui/components/`), and theming (`ui/theme/`).
- **Compose & State Conventions:**
  - Keep UI state in `StateFlow` exposed from `HatchViewModel` and mark data models `@Immutable` or `@Stable` where appropriate.
  - Keep blocking file I/O, archive parsing, and SAF `ContentResolver` queries off the main thread (`Dispatchers.IO`).
  - Provide `Modifier.testTag(...)` on primary interactive elements and meaningful `contentDescription` values for accessibility.
  - Respect Android edge-to-edge window insets and native display refresh rate adaptation without forcing global refresh rate overrides.

---

## Extra Care for Extraction, Integrity & Storage Code

**Hatch Mode is intentionally destructive to the source archive.** Because it extracts entries from the end of a `.zip` / `.apk` archive and progressively shrinks the source file in place via `Os.ftruncate(FileDescriptor, Long)`, bugs in offset math, ordering, synchronization, or error handling can permanently corrupt user data.

When modifying `KaiHatchEngine`, `ZipParser`, `ResumeJournalManager`, `HatchCompressEngine`, or `StorageHelper`:

1. **Preserve Extraction Ordering & Synchronization:**
   - Never truncate a byte range of the source archive before the corresponding output file (or tail-swap slot) has been written, verified, and flushed (`fsync`) to disk.
2. **Preserve Integrity Verification:**
   - Maintain `CRC32` verification against central directory headers before any truncation step occurs.
3. **Preserve Recovery Journaling:**
   - Ensure `ResumeJournalManager` state updates remain atomic (`write temp` -> `fsync` -> `rename`) so interrupted extractions can be detected accurately.
4. **Respect Format & Storage Boundaries:**
   - Only local seekable `.zip` and `.apk` archives with a writable POSIX file descriptor support progressive in-place shrinking. Sequential stream formats (`.tar`, `.tar.gz`, `.tar.bz2`, `.tar.xz`, `.gz`, `.bz2`, `.xz`) and `.7z` archives must remain non-destructive during extraction and only remove the source archive after full extraction verification when requested.
5. **Keep Changes Focused:**
   - Submit one logical change per pull request with clear commit messages and corresponding unit/Robolectric tests.

---

## Submitting a Pull Request

1. Fork the repository and create a feature or bugfix branch from `main`.
2. Keep your changes scoped to the issue or improvement being addressed.
3. Verify that `./gradlew :app:assembleDebug` compiles cleanly and run relevant unit tests under `app/src/test/`.
4. Open a pull request against `main` and complete the pull request template, including the archive integrity and storage-safety checklist if any engine or storage code was touched.
