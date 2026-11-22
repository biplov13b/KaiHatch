# Changelog

Notable user-facing changes are recorded here. For technical history, see [Git commits](https://github.com/biplov13b/KaiHatch/compare/v1.0.1...main).

## [v1.1.0] — 2026-11-23

### Added
- **32 language options**, including regional Portuguese and Chinese variants, searchable selection, and system-language matching.
- **Built-in GitHub updater** with stable-release checks, background update notifications, verified APK downloads, cached-installer reuse, and Android installer handoff.
- **Crash-safe extraction recovery** with durable journals, transactional DEFLATE tail-swap checkpoints, and guarded Resume after interruption.
- **Restore Remaining ZIP** for eligible stopped/interrupted ZIP/ZIP64 archives; rebuilds the remaining archive in place without payload recompression.
- **Verify Before Hatch:** structural/header Quick Check and read-only streaming/CRC32 Full Verify for supported formats.
- **Pre-extraction Storage Planner** comparing estimated peak additional space, working space, and safety reserve for Standard and Hatch modes.
- **Optional All Files Access** during onboarding and in Settings, alongside SAF access.
- **Persistent I/O Console** history with explicit clearing.

### Changed
- Material 3 Expressive Settings reorganized into animated, expandable sections, with refined navigation, haptics, and transitions.
- Refined onboarding permission cards and recovery UI, including clearer eligibility, progress, and diagnostic states.
- Improved archive browsing, large-file feedback, extraction notifications, storage capability checks, and adaptive I/O/memory management.
- Hardened APK update flow, including system installer targeting, cold-start notification events, download/cache concurrency, and large release-note handling.
- Renamed development-phase test suites to functionality-based names without changing production identifiers or recovery formats.

### Fixed
- SAF output misrouting and unsafe fallback away from the selected destination.
- Recovery journal durability issues, DEFLATE tail-swap crash windows, and interruption/restore edge cases.
- Source descriptor cleanup and `EIO`-on-close errors following successful recovery/completion.
- ZIP64 offset/size handling, archive metadata issues, and verified-output reconciliation.
- Large 7z/LZMA2 memory-budget failures and other large-archive performance problems.
- Temporary compression/output cleanup issues and extraction/notification state inconsistencies.
- Updater self-interception during APK installation, Filipino translation parity, WorkManager payload-size limits, partial-download cleanup races, and cold-start installer event loss.

### Compatibility & notes
- Android 7.0+; ZIP restoration is restricted to eligible ZIP/ZIP64 cases.
- Hatch Mode modifies/consumes the original archive; Standard Extraction preserves it.
- Some archive types, encryption methods, and storage providers do not support in-place extraction or recovery.
- ZIP/7z/TAR and compressed TAR have different format-specific integrity/recovery limitations; see the [README](README.md).

## [v1.0.1] — 2026-11-02

First public KaiHatch release. See the [v1.0.1 release notes](https://github.com/biplov13b/KaiHatch/releases/tag/v1.0.1).
