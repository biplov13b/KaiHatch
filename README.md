# KaiHatch

**KaiHatch** is an Android archive extractor and compressor built for low-storage scenarios. Its primary feature—**Hatch Mode**—performs genuine progressive in-place archive extraction on local Android storage, shrinking the original archive file via `ftruncate` as entries are extracted from the end of the file toward the beginning.

---

## Inspiration & Independence Notice

KaiHatch was inspired by the progressive in-place extraction concept of [**HatchZip**](https://github.com/Fabiannez/HatchZip).

**KaiHatch is an independent Android implementation** written in Kotlin and Jetpack Compose. It shares no source code with HatchZip and is **not affiliated with, endorsed by, or an official release of the HatchZip project**.

---

## Important Data-Safety Disclosure

> **WARNING — Destructive In-Place Operation:**
>
> **Hatch Mode modifies, truncates, and ultimately deletes the original archive file in place** in order to extract archives when free disk space is smaller than the archive itself.
>
> **Interruption during certain destructive extraction operations may leave an archive unrecoverable:**
> 1. **Large Single-Entry Tail-Swap Window:** When extracting a single large entry whose uncompressed output exceeds available free storage, KaiHatch performs an in-place tail-swap that overwrites the beginning of the archive (`slot 0`) after the entry has been decompressed and CRC-verified. If the process is killed, the device loses power, or an I/O failure occurs **after** `slot 0` overwrite has begun and before the final `fsync` + `rename` completes, the original compressed header at `slot 0` is already overwritten and **the archive cannot be recovered**.
> 2. **Mid-Stream Integrity / Decompression Failure After Partial Consumption:** In multi-entry archives, each completed entry at the tail of the archive is immediately truncated off the source archive. If a later entry at an earlier byte offset fails CRC32 verification or decompression, all previously extracted entries remain valid on disk, and KaiHatch halts before deleting the remaining prefix of the archive—but the already-truncated tail entries are no longer present inside the source archive file.
> 3. **External Modification While Paused/Interrupted:** If an external app modifies or truncates a paused archive so its size or header CRC no longer matches the write-ahead journal checkpoint, resume is blocked to prevent silent corruption.
>
> Use **Standard Mode** whenever you have sufficient free storage and want the original archive preserved untouched.

---

## How Progressive In-Place Extraction ("Hatch Mode") Works

Conventional archive extractors require free space equal to the full uncompressed size while keeping the entire compressed archive on disk until extraction finishes.

KaiHatch's **Hatch Mode** reduces peak disk overhead through four mechanisms:

1. **Reverse Physical Offset Ordering:** Archive entries are sorted in descending order of their physical byte offset (`headerOffset` / `dataOffset`). The entry closest to the end of the archive is extracted first.
2. **Verify-Before-Truncate:** Each entry is extracted to its destination path, verified against its archive `CRC32` (for `STORED` and `DEFLATE` ZIP entries and `.7z` entries where present), and synced to disk (`fsync`) **before** the source archive is truncated.
3. **Progressive `ftruncate` Shrinking:** Once an entry's output is durably committed and verified, the source archive file descriptor (or local SAF `ParcelFileDescriptor` opened in `"rw"` mode) is truncated down to that entry's starting offset via `Os.ftruncate`, immediately reclaiming storage blocks to the filesystem.
4. **Decompress-First Large-Entry Tail-Swap:** When a single large entry (`STORED` or `DEFLATE`) cannot fit alongside the full source archive:
   - **Reverse-Slice `STORED` Extraction:** Uncompressed `STORED` entries are copied in reverse slices from the tail of the archive and truncated slice-by-slice, with the final forward pass verifying the full `CRC32`.
   - **Decompress-First `DEFLATE` Tail-Swap:** Using Java's native `Inflater`, KaiHatch first decompresses the entry from `slot 0` through `N`, capturing the initial decompressed chunk (`chunk 0`) into a temporary tail file while streaming subsequent chunks (`1..N`) to the destination file and verifying the full `CRC32` **before** any source bytes are modified. Only after full decompression and CRC verification succeed does it truncate the tail of the source archive, copy `chunk 0` into `slot 0` of the archive, shift chunks `1..N` into place, truncate any remaining padding, and atomically rename the file.

---

## Supported Archive Formats

| Format | Extensions | Hatch Mode (In-Place Shrinking) | Standard Mode (Non-Destructive) | Notes & Constraints |
| :--- | :--- | :---: | :---: | :--- |
| **ZIP / ZIP64 / JAR / APK** | `.zip`, `.jar`, `.apk` | Yes (`STORED` & `DEFLATE`) | Yes | Full CRC32 verification on all entries; reverse-offset extraction and progressive `ftruncate`. |
| **TAR (Uncompressed)** | `.tar` | Yes | Yes | Validated via POSIX `ustar` header magic and header checksum before extraction. |
| **7z (Non-Solid)** | `.7z` | Yes (Independent blocks) | Yes | Requires independent per-entry folder blocks (`folderIndex >= 0`) with monotonically aligned physical stream offsets. |
| **7z (Solid)** | `.7z` | No (Explicitly rejected) | Yes | Solid `.7z` archives share a single forward-only LZMA/LZMA2 dictionary across entries and cannot be truncated from the tail. |
| **GZIP-Compressed TAR / GZIP** | `.tar.gz`, `.tgz`, `.gz` | No (Standard stream only) | Yes | Single continuous forward-only DEFLATE stream; cannot be truncated from the tail without invalidating earlier stream state. |

> **No Silent Fallback Policy:** When **Hatch Mode** is selected, KaiHatch never silently falls back to Standard Mode. If the selected archive format (e.g., solid `.7z`) or the underlying storage provider does not support writable in-place truncation, extraction fails immediately with a clear error message explaining why Hatch Mode cannot proceed.

---

## Android Requirements & Storage Compatibility

- **Minimum Android Version:** Android 7.0 (API 24 / `minSdk = 24`)
- **Target / Compile SDK:** Android 16 (API 36 / `compileSdk = 36`, `targetSdk = 36`)
- **Supported Local Storage Providers (Hatch Mode):**
  - Direct local filesystem paths (`java.io.File` / `RandomAccessFile`)
  - Local files selected via Android's Storage Access Framework (SAF) system picker (`Intent.ACTION_OPEN_DOCUMENT`) backed by local block storage—such as **Downloads** (`com.android.providers.downloads.documents`) and **Internal / SD Card Storage** (`com.android.externalstorage.documents`)—that grant `"rw"` `ParcelFileDescriptor` access and support `Os.ftruncate` and positional `pwrite`.
- **Unsupported Providers for Hatch Mode:**
  - Read-only SAF providers, virtual documents (`COLUMN_FLAGS & FLAG_VIRTUAL_DOCUMENT`), pipe/socket descriptors (`FIFO`), and cloud-backed document providers that do not expose a seekable, truncatable `"rw"` file descriptor. (Cloud storage integration is outside the scope of KaiHatch.)

---

## Known Limitations

1. **Unrecoverable Window During Final Large-Entry Tail-Swap:** As detailed in the [Important Data-Safety Disclosure](#important-data-safety-disclosure) above, if an interruption occurs during the final `slot 0` overwrite phase of a single large entry tail-swap, the original compressed bytes at `slot 0` have already been overwritten by decompressed output and cannot be re-inflated on resume.
2. **Solid `.7z` and `.tar.gz` Archives:** Progressive tail-truncation is mathematically impossible on single-dictionary solid `.7z` streams and `.tar.gz` streams without decompressing from byte 0. Use Standard Mode for these formats.
3. **Encrypted Archives:** Password-protected / AES-encrypted `.zip` and `.7z` archives are detected and rejected with an explicit unsupported-encryption error.
4. **SAF Destination Directory Permissions:** Extracting via SAF requires selecting a writable local output folder via `ACTION_OPEN_DOCUMENT_TREE`.

---

## Building & Running Tests

### Prerequisites
- **JDK:** Java 17 or newer
- **Android SDK:** API 36 installed (`ANDROID_HOME` / `ANDROID_SDK_ROOT` configured)

### Build Debug APK
```bash
./gradlew :app:assembleDebug
```
The compiled debug APK will be generated under `app/build/outputs/apk/debug/`.

### Run Unit & Robolectric Regression Tests
KaiHatch includes a comprehensive JVM + Robolectric test suite covering fault injection, crash-consistent write-ahead journal recovery, large-DEFLATE decompress-first ordering, CRC32 integrity verification, `.7z` solid/non-solid handling, and local SAF descriptor compatibility:

```bash
./gradlew :app:testDebugUnitTest
```

> **Note on Expected Test Failure in Phase 1 Suite:**
> `KaiHatchPhase1RegressionTest.faultInjection_largeDeflateTailSwap_tornWriteAndCrash_mustRecoverValidSha256OnResume` is an intentionally retained fault-injection test documenting the unrecoverable mid-`slot 0` torn-write window described in [Known Limitations](#known-limitations) (52 of 53 tests pass; 1 expected failure documenting the mid-tail-swap torn-write constraint). It is kept active and unsuppressed so this recovery boundary remains visible; if CI is configured, this test may be explicitly classified as a known expected failure while remaining visible.

---

## License

Copyright 2026 Biplov Biswas

Licensed under the **Apache License, Version 2.0**. See [`LICENSE`](LICENSE), [`NOTICE`](NOTICE), and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for full license terms and third-party attributions.
