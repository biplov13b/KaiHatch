# KaiHatch — Next Release

A major update to KaiHatch: safer in-place extraction, better crash recovery, more control over storage, 32 language options, and a smoother Android experience.

## ✨ What's new

- **32 language options** — Multilingual UI with a searchable picker and system-language support.
- **Crash-safe Resume** — Durable recovery checkpoints help resume eligible interrupted Hatch Mode operations, including supported large DEFLATE extractions.
- **Restore Remaining ZIP** — After stopping or interrupting extraction, rebuild the remaining contents as a usable ZIP/ZIP64 **in place**, when recovery checks permit it.
- **Verify Before Hatch** — Structural Quick Check and streaming/CRC32 Full Verify, without modifying the archive.
- **Storage Planner** — See estimated Standard vs Hatch peak storage requirements before unpacking.
- **GitHub Updater** — Check for stable releases, download verified APKs, receive notifications, and reuse cached downloads after cancelled installation.
- **Optional All Files Access** — Improved direct local-storage and recovery compatibility, with SAF access still available.

<details>
<summary><strong>See all 32 supported language options</strong></summary>

English · Spanish · French · German · Italian · Portuguese (Brazil) · Portuguese (Portugal) · Dutch  
Russian · Ukrainian · Polish · Czech · Romanian · Hungarian · Greek · Turkish  
Swedish · Danish · Norwegian Bokmål · Finnish · Arabic · Hebrew · Persian  
Japanese · Korean · Chinese (Simplified) · Chinese (Traditional) · Indonesian · Malay · Vietnamese · Thai · Filipino

</details>

## 🎨 Improvements

- Redesigned, animated Material 3 Expressive Settings and refined onboarding.
- Clearer recovery choices, progress indicators, and extraction notifications.
- Persistent I/O Console logs across sessions.
- Improved archive browsing, haptic feedback, large-file handling, and memory/storage efficiency.

## 🐛 Fixes

- Fixed SAF destination routing and local-storage compatibility issues.
- Hardened interrupted DEFLATE extraction, journal checkpoints, and ZIP restoration.
- Fixed source close/`EIO` completion issues, ZIP64 handling, temporary-file cleanup, and large 7z/LZMA2 memory failures.
- Fixed installer routing, update caching races, notification cold-start events, and translation parity.

## ⚠️ Before you use Hatch Mode

**Hatch Mode progressively consumes the original archive.** Recovery and ZIP restoration are available only when the archive, checkpoint, and storage provider pass eligibility checks; restoration preserves **remaining** entries, not the original complete archive. **Standard Extraction** remains the safest choice when you must retain your source archive.

Core archive operations run offline. The network is used for optional GitHub update functionality. Read the [README](https://github.com/biplov13b/KaiHatch#safety--limitations) for formats and safety details.

## 📦 Install or update

Download the release APK from **Assets** below. Android 7.0+ is required. If updating an existing KaiHatch installation, the APK must use a newer `versionCode` and the same signing certificate. Verify the attached `.sha256` checksum if supplied.

Thanks for trying KaiHatch and reporting bugs! ❤️
