## Summary

<!-- Provide a clear, concise summary of what this pull request changes and why. -->

## Related Issue

<!-- Link the issue(s) addressed by this PR, e.g., Closes #123 or Related to #123. -->

## Type of Change

- [ ] Bug fix (non-breaking change that fixes an issue)
- [ ] UI / UX improvement (Jetpack Compose screens, theming, or accessibility)
- [ ] Extraction / compression engine change (`KaiHatchEngine`, `HatchCompressEngine`, `ZipParser`, `SpaceLabEngine`)
- [ ] Storage / SAF / recovery journal change (`StorageHelper`, `ResumeJournalManager`)
- [ ] Documentation or repository configuration update

## Testing Performed

<!-- Describe how you verified these changes (e.g., ./gradlew :app:assembleDebug, ./gradlew :app:testDebugUnitTest, manual device testing). Do not invent test results. -->

- [ ] `./gradlew :app:assembleDebug` compiles cleanly
- [ ] `./gradlew :app:testDebugUnitTest` executed (specify test classes run)

## Screenshots (for UI Changes)

<!-- Attach before/after screenshots or screen recordings if any UI components, themes, or layouts were changed. -->

## Archive Integrity & Storage-Safety Checklist

*Complete this section if your PR touches `engine/`, `data/`, archive parsing, truncation, or storage handling:*

- [ ] Source archive bytes are **never** truncated via `ftruncate` before the extracted output (or tail-swap slot) is written, verified, and flushed (`fsync`) to disk.
- [ ] `CRC32` integrity verification is preserved before any destructive truncation step.
- [ ] Recovery journaling (`ResumeJournalManager`) atomic state transitions are preserved.
- [ ] Non-seekable / stream formats (`.tar`, `.tar.*`, `.gz`, `.bz2`, `.xz`, `.7z`) remain non-destructive during extraction.
- [ ] SAF folder permissions and `FileProvider` URIs follow least-privilege handling without leaking or overwriting unintended paths.

## Documentation Updates

- [ ] Updated `README.md` or relevant documentation if user-facing behavior or technical guarantees changed.
- [ ] No documentation changes required.
