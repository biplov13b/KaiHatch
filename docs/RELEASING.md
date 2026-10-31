# Releasing KaiHatch

This guide walks the repository maintainer through generating a permanent Android release signing key, configuring GitHub Actions secrets, building and verifying a signed release APK, and publishing an official GitHub Release for **KaiHatch** (`com.biplov.kaihatch`).

---

## 1. Critical Signing Key Warning

> **IMPORTANT — Keep Your Release Keystore Permanent and Backed Up:**
>
> Android identifies application updates by combining the `applicationId` (`com.biplov.kaihatch`) with the cryptographic certificate used to sign the APK.
>
> - **Never lose or rotate your release keystore** unless absolutely unavoidable. If a future APK is signed with a different key, Android will reject in-place updates (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), forcing every user to uninstall the app before installing the new version.
> - **Never commit `.jks` or `.keystore` files, Base64 keystore dumps, or passwords to Git.** Keep your keystore outside the repository directory and store multiple encrypted offline backups.

---

## 2. Generate a Permanent Release Keystore Locally

Use `keytool` (included with JDK 17+) to generate a single permanent release keystore on your local machine **outside** the repository folder.

### Windows (PowerShell or Command Prompt)

```powershell
mkdir "$HOME\AndroidKeystores" -Force

keytool -genkeypair `
  -v `
  -keystore "$HOME\AndroidKeystores\kaihatch-release.jks" `
  -storetype PKCS12 `
  -alias upload `
  -keyalg RSA `
  -keysize 4096 `
  -validity 10000 `
  -dname "CN=Biplov Biswas, OU=KaiHatch, O=Biplov, C=IN"
```

### Linux / macOS

```bash
mkdir -p "$HOME/.android-keystores"
chmod 700 "$HOME/.android-keystores"

keytool -genkeypair \
  -v \
  -keystore "$HOME/.android-keystores/kaihatch-release.jks" \
  -storetype PKCS12 \
  -alias upload \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=Biplov Biswas, OU=KaiHatch, O=Biplov, C=IN"
```

When prompted:
1. Enter a strong **keystore password** (`ANDROID_KEYSTORE_PASSWORD`).
2. Enter the **key password** (`ANDROID_KEY_PASSWORD`) — for PKCS12 keystores, use the same password as the keystore password.
3. Verify the generated certificate fingerprint:
   ```bash
   keytool -list -v -keystore "$HOME/.android-keystores/kaihatch-release.jks" -alias upload
   ```

---

## 3. Back Up the Keystore Safely

Before configuring CI or building a release, back up all of the following in a trusted password manager and an offline encrypted backup:

- The `.jks` keystore file (`kaihatch-release.jks`)
- Keystore password (`ANDROID_KEYSTORE_PASSWORD`)
- Key alias (`ANDROID_KEY_ALIAS`, e.g., `upload`)
- Key password (`ANDROID_KEY_PASSWORD`)
- Certificate SHA-256 fingerprint (from `keytool -list -v`)

---

## 4. Base64-Encode the Keystore & Configure GitHub Actions Secrets

The automated release workflow (`.github/workflows/release-apk.yml`) expects four repository secrets in **GitHub → Settings → Secrets and variables → Actions → New repository secret**:

| Secret Name | Value |
| :--- | :--- |
| `ANDROID_KEYSTORE_BASE64` | Single-line Base64 string of `kaihatch-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Your keystore password |
| `ANDROID_KEY_ALIAS` | Your key alias (for example, `upload`) |
| `ANDROID_KEY_PASSWORD` | Your key password |

### Encode the Keystore on Windows PowerShell

Run the following command in PowerShell to copy the single-line Base64 string directly to your clipboard (without creating a lingering plaintext file in the repo):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\AndroidKeystores\kaihatch-release.jks")) | Set-Clipboard
```

Paste the clipboard contents into the `ANDROID_KEYSTORE_BASE64` secret in GitHub.

### Encode the Keystore on Linux / macOS

```bash
base64 -w 0 "$HOME/.android-keystores/kaihatch-release.jks"
# Or on macOS:
base64 -i "$HOME/.android-keystores/kaihatch-release.jks" | tr -d '\n'
```

Copy the single-line output into the `ANDROID_KEYSTORE_BASE64` secret in GitHub.

---

## 5. Update `versionName` and `versionCode` Before Each Release

Before triggering a release, check `defaultConfig` in `app/build.gradle.kts`:

```kotlin
defaultConfig {
    applicationId = "com.biplov.kaihatch"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
}
```

For each new release:
1. Increment `versionCode` by at least `+1` (must always be a strictly increasing integer so Android permits upgrading over older installations).
2. Update `versionName` (for example, `"1.0"` for `v1.0`, `"1.1"` for `v1.1`).
3. Commit and push the version bump to `main`.

> **Note:** The release workflow automatically checks that the `version_tag` input (such as `v1.0`) matches `versionName` (`"1.0"`) in `app/build.gradle.kts` and fails early if they do not match or if the tag/release already exists.

---

## 6. Run the Automated Release Workflow

1. Open the repository on GitHub and go to **Actions → Release Signed KaiHatch APK**.
2. Click **Run workflow** on the `main` branch.
3. Enter the **Release version tag** (for example, `v1.0`).
4. Leave **Mark draft release as a pre-release** unchecked for stable releases (or check it for beta/RC builds).
5. Click **Run workflow**.

What the workflow does automatically:
- Validates that all four signing secrets are present and non-empty.
- Verifies that `version_tag` matches `versionName` in `app/build.gradle.kts` and does not overwrite an existing Git tag or GitHub Release.
- Decodes `ANDROID_KEYSTORE_BASE64` into temporary runner storage (`$RUNNER_TEMP`), builds and signs the release APK via `./gradlew :app:assembleRelease --stacktrace`, and deletes the temporary keystore in an `always()` cleanup step.
- Renames the signed APK to `KaiHatch-<version_tag>.apk` (e.g., `KaiHatch-v1.0.apk`).
- Verifies the cryptographic APK signature (`v1`/`v2`/`v3`) using Android SDK `apksigner verify --verbose --print-certs`.
- Generates `KaiHatch-<version_tag>.apk.sha256`.
- Uploads both files as workflow artifacts and creates a **Draft GitHub Release** containing `KaiHatch-<version_tag>.apk` and `KaiHatch-<version_tag>.apk.sha256`.

---

## 7. Verify the Signed APK and Checksum Locally

Before publishing the draft release to users:

1. Open the draft release under **GitHub → Releases** (or download the `KaiHatch-Release-<version_tag>` workflow artifact).
2. Download both `KaiHatch-v1.0.apk` and `KaiHatch-v1.0.apk.sha256`.
3. Verify the SHA-256 checksum:
   - **Windows PowerShell:**
     ```powershell
     Get-FileHash .\KaiHatch-v1.0.apk -Algorithm SHA256
     Get-Content .\KaiHatch-v1.0.apk.sha256
     ```
   - **Linux / macOS:**
     ```bash
     sha256sum --check KaiHatch-v1.0.apk.sha256
     ```
4. Verify the signing certificate with `apksigner`:
   ```bash
   apksigner verify --verbose --print-certs KaiHatch-v1.0.apk
   ```
   Confirm that the printed `Signer #1 certificate SHA-256 digest` matches your permanent release keystore fingerprint.

---

## 8. Install and Test on a Real Android Device

1. Copy `KaiHatch-v1.0.apk` to a physical Android device (or install via `adb install -r KaiHatch-v1.0.apk`).
2. If a previous signed release is already installed, verify that the new APK installs cleanly as an **in-place update** without requiring uninstall.
3. Smoke-test core flows:
   - First-launch onboarding and theme selection
   - Persistent folder permission grant in Settings
   - Standard Mode and Hatch Mode extraction on a sample `.zip` archive
   - Pack Studio archive creation
   - About section links (GitHub, Email, Telegram, Ko-fi, Open-source licenses)

---

## 9. Publish the Draft GitHub Release

Once you have verified the APK on a real device:

1. Go to **https://github.com/biplov13b/KaiHatch/releases**.
2. Click **Edit** on the draft release (`KaiHatch v1.0`).
3. Review the release title, release notes, and attached assets (`KaiHatch-v1.0.apk` and `KaiHatch-v1.0.apk.sha256`).
4. Click **Publish release**.
5. Once published, GitHub automatically creates the tag (`v1.0`) and makes the signed APK available at the official download link referenced in `README.md`.
