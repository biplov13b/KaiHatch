# KaiHatch v1.1.0 — A Major Update

**Safer extraction. Better recovery. More control.**

KaiHatch is an Android port of [HatchZip](https://github.com/xevrion/hatchzip) by [xevrion](https://github.com/xevrion), with additional features built for Android using Kotlin and Jetpack Compose.

This update brings stronger in-place extraction recovery, archive verification, more storage controls, **32 language options**, and a refined Material 3 Expressive experience.

## ✨ What's New

- **Crash-safe Resume** — Durable recovery checkpoints help resume eligible interrupted Hatch Mode extractions, including supported large DEFLATE operations.
- **Restore Remaining ZIP** — Rebuild a usable ZIP/ZIP64 archive containing the *remaining* entries in place after eligible interruptions or stops, subject to recovery checks.
- **Verify Before Hatch** — Run a read-only Quick Check for archive structure and headers, or a Full Verify with streaming and CRC32 integrity checks where supported.
- **Storage Planner** — Compare estimated peak storage needs for Standard and Hatch modes before starting extraction.
- **32 language options** — Searchable in-app language selection, regional variants, and system-language support.
- **Built-in GitHub updater** — Check for new stable releases, download verified APKs, receive update notifications, and reuse verified cached downloads if installation is cancelled.
- **Optional All Files Access** — Improved direct local-file access and recovery compatibility, while Android's Storage Access Framework remains available.

<details>
<summary><strong>See all 32 language options</strong></summary>

English · Spanish · French · German · Italian · Portuguese (Brazil) · Portuguese (Portugal) · Dutch  
Russian · Ukrainian · Polish · Czech · Romanian · Hungarian · Greek · Turkish  
Swedish · Danish · Norwegian Bokmål · Finnish · Arabic · Hebrew · Persian  
Japanese · Korean · Chinese (Simplified) · Chinese (Traditional) · Indonesian · Malay · Vietnamese · Thai · Filipino

</details>

## 🎨 Changes & Improvements

- Redesigned animated Material 3 Expressive Settings with expandable sections, plus refined onboarding, themes, and haptics.
- Clearer recovery controls, progress states, extraction actions, and notifications.
- Persistent I/O Console logs across app sessions, with explicit clearing.
- Improved archive browsing, large-file feedback, storage-provider compatibility, and memory/I/O efficiency.

## 🛠️ Fixes

- Corrected SAF destination routing and unsafe fallback behaviors.
- Hardened DEFLATE tail-swap recovery, checkpoint durability, and ZIP restoration after interruption.
- Fixed ZIP64 metadata/offset edge cases, source-close/`EIO` completion errors, and temporary-file cleanup.
- Addressed large 7z/LZMA2 memory-budget failures and extraction state inconsistencies.
- Fixed updater installer routing, cache/download races, cold-start notifications, and translation parity.

## ⚠️ Important Notes

**Hatch Mode progressively modifies and consumes the original archive.** Recovery and in-place ZIP restoration are conditional on an eligible archive, valid checkpoints, and compatible storage. **Restore Remaining ZIP restores only unextracted entries**; it does not recreate the original complete archive.

Use **Standard Extraction** when preserving your original archive is essential. Not every format, encryption method, or storage provider supports in-place extraction or recovery.

Archive extraction and verification work offline. Internet access is used only for optional GitHub update checks and downloads. See the [README safety notes](https://github.com/biplov13b/KaiHatch#safety--limitations) and [full changelog](https://github.com/biplov13b/KaiHatch/blob/main/CHANGELOG.md).

## 📦 Installation & Update

- **Version:** `1.1.0` (`versionCode 3`)
- **Application ID:** `com.biplov.kaihatch`
- **Android requirement:** Android 7.0 (API 24) or newer
- **APK asset:** `KaiHatch-v1.1.0.apk`
- **Checksum asset:** `KaiHatch-v1.1.0.apk.sha256`

Download the APK from **Assets** below and follow Android's installation prompt. Updating over an existing installation requires a higher `versionCode` and the same signing certificate.

To verify the downloaded APK, compare its SHA-256 to:

```text
361efea6dfca16112a979157ef816057987120c130eee0524cdc77d4adfa1714  KaiHatch-v1.1.0.apk
```

Thanks for supporting KaiHatch and reporting issues! ❤️
