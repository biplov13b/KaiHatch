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

> **Recovery and fault-injection tests:** `ExtractionBaselineAndFaultInjectionTest` includes `faultInjection_largeDeflateTailSwap_tornWriteAndCrash_mustRecoverValidSha256OnResume`. The test verifies that eligible interruptions with intact transactional checkpoints resume and reproduce the expected SHA-256, while corrupted checkpoints are rejected safely. Treat test failures as regressions to investigate; do not mark them as expected or bypass the safety assertions.
>
> **Robolectric environment:** Some tests need Android runtime artifacts downloaded by Robolectric. A dependency-download failure (for example, a Maven Central HTTP 429) is an infrastructure issue, not a passing test result. Rerun when the required artifacts are available, and distinguish unexecuted tests from passing tests.

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

**In-Place Extraction (Hatch Mode) intentionally modifies and consumes the source archive.** On compatible seekable and writable storage, the engine progressively reclaims source space as eligible archive entries are extracted. Bugs in offset math, extraction order, durability, checkpointing, or storage-provider handling can permanently damage user data. **Standard Extraction must preserve the original archive.**

When modifying `KaiHatchEngine`, `ZipParser`, `ResumeJournalManager`, `ZipInPlaceRestoreEngine`, `CheckpointableInflateEngine`, `HatchCompressEngine`, or `StorageHelper`:

1. **Preserve Extraction Ordering & Durability:**
   - Never reclaim source bytes until the corresponding output or recoverable in-progress state has been safely written, validated, and durably synchronized. Keep source truncation, tail-swap operations, and recovery checkpoints in their verified transactional order.
2. **Preserve Format-Specific Integrity Checks:**
   - For ZIP/ZIP64, retain appropriate header validation and CRC32 checks against archive metadata. Apply the correct validation rules to TAR and 7z rather than assuming every format has ZIP-style central-directory CRC32 fields. Reject unsupported or unsafe archive layouts.
3. **Preserve Recovery Journaling and Restoration:**
   - Keep `ResumeJournalManager` writes atomic and durable (`write temp` → `fsync` → `rename`, with the required backup/checkpoint ordering). Validate source, output, and saved checkpoints before offering Resume or Restore Remaining ZIP. Restoration is available only for eligible ZIP/ZIP64 cases and recreates **remaining entries**, not the original full archive. Corrupted or missing recovery data must fail closed.
4. **Respect Format and Storage Boundaries:**
   - Hatch Mode supports compatible ZIP/ZIP64/JAR/APK (`STORED`/`DEFLATE`), uncompressed TAR, and eligible non-solid 7z archives. Solid 7z and gzip-based compressed streams (including `.tar.gz`, `.tgz`, and `.gz`) use Standard Extraction. In-place operations additionally require a seekable, writable provider with working positional I/O, truncation, and durable sync; supported local SAF-backed storage may qualify. Do not silently switch an unsupported Hatch operation into Standard Extraction or remove source data on failure.
5. **Keep Changes Focused:**
   - Submit one logical change per pull request with clear commit messages and corresponding unit/Robolectric tests. For destructive-path changes, include failure-injection and recovery or rollback coverage where applicable.

---

## Submitting a Pull Request

1. Fork the repository and create a feature or bugfix branch from `main`.
2. Keep your changes scoped to the issue or improvement being addressed.
3. Verify that `./gradlew :app:assembleDebug` compiles cleanly and run relevant unit tests under `app/src/test/`.
4. Open a pull request against `main` and complete the pull request template, including the archive integrity and storage-safety checklist if any engine or storage code was touched.
