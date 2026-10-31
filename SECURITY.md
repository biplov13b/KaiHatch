# Security Policy

## Supported Versions

Security fixes and integrity improvements are applied to the latest code on the `main` branch.

| Version / Branch | Supported |
| :--- | :--- |
| `main` (latest) | Yes |
| Older commits / builds | No |

---

## Security-Sensitive Areas in KaiHatch

Because KaiHatch parses untrusted archive files and performs in-place filesystem operations on Android, the following areas are treated as security- and data-safety-critical:

1. **Archive Traversal & Path Sanitization (Zip Slip):**
   - Ensuring archive entry paths (`../`, absolute paths, symlink escapes, or malformed entry names) cannot write files outside the intended extraction destination directory.
2. **Destructive Extraction & In-Place Truncation (`Hatch Mode`):**
   - Bugs in offset calculation, tail-swap sliding window boundaries, `CRC32` validation, `fsync` ordering, or `Os.ftruncate` execution that could cause silent data corruption or unintended file truncation.
3. **Unsafe File Operations & Storage Permissions (SAF / FileProvider):**
   - Mishandling Android Storage Access Framework (`content://`) URIs, persisted folder URI permissions, or `FileProvider` URI grants that could expose or overwrite unintended files.
4. **Malformed Archive Handling (Zip Bombs / Resource Exhaustion):**
   - Crafted central directories, cyclic headers, or extreme compression ratios designed to trigger uncontrolled memory allocation, infinite loops, or storage exhaustion.
5. **Encrypted Archive Handling:**
   - Handling of archive passwords in memory and preventing unintended persistence of sensitive credentials in logs or crash journals.

---

## Reporting a Vulnerability

**Please do not report security vulnerabilities or exploitable data-corruption bugs through public GitHub issues.**

### Preferred Channel: GitHub Private Vulnerability Reporting

If Private Vulnerability Reporting is enabled on this repository, please submit your report directly via GitHub:

- Go to the [**Security** tab of `biplov13b/KaiHatch`](https://github.com/biplov13b/KaiHatch/security/advisories/new) and click **"Report a vulnerability"**.

### Fallback Contact

If GitHub Private Vulnerability Reporting is unavailable, email the maintainer directly at:

- **Email:** [biplovbiswas13@gmail.com](mailto:biplovbiswas13@gmail.com)
- **Subject line:** `[SECURITY] KaiHatch: <brief summary>`

### What to Include in Your Report

To help reproduce and evaluate the issue effectively, please include:

- A clear description of the vulnerability or unsafe file operation and its potential impact.
- Affected component(s), file path(s), and commit hash or app version.
- Step-by-step instructions or a minimal proof-of-concept (e.g., a small synthetic archive generator script or hex layout) to reproduce the issue.
- Device model, Android version, and extraction mode (`Hatch Mode` or `Standard Mode`), if applicable.
- Please **do not** include personal data or real private files in sample archives.

---

## Responsible Disclosure

KaiHatch is maintained as an independent open-source project. While specific response or remediation timelines cannot be guaranteed, reports are reviewed on a best-effort basis. Please allow reasonable time for investigation and a fix before disclosing details publicly.
