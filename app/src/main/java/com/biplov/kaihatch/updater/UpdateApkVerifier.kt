package com.biplov.kaihatch.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

data class ApkArchiveMetadata(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val signingCertSha256Digests: Set<String>
)

interface PackageArchiveInspector {
    fun inspectArchive(context: Context, apkFile: File): ApkArchiveMetadata?
    fun getInstalledAppInfo(context: Context): ApkArchiveMetadata?
}

class AndroidPackageArchiveInspector : PackageArchiveInspector {

    override fun inspectArchive(context: Context, apkFile: File): ApkArchiveMetadata? {
        val pm = context.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
            }
        } catch (_: Exception) {
            null
        } ?: return null

        return extractMetadata(info)
    }

    override fun getInstalledAppInfo(context: Context): ApkArchiveMetadata? {
        val pm = context.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
        } catch (_: Exception) {
            null
        } ?: return null

        return extractMetadata(info)
    }

    private fun extractMetadata(info: PackageInfo): ApkArchiveMetadata? {
        val pkgName = info.packageName?.takeIf { it.isNotBlank() } ?: return null
        val versionCode = PackageInfoCompat.getLongVersionCode(info)
        val versionName = info.versionName.orEmpty()
        val digests = extractSigningDigests(info)
        return ApkArchiveMetadata(
            packageName = pkgName,
            versionCode = versionCode,
            versionName = versionName,
            signingCertSha256Digests = digests
        )
    }

    private fun extractSigningDigests(info: PackageInfo): Set<String> {
        val result = linkedSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo
            if (signingInfo != null) {
                val signers = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
                signers?.forEach { sig ->
                    result.add(UpdateApkVerifier.sha256Hex(sig.toByteArray()))
                }
            }
        }
        if (result.isEmpty()) {
            @Suppress("DEPRECATION")
            info.signatures?.forEach { sig ->
                result.add(UpdateApkVerifier.sha256Hex(sig.toByteArray()))
            }
        }
        return result
    }
}

class UpdateApkVerifier(
    private val archiveInspector: PackageArchiveInspector = AndroidPackageArchiveInspector()
) {

    fun verifyApk(
        context: Context,
        apkFile: File,
        expectedSha256: String?,
        expectedPackageName: String = context.packageName,
        installedVersionCodeOverride: Long? = null,
        installedSigningDigestsOverride: Set<String>? = null
    ): ApkValidationResult {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() <= 0L) {
            return ApkValidationResult.Invalid(
                reason = ApkValidationFailureReason.FILE_MISSING,
                detail = "APK file is missing or empty"
            )
        }

        val normalizedExpectedSha = expectedSha256?.trim()?.lowercase(Locale.US)
        if (!GitHubReleaseClient.isValidSha256Hex(normalizedExpectedSha)) {
            return ApkValidationResult.Invalid(
                reason = ApkValidationFailureReason.SHA256_MISSING,
                detail = "Release does not include a valid SHA-256 checksum"
            )
        }

        val actualSha256 = try {
            computeFileSha256(apkFile)
        } catch (e: Exception) {
            return ApkValidationResult.Invalid(
                reason = ApkValidationFailureReason.INVALID_ARCHIVE,
                detail = "Failed to hash APK: ${e.message}"
            )
        }

        if (actualSha256 != normalizedExpectedSha) {
            return ApkValidationResult.Invalid(
                reason = ApkValidationFailureReason.SHA256_MISMATCH,
                detail = "SHA-256 mismatch (expected $normalizedExpectedSha, got $actualSha256)"
            )
        }

        val archiveMeta = archiveInspector.inspectArchive(context, apkFile)
            ?: return ApkValidationResult.Invalid(
                reason = ApkValidationFailureReason.INVALID_ARCHIVE,
                detail = "Unable to parse APK package metadata"
            )

        if (archiveMeta.packageName != expectedPackageName) {
            return ApkValidationResult.Invalid(
                reason = ApkValidationFailureReason.PACKAGE_ID_MISMATCH,
                detail = "Package ID mismatch: expected $expectedPackageName, got ${archiveMeta.packageName}"
            )
        }

        val installedMeta = archiveInspector.getInstalledAppInfo(context)
        val installedVersionCode = installedVersionCodeOverride
            ?: installedMeta?.versionCode
            ?: com.biplov.kaihatch.BuildConfig.VERSION_CODE.toLong()

        if (archiveMeta.versionCode <= installedVersionCode) {
            return ApkValidationResult.Invalid(
                reason = ApkValidationFailureReason.VERSION_NOT_NEWER,
                detail = "APK versionCode (${archiveMeta.versionCode}) is not newer than installed ($installedVersionCode)"
            )
        }

        val installedSigners = installedSigningDigestsOverride
            ?: installedMeta?.signingCertSha256Digests
            ?: emptySet()

        if (installedSigners.isEmpty() ||
            archiveMeta.signingCertSha256Digests.isEmpty() ||
            archiveMeta.signingCertSha256Digests.intersect(installedSigners).isEmpty()
        ) {
            return ApkValidationResult.Invalid(
                reason = ApkValidationFailureReason.SIGNING_MISMATCH,
                detail = "APK signing certificate does not match the installed application"
            )
        }

        return ApkValidationResult.Valid(
            packageName = archiveMeta.packageName,
            versionCode = archiveMeta.versionCode,
            versionName = archiveMeta.versionName,
            sha256 = actualSha256,
            signingDigests = archiveMeta.signingCertSha256Digests
        )
    }

    companion object {
        fun computeFileSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(FileInputStream(file), 64 * 1024).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            return bytesToHex(digest.digest())
        }

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return bytesToHex(digest.digest(bytes))
        }

        private fun bytesToHex(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                sb.append(HEX_CHARS[v ushr 4])
                sb.append(HEX_CHARS[v and 0x0F])
            }
            return sb.toString()
        }

        private val HEX_CHARS = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
        )
    }
}
