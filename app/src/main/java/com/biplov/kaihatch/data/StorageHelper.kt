package com.biplov.kaihatch.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.core.content.FileProvider
import com.biplov.kaihatch.R
import com.biplov.kaihatch.engine.ZipCryptoCipher
import com.biplov.kaihatch.engine.ZipParser
import com.biplov.kaihatch.model.ZipEntryInfo
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipFile

@Immutable
data class FilePreviewData(
    val fileName: String,
    val sizeBytes: Long,
    val isBinary: Boolean,
    val isImage: Boolean = false,
    val imageBytes: ByteArray? = null,
    val mimeType: String,
    val contentPreview: String
)

@Immutable
data class GrantedFolderInfo(
    val uriString: String,
    val displayName: String,
    val readablePath: String,
    val isWriteEnabled: Boolean = true
) {
    val uri: Uri
        get() = Uri.parse(uriString)
}

@Immutable
data class ExtractedFileEntry(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val localFile: File? = null,
    val safUri: Uri? = null,
    val isSaf: Boolean = false,
    val isDirectory: Boolean = false,
    val relativePath: String = name,
    val parentId: String = "",
    val safTreeUri: Uri? = null,
    val safDocId: String? = null
)

@Immutable
data class ExtractedFolderEntry(
    val id: String,
    val name: String,
    val isSaf: Boolean,
    val localFile: File? = null,
    val safUri: Uri? = null,
    val totalSizeBytes: Long = 0L,
    val children: List<ExtractedFileEntry> = emptyList(),
    val locationDescription: String = "",
    val totalFileCount: Int = children.size,
    val safTreeUri: Uri? = null,
    val safDocId: String? = null,
    val canonicalPath: String? = null
)

data class TargetOutput(
    val outputStream: java.io.OutputStream,
    val channel: FileChannel,
    val localFile: File? = null,
    val documentUri: Uri? = null,
    val close: () -> Unit,
    val sync: () -> Boolean = { StorageHelper.syncFileDescriptorStrict(null, channel) },
    val closeStrict: () -> Unit = close
)

data class TransactableDescriptorProbe(
    val isSupported: Boolean,
    val sizeBytes: Long,
    val reason: String? = null
)

data class SafPartialOutputVerification(
    val isValid: Boolean,
    val documentUri: Uri? = null,
    val actualSize: Long = -1L,
    val actualPrefixCrc32: Long = -1L,
    val failureReason: String? = null
)

sealed class SafRecoveryOutputOpenResult {
    data class Opened(
        val targetOutput: TargetOutput,
        val documentUri: Uri,
        val verifiedPrefixBytes: Long,
        val trimmedTrailingBytes: Long
    ) : SafRecoveryOutputOpenResult()

    data class Failed(val reason: String) : SafRecoveryOutputOpenResult()
}

@Immutable
data class DestinationCapacityInfo(
    val availableBytes: Long?,
    val totalBytes: Long?,
    val isReliable: Boolean,
    val isDirectPosix: Boolean
)

object StorageHelper {

    private const val PREFS_NAME = "kaihatch_settings"
    private const val KEY_SELECTED_FOLDER_URI = "selected_saf_folder_uri"

    fun getDeviceFreeBytes(context: Context): Long {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            1024L * 1024L * 1024L * 4L
        }
    }

    fun getDeviceTotalBytes(context: Context): Long {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.blockCountLong * stat.blockSizeLong
        } catch (e: Exception) {
            1024L * 1024L * 1024L * 64L
        }
    }

    /**
     * Queries the available and total bytes of the selected extraction destination without
     * fabricating a fallback capacity when a SAF provider's underlying volume capacity cannot
     * be reliably determined.
     */
    fun queryDestinationCapacity(
        context: Context?,
        targetDir: File?,
        targetTreeUri: Uri?
    ): DestinationCapacityInfo {
        val isDirectPosix = if (targetDir != null || targetTreeUri != null) {
            isDirectPosixDestination(context, targetDir, targetTreeUri)
        } else {
            true
        }

        fun statLocalFile(candidate: File?): Pair<Long, Long>? {
            if (candidate == null) return null
            return try {
                val existing = generateSequence(candidate) { it.parentFile }
                    .firstOrNull { it.exists() } ?: return null
                val stat = StatFs(existing.absolutePath)
                val avail = stat.availableBlocksLong * stat.blockSizeLong
                val total = stat.blockCountLong * stat.blockSizeLong
                if (avail >= 0L && total > 0L) avail to total else null
            } catch (_: Throwable) {
                null
            }
        }

        if (targetTreeUri != null) {
            if (targetTreeUri.scheme.equals("file", ignoreCase = true)) {
                val fileTarget = targetDir ?: targetTreeUri.path?.let { File(it) }
                val stats = statLocalFile(fileTarget)
                return if (stats != null) {
                    DestinationCapacityInfo(
                        availableBytes = stats.first,
                        totalBytes = stats.second,
                        isReliable = true,
                        isDirectPosix = true
                    )
                } else {
                    DestinationCapacityInfo(
                        availableBytes = null,
                        totalBytes = null,
                        isReliable = false,
                        isDirectPosix = false
                    )
                }
            }

            // 1. If targetDir is already verified as a direct external POSIX directory, stat it directly.
            if (isDirectPosix && targetDir != null) {
                val stats = statLocalFile(targetDir)
                if (stats != null) {
                    return DestinationCapacityInfo(
                        availableBytes = stats.first,
                        totalBytes = stats.second,
                        isReliable = true,
                        isDirectPosix = true
                    )
                }
            }

            // 2. Try querying the SAF tree document descriptor via Os.fstatvfs.
            if (context != null) {
                try {
                    val treeDocId = DocumentsContract.getTreeDocumentId(targetTreeUri)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(targetTreeUri, treeDocId)
                    context.contentResolver.openFileDescriptor(docUri, "r")?.use { pfd ->
                        val vfs = android.system.Os.fstatvfs(pfd.fileDescriptor)
                        val frsize = if (vfs.f_frsize > 0L) vfs.f_frsize else vfs.f_bsize
                        val avail = vfs.f_bavail * frsize
                        val total = vfs.f_blocks * frsize
                        if (avail >= 0L && total > 0L) {
                            return DestinationCapacityInfo(
                                availableBytes = avail,
                                totalBytes = total,
                                isReliable = true,
                                isDirectPosix = isDirectPosix
                            )
                        }
                    }
                } catch (_: Throwable) {
                }
            }

            // 3. Only if the SAF URI belongs to the standard Android external storage provider
            //    for primary:/home:, resolve the external storage mount root for StatFs.
            val authority = targetTreeUri.authority.orEmpty()
            if (authority.equals("com.android.externalstorage.documents", ignoreCase = true)) {
                val rootDir = tryResolveDirectTreeRootDir(targetTreeUri)
                val stats = statLocalFile(rootDir)
                if (stats != null) {
                    val canWriteDirect = rootDir != null && (targetDir != null && isDirectPosix)
                    return DestinationCapacityInfo(
                        availableBytes = stats.first,
                        totalBytes = stats.second,
                        isReliable = true,
                        isDirectPosix = canWriteDirect
                    )
                }
            }

            // Never guarantee or fabricate free space when SAF capacity cannot be determined.
            return DestinationCapacityInfo(
                availableBytes = null,
                totalBytes = null,
                isReliable = false,
                isDirectPosix = false
            )
        }

        if (targetDir != null) {
            val stats = statLocalFile(targetDir)
            if (stats != null) {
                return DestinationCapacityInfo(
                    availableBytes = stats.first,
                    totalBytes = stats.second,
                    isReliable = true,
                    isDirectPosix = true
                )
            }
        }

        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val avail = stat.availableBlocksLong * stat.blockSizeLong
            val total = stat.blockCountLong * stat.blockSizeLong
            if (avail >= 0L && total > 0L) {
                DestinationCapacityInfo(
                    availableBytes = avail,
                    totalBytes = total,
                    isReliable = true,
                    isDirectPosix = true
                )
            } else {
                DestinationCapacityInfo(
                    availableBytes = null,
                    totalBytes = null,
                    isReliable = false,
                    isDirectPosix = isDirectPosix
                )
            }
        } catch (_: Throwable) {
            DestinationCapacityInfo(
                availableBytes = null,
                totalBytes = null,
                isReliable = false,
                isDirectPosix = isDirectPosix
            )
        }
    }

    fun getArchivesDir(context: Context): File {
        val dir = File(context.filesDir, "archives")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getSampleArchivesDir(context: Context): File {
        val dir = File(context.filesDir, "sample_archives")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getExtractedDir(context: Context): File {
        val dir = File(context.filesDir, "extracted")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private const val TAG_STORAGE = "KaiHatchStorage"

    private val activeCompressionPaths: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun normalizeStoragePath(file: File): String {
        return try {
            file.canonicalPath
        } catch (_: Throwable) {
            file.absolutePath
        }
    }

    fun getTempDir(context: Context): File {
        val dir = File(context.cacheDir, "compress_temp")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getCompressStagingDir(context: Context): File {
        val dir = File(context.cacheDir, "compress_staging")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun registerActiveCompressionFile(file: File?) {
        if (file == null) return
        val norm = normalizeStoragePath(file)
        activeCompressionPaths.add(norm)
        runCatching {
            android.util.Log.d(
                TAG_STORAGE,
                "Registered active compression file: path=$norm exists=${file.exists()} size=${if (file.exists() && file.isFile) file.length() else 0L}"
            )
        }
    }

    fun unregisterActiveCompressionFile(file: File?) {
        if (file == null) return
        val norm = normalizeStoragePath(file)
        activeCompressionPaths.remove(norm)
    }

    fun isCompressionFileActive(file: File?): Boolean {
        if (file == null) return false
        val norm = normalizeStoragePath(file)
        if (activeCompressionPaths.contains(norm)) return true
        val prefix = if (norm.endsWith(File.separator)) norm else "$norm${File.separator}"
        return activeCompressionPaths.any { active ->
            val activePrefix = if (active.endsWith(File.separator)) active else "$active${File.separator}"
            active.startsWith(prefix) || norm.startsWith(activePrefix)
        }
    }

    private fun isDescendantOfDir(file: File, dir: File): Boolean {
        val normFile = normalizeStoragePath(file)
        val normDir = normalizeStoragePath(dir)
        if (normFile == normDir) return false
        val prefix = if (normDir.endsWith(File.separator)) normDir else "$normDir${File.separator}"
        return normFile.startsWith(prefix)
    }

    fun isCompressionTempFile(context: Context, file: File?): Boolean {
        if (file == null) return false
        val tempRoot = File(context.cacheDir, "compress_temp")
        return isDescendantOfDir(file, tempRoot)
    }

    fun isCompressionStagingFile(context: Context, file: File?): Boolean {
        if (file == null) return false
        val stagingRoot = File(context.cacheDir, "compress_staging")
        return isDescendantOfDir(file, stagingRoot)
    }

    fun isDisposableCompressionFile(context: Context, file: File?): Boolean {
        return isCompressionTempFile(context, file) || isCompressionStagingFile(context, file)
    }

    fun isProtectedExtractionOrRecoveryFile(context: Context, file: File?): Boolean {
        if (file == null) return false
        val name = file.name
        if (name.endsWith(".hatchstate.json") ||
            name.endsWith(".ckpt.bin") ||
            name.endsWith(".index.bin") ||
            name.endsWith(".tailswap.guard") ||
            name.startsWith(".kaihatch_")
        ) {
            return true
        }
        val journalsDir = File(context.filesDir, "hatch_journals")
        val extractedDir = File(context.filesDir, "extracted")
        val archivesDir = File(context.filesDir, "archives")
        val sampleArchivesDir = File(context.filesDir, "sample_archives")
        val normFile = normalizeStoragePath(file)
        return listOf(journalsDir, extractedDir, archivesDir, sampleArchivesDir).any { protectedDir ->
            val normProtected = normalizeStoragePath(protectedDir)
            val prefix = if (normProtected.endsWith(File.separator)) normProtected else "$normProtected${File.separator}"
            normFile == normProtected || normFile.startsWith(prefix)
        }
    }

    /**
     * Safely deletes a disposable compression temporary or staged file/directory owned by KaiHatch.
     * Never deletes active files, extraction recovery journals, legacy `files/extracted` contents,
     * or user files outside `cacheDir/compress_temp` and `cacheDir/compress_staging`.
     */
    fun cleanupCompressionFile(
        context: Context,
        file: File?,
        reason: String,
        forceUnregister: Boolean = false
    ): Boolean {
        if (file == null) return false
        if (forceUnregister) {
            unregisterActiveCompressionFile(file)
        }
        return try {
            if (isProtectedExtractionOrRecoveryFile(context, file)) {
                runCatching {
                    android.util.Log.d(
                        TAG_STORAGE,
                        "Retained protected extraction/recovery file (cleanup skipped): path=${file.absolutePath} reason=$reason"
                    )
                }
                return false
            }
            if (!isDisposableCompressionFile(context, file)) {
                return false
            }
            if (isCompressionFileActive(file)) {
                runCatching {
                    android.util.Log.d(
                        TAG_STORAGE,
                        "Retained active compression file (in use): path=${file.absolutePath} reason=$reason"
                    )
                }
                return false
            }
            if (!file.exists()) {
                return true
            }
            val sizeBytes = runCatching {
                if (file.isDirectory) getDirectorySize(file) else file.length()
            }.getOrDefault(0L)
            val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
            if (deleted || !file.exists()) {
                runCatching {
                    android.util.Log.d(
                        TAG_STORAGE,
                        "Deleted compression temp file: path=${file.absolutePath} bytes=$sizeBytes reason=$reason"
                    )
                }
                true
            } else {
                runCatching {
                    android.util.Log.w(
                        TAG_STORAGE,
                        "Failed to delete compression temp file: path=${file.absolutePath} bytes=$sizeBytes reason=$reason"
                    )
                }
                false
            }
        } catch (t: Throwable) {
            runCatching {
                android.util.Log.w(
                    TAG_STORAGE,
                    "Exception during compression temp cleanup: path=${file.absolutePath} reason=$reason error=${t.message}"
                )
            }
            false
        }
    }

    fun cleanupStagedCompressionItems(
        context: Context,
        items: Collection<File>,
        reason: String
    ): Int {
        var cleanedCount = 0
        for (item in items) {
            unregisterActiveCompressionFile(item)
            if (isCompressionStagingFile(context, item)) {
                if (cleanupCompressionFile(context, item, reason, forceUnregister = true)) {
                    cleanedCount++
                }
            }
        }
        return cleanedCount
    }

    /**
     * Scans `cacheDir/compress_temp` and `cacheDir/compress_staging` when no compression job is running
     * and removes abandoned temporary files from prior interrupted/crashed sessions.
     * Strictly scoped to compression temp/staging directories; never touches `files/extracted`,
     * `files/hatch_journals`, `files/archives`, or any active/queued items.
     */
    fun cleanupAbandonedCompressionTempFiles(
        context: Context,
        isCompressionRunning: Boolean = false,
        queuedItems: Collection<File> = emptyList(),
        minAgeMs: Long = 0L,
        queuedItemsProvider: () -> Collection<File> = { queuedItems }
    ): Int {
        if (isCompressionRunning) {
            runCatching {
                android.util.Log.d(
                    TAG_STORAGE,
                    "Skipping abandoned compression temp sweep: compression is currently running"
                )
            }
            return 0
        }
        var reclaimedFiles = 0
        var reclaimedBytes = 0L
        val nowMs = System.currentTimeMillis()

        fun isQueuedOrDescendant(candidate: File): Boolean {
            val dynamicQueued = queuedItemsProvider()
            val queuedCanonical = HashSet<String>(queuedItems.size + dynamicQueued.size)
            queuedItems.forEach { queuedCanonical.add(normalizeStoragePath(it)) }
            dynamicQueued.forEach { queuedCanonical.add(normalizeStoragePath(it)) }
            if (queuedCanonical.isEmpty()) return false
            val norm = normalizeStoragePath(candidate)
            if (queuedCanonical.contains(norm)) return true
            val prefix = if (norm.endsWith(File.separator)) norm else "$norm${File.separator}"
            return queuedCanonical.any { q ->
                val qPrefix = if (q.endsWith(File.separator)) q else "$q${File.separator}"
                q.startsWith(prefix) || norm.startsWith(qPrefix)
            }
        }

        fun isRecentlyModified(candidate: File): Boolean {
            if (minAgeMs <= 0L) return false
            val cutoff = nowMs - minAgeMs
            var latestMod = candidate.lastModified()
            if (latestMod > cutoff) return true
            if (candidate.isDirectory) {
                val hasRecentDescendant = runCatching {
                    candidate.walkTopDown().any { it.lastModified() > cutoff }
                }.getOrDefault(true)
                if (hasRecentDescendant) return true
            }
            return false
        }

        val tempRoot = File(context.cacheDir, "compress_temp")
        if (tempRoot.exists() && tempRoot.isDirectory) {
            tempRoot.listFiles()?.forEach { child ->
                if (!isCompressionFileActive(child) && !isQueuedOrDescendant(child) && !isRecentlyModified(child)) {
                    val bytes = runCatching {
                        if (child.isDirectory) getDirectorySize(child) else child.length()
                    }.getOrDefault(0L)
                    runCatching {
                        android.util.Log.d(
                            TAG_STORAGE,
                            "Detected abandoned compression temp archive: path=${child.absolutePath} bytes=$bytes"
                        )
                    }
                    if (cleanupCompressionFile(context, child, "abandoned_compress_temp_sweep")) {
                        reclaimedFiles++
                        reclaimedBytes += bytes
                    }
                }
            }
        }

        val stagingRoot = File(context.cacheDir, "compress_staging")
        if (stagingRoot.exists() && stagingRoot.isDirectory) {
            stagingRoot.listFiles()?.forEach { child ->
                if (!isCompressionFileActive(child) && !isQueuedOrDescendant(child) && !isRecentlyModified(child)) {
                    val bytes = runCatching {
                        if (child.isDirectory) getDirectorySize(child) else child.length()
                    }.getOrDefault(0L)
                    runCatching {
                        android.util.Log.d(
                            TAG_STORAGE,
                            "Detected abandoned compression staging item: path=${child.absolutePath} bytes=$bytes"
                        )
                    }
                    if (cleanupCompressionFile(context, child, "abandoned_compress_staging_sweep")) {
                        reclaimedFiles++
                        reclaimedBytes += bytes
                    }
                }
            }
        }

        if (reclaimedFiles > 0) {
            runCatching {
                android.util.Log.d(
                    TAG_STORAGE,
                    "Abandoned compression sweep complete: reclaimedFiles=$reclaimedFiles reclaimedBytes=$reclaimedBytes"
                )
            }
        }
        return reclaimedFiles
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        return String.format(Locale.US, "%.2f %s", value, units[unitIndex])
    }

    /**
     * Formats the storage saved between [standardPeakBytes] and [hatchPeakBytes] so that when both peaks
     * share the same display unit (e.g., MB or GB), the displayed saved value equals the exact difference
     * of the two 2-decimal rounded peak numbers (preventing 1-ulp display rounding discrepancies such as
     * 698.70 MB - 384.06 MB showing 314.65 MB instead of 314.64 MB).
     */
    fun formatConsistentSavedBytes(standardPeakBytes: Long, hatchPeakBytes: Long): String {
        val rawDiff = (standardPeakBytes - hatchPeakBytes).coerceAtLeast(0L)
        if (rawDiff <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")

        var stdVal = standardPeakBytes.toDouble()
        var stdUnit = 0
        while (stdVal >= 1024.0 && stdUnit < units.size - 1) {
            stdVal /= 1024.0
            stdUnit++
        }

        var hatchVal = hatchPeakBytes.toDouble()
        var hatchUnit = 0
        while (hatchVal >= 1024.0 && hatchUnit < units.size - 1) {
            hatchVal /= 1024.0
            hatchUnit++
        }

        if (stdUnit == hatchUnit && stdUnit >= 2) {
            val stdRounded = kotlin.math.round(stdVal * 100.0) / 100.0
            val hatchRounded = kotlin.math.round(hatchVal * 100.0) / 100.0
            val diffRounded = (stdRounded - hatchRounded).coerceAtLeast(0.0)
            if (diffRounded >= 1.0) {
                return String.format(Locale.US, "%.2f %s", diffRounded, units[stdUnit])
            }
        }
        return formatBytes(rawDiff)
    }

    fun takePersistablePermission(context: Context, uri: Uri) {
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (_: Exception) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Saves the granted DocumentTree folder URI once, releasing any previous tree URI so only the
     * active workspace folder is kept and displayed in Settings.
     */
    fun saveGrantedFolder(context: Context, treeUri: Uri): GrantedFolderInfo {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldUriStr = prefs.getString(KEY_SELECTED_FOLDER_URI, null)
        if (!oldUriStr.isNullOrEmpty() && oldUriStr != treeUri.toString()) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.releasePersistableUriPermission(Uri.parse(oldUriStr), flags)
            } catch (_: Exception) {
            }
        }

        takePersistablePermission(context, treeUri)
        prefs.edit().putString(KEY_SELECTED_FOLDER_URI, treeUri.toString()).apply()
        return parseGrantedFolderUri(treeUri, isWriteEnabled = true)
    }

    /**
     * Removes / revokes the granted folder permission and clears the saved setting.
     */
    fun removeGrantedFolder(context: Context, uriString: String? = null) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val targetUriStr = uriString ?: prefs.getString(KEY_SELECTED_FOLDER_URI, null)
        if (!targetUriStr.isNullOrEmpty()) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.releasePersistableUriPermission(Uri.parse(targetUriStr), flags)
            } catch (_: Exception) {
            }
        }
        // Also release any remaining tree URI permissions so state is completely clean
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.persistedUriPermissions.forEach { perm ->
                if (isTreeUriSafe(perm.uri)) {
                    try {
                        context.contentResolver.releasePersistableUriPermission(perm.uri, flags)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        prefs.edit().remove(KEY_SELECTED_FOLDER_URI).apply()
    }

    /**
     * Fast non-blocking lookup that reads only the cached SharedPreferences URI without
     * invoking a synchronous Binder IPC call to ContentResolver.persistedUriPermissions.
     */
    fun getGrantedFolderInfoFast(context: Context): GrantedFolderInfo? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedUriStr = prefs.getString(KEY_SELECTED_FOLDER_URI, null)
        if (!savedUriStr.isNullOrEmpty()) {
            return parseGrantedFolderUri(Uri.parse(savedUriStr), true)
        }
        return null
    }

    /**
     * Returns the currently granted persistent folder (if any), checking both SharedPreferences
     * and Android's persisted DocumentTree URI permissions.
     */
    fun getGrantedFolderInfo(context: Context): GrantedFolderInfo? {
        getGrantedFolderInfoFast(context)?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        try {
            val persistedTrees = context.contentResolver.persistedUriPermissions.filter { isTreeUriSafe(it.uri) }
            if (persistedTrees.isNotEmpty()) {
                val latest = persistedTrees.first()
                prefs.edit().putString(KEY_SELECTED_FOLDER_URI, latest.uri.toString()).apply()
                return parseGrantedFolderUri(latest.uri, latest.isWritePermission)
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun getPersistedUrisCount(context: Context): Int {
        return if (getGrantedFolderInfo(context) != null) 1 else 0
    }

    private fun isTreeUriSafe(uri: Uri): Boolean {
        return try {
            DocumentsContract.isTreeUri(uri) || uri.toString().contains("/tree/")
        } catch (_: Throwable) {
            uri.toString().contains("/tree/")
        }
    }

    fun parseGrantedFolderUri(uri: Uri, isWriteEnabled: Boolean = true): GrantedFolderInfo {
        val rawStr = try {
            URLDecoder.decode(uri.toString(), "UTF-8")
        } catch (_: Exception) {
            uri.toString()
        }

        val treePart = rawStr.substringAfter("/tree/", rawStr.substringAfterLast('/', rawStr))
            .substringBefore("/document/")

        val volumeAndRel = if (treePart.contains(':')) {
            val vol = treePart.substringBefore(':')
            val rel = treePart.substringAfter(':').trim('/')
            val volLabel = when (vol.lowercase(Locale.US)) {
                "primary" -> "Internal Storage"
                "home" -> "Documents"
                else -> "Storage ($vol)"
            }
            if (rel.isEmpty()) {
                volLabel to volLabel
            } else {
                val folderName = rel.substringAfterLast('/')
                val fullReadable = "$volLabel / ${rel.replace("/", " / ")}"
                folderName to fullReadable
            }
        } else {
            val clean = treePart.trim('/').ifEmpty { "Selected Folder" }
            clean.substringAfterLast('/') to clean.replace("/", " / ")
        }

        return GrantedFolderInfo(
            uriString = uri.toString(),
            displayName = volumeAndRel.first.ifEmpty { "Workspace Folder" },
            readablePath = volumeAndRel.second.ifEmpty { rawStr },
            isWriteEnabled = isWriteEnabled
        )
    }

    fun queryUriSize(context: Context, uri: Uri): Long {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (idx != -1 && !cursor.isNull(idx)) {
                            val size = cursor.getLong(idx)
                            if (size > 0L) return size
                        }
                    }
                }
            } catch (_: Exception) {
            }
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    if (pfd.statSize > 0) return pfd.statSize
                    FileInputStream(pfd.fileDescriptor).use { fis ->
                        val chSize = fis.channel.size()
                        if (chSize > 0L) return chSize
                    }
                }
            } catch (_: Exception) {
            }
        } else if (uri.scheme == "file") {
            val path = uri.path
            if (!path.isNullOrEmpty()) {
                val f = File(path)
                if (f.exists()) return f.length()
            }
        }
        return 0L
    }

    fun copyUriToLocalFile(context: Context, uri: Uri, destDir: File): File? {
        return try {
            takePersistablePermission(context, uri)
            val fileName = queryFileName(context, uri) ?: "imported_${System.currentTimeMillis()}.zip"
            val sanitizedName = fileName.replace(Regex("[^a-zA-Z0-9._\\- ()]"), "_")
            val destFile = File(destDir, sanitizedName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            destFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun reassembleSplitVolumesInPlace(firstVolumeFile: File): File {
        val name = firstVolumeFile.name
        if (!name.matches(Regex(".*\\.\\d{3}$"))) return firstVolumeFile

        val baseName = name.substringBeforeLast('.')
        val parent = firstVolumeFile.parentFile ?: return firstVolumeFile
        val mergedFile = File(parent, if (baseName.endsWith(".zip", true)) baseName else "$baseName.zip")

        if (mergedFile.exists()) mergedFile.delete()
        firstVolumeFile.renameTo(mergedFile)

        val buffer = ByteArray(64 * 1024)
        var partIndex = 2
        FileOutputStream(mergedFile, true).use { mergedOut ->
            while (true) {
                val ext = String.format(Locale.US, "%03d", partIndex)
                val partFile = File(parent, "$baseName.$ext")
                if (!partFile.exists()) break

                FileInputStream(partFile).use { partIn ->
                    var r: Int
                    while (partIn.read(buffer).also { r = it } != -1) {
                        mergedOut.write(buffer, 0, r)
                    }
                }
                partFile.delete()
                partIndex++
            }
        }
        return mergedFile
    }

    fun exportFileToUri(
        context: Context,
        sourceFile: File,
        targetUri: Uri,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)? = null
    ): Boolean {
        return exportFileOrUri(context, sourceFile = sourceFile, sourceUri = null, targetUri = targetUri, fileName = sourceFile.name, onProgress = onProgress)
    }

    fun exportFileOrUri(
        context: Context,
        sourceFile: File?,
        sourceUri: Uri?,
        targetUri: Uri,
        fileName: String,
        onProgress: ((bytesCopied: Long, totalBytes: Long) -> Unit)? = null
    ): Boolean {
        return try {
            val totalBytes = when {
                sourceFile != null && sourceFile.exists() -> sourceFile.length()
                sourceUri != null -> queryUriSize(context, sourceUri)
                else -> 0L
            }

            val inputStream = when {
                sourceFile != null && sourceFile.exists() -> FileInputStream(sourceFile)
                sourceUri != null -> context.contentResolver.openInputStream(sourceUri)
                else -> null
            } ?: return false

            inputStream.use { input ->
                context.contentResolver.openOutputStream(targetUri)?.use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var copied = 0L
                    var read: Int
                    var lastReport = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        copied += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 150L || copied >= totalBytes) {
                            lastReport = now
                            onProgress?.invoke(copied, totalBytes)
                        }
                    }
                    output.flush()
                } ?: return false
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun shareFile(context: Context, file: File) {
        shareFileOrUri(context, file = file, uri = null, fileName = file.name)
    }

    fun shareFileOrUri(
        context: Context,
        file: File?,
        uri: Uri?,
        fileName: String
    ) {
        try {
            val targetUri = uri ?: file?.let {
                if (!it.exists()) {
                    Toast.makeText(context, context.getString(R.string.toast_file_not_found), Toast.LENGTH_SHORT).show()
                    return
                }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
            } ?: run {
                Toast.makeText(context, context.getString(R.string.toast_file_not_found), Toast.LENGTH_SHORT).show()
                return
            }

            val ext = fileName.substringAfterLast('.', "")
            val mimeType = getMimeTypeFromExtension(ext)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, targetUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, context.getString(R.string.sheet_viewer_share_chooser, fileName)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.toast_unable_to_share, e.localizedMessage ?: ""),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun openFileWithSystem(context: Context, file: File): Boolean {
        return openFileOrUriWithSystem(context, file = file, uri = null, fileName = file.name)
    }

    fun openExtractedFileEntryWithSystem(context: Context, entry: ExtractedFileEntry): Boolean {
        return openFileOrUriWithSystem(
            context = context,
            file = entry.localFile,
            uri = entry.safUri,
            fileName = entry.name
        )
    }

    /**
     * Resolves a safe `content://` URI for opening an extracted file externally.
     * Verifies the underlying local file or SAF document exists and is readable, and never
     * returns a raw `file://` URI.
     */
    fun resolveOpenContentUri(
        context: Context,
        file: File?,
        uri: Uri?
    ): Uri? {
        if (file != null && file.exists() && file.isFile && file.canRead()) {
            try {
                return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (_: Throwable) {
                // Fall through to SAF URI if available
            }
        }
        if (uri != null) {
            if (uri.scheme.equals("file", ignoreCase = true)) {
                val rawPath = uri.path
                if (!rawPath.isNullOrBlank()) {
                    val f = File(rawPath)
                    if (f.exists() && f.isFile && f.canRead()) {
                        return try {
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }
                return null
            }
            if (uri.scheme.equals("content", ignoreCase = true)) {
                val accessible = try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
                } catch (_: Throwable) {
                    false
                }
                if (accessible) return uri
            }
        }
        return null
    }

    /**
     * Determines the MIME type for external `ACTION_VIEW` intents from a file name's extension,
     * falling back to a wildcard MIME type when the extension is missing or unknown.
     */
    fun resolveOpenIntentMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").trim().lowercase(Locale.US)
        if (ext.isEmpty()) return "*/*"
        val detected = getMimeTypeFromExtension(ext)
        return if (detected == "application/octet-stream" && ext != "bin") "*/*" else detected
    }

    /**
     * Dispatches `Intent.ACTION_VIEW` with a safe `content://` URI (`FileProvider` or SAF),
     * `FLAG_GRANT_READ_URI_PERMISSION`, and an extension-derived MIME type (with wildcard fallback).
     */
    fun openFileOrUriWithSystem(
        context: Context,
        file: File?,
        uri: Uri?,
        fileName: String
    ): Boolean {
        return try {
            val targetUri = resolveOpenContentUri(context, file, uri)
            if (targetUri == null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_unable_to_open_missing, fileName),
                    Toast.LENGTH_SHORT
                ).show()
                return false
            }

            val mimeType = resolveOpenIntentMimeType(fileName)
            val clip = android.content.ClipData.newRawUri(fileName, targetUri)

            fun buildViewIntent(type: String): Intent {
                return Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(targetUri, type)
                    clipData = clip
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (context !is android.app.Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            }

            val primaryIntent = buildViewIntent(mimeType)
            val pm = context.packageManager
            val canResolvePrimary = pm.resolveActivity(primaryIntent, 0) != null
            val effectiveIntent = if (canResolvePrimary || mimeType == "*/*") {
                primaryIntent
            } else {
                buildViewIntent("*/*")
            }

            if (!canResolvePrimary && pm.resolveActivity(effectiveIntent, 0) == null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_no_app_to_open, fileName),
                    Toast.LENGTH_SHORT
                ).show()
                return false
            }

            val chooser = Intent.createChooser(
                effectiveIntent,
                context.getString(R.string.chooser_open_file, fileName)
            ).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            context.startActivity(chooser)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(
                context,
                context.getString(R.string.toast_no_app_to_open, fileName),
                Toast.LENGTH_SHORT
            ).show()
            false
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(
                    R.string.toast_unable_to_open_error,
                    fileName,
                    e.localizedMessage ?: "unknown error"
                ),
                Toast.LENGTH_SHORT
            ).show()
            false
        }
    }

    fun getMimeType(file: File): String {
        return getMimeTypeFromExtension(file.extension)
    }

    fun getMimeTypeFromExtension(extension: String): String {
        val ext = extension.trim().lowercase(Locale.US)
        when (ext) {
            "iso" -> return "application/x-iso9660-image"
            "img" -> return "application/x-raw-disk-image"
            "chd" -> return "application/x-chd-disc-image"
            "cso" -> return "application/x-cso-compressed-iso"
            "bin" -> return "application/octet-stream"
            "cue" -> return "application/x-cue"
            "rom" -> return "application/x-game-rom"
            "pbp" -> return "application/x-psp-game"
            "nds" -> return "application/x-nintendo-ds-rom"
            "gba" -> return "application/x-gba-rom"
            "sfc", "smc" -> return "application/x-snes-rom"
            "nes" -> return "application/x-nes-rom"
            "gcm", "wbfs" -> return "application/x-gamecube-rom"
            "zip", "jar" -> return "application/zip"
            "7z" -> return "application/x-7z-compressed"
            "rar" -> return "application/vnd.rar"
            "apk", "xapk", "apks" -> return "application/vnd.android.package-archive"
            "tar" -> return "application/x-tar"
            "gz", "tgz" -> return "application/gzip"
            "txt", "log", "md", "csv", "py", "kt", "java", "sh", "ini", "cfg", "gradle", "properties" -> return "text/plain"
            "json" -> return "application/json"
            "xml" -> return "application/xml"
            "html", "htm" -> return "text/html"
            "css" -> return "text/css"
            "js", "mjs" -> return "text/javascript"
            "jpg", "jpeg" -> return "image/jpeg"
            "png" -> return "image/png"
            "webp" -> return "image/webp"
            "gif" -> return "image/gif"
            "bmp" -> return "image/bmp"
            "svg" -> return "image/svg+xml"
            "heic" -> return "image/heic"
            "heif" -> return "image/heif"
            "mp3" -> return "audio/mpeg"
            "wav" -> return "audio/wav"
            "ogg", "oga" -> return "audio/ogg"
            "flac" -> return "audio/flac"
            "m4a", "aac" -> return "audio/mp4"
            "opus" -> return "audio/opus"
            "mp4", "m4v" -> return "video/mp4"
            "mkv" -> return "video/x-matroska"
            "webm" -> return "video/webm"
            "avi" -> return "video/x-msvideo"
            "mov" -> return "video/quicktime"
            "3gp" -> return "video/3gpp"
            "pdf" -> return "application/pdf"
            "epub" -> return "application/epub+zip"
            "rtf" -> return "application/rtf"
            "doc" -> return "application/msword"
            "docx" -> return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> return "application/vnd.ms-excel"
            "xlsx" -> return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> return "application/vnd.ms-powerpoint"
            "pptx" -> return "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        }
        return try {
            MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        } catch (e: Throwable) {
            "application/octet-stream"
        }
    }

    fun extractSingleZipEntry(zipFile: File, entryPath: String, destDir: File): File? {
        if (!destDir.exists()) destDir.mkdirs()
        var candidateOut: File? = null
        return try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(entryPath) ?: return null
                if (entry.isDirectory) return null
                val cleanName = entry.name.substringAfterLast('/')
                val outFile = File(destDir, cleanName.ifEmpty { "extracted_entry" })
                candidateOut = outFile
                val crc = java.util.zip.CRC32()
                var bytesWritten = 0L
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val r = input.read(buf)
                            if (r <= 0) break
                            output.write(buf, 0, r)
                            crc.update(buf, 0, r)
                            bytesWritten += r
                        }
                        output.flush()
                    }
                }
                val expectedSize = entry.size
                val expectedCrc = entry.crc
                if (expectedSize >= 0L && bytesWritten != expectedSize) {
                    outFile.delete()
                    return null
                }
                if (expectedCrc >= 0L && expectedCrc != 0L && crc.value != expectedCrc) {
                    outFile.delete()
                    return null
                }
                outFile
            }
        } catch (e: Exception) {
            candidateOut?.let { if (it.exists()) it.delete() }
            null
        }
    }

    /**
     * Universal Zero-Copy Entry Previewer:
     * Reads an entry from either a local File OR a Zero-Copy SAF Uri (ZIP, APK, ZIP64, 7Z, ZipCrypto, or TAR).
     */
    fun readArchiveEntryPreview(
        context: Context,
        archiveFile: File?,
        safUri: Uri?,
        entry: ZipEntryInfo,
        password: String? = null,
        maxBytes: Int = 8192
    ): FilePreviewData {
        if (entry.isDirectory) {
            return FilePreviewData(
                fileName = entry.name,
                sizeBytes = 0L,
                isBinary = false,
                mimeType = "inode/directory",
                contentPreview = "Directory entry"
            )
        }

        // Fast path for standard non-encrypted local ZIP
        if (archiveFile != null && !entry.isEncrypted && entry.compressionMethod !in listOf(70, 100) && !entry.isZip64) {
            return readZipEntryPreview(archiveFile, entry.path, maxBytes)
        }

        return try {
            val cleanName = entry.name.substringAfterLast('/')
            val ext = cleanName.substringAfterLast('.', "")
            val mime = getMimeTypeFromExtension(ext)
            val isImg = mime.startsWith("image/") && entry.uncompressedSize in 1..(4 * 1024 * 1024)
            val readCap = if (isImg) entry.uncompressedSize.toInt() else maxBytes

            val extractedSlice: ByteArray? = if (archiveFile != null) {
                RandomAccessFile(archiveFile, "r").use { raf ->
                    readEntryBytesFromChannel(raf.channel, entry, password, readCap)
                }
            } else if (safUri != null) {
                context.contentResolver.openFileDescriptor(safUri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { fis ->
                        readEntryBytesFromChannel(fis.channel, entry, password, readCap)
                    }
                }
            } else null

            if (extractedSlice == null) {
                return FilePreviewData(cleanName, entry.uncompressedSize, false, mimeType = mime, contentPreview = "Unable to open archive channel for preview.")
            }

            val previewBytes = if (extractedSlice.size > maxBytes) extractedSlice.copyOf(maxBytes) else extractedSlice
            buildPreviewFromBytes(
                fileName = cleanName,
                totalSize = entry.uncompressedSize.coerceAtLeast(extractedSlice.size.toLong()),
                mimeType = mime,
                bytes = previewBytes,
                isImage = isImg,
                imageBytes = if (isImg) extractedSlice else null
            )
        } catch (e: Exception) {
            FilePreviewData(
                fileName = entry.name.substringAfterLast('/'),
                sizeBytes = entry.uncompressedSize,
                isBinary = false,
                mimeType = "text/plain",
                contentPreview = "Preview error: ${e.message}"
            )
        }
    }

    /**
     * Extracts a single entry from either a local File or Zero-Copy SAF Uri for sharing/saving.
     */
    fun extractSingleArchiveEntry(
        context: Context,
        archiveFile: File?,
        safUri: Uri?,
        entry: ZipEntryInfo,
        destDir: File,
        password: String? = null
    ): File? {
        if (entry.isDirectory) return null
        if (archiveFile != null && !entry.isEncrypted && entry.compressionMethod !in listOf(70, 100) && !entry.isZip64) {
            return extractSingleZipEntry(archiveFile, entry.path, destDir)
        }

        var candidateOut: File? = null
        return try {
            if (!destDir.exists()) destDir.mkdirs()
            val cleanName = entry.name.substringAfterLast('/').ifEmpty { "extracted_entry" }
            val outFile = File(destDir, cleanName)
            candidateOut = outFile
            val cap = entry.uncompressedSize.coerceAtMost(64L * 1024L * 1024L).toInt()

            val bytes = if (archiveFile != null) {
                RandomAccessFile(archiveFile, "r").use { raf ->
                    readEntryBytesFromChannel(raf.channel, entry, password, cap)
                }
            } else if (safUri != null) {
                context.contentResolver.openFileDescriptor(safUri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { fis ->
                        readEntryBytesFromChannel(fis.channel, entry, password, cap)
                    }
                }
            } else null

            if (bytes != null) {
                if (entry.uncompressedSize in 0L..(64L * 1024L * 1024L) && bytes.size.toLong() != entry.uncompressedSize) {
                    return null
                }
                if (entry.crc32 != 0L && entry.uncompressedSize in 1L..(64L * 1024L * 1024L)) {
                    val crc = java.util.zip.CRC32()
                    crc.update(bytes)
                    if (crc.value != entry.crc32) {
                        return null
                    }
                }
                outFile.writeBytes(bytes)
                outFile
            } else null
        } catch (e: Exception) {
            candidateOut?.let { if (it.exists()) it.delete() }
            null
        }
    }

    private fun readLocalHeaderDataOffset(channel: FileChannel, localHeaderOffset: Long): Long {
        val headerBytes = ByteArray(30)
        val buf = ByteBuffer.wrap(headerBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        channel.position(localHeaderOffset)
        while (buf.hasRemaining()) {
            val r = channel.read(buf)
            if (r <= 0) break
        }
        buf.flip()
        buf.position(26)
        val fileNameLen = buf.short.toInt() and 0xFFFF
        val extraLen = buf.short.toInt() and 0xFFFF
        return localHeaderOffset + 30L + fileNameLen + extraLen
    }

    private fun readEntryBytesFromChannel(
        channel: FileChannel,
        entry: ZipEntryInfo,
        password: String?,
        maxBytesToRead: Int
    ): ByteArray {
        if (entry.compressionMethod == 70) {
            // 7Z entry preview via CommonsCompressBufferStrategy buffered SevenZFile
            return try {
                channel.position(0L)
                com.biplov.kaihatch.engine.CommonsCompressBufferStrategy.openBufferedSevenZFile(
                    channel = channel,
                    archiveName = "Archive.7z",
                    password = password,
                    closeChannelOnClose = false
                ).use { archive ->
                    while (true) {
                        val next = archive.nextEntry ?: break
                        val norm = next.name?.replace('\\', '/')?.trimStart('/') ?: ""
                        if (norm == entry.path || norm.substringAfterLast('/') == entry.name) {
                            val cap = maxBytesToRead.coerceIn(1, 8 * 1024 * 1024)
                            val buf = ByteArray(cap)
                            var totalRead = 0
                            while (totalRead < cap) {
                                val r = archive.read(buf, totalRead, cap - totalRead)
                                if (r <= 0) break
                                totalRead += r
                            }
                            return buf.copyOf(totalRead)
                        }
                    }
                }
                "7z Solid Container Entry (${entry.name} • ${formatBytes(entry.uncompressedSize)})".toByteArray(Charsets.UTF_8)
            } catch (e: Throwable) {
                "7z Entry Preview: ${entry.name} (${formatBytes(entry.uncompressedSize)})".toByteArray(Charsets.UTF_8)
            }
        }

        val dataOffset = if (entry.compressionMethod == 100) {
            // TAR entry: payload starts 512 bytes after header
            entry.localHeaderOffset + 512L
        } else {
            readLocalHeaderDataOffset(channel, entry.localHeaderOffset)
        }

        val boundedIn = ChannelSliceInputStream(channel, dataOffset, entry.compressedSize)
        val decryptedStream: InputStream = if (entry.isEncrypted) {
            val pwd = password ?: throw IllegalStateException("Password required to preview encrypted entry.")
            val cipher = ZipCryptoCipher(pwd)
            val header = ByteArray(12)
            var r = 0
            while (r < 12) {
                val n = boundedIn.read(header, r, 12 - r)
                if (n <= 0) break
                r += n
            }
            cipher.decryptHeaderAndVerify(header, entry.crc32)
            object : InputStream() {
                override fun read(): Int {
                    val b = boundedIn.read()
                    if (b == -1) return -1
                    val arr = byteArrayOf(b.toByte())
                    cipher.decryptBytesInPlace(arr, 0, 1)
                    return arr[0].toInt() and 0xFF
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val count = boundedIn.read(b, off, len)
                    if (count > 0) cipher.decryptBytesInPlace(b, off, count)
                    return count
                }
            }
        } else {
            boundedIn
        }

        val inflater = if (entry.compressionMethod == 8) Inflater(true) else null
        val payloadStream: InputStream = if (inflater != null) {
            InflaterInputStream(decryptedStream, inflater, 16 * 1024)
        } else {
            decryptedStream
        }

        return try {
            val buffer = ByteArray(maxBytesToRead)
            var totalRead = 0
            while (totalRead < maxBytesToRead) {
                val count = payloadStream.read(buffer, totalRead, maxBytesToRead - totalRead)
                if (count <= 0) break
                totalRead += count
            }
            buffer.copyOf(totalRead)
        } finally {
            inflater?.end()
        }
    }

    fun createSliceStream(channel: FileChannel, startOffset: Long, length: Long): InputStream {
        return ChannelSliceInputStream(channel, startOffset, length)
    }

    class ChannelSliceInputStream(
        private val channel: FileChannel,
        startOffset: Long,
        private val length: Long
    ) : InputStream() {
        private var currentPos = startOffset
        private var remaining = length

        override fun read(): Int {
            if (remaining <= 0) return -1
            val buf = ByteBuffer.allocate(1)
            val r = channel.read(buf, currentPos)
            if (r <= 0) return -1
            currentPos += 1
            remaining -= 1
            buf.flip()
            return buf.get().toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = len.toLong().coerceAtMost(remaining).toInt()
            val buf = ByteBuffer.wrap(b, off, toRead)
            val r = channel.read(buf, currentPos)
            if (r <= 0) return -1
            currentPos += r
            remaining -= r
            return r
        }
    }

    fun readFilePreview(file: File, maxBytes: Int = 8192): FilePreviewData {
        if (!file.exists() || file.isDirectory) {
            return FilePreviewData(
                fileName = file.name,
                sizeBytes = 0L,
                isBinary = false,
                mimeType = "text/plain",
                contentPreview = "Directory or unavailable file."
            )
        }
        return try {
            val mime = getMimeType(file)
            val isImg = mime.startsWith("image/") && file.length() <= 5 * 1024 * 1024
            val imgBytes = if (isImg) file.readBytes() else null

            val bytesToRead = file.length().coerceAtMost(maxBytes.toLong()).toInt()
            val buffer = ByteArray(bytesToRead)
            FileInputStream(file).use { it.read(buffer) }
            buildPreviewFromBytes(file.name, file.length(), mime, buffer, isImg, imgBytes)
        } catch (e: Exception) {
            FilePreviewData(
                fileName = file.name,
                sizeBytes = file.length(),
                isBinary = false,
                mimeType = "text/plain",
                contentPreview = "Could not read preview: ${e.message}"
            )
        }
    }

    fun readZipEntryPreview(zipFile: File, entryPath: String, maxBytes: Int = 8192): FilePreviewData {
        return try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(entryPath)
                    ?: return FilePreviewData(entryPath, 0L, false, mimeType = "text/plain", contentPreview = "Entry not found in archive.")
                if (entry.isDirectory) {
                    return FilePreviewData(entry.name, 0L, false, mimeType = "inode/directory", contentPreview = "Directory entry")
                }
                val cleanName = entry.name.substringAfterLast('/')
                val ext = cleanName.substringAfterLast('.', "")
                val mime = getMimeTypeFromExtension(ext)
                val isImg = mime.startsWith("image/") && entry.size in 1..(4 * 1024 * 1024)

                val readCap = if (isImg) entry.size.toInt() else maxBytes
                val buffer = ByteArray(readCap)
                var totalRead = 0
                zip.getInputStream(entry).use { input ->
                    while (totalRead < readCap) {
                        val r = input.read(buffer, totalRead, readCap - totalRead)
                        if (r <= 0) break
                        totalRead += r
                    }
                }
                val slice = buffer.copyOf(totalRead)
                val previewBytes = if (slice.size > maxBytes) slice.copyOf(maxBytes) else slice
                buildPreviewFromBytes(
                    fileName = cleanName,
                    totalSize = entry.size.coerceAtLeast(totalRead.toLong()),
                    mimeType = mime,
                    bytes = previewBytes,
                    isImage = isImg,
                    imageBytes = if (isImg) slice else null
                )
            }
        } catch (e: Exception) {
            FilePreviewData(
                fileName = entryPath.substringAfterLast('/'),
                sizeBytes = 0L,
                isBinary = false,
                mimeType = "text/plain",
                contentPreview = "Unable to preview entry: ${e.message}"
            )
        }
    }

    private fun buildPreviewFromBytes(
        fileName: String,
        totalSize: Long,
        mimeType: String,
        bytes: ByteArray,
        isImage: Boolean = false,
        imageBytes: ByteArray? = null
    ): FilePreviewData {
        if (bytes.isEmpty()) {
            return FilePreviewData(fileName, totalSize, false, isImage, imageBytes, mimeType, "(Empty file - 0 bytes)")
        }

        var suspiciousBytes = 0
        val checkLen = bytes.size.coerceAtMost(512)
        for (i in 0 until checkLen) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) {
                suspiciousBytes += 20
            } else if (b < 0x09 || (b in 0x0E..0x1F)) {
                suspiciousBytes++
            }
        }

        val isBinary = suspiciousBytes > (checkLen / 10).coerceAtLeast(2)

        return if (!isBinary) {
            val text = String(bytes, Charsets.UTF_8)
            val suffix = if (totalSize > bytes.size) "\n\n... [Preview truncated at ${formatBytes(bytes.size.toLong())} of ${formatBytes(totalSize)}]" else ""
            FilePreviewData(
                fileName = fileName,
                sizeBytes = totalSize,
                isBinary = false,
                isImage = isImage,
                imageBytes = imageBytes,
                mimeType = mimeType,
                contentPreview = text + suffix
            )
        } else {
            val dumpLimit = bytes.size.coerceAtMost(512)
            val sb = StringBuilder()
            sb.append("HEX / ASCII INSPECTOR (First $dumpLimit bytes of ${formatBytes(totalSize)})\n")
            sb.append("OFFSET    00 01 02 03 04 05 06 07  ASCII\n")
            sb.append("--------  -----------------------  --------\n")

            var offset = 0
            while (offset < dumpLimit) {
                val lineLen = (dumpLimit - offset).coerceAtMost(8)
                sb.append(String.format(Locale.US, "%08X  ", offset))
                val ascii = StringBuilder()
                for (i in 0 until 8) {
                    if (i < lineLen) {
                        val v = bytes[offset + i].toInt() and 0xFF
                        sb.append(String.format(Locale.US, "%02X ", v))
                        ascii.append(if (v in 32..126) v.toChar() else '.')
                    } else {
                        sb.append("   ")
                    }
                }
                sb.append(" ").append(ascii).append("\n")
                offset += 8
            }
            FilePreviewData(
                fileName = fileName,
                sizeBytes = totalSize,
                isBinary = true,
                isImage = isImage,
                imageBytes = imageBytes,
                mimeType = mimeType,
                contentPreview = sb.toString()
            )
        }
    }

    fun queryFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            val name = cursor.getString(index)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            } catch (e: Exception) {
                // fall through
            }
        }
        val decodedLast = try {
            uri.lastPathSegment?.let { URLDecoder.decode(it, "UTF-8") }
        } catch (_: Exception) {
            uri.lastPathSegment
        }
        return decodedLast?.substringAfterLast('/')?.substringAfterLast(':')
    }

    fun getDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) getDirectorySize(file) else file.length()
        }
        return size
    }

    fun resolveSafeDestFile(targetDir: File, relativePath: String): File {
        val cleanPath = relativePath.replace('\\', '/').trimStart('/')
        val destFile = File(targetDir, cleanPath)
        val canonicalTarget = targetDir.canonicalPath
        val canonicalDest = destFile.canonicalPath
        if (!canonicalDest.startsWith(canonicalTarget)) {
            throw SecurityException("Malicious path traversal detected: $relativePath")
        }
        return destFile
    }

    private val safDirUriCache = java.util.concurrent.ConcurrentHashMap<String, Uri>()
    private val safDirLock = Any()
    private val posixDirLock = Any()

    fun clearSafDirCache() {
        safDirUriCache.clear()
    }

    @Volatile
    internal var allFilesAccessOverrideForTest: Boolean? = null

    @Volatile
    internal var forceFsyncFailureForTest: ((FileDescriptor?) -> Boolean)? = null

    @Volatile
    internal var externalStorageDirOverrideForTest: File? = null

    fun hasAllFilesAccess(): Boolean {
        allFilesAccessOverrideForTest?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            Environment.isExternalStorageManager()
        } catch (_: Throwable) {
            false
        }
    }

    fun isAllFilesAccessSupportedOnDevice(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun createManageAllFilesAccessIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * Strictly flushes kernel page cache & metadata via `FileDescriptor.sync()` (or `FileChannel.force(true)`).
     * Never silently swallows errors — returns `false` if `fsync` fails or is unsupported.
     */
    fun syncFileDescriptorStrict(fd: FileDescriptor?, channel: FileChannel?): Boolean {
        forceFsyncFailureForTest?.let { hook ->
            if (hook(fd)) return false
        }
        if (fd != null) {
            if (!fd.valid()) return false
            return try {
                fd.sync()
                true
            } catch (_: Throwable) {
                false
            }
        }
        if (channel != null) {
            return try {
                channel.force(true)
                true
            } catch (_: Throwable) {
                false
            }
        }
        return false
    }

    /**
     * Probes whether [fd] / [channel] is a real seekable regular file descriptor supporting
     * positional read, non-destructive `pwrite`, `ftruncate`, and strict `fsync`.
     * Fails closed for pipes, sockets, read-only descriptors, and non-syncable/proxy providers.
     */
    fun probeTransactableFileDescriptor(
        fd: FileDescriptor,
        channel: FileChannel?,
        requireWriteAndTruncate: Boolean = true
    ): TransactableDescriptorProbe {
        if (!fd.valid()) {
            return TransactableDescriptorProbe(
                isSupported = false,
                sizeBytes = -1L,
                reason = "File descriptor is invalid or already closed."
            )
        }

        var statSize = -1L
        var realPosixOsAvailable = false
        try {
            val st = Os.fstat(fd)
            if (st != null && st.st_mode != 0) {
                realPosixOsAvailable = true
                if (!OsConstants.S_ISREG(st.st_mode)) {
                    return TransactableDescriptorProbe(
                        isSupported = false,
                        sizeBytes = -1L,
                        reason = "File descriptor is not a regular seekable file (pipe, stream, or virtual provider)."
                    )
                }
                statSize = st.st_size
            }
        } catch (e: android.system.ErrnoException) {
            val msg = e.localizedMessage ?: e.javaClass.simpleName
            return TransactableDescriptorProbe(
                isSupported = false,
                sizeBytes = -1L,
                reason = "fstat failed on file descriptor ($msg)."
            )
        } catch (_: LinkageError) {
            // Host JVM unit tests without Bionic Os linkage fall through to channel checks.
        } catch (_: UnsupportedOperationException) {
            // Host JVM unit tests without shadowed Os fall through to channel checks.
        }

        val resolvedSize = try {
            channel?.size()?.takeIf { it >= 0L } ?: statSize
        } catch (t: Throwable) {
            if (!realPosixOsAvailable) {
                return TransactableDescriptorProbe(
                    isSupported = false,
                    sizeBytes = -1L,
                    reason = "File descriptor is not a regular seekable file (${t.localizedMessage ?: t.javaClass.simpleName})."
                )
            }
            statSize
        }
        if (resolvedSize < 0L) {
            return TransactableDescriptorProbe(
                isSupported = false,
                sizeBytes = -1L,
                reason = "File descriptor does not expose a valid seekable size."
            )
        }

        // Verify seekability even when resolvedSize == 0 (catches pipes/FIFOs when Bionic Os.fstat is unshadowed)
        if (!realPosixOsAvailable && channel != null) {
            val seekOk = try {
                val cur = channel.position()
                channel.position(cur)
                true
            } catch (_: Throwable) {
                false
            }
            if (!seekOk) {
                return TransactableDescriptorProbe(
                    isSupported = false,
                    sizeBytes = -1L,
                    reason = "File descriptor is not seekable (pipe, stream, or virtual provider)."
                )
            }
        }

        val probeByte = ByteArray(1)
        if (resolvedSize > 0L) {
            val readOk = try {
                val readViaChannel = if (channel != null) {
                    val bb = ByteBuffer.wrap(probeByte)
                    channel.read(bb, 0L) == 1
                } else false
                if (readViaChannel) {
                    true
                } else if (realPosixOsAvailable) {
                    Os.pread(fd, probeByte, 0, 1, 0L) == 1
                } else {
                    false
                }
            } catch (_: LinkageError) {
                false
            } catch (t: Throwable) {
                return TransactableDescriptorProbe(
                    isSupported = false,
                    sizeBytes = resolvedSize,
                    reason = "File descriptor does not support positional seek/read (${t.localizedMessage ?: t.javaClass.simpleName})."
                )
            }
            if (!readOk) {
                return TransactableDescriptorProbe(
                    isSupported = false,
                    sizeBytes = resolvedSize,
                    reason = "File descriptor failed positional read probe at offset 0."
                )
            }
        }

        if (requireWriteAndTruncate) {
            // 1. Non-destructive pwrite probe at offset 0 when file is non-empty
            if (resolvedSize > 0L) {
                var wrote = false
                if (channel != null) {
                    try {
                        val bb = ByteBuffer.wrap(probeByte)
                        if (channel.write(bb, 0L) == 1) {
                            wrote = true
                        }
                    } catch (_: Throwable) {
                    }
                }
                if (!wrote) {
                    try {
                        val outCh = FileOutputStream(fd).channel
                        val bb = ByteBuffer.wrap(probeByte)
                        if (outCh.write(bb, 0L) == 1) {
                            wrote = true
                        }
                    } catch (_: Throwable) {
                    }
                }
                if (!wrote && realPosixOsAvailable) {
                    try {
                        if (Os.pwrite(fd, probeByte, 0, 1, 0L) == 1) {
                            wrote = true
                        }
                    } catch (_: Throwable) {
                    }
                }
                if (!wrote) {
                    return TransactableDescriptorProbe(
                        isSupported = false,
                        sizeBytes = resolvedSize,
                        reason = "File descriptor does not support positional in-place write (pwrite)."
                    )
                }
            }

            // 2. Non-destructive ftruncate probe at current size
            var truncated = false
            if (channel != null) {
                try {
                    channel.truncate(resolvedSize)
                    truncated = true
                } catch (_: Throwable) {
                }
            }
            if (!truncated) {
                try {
                    FileOutputStream(fd).channel.truncate(resolvedSize)
                    truncated = true
                } catch (_: Throwable) {
                }
            }
            if (!truncated && realPosixOsAvailable) {
                try {
                    Os.ftruncate(fd, resolvedSize)
                    truncated = true
                } catch (_: Throwable) {
                }
            }
            if (!truncated) {
                return TransactableDescriptorProbe(
                    isSupported = false,
                    sizeBytes = resolvedSize,
                    reason = "File descriptor does not support in-place truncation (ftruncate)."
                )
            }

            // 3. Strict fsync probe — never allow transactional mutations without durable fsync
            if (!syncFileDescriptorStrict(fd, channel)) {
                return TransactableDescriptorProbe(
                    isSupported = false,
                    sizeBytes = resolvedSize,
                    reason = "File descriptor does not support durable fsync."
                )
            }
        }

        return TransactableDescriptorProbe(
            isSupported = true,
            sizeBytes = resolvedSize,
            reason = null
        )
    }

    private fun resolveExternalStorageRoot(): File =
        externalStorageDirOverrideForTest ?: Environment.getExternalStorageDirectory()

    private fun resolveDocumentsPublicRoot(): File =
        externalStorageDirOverrideForTest?.let { File(it, Environment.DIRECTORY_DOCUMENTS) }
            ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)

    private fun resolveDownloadsPublicRoot(): File =
        externalStorageDirOverrideForTest?.let { File(it, Environment.DIRECTORY_DOWNLOADS) }
            ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /**
     * Resolves a source archive [Uri] to a direct-POSIX [File] ONLY when:
     * 1. Direct filesystem access is permitted (`file://`, or `hasAllFilesAccess()` on Android 11+, or API < 30),
     * 2. The URI belongs to a known local storage provider (`externalstorage`, `downloads`, `media`),
     * 3. The resolved file exists, is a regular file, is readable & writable, and passes `fsync`, AND
     * 4. File identity is strictly proven against the opened URI descriptor (`st_size` match AND
     *    either exact kernel `st_dev`+`st_ino` match or exact head/middle/tail 8 KB byte-for-byte + mtime match).
     *
     * Never assumes a URI maps safely to a local file without identity and permission checks.
     */
    fun tryResolveVerifiedDirectSourceFile(context: Context, uri: Uri): File? {
        return try {
            if (uri.scheme.equals("file", ignoreCase = true)) {
                val path = uri.path?.takeIf { it.isNotBlank() } ?: return null
                val candidate = File(path)
                if (!candidate.exists() || !candidate.isFile || !candidate.canRead() || !candidate.canWrite()) {
                    return null
                }
                return if (verifyDirectFileWritableAndSyncable(candidate)) candidate else null
            }

            if (!uri.scheme.equals("content", ignoreCase = true)) return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()) {
                return null
            }

            val candidate = resolveCandidateLocalFileFromContentUri(context, uri) ?: return null
            if (!candidate.exists() || !candidate.isFile || !candidate.canRead() || !candidate.canWrite()) {
                return null
            }
            if (candidate.length() <= 0L) return null

            // Prove identity against the actual ContentResolver file descriptor before trusting candidate!
            val identityMatches = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                verifyDescriptorMatchesLocalFile(pfd.fileDescriptor, candidate)
            } ?: false

            if (!identityMatches) return null
            if (!verifyDirectFileWritableAndSyncable(candidate)) return null

            candidate
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveCandidateLocalFileFromContentUri(context: Context, uri: Uri): File? {
        val authority = uri.authority?.lowercase(Locale.US) ?: return null
        return when (authority) {
            "com.android.externalstorage.documents" -> {
                val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
                if (!docId.contains(':')) return null
                val vol = docId.substringBefore(':').lowercase(Locale.US)
                val rel = docId.substringAfter(':').trimStart('/')
                if (rel.isEmpty()) return null
                when (vol) {
                    "primary" -> File(resolveExternalStorageRoot(), rel)
                    "home" -> File(resolveDocumentsPublicRoot(), rel)
                    else -> null
                }
            }
            "com.android.providers.downloads.documents" -> {
                val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
                when {
                    docId.startsWith("raw:", ignoreCase = true) -> {
                        val rawPath = docId.substring(4)
                        if (rawPath.isNotBlank()) File(rawPath) else null
                    }
                    docId.startsWith("msf:", ignoreCase = true) -> {
                        val numericId = docId.substring(4).toLongOrNull() ?: return null
                        queryMediaStoreDataPath(context, MediaStore.Files.getContentUri("external"), numericId)
                            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                queryMediaStoreDataPath(context, MediaStore.Downloads.EXTERNAL_CONTENT_URI, numericId)
                            } else null
                    }
                    docId.toLongOrNull() != null -> {
                        val numericId = docId.toLong()
                        queryMediaStoreDataPath(context, MediaStore.Files.getContentUri("external"), numericId)
                            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                queryMediaStoreDataPath(context, MediaStore.Downloads.EXTERNAL_CONTENT_URI, numericId)
                            } else null
                    }
                    else -> null
                }
            }
            "media" -> {
                queryDirectDataColumn(context, uri)
            }
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun queryMediaStoreDataPath(context: Context, contentUri: Uri, id: Long): File? {
        return try {
            val projection = arrayOf(MediaStore.MediaColumns.DATA)
            context.contentResolver.query(
                contentUri,
                projection,
                "${MediaStore.MediaColumns._ID} = ?",
                arrayOf(id.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val col = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (col >= 0 && !cursor.isNull(col)) {
                        val p = cursor.getString(col)
                        if (!p.isNullOrBlank()) return File(p)
                    }
                }
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun queryDirectDataColumn(context: Context, uri: Uri): File? {
        return try {
            val projection = arrayOf(MediaStore.MediaColumns.DATA)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val col = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (col >= 0 && !cursor.isNull(col)) {
                        val p = cursor.getString(col)
                        if (!p.isNullOrBlank()) return File(p)
                    }
                }
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun verifyDirectFileWritableAndSyncable(file: File): Boolean {
        return try {
            RandomAccessFile(file, "rw").use { raf ->
                val probe = probeTransactableFileDescriptor(raf.fd, raf.channel, requireWriteAndTruncate = true)
                probe.isSupported
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun verifyDescriptorMatchesLocalFile(fd: FileDescriptor, candidate: File): Boolean {
        if (!fd.valid() || !candidate.exists() || !candidate.isFile) return false
        val fileLen = candidate.length()
        if (fileLen <= 0L) return false

        var kernelStatChecked = false
        try {
            val fdStat = Os.fstat(fd)
            val fileStat = Os.stat(candidate.absolutePath)
            if (fdStat != null && fileStat != null && fdStat.st_mode != 0 && fileStat.st_mode != 0) {
                if (!OsConstants.S_ISREG(fdStat.st_mode) || !OsConstants.S_ISREG(fileStat.st_mode)) {
                    return false
                }
                if (fdStat.st_size != fileStat.st_size || fdStat.st_size != fileLen) {
                    return false
                }
                kernelStatChecked = true
                if (fdStat.st_dev == fileStat.st_dev && fdStat.st_ino == fileStat.st_ino) {
                    return true
                }
                // Different st_dev/st_ino can happen across Android FUSE upper/lower mounts for the same file;
                // require close mtime AND exact sampled byte-for-byte content match before trusting.
                if (kotlin.math.abs(fdStat.st_mtime - fileStat.st_mtime) > 2L) {
                    return false
                }
            }
        } catch (_: android.system.ErrnoException) {
            return false
        } catch (_: LinkageError) {
            // Host JVM Robolectric fallback: verify via exact channel size + sampled byte-for-byte match.
        } catch (_: UnsupportedOperationException) {
            // Host JVM Robolectric fallback: verify via exact channel size + sampled byte-for-byte match.
        }

        return try {
            FileInputStream(fd).use { fis ->
                val ch = fis.channel
                val chSize = ch.size()
                if (chSize != fileLen) return false
                val sampleSize = minOf(8192L, fileLen).toInt()
                val offsets = linkedSetOf(
                    0L,
                    ((fileLen / 2L) - (sampleSize / 2L)).coerceAtLeast(0L),
                    (fileLen - sampleSize).coerceAtLeast(0L)
                )
                val fdBuf = ByteArray(sampleSize)
                val fileBuf = ByteArray(sampleSize)
                RandomAccessFile(candidate, "r").use { raf ->
                    for (off in offsets) {
                        val toRead = minOf(sampleSize.toLong(), fileLen - off).toInt()
                        if (toRead <= 0) continue
                        val bb = ByteBuffer.wrap(fdBuf, 0, toRead)
                        var readPos = off
                        while (bb.hasRemaining()) {
                            val r = ch.read(bb, readPos)
                            if (r <= 0) return false
                            readPos += r
                        }
                        raf.seek(off)
                        raf.readFully(fileBuf, 0, toRead)
                        for (i in 0 until toRead) {
                            if (fdBuf[i] != fileBuf[i]) return false
                        }
                    }
                }
                // Extra sanity guard if kernel stat wasn't available: confirm canonical paths don't escape
                kernelStatChecked || candidate.canonicalFile.isFile
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun tryResolveDirectTreeRootDir(treeUri: Uri): File? {
        return try {
            if (treeUri.scheme == "file") {
                val p = treeUri.path ?: return null
                val dir = File(p)
                if (!dir.exists()) dir.mkdirs()
                return if (dir.exists() && dir.isDirectory) dir else null
            }
            val authority = treeUri.authority?.lowercase(Locale.US).orEmpty()
            val treeDocId = try {
                DocumentsContract.getTreeDocumentId(treeUri)
            } catch (_: Throwable) {
                val raw = URLDecoder.decode(treeUri.toString(), "UTF-8")
                raw.substringAfter("/tree/", "").substringBefore("/document/")
            }
            val parentDir: File = when (authority) {
                "com.android.externalstorage.documents" -> {
                    if (!treeDocId.contains(':')) return null
                    val vol = treeDocId.substringBefore(':').lowercase(Locale.US)
                    val rel = treeDocId.substringAfter(':').trim('/')
                    val rootDir = when (vol) {
                        "primary" -> resolveExternalStorageRoot()
                        "home" -> resolveDocumentsPublicRoot()
                        else -> return null
                    }
                    if (rel.isEmpty()) rootDir else File(rootDir, rel)
                }
                "com.android.providers.downloads.documents" -> {
                    when {
                        treeDocId.equals("downloads", ignoreCase = true) -> resolveDownloadsPublicRoot()
                        treeDocId.startsWith("raw:", ignoreCase = true) -> {
                            val rawPath = treeDocId.substring(4)
                            if (rawPath.isBlank()) return null else File(rawPath)
                        }
                        else -> return null
                    }
                }
                else -> return null
            }
            if (!parentDir.exists()) {
                parentDir.mkdirs()
            }
            if (parentDir.exists() && parentDir.isDirectory) parentDir else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Resolves a direct POSIX [File] path for a subfolder inside a verified local DocumentTree URI
     * (e.g., `/storage/emulated/0/Download/<subfolderName>`) if the kernel FUSE mount permits
     * direct POSIX file creation and durable `fsync` by this app inside the subfolder.
     * Bypasses per-file SAF ContentResolver Binder IPC for 10x–20x higher extraction throughput.
     */
    fun tryResolveDirectWritableSubfolder(treeUri: Uri, subfolderName: String): File? {
        return try {
            val parentDir = tryResolveDirectTreeRootDir(treeUri) ?: return null
            val candidateDir = File(parentDir, subfolderName)
            if (!candidateDir.exists()) {
                candidateDir.mkdirs()
            }
            if (!candidateDir.exists() || !candidateDir.isDirectory) return null

            // Verify POSIX write + durable fsync + delete access inside the subfolder with a non-media probe file
            val probe = File(candidateDir, ".kaihatch_io_probe.bin")
            var syncOk = false
            FileOutputStream(probe).use { fos ->
                fos.write(1)
                fos.flush()
                syncOk = syncFileDescriptorStrict(fos.fd, fos.channel)
            }
            probe.delete()
            if (syncOk) candidateDir else null
        } catch (_: Throwable) {
            null
        }
    }

    fun isAppInternalFallbackDir(context: Context?, dir: File?): Boolean {
        if (context == null || dir == null) return false
        val absPath = dir.absolutePath
        if (absPath.startsWith(context.cacheDir.absolutePath) ||
            absPath.startsWith(context.filesDir.absolutePath)
        ) {
            return true
        }
        return try {
            val canonDir = dir.canonicalPath
            val canonCache = context.cacheDir.canonicalPath
            val canonFiles = context.filesDir.canonicalPath
            canonDir.startsWith(canonCache) || canonDir.startsWith(canonFiles)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Returns true ONLY when the user-selected destination supports direct POSIX file I/O:
     * - Either no SAF tree URI was selected (`targetTreeUri == null`), meaning the caller explicitly
     *   requested local POSIX extraction to `targetDir`, OR
     * - A SAF tree URI was selected AND `targetDir` was verified as a direct external POSIX directory
     *   (never an internal fallback directory inside `context.filesDir` or `context.cacheDir`).
     */
    fun isDirectPosixDestination(
        context: Context?,
        targetDir: File?,
        targetTreeUri: Uri?
    ): Boolean {
        if (targetDir == null) return false
        if (targetTreeUri == null || targetTreeUri.scheme == "file") return true
        if (context == null) return false
        return !isAppInternalFallbackDir(context, targetDir)
    }

    fun ensureSafDirectoryPath(
        context: Context,
        treeUri: Uri,
        targetFolderDocUri: Uri?,
        fallbackSubfolderName: String?,
        relativeDirPath: String
    ): Uri? {
        val baseFolderUri = targetFolderDocUri
            ?: (!fallbackSubfolderName.isNullOrEmpty()).let { hasName ->
                if (hasName) getOrCreateDocumentSubfolder(context, treeUri, fallbackSubfolderName!!) else null
            }
            ?: try {
                DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri)
                )
            } catch (_: Throwable) {
                null
            } ?: return null

        val segments = relativeDirPath.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() }
        var currentParent = baseFolderUri
        for (seg in segments) {
            val next = getOrCreateDocumentChildDir(context, treeUri, currentParent, seg) ?: return null
            currentParent = next
        }
        return currentParent
    }

    fun openTargetOutput(
        context: Context?,
        targetDir: File?,
        targetTreeUri: Uri?,
        targetFolderDocUri: Uri?,
        relativePath: String,
        expectedBytes: Long,
        randomAccess: Boolean = false
    ): TargetOutput {
        val canUseDirectPosix = isDirectPosixDestination(context, targetDir, targetTreeUri)
        // Fast path: if targetDir is a direct-writable POSIX directory (or local extraction without SAF),
        // try direct POSIX output first to avoid per-file SAF Binder IPC overhead.
        if (canUseDirectPosix && targetDir != null) {
            try {
                val destFile = resolveSafeDestFile(targetDir, relativePath)
                val parent = destFile.parentFile
                if (parent != null && !parent.exists()) {
                    synchronized(posixDirLock) {
                        if (!parent.exists()) parent.mkdirs()
                    }
                }
                return if (randomAccess) {
                    val raf = java.io.RandomAccessFile(destFile, "rw")
                    val fos = FileOutputStream(raf.fd)
                    val closed = java.util.concurrent.atomic.AtomicBoolean(false)
                    val closeStrictFn: () -> Unit = {
                        if (closed.compareAndSet(false, true)) {
                            var firstErr: Throwable? = null
                            try { fos.flush() } catch (t: Throwable) { firstErr = t }
                            try { fos.close() } catch (t: Throwable) { if (firstErr == null) firstErr = t }
                            try { raf.close() } catch (t: Throwable) { if (firstErr == null) firstErr = t }
                            if (firstErr != null) {
                                if (firstErr is java.io.IOException) throw firstErr
                                throw java.io.IOException("Failed to close output file '${destFile.name}': ${firstErr.message}", firstErr)
                            }
                        }
                    }
                    TargetOutput(
                        outputStream = fos,
                        channel = raf.channel,
                        localFile = destFile,
                        documentUri = null,
                        close = {
                            if (closed.compareAndSet(false, true)) {
                                try { fos.flush() } catch (_: Exception) {}
                                try { raf.fd.sync() } catch (_: Throwable) {}
                                try { fos.close() } catch (_: Exception) {}
                                try { raf.close() } catch (_: Exception) {}
                            }
                        },
                        sync = { syncFileDescriptorStrict(raf.fd, raf.channel) },
                        closeStrict = closeStrictFn
                    )
                } else {
                    val fos = FileOutputStream(destFile)
                    val closed = java.util.concurrent.atomic.AtomicBoolean(false)
                    val closeStrictFn: () -> Unit = {
                        if (closed.compareAndSet(false, true)) {
                            var firstErr: Throwable? = null
                            try { fos.flush() } catch (t: Throwable) { firstErr = t }
                            try { fos.close() } catch (t: Throwable) { if (firstErr == null) firstErr = t }
                            if (firstErr != null) {
                                if (firstErr is java.io.IOException) throw firstErr
                                throw java.io.IOException("Failed to close output file '${destFile.name}': ${firstErr.message}", firstErr)
                            }
                        }
                    }
                    TargetOutput(
                        outputStream = fos,
                        channel = fos.channel,
                        localFile = destFile,
                        documentUri = null,
                        close = {
                            if (closed.compareAndSet(false, true)) {
                                try { fos.flush() } catch (_: Exception) {}
                                try { fos.fd.sync() } catch (_: Throwable) {}
                                try { fos.close() } catch (_: Exception) {}
                            }
                        },
                        sync = { syncFileDescriptorStrict(fos.fd, fos.channel) },
                        closeStrict = closeStrictFn
                    )
                }
            } catch (t: Throwable) {
                // If no SAF destination was selected, rethrow immediately instead of redirecting to /tmp
                if (targetTreeUri == null) {
                    if (t is java.io.IOException) throw t
                    throw java.io.IOException("Failed to open local output file for '$relativePath': ${t.message}", t)
                }
                // Otherwise fall through to SAF ContentResolver when FUSE blocks direct POSIX creation
            }
        }

        if (targetTreeUri != null) {
            if (context == null) {
                throw java.io.IOException(
                    "Cannot write '$relativePath' to selected SAF destination ($targetTreeUri) without Android Context"
                )
            }
            val subfolderName = targetDir?.name?.takeIf { it.isNotEmpty() }
            var effectiveFolderDocUri = targetFolderDocUri
                ?: if (subfolderName != null) {
                    getOrCreateDocumentSubfolder(context, targetTreeUri, subfolderName)
                } else {
                    try {
                        DocumentsContract.buildDocumentUriUsingTree(
                            targetTreeUri,
                            DocumentsContract.getTreeDocumentId(targetTreeUri)
                        )
                    } catch (_: Throwable) {
                        null
                    }
                }

            if (effectiveFolderDocUri != null) {
                val safOut = openSafTargetOutput(
                    context = context,
                    treeUri = targetTreeUri,
                    targetFolderDocUri = effectiveFolderDocUri,
                    relativePath = relativePath,
                    expectedBytes = expectedBytes,
                    randomAccess = randomAccess
                )
                if (safOut != null) return safOut
            }

            // Retry once after clearing cached SAF directory URIs in case a cached folder URI became stale
            clearSafDirCache()
            effectiveFolderDocUri = if (subfolderName != null) {
                getOrCreateDocumentSubfolder(context, targetTreeUri, subfolderName)
            } else {
                targetFolderDocUri ?: try {
                    DocumentsContract.buildDocumentUriUsingTree(
                        targetTreeUri,
                        DocumentsContract.getTreeDocumentId(targetTreeUri)
                    )
                } catch (_: Throwable) {
                    null
                }
            }
            if (effectiveFolderDocUri != null) {
                val retrySafOut = openSafTargetOutput(
                    context = context,
                    treeUri = targetTreeUri,
                    targetFolderDocUri = effectiveFolderDocUri,
                    relativePath = relativePath,
                    expectedBytes = expectedBytes,
                    randomAccess = randomAccess
                )
                if (retrySafOut != null) return retrySafOut
            }

            // NEVER silently fall back to writing into context.filesDir/extracted when a SAF destination was selected!
            throw java.io.IOException(
                "Failed to create or open output file '$relativePath' in selected SAF destination folder ($targetTreeUri)"
            )
        }

        val safeDir = targetDir
            ?: throw java.io.IOException("No extraction destination directory specified for '$relativePath'")
        val destFile = resolveSafeDestFile(safeDir, relativePath)
        val parent = destFile.parentFile
        if (parent != null && !parent.exists()) {
            synchronized(posixDirLock) {
                if (!parent.exists()) parent.mkdirs()
            }
        }
        return if (randomAccess) {
            val raf = java.io.RandomAccessFile(destFile, "rw")
            val fos = FileOutputStream(raf.fd)
            val closed = java.util.concurrent.atomic.AtomicBoolean(false)
            val closeStrictFn: () -> Unit = {
                if (closed.compareAndSet(false, true)) {
                    var firstErr: Throwable? = null
                    try { fos.flush() } catch (t: Throwable) { firstErr = t }
                    try { fos.close() } catch (t: Throwable) { if (firstErr == null) firstErr = t }
                    try { raf.close() } catch (t: Throwable) { if (firstErr == null) firstErr = t }
                    if (firstErr != null) {
                        if (firstErr is java.io.IOException) throw firstErr
                        throw java.io.IOException("Failed to close output file '${destFile.name}': ${firstErr.message}", firstErr)
                    }
                }
            }
            TargetOutput(
                outputStream = fos,
                channel = raf.channel,
                localFile = destFile,
                documentUri = null,
                close = {
                    if (closed.compareAndSet(false, true)) {
                        try { fos.flush() } catch (_: Exception) {}
                        try { raf.fd.sync() } catch (_: Throwable) {}
                        try { fos.close() } catch (_: Exception) {}
                        try { raf.close() } catch (_: Exception) {}
                    }
                },
                sync = { syncFileDescriptorStrict(raf.fd, raf.channel) },
                closeStrict = closeStrictFn
            )
        } else {
            val fos = FileOutputStream(destFile)
            val closed = java.util.concurrent.atomic.AtomicBoolean(false)
            val closeStrictFn: () -> Unit = {
                if (closed.compareAndSet(false, true)) {
                    var firstErr: Throwable? = null
                    try { fos.flush() } catch (t: Throwable) { firstErr = t }
                    try { fos.close() } catch (t: Throwable) { if (firstErr == null) firstErr = t }
                    if (firstErr != null) {
                        if (firstErr is java.io.IOException) throw firstErr
                        throw java.io.IOException("Failed to close output file '${destFile.name}': ${firstErr.message}", firstErr)
                    }
                }
            }
            TargetOutput(
                outputStream = fos,
                channel = fos.channel,
                localFile = destFile,
                documentUri = null,
                close = {
                    if (closed.compareAndSet(false, true)) {
                        try { fos.flush() } catch (_: Exception) {}
                        try { fos.fd.sync() } catch (_: Throwable) {}
                        try { fos.close() } catch (_: Exception) {}
                    }
                },
                sync = { syncFileDescriptorStrict(fos.fd, fos.channel) },
                closeStrict = closeStrictFn
            )
        }
    }

    fun getOrCreateDocumentSubfolder(context: Context, treeUri: Uri, folderName: String): Uri? {
        return try {
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val cacheKey = "${treeUri}|$treeDocId|$folderName"
            safDirUriCache[cacheKey]?.let { return it }

            synchronized(safDirLock) {
                safDirUriCache[cacheKey]?.let { return it }

                val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)

                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null, null, null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol)
                        val mime = cursor.getString(mimeCol)
                        if (name == folderName && mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            val docId = cursor.getString(idCol)
                            val foundUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            safDirUriCache[cacheKey] = foundUri
                            return foundUri
                        }
                    }
                }

                val created = DocumentsContract.createDocument(
                    context.contentResolver,
                    parentDocUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    folderName
                )
                if (created != null) {
                    safDirUriCache[cacheKey] = created
                }
                created
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getOrCreateDocumentChildDir(
        context: Context,
        treeUri: Uri,
        parentFolderDocUri: Uri,
        dirName: String
    ): Uri? {
        return try {
            val parentDocId = DocumentsContract.getDocumentId(parentFolderDocUri)
            val cacheKey = "${treeUri}|$parentDocId|$dirName"
            safDirUriCache[cacheKey]?.let { return it }

            synchronized(safDirLock) {
                safDirUriCache[cacheKey]?.let { return it }

                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null, null, null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol)
                        val mime = cursor.getString(mimeCol)
                        if (name == dirName && mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            val docId = cursor.getString(idCol)
                            val foundUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            safDirUriCache[cacheKey] = foundUri
                            return foundUri
                        }
                    }
                }

                val created = DocumentsContract.createDocument(
                    context.contentResolver,
                    parentFolderDocUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    dirName
                )
                if (created != null) {
                    safDirUriCache[cacheKey] = created
                }
                created
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun openSafTargetOutput(
        context: Context,
        treeUri: Uri,
        targetFolderDocUri: Uri,
        relativePath: String,
        expectedBytes: Long,
        randomAccess: Boolean = false
    ): TargetOutput? {
        return try {
            val cleanPath = relativePath.replace('\\', '/').trimStart('/')
            val segments = cleanPath.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) return null

            var currentParent = targetFolderDocUri
            for (i in 0 until segments.size - 1) {
                val subDir = getOrCreateDocumentChildDir(context, treeUri, currentParent, segments[i])
                    ?: return null
                currentParent = subDir
            }

            val fileName = segments.last()
            val ext = fileName.substringAfterLast('.', "")
            val mimeType = getMimeTypeFromExtension(ext)

            // Remove any pre-existing incomplete/stale document with the same name before creating
            // so Android ExternalStorageProvider never creates duplicate "file (1).ext" entries on resume/retry.
            deleteChildIfPresent(context, treeUri, currentParent, fileName)
            val fileDocUri = try {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    currentParent,
                    mimeType,
                    fileName
                )
            } catch (_: Throwable) {
                null
            } ?: return null

            val pfd = if (randomAccess) {
                try {
                    context.contentResolver.openFileDescriptor(fileDocUri, "rw")
                } catch (_: Throwable) {
                    null
                } ?: context.contentResolver.openFileDescriptor(fileDocUri, "w")
            } else {
                // Open in sequential write-only ("w") mode first so FUSE uses write-behind page caching
                try {
                    context.contentResolver.openFileDescriptor(fileDocUri, "w")
                } catch (_: Throwable) {
                    null
                } ?: context.contentResolver.openFileDescriptor(fileDocUri, "rw")
            } ?: return null

            val fos = FileOutputStream(pfd.fileDescriptor)
            val closed = java.util.concurrent.atomic.AtomicBoolean(false)
            TargetOutput(
                outputStream = fos,
                channel = fos.channel,
                localFile = null,
                documentUri = fileDocUri,
                close = {
                    if (closed.compareAndSet(false, true)) {
                        try { fos.flush() } catch (_: Exception) {}
                        try { fos.fd.sync() } catch (_: Throwable) {}
                        try { fos.close() } catch (_: Exception) {}
                        try { pfd.close() } catch (_: Exception) {}
                    }
                },
                sync = { syncFileDescriptorStrict(pfd.fileDescriptor, fos.channel) },
                closeStrict = {
                    if (closed.compareAndSet(false, true)) {
                        var firstErr: Throwable? = null
                        try { fos.flush() } catch (t: Throwable) { firstErr = t }
                        try { fos.close() } catch (t: Throwable) { if (firstErr == null) firstErr = t }
                        try { pfd.close() } catch (t: Throwable) { if (firstErr == null) firstErr = t }
                        if (firstErr != null) {
                            if (firstErr is java.io.IOException) throw firstErr
                            throw java.io.IOException("Failed to close SAF output '$fileName': ${firstErr.message}", firstErr)
                        }
                    }
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Positional append-only OutputStream backed by an already-opened `"rw"` [FileDescriptor] and
     * [FileChannel]. Writes strictly at monotonically increasing offsets starting at [initialOffset],
     * never truncating or overwriting checkpointed prefix bytes `[0, initialOffset)`.
     */
    private class PositionalDescriptorOutputStream(
        private val fd: FileDescriptor,
        private val outChannel: FileChannel,
        initialOffset: Long
    ) : OutputStream() {
        private var writePosition: Long = initialOffset
        private val singleByte = ByteArray(1)

        override fun write(b: Int) {
            singleByte[0] = b.toByte()
            write(singleByte, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            var writtenTotal = 0
            try {
                val bb = ByteBuffer.wrap(b, off, len)
                var pos = writePosition
                while (bb.hasRemaining()) {
                    val w = outChannel.write(bb, pos)
                    if (w <= 0) break
                    pos += w
                    writtenTotal += w
                }
                if (writtenTotal == len) {
                    writePosition = pos
                    return
                }
            } catch (_: Throwable) {
                // Fall back to POSIX pwrite for providers whose FileChannel does not support positional write
            }

            var rem = len - writtenTotal
            var curOff = off + writtenTotal
            var curPos = writePosition + writtenTotal
            while (rem > 0) {
                val w = try {
                    Os.pwrite(fd, b, curOff, rem, curPos)
                } catch (t: Throwable) {
                    throw IOException("Failed positional SAF append at offset $curPos: ${t.localizedMessage ?: t.javaClass.simpleName}", t)
                }
                if (w <= 0) {
                    throw IOException("Short positional SAF append at offset $curPos ($writtenTotal / $len bytes written)")
                }
                curOff += w
                rem -= w
                curPos += w
                writtenTotal += w
            }
            writePosition = curPos
        }

        override fun flush() {
            // Unbuffered positional writes go directly to the kernel descriptor; callers invoke TargetOutput.sync() for fsync.
        }
    }

    private fun computeDescriptorPrefixCrc32(
        fd: FileDescriptor,
        channel: FileChannel,
        byteCount: Long
    ): Long {
        if (byteCount < 0L) return -1L
        if (byteCount == 0L) return 0L
        val crc = CRC32()
        val buf = ByteArray(64 * 1024)
        var remaining = byteCount
        var pos = 0L
        while (remaining > 0L) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val bb = ByteBuffer.wrap(buf, 0, toRead)
            var rTotal = 0
            while (bb.hasRemaining()) {
                val r = try {
                    channel.read(bb, pos)
                } catch (_: Throwable) {
                    try {
                        Os.pread(fd, buf, rTotal, toRead - rTotal, pos).also { n ->
                            if (n > 0) bb.position(bb.position() + n)
                        }
                    } catch (_: Throwable) {
                        -1
                    }
                }
                if (r <= 0) return -1L
                pos += r
                rTotal += r
            }
            crc.update(buf, 0, rTotal)
            remaining -= rTotal
        }
        return crc.value
    }

    /**
     * Read-only verification of an existing partial SAF output file for transactional DEFLATE recovery.
     * Never deletes, recreates, or truncates the file. Verifies `"rw"` access, regular-file descriptor,
     * positional `pwrite`/`ftruncate`/`fsync` capabilities, `size >= expectedOffset`, and exact prefix CRC32.
     */
    fun verifySafPartialOutputForRecovery(
        context: Context,
        treeUri: Uri,
        rootFolderDocUri: Uri,
        relativePath: String,
        expectedOffset: Long,
        expectedPrefixCrc32: Long
    ): SafPartialOutputVerification {
        val docUri = findSafRelativeDocumentUri(context, treeUri, rootFolderDocUri, relativePath)
            ?: return SafPartialOutputVerification(
                isValid = false,
                failureReason = "Partial SAF output file for '$relativePath' is missing in the selected destination folder."
            )

        val pfd = try {
            context.contentResolver.openFileDescriptor(docUri, "rw")
        } catch (t: Throwable) {
            return SafPartialOutputVerification(
                isValid = false,
                documentUri = docUri,
                failureReason = "SAF destination provider denied read-write ('rw') access to partial output '$relativePath' (${t.localizedMessage ?: t.javaClass.simpleName}); requires a seekable read-write SAF provider or direct-POSIX access."
            )
        } ?: return SafPartialOutputVerification(
            isValid = false,
            documentUri = docUri,
            failureReason = "SAF destination provider returned a null read-write descriptor for partial output '$relativePath'; requires a seekable read-write SAF provider or direct-POSIX access."
        )

        return try {
            pfd.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { fis ->
                    val ch = fis.channel
                    val probe = probeTransactableFileDescriptor(
                        fd = descriptor.fileDescriptor,
                        channel = ch,
                        requireWriteAndTruncate = true
                    )
                    if (!probe.isSupported) {
                        return@use SafPartialOutputVerification(
                            isValid = false,
                            documentUri = docUri,
                            actualSize = probe.sizeBytes,
                            failureReason = "SAF destination provider cannot safely recover partial output '$relativePath': ${probe.reason} Requires a seekable read-write SAF provider supporting truncate and fsync, or direct-POSIX access."
                        )
                    }
                    if (probe.sizeBytes < expectedOffset) {
                        return@use SafPartialOutputVerification(
                            isValid = false,
                            documentUri = docUri,
                            actualSize = probe.sizeBytes,
                            failureReason = "Partial SAF output file for '$relativePath' is shorter than checkpoint offset (${probe.sizeBytes} < $expectedOffset)."
                        )
                    }
                    val actualPrefixCrc = computeDescriptorPrefixCrc32(descriptor.fileDescriptor, ch, expectedOffset)
                    if (actualPrefixCrc != expectedPrefixCrc32) {
                        return@use SafPartialOutputVerification(
                            isValid = false,
                            documentUri = docUri,
                            actualSize = probe.sizeBytes,
                            actualPrefixCrc32 = actualPrefixCrc,
                            failureReason = "Partial SAF output prefix CRC32 mismatch for '$relativePath' (expected 0x${String.format("%08X", expectedPrefixCrc32)}, got 0x${String.format("%08X", actualPrefixCrc)})."
                        )
                    }
                    SafPartialOutputVerification(
                        isValid = true,
                        documentUri = docUri,
                        actualSize = probe.sizeBytes,
                        actualPrefixCrc32 = actualPrefixCrc,
                        failureReason = null
                    )
                }
            }
        } catch (t: Throwable) {
            SafPartialOutputVerification(
                isValid = false,
                documentUri = docUri,
                failureReason = "Failed to verify partial SAF output '$relativePath': ${t.localizedMessage ?: t.javaClass.simpleName}"
            )
        }
    }

    /**
     * Reopens an existing partial SAF output file in `"rw"` mode WITHOUT deleting or truncating
     * checkpointed data `[0, expectedOffset)`:
     * 1. Probes regular-file `"rw"`, `pwrite`, `ftruncate`, and strict `fsync` capabilities.
     * 2. Verifies `actualSize >= expectedOffset`.
     * 3. Trims any uncommitted trailing bytes above `expectedOffset` via `ftruncate` + strict `fsync`.
     * 4. Verifies exact prefix CRC32 over `[0, expectedOffset)`.
     * 5. Returns a [TargetOutput] that appends strictly from `expectedOffset` with strict `fsync`.
     */
    fun openExistingSafOutputForRecovery(
        context: Context,
        treeUri: Uri,
        rootFolderDocUri: Uri,
        relativePath: String,
        expectedOffset: Long,
        expectedPrefixCrc32: Long
    ): SafRecoveryOutputOpenResult {
        val docUri = findSafRelativeDocumentUri(context, treeUri, rootFolderDocUri, relativePath)
            ?: return SafRecoveryOutputOpenResult.Failed(
                "Partial SAF output file for '$relativePath' is missing in destination folder."
            )

        val pfd = try {
            context.contentResolver.openFileDescriptor(docUri, "rw")
        } catch (t: Throwable) {
            return SafRecoveryOutputOpenResult.Failed(
                "SAF destination provider denied read-write ('rw') access to partial output '$relativePath' (${t.localizedMessage ?: t.javaClass.simpleName})."
            )
        } ?: return SafRecoveryOutputOpenResult.Failed(
            "SAF destination provider returned null read-write descriptor for partial output '$relativePath'."
        )

        var keepOpen = false
        try {
            val fd = pfd.fileDescriptor
            val inStream = FileInputStream(fd)
            val readCh = inStream.channel
            val outStream = FileOutputStream(fd)
            val outCh = outStream.channel

            val probe = probeTransactableFileDescriptor(fd, readCh, requireWriteAndTruncate = true)
            if (!probe.isSupported) {
                return SafRecoveryOutputOpenResult.Failed(
                    "SAF destination provider cannot safely recover partial output '$relativePath': ${probe.reason}"
                )
            }
            if (probe.sizeBytes < expectedOffset) {
                return SafRecoveryOutputOpenResult.Failed(
                    "Partial SAF output file for '$relativePath' is shorter than checkpoint offset (${probe.sizeBytes} < $expectedOffset)."
                )
            }

            var trimmedBytes = 0L
            if (probe.sizeBytes > expectedOffset) {
                var truncated = false
                try {
                    outCh.truncate(expectedOffset)
                    truncated = true
                } catch (_: Throwable) {
                }
                if (!truncated) {
                    try {
                        Os.ftruncate(fd, expectedOffset)
                        truncated = true
                    } catch (_: LinkageError) {
                    } catch (_: Throwable) {
                    }
                }
                if (!truncated) {
                    return SafRecoveryOutputOpenResult.Failed(
                        "Failed to truncate uncommitted trailing bytes of partial SAF output '$relativePath' back to $expectedOffset."
                    )
                }
                if (!syncFileDescriptorStrict(fd, outCh)) {
                    return SafRecoveryOutputOpenResult.Failed(
                        "Failed to fsync partial SAF output '$relativePath' after trimming uncommitted trailing bytes."
                    )
                }
                val newSize = try { readCh.size() } catch (_: Throwable) { -1L }
                if (newSize != expectedOffset) {
                    return SafRecoveryOutputOpenResult.Failed(
                        "Partial SAF output '$relativePath' length after trim ($newSize) does not match checkpoint offset ($expectedOffset)."
                    )
                }
                trimmedBytes = probe.sizeBytes - expectedOffset
            }

            val actualPrefixCrc = computeDescriptorPrefixCrc32(fd, readCh, expectedOffset)
            if (actualPrefixCrc != expectedPrefixCrc32) {
                return SafRecoveryOutputOpenResult.Failed(
                    "Partial SAF output prefix CRC32 mismatch for '$relativePath' (expected 0x${String.format("%08X", expectedPrefixCrc32)}, got 0x${String.format("%08X", actualPrefixCrc)})."
                )
            }

            val appendStream = PositionalDescriptorOutputStream(
                fd = fd,
                outChannel = outCh,
                initialOffset = expectedOffset
            )
            val closed = java.util.concurrent.atomic.AtomicBoolean(false)
            val targetOutput = TargetOutput(
                outputStream = appendStream,
                channel = outCh,
                localFile = null,
                documentUri = docUri,
                close = {
                    if (closed.compareAndSet(false, true)) {
                        try { appendStream.flush() } catch (_: Throwable) {}
                        try { syncFileDescriptorStrict(fd, outCh) } catch (_: Throwable) {}
                        try { outStream.close() } catch (_: Throwable) {}
                        try { inStream.close() } catch (_: Throwable) {}
                        try { pfd.close() } catch (_: Throwable) {}
                    }
                },
                sync = { syncFileDescriptorStrict(fd, outCh) },
                closeStrict = {
                    if (closed.compareAndSet(false, true)) {
                        var firstErr: Throwable? = null
                        try { appendStream.flush() } catch (t: Throwable) { firstErr = t }
                        try { outStream.close() } catch (t: Throwable) { if (firstErr == null) firstErr = t }
                        try { inStream.close() } catch (t: Throwable) { if (firstErr == null) firstErr = t }
                        try { pfd.close() } catch (t: Throwable) { if (firstErr == null) firstErr = t }
                        if (firstErr != null) {
                            if (firstErr is java.io.IOException) throw firstErr
                            throw java.io.IOException("Failed to close SAF recovery output '$relativePath': ${firstErr.message}", firstErr)
                        }
                    }
                }
            )
            keepOpen = true
            return SafRecoveryOutputOpenResult.Opened(
                targetOutput = targetOutput,
                documentUri = docUri,
                verifiedPrefixBytes = expectedOffset,
                trimmedTrailingBytes = trimmedBytes
            )
        } catch (t: Throwable) {
            return SafRecoveryOutputOpenResult.Failed(
                "Failed to reopen partial SAF output '$relativePath': ${t.localizedMessage ?: t.javaClass.simpleName}"
            )
        } finally {
            if (!keepOpen) {
                try { pfd.close() } catch (_: Throwable) {}
            }
        }
    }

    private fun deleteChildIfPresent(
        context: Context,
        treeUri: Uri,
        parentDocUri: Uri,
        displayName: String
    ) {
        try {
            val parentDocId = DocumentsContract.getDocumentId(parentDocUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol)
                    if (name == displayName) {
                        val docId = cursor.getString(idCol)
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        DocumentsContract.deleteDocument(context.contentResolver, childUri)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Verifies that a SAF destination tree URI (and its target extraction subfolder) is accessible
     * and writable. Returns the resolved target folder document URI if accessible, or `null` if
     * SAF permissions were revoked or the destination folder is inaccessible.
     */
    fun verifySafDestinationAccessible(
        context: Context,
        treeUri: Uri,
        targetFolderDocUri: Uri?,
        subfolderName: String?
    ): Uri? {
        return try {
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
            // Verify we can actively query the tree URI (fails with SecurityException or null cursor if permission revoked)
            val rootQueryOk = context.contentResolver.query(
                rootChildrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null
            )?.use { true } ?: false
            if (!rootQueryOk) return null

            val cleanSubfolder = subfolderName?.takeIf { it.isNotEmpty() }
            val resolvedFolderUri = if (targetFolderDocUri != null) {
                val folderDocId = DocumentsContract.getDocumentId(targetFolderDocUri)
                val folderChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderDocId)
                val folderExists = try {
                    context.contentResolver.query(
                        folderChildrenUri,
                        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                        null, null, null
                    )?.use { true } ?: false
                } catch (_: Throwable) {
                    false
                }
                if (folderExists) {
                    targetFolderDocUri
                } else if (cleanSubfolder != null) {
                    clearSafDirCache()
                    getOrCreateDocumentSubfolder(context, treeUri, cleanSubfolder)
                } else {
                    null
                }
            } else if (cleanSubfolder != null) {
                getOrCreateDocumentSubfolder(context, treeUri, cleanSubfolder)
            } else {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
            }
            resolvedFolderUri
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Resolves an existing relative document path under [rootFolderDocUri] without creating missing folders.
     */
    fun findSafRelativeDocumentUri(
        context: Context,
        treeUri: Uri,
        rootFolderDocUri: Uri,
        relativePath: String
    ): Uri? {
        return try {
            val cleanPath = relativePath.replace('\\', '/').trimStart('/')
            val segments = cleanPath.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) return null

            var currentDocUri = rootFolderDocUri
            for ((index, segment) in segments.withIndex()) {
                val isLast = index == segments.lastIndex
                val parentDocId = DocumentsContract.getDocumentId(currentDocUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
                var matchedChildUri: Uri? = null
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null, null, null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol)
                        val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else ""
                        if (name == segment) {
                            if (!isLast && mime != DocumentsContract.Document.MIME_TYPE_DIR) continue
                            val docId = cursor.getString(idCol)
                            matchedChildUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            break
                        }
                    }
                }
                currentDocUri = matchedChildUri ?: return null
            }
            currentDocUri
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Verifies that an extracted file inside a SAF destination exists, matches [expectedSize],
     * and matches [expectedCrc32] (when non-null) via full-stream CRC32 check.
     */
    fun verifySafOutputFileIntegrity(
        context: Context,
        treeUri: Uri,
        rootFolderDocUri: Uri,
        relativePath: String,
        expectedSize: Long,
        expectedCrc32: Long?
    ): Boolean {
        return try {
            val docUri = findSafRelativeDocumentUri(context, treeUri, rootFolderDocUri, relativePath)
                ?: return false
            context.contentResolver.openInputStream(docUri)?.use { input ->
                val crc = java.util.zip.CRC32()
                val buf = ByteArray(64 * 1024)
                var totalRead = 0L
                while (true) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    crc.update(buf, 0, r)
                    totalRead += r
                }
                if (expectedSize >= 0L && totalRead != expectedSize) return false
                if (expectedCrc32 != null && (crc.value and 0xFFFFFFFFL) != (expectedCrc32 and 0xFFFFFFFFL)) {
                    return false
                }
                true
            } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    fun deleteSafDocument(context: Context, documentUri: Uri): Boolean {
        return try {
            DocumentsContract.deleteDocument(context.contentResolver, documentUri)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    data class GrantedFolderArchiveTarget(
        val workingFile: File,
        val directPosixFile: File?,
        val documentUri: Uri?,
        val folderDisplayLabel: String,
        val requiresSafStreamOnFinish: Boolean
    )

    /**
     * Prepares a target archive directly inside the user's selected SAF folder (`treeUri`).
     * - First registers/creates the file in the SAF folder via `DocumentsContract.createDocument` so
     *   Android's MediaProvider/FUSE grants our app direct ownership of the file.
     * - If the folder maps to a direct POSIX path (e.g. `/storage/emulated/0/...`), returns that
     *   `File` directly so `HatchCompressEngine` writes in-place with zero extra copy and zero app-data use.
     * - Otherwise uses a temporary working file that is streamed into `documentUri` and deleted upon completion.
     */
    fun prepareArchiveTargetInGrantedFolder(
        context: Context,
        treeUri: Uri?,
        fileName: String
    ): GrantedFolderArchiveTarget {
        if (treeUri == null) {
            val fallbackFile = File(getArchivesDir(context), fileName)
            return GrantedFolderArchiveTarget(
                workingFile = fallbackFile,
                directPosixFile = fallbackFile,
                documentUri = null,
                folderDisplayLabel = "App Storage",
                requiresSafStreamOnFinish = false
            )
        }

        val folderInfo = parseGrantedFolderUri(treeUri)
        val ext = if (fileName.endsWith(".tar.gz", ignoreCase = true)) {
            "gz"
        } else {
            fileName.substringAfterLast('.', "zip")
        }
        val mimeType = getMimeTypeFromExtension(ext)

        var createdDocUri: Uri? = null
        if (treeUri.scheme == "content") {
            try {
                val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
                deleteChildIfPresent(context, treeUri, parentDocUri, fileName)
                createdDocUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    parentDocUri,
                    mimeType,
                    fileName
                )
            } catch (_: Throwable) {
            }
        }

        val posixRoot = tryResolveDirectTreeRootDir(treeUri)
        if (posixRoot != null) {
            try {
                val directFile = File(posixRoot, fileName)
                RandomAccessFile(directFile, "rw").use { raf ->
                    raf.setLength(0L)
                }
                registerActiveCompressionFile(directFile)
                return GrantedFolderArchiveTarget(
                    workingFile = directFile,
                    directPosixFile = directFile,
                    documentUri = createdDocUri,
                    folderDisplayLabel = folderInfo.displayName,
                    requiresSafStreamOnFinish = false
                )
            } catch (_: Throwable) {
                // Fall back to temp file + SAF stream if FUSE blocks direct random access
            }
        }

        if (createdDocUri == null) {
            throw java.io.IOException(
                "Failed to create archive '$fileName' in selected SAF destination folder (${folderInfo.displayName})"
            )
        }

        val tempWorkingFile = File(getTempDir(context), fileName)
        if (tempWorkingFile.exists() && !isCompressionFileActive(tempWorkingFile)) {
            cleanupCompressionFile(context, tempWorkingFile, "prepare_overwrite_stale_temp")
        }
        registerActiveCompressionFile(tempWorkingFile)
        runCatching {
            android.util.Log.d(
                TAG_STORAGE,
                "Created compression temp target: path=${tempWorkingFile.absolutePath} safDoc=$createdDocUri"
            )
        }
        return GrantedFolderArchiveTarget(
            workingFile = tempWorkingFile,
            directPosixFile = null,
            documentUri = createdDocUri,
            folderDisplayLabel = folderInfo.displayName,
            requiresSafStreamOnFinish = true
        )
    }

    fun finalizeArchiveTargetInGrantedFolder(
        context: Context,
        target: GrantedFolderArchiveTarget
    ): Long {
        if (!target.requiresSafStreamOnFinish) {
            unregisterActiveCompressionFile(target.workingFile)
            return target.workingFile.length()
        }
        val docUri = target.documentUri ?: run {
            cleanupCompressionFile(context, target.workingFile, "finalize_missing_saf_doc_uri", forceUnregister = true)
            throw java.io.IOException("Missing SAF document URI for finalized archive '${target.workingFile.name}'")
        }
        val finalSize = target.workingFile.length()
        try {
            val exported = exportFileToUri(context, target.workingFile, docUri)
            if (!exported) {
                runCatching { deleteSafDocument(context, docUri) }
                throw java.io.IOException(
                    "Failed to stream temporary archive '${target.workingFile.name}' to selected SAF folder"
                )
            }
            try {
                context.contentResolver.openFileDescriptor(docUri, "rw")?.use { pfd ->
                    try { pfd.fileDescriptor.sync() } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {
            }
        } finally {
            cleanupCompressionFile(
                context = context,
                file = target.workingFile,
                reason = "finalizeArchiveTargetInGrantedFolder",
                forceUnregister = true
            )
        }
        return finalSize
    }

    val EXTRACTED_ENTRY_COMPARATOR: Comparator<ExtractedFileEntry> =
        compareBy<ExtractedFileEntry> { !it.isDirectory }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.name }
            .thenBy { it.id }

    fun sortExtractedEntries(entries: List<ExtractedFileEntry>): List<ExtractedFileEntry> {
        val size = entries.size
        if (size <= 1) return entries
        var alreadySorted = true
        for (i in 1 until size) {
            if (EXTRACTED_ENTRY_COMPARATOR.compare(entries[i - 1], entries[i]) > 0) {
                alreadySorted = false
                break
            }
        }
        if (alreadySorted) return entries
        return entries.sortedWith(EXTRACTED_ENTRY_COMPARATOR)
    }

    private data class LocalFolderQuickStats(
        val totalBytes: Long,
        val totalFileCount: Int,
        val hasAnyDirectory: Boolean
    )

    private fun computeLocalFolderQuickStats(rootFolder: File, maxVisitedEntries: Int = 25_000): LocalFolderQuickStats {
        var totalBytes = 0L
        var fileCount = 0
        var hasAnyDir = false
        var visited = 0
        val stack = ArrayDeque<File>()
        stack.addLast(rootFolder)
        while (stack.isNotEmpty() && visited < maxVisitedEntries) {
            val current = stack.removeLast()
            val list = try { current.listFiles() } catch (_: Throwable) { null } ?: continue
            for (child in list) {
                val childName = child.name
                if (childName.startsWith(".kaihatch_")) continue
                visited++
                if (child.isDirectory) {
                    hasAnyDir = true
                    if (visited < maxVisitedEntries) {
                        stack.addLast(child)
                    }
                } else {
                    fileCount++
                    totalBytes += child.length().coerceAtLeast(0L)
                }
            }
        }
        return LocalFolderQuickStats(
            totalBytes = totalBytes,
            totalFileCount = fileCount,
            hasAnyDirectory = hasAnyDir
        )
    }

    fun listLocalDirectoryDirectChildren(
        dir: File,
        parentRelativePath: String = "",
        isSafBacked: Boolean = false,
        safTreeUri: Uri? = null,
        safDirectMapByName: Map<String, ExtractedFileEntry> = emptyMap()
    ): List<ExtractedFileEntry> {
        val rawFiles = try { dir.listFiles() } catch (_: Throwable) { null } ?: return emptyList()
        val parentPath = dir.absolutePath
        val result = ArrayList<ExtractedFileEntry>(rawFiles.size)
        for (f in rawFiles) {
            val name = f.name
            if (name.startsWith(".kaihatch_")) continue
            val isDir = f.isDirectory
            val matchSaf = safDirectMapByName[name]
            val relPath = if (parentRelativePath.isEmpty()) name else "$parentRelativePath/$name"
            val entryId = f.absolutePath
            result.add(
                ExtractedFileEntry(
                    id = entryId,
                    name = name,
                    sizeBytes = if (isDir) 0L else f.length().coerceAtLeast(matchSaf?.sizeBytes ?: 0L),
                    mimeType = if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else getMimeTypeFromExtension(f.extension),
                    localFile = f,
                    safUri = matchSaf?.safUri,
                    isSaf = isSafBacked,
                    isDirectory = isDir,
                    relativePath = relPath,
                    parentId = parentPath,
                    safTreeUri = safTreeUri ?: matchSaf?.safTreeUri,
                    safDocId = matchSaf?.safDocId
                )
            )
        }
        return sortExtractedEntries(result)
    }

    fun listSafDirectoryDirectChildren(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        parentRelativePath: String = "",
        posixDir: File? = null
    ): List<ExtractedFileEntry> {
        val safEntries = mutableListOf<ExtractedFileEntry>()
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol) ?: continue
                    val displayName = cursor.getString(nameCol) ?: continue
                    if (displayName.startsWith(".kaihatch_")) continue
                    val mime = cursor.getString(mimeCol) ?: "application/octet-stream"
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (!isDir && sizeCol != -1 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val relPath = if (parentRelativePath.isEmpty()) displayName else "$parentRelativePath/$displayName"
                    val childLocalFile = posixDir?.let { File(it, displayName) }?.takeIf { it.exists() }
                    safEntries.add(
                        ExtractedFileEntry(
                            id = childLocalFile?.absolutePath ?: docId,
                            name = displayName,
                            sizeBytes = if (isDir) 0L else (childLocalFile?.length()?.coerceAtLeast(size) ?: size),
                            mimeType = if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else mime,
                            localFile = childLocalFile,
                            safUri = docUri,
                            isSaf = true,
                            isDirectory = isDir,
                            relativePath = relPath,
                            parentId = posixDir?.absolutePath ?: parentDocId,
                            safTreeUri = treeUri,
                            safDocId = docId
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }

        if (posixDir != null && posixDir.exists() && posixDir.isDirectory) {
            val safMapByName = safEntries.associateBy { it.name }
            val posixChildren = listLocalDirectoryDirectChildren(
                dir = posixDir,
                parentRelativePath = parentRelativePath,
                isSafBacked = true,
                safTreeUri = treeUri,
                safDirectMapByName = safMapByName
            )
            if (posixChildren.size >= safEntries.size && posixChildren.isNotEmpty()) {
                return posixChildren
            }
        }

        return sortExtractedEntries(safEntries)
    }

    /**
     * Lazily enumerates the direct children of a single subfolder (1 level only, never recursive).
     * Supports both local POSIX directories and SAF document trees, sorting folders first then files alphabetically.
     */
    fun listExtractedSubfolderChildren(
        context: Context,
        subfolder: ExtractedFileEntry
    ): List<ExtractedFileEntry> {
        if (!subfolder.isDirectory) return emptyList()
        val treeUri = subfolder.safTreeUri
        val docId = subfolder.safDocId
        val localDir = subfolder.localFile
        if (treeUri != null && !docId.isNullOrEmpty()) {
            return listSafDirectoryDirectChildren(
                context = context,
                treeUri = treeUri,
                parentDocId = docId,
                parentRelativePath = subfolder.relativePath,
                posixDir = localDir
            )
        }
        if (localDir != null && localDir.exists() && localDir.isDirectory) {
            return listLocalDirectoryDirectChildren(
                dir = localDir,
                parentRelativePath = subfolder.relativePath,
                isSafBacked = subfolder.isSaf,
                safTreeUri = treeUri
            )
        }
        return emptyList()
    }

    fun listSafExtractedFolders(context: Context, treeUri: Uri): List<ExtractedFolderEntry> {
        val result = mutableListOf<ExtractedFolderEntry>()
        val folderInfo = parseGrantedFolderUri(treeUri)
        val posixRoot = tryResolveDirectTreeRootDir(treeUri)

        try {
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)

            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol)
                    val displayName = cursor.getString(nameCol) ?: continue
                    val mime = cursor.getString(mimeCol) ?: ""

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR && displayName.endsWith("_extracted")) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        val posixSubfolder = posixRoot?.let { File(it, displayName) }?.takeIf { it.exists() && it.isDirectory }
                        val directChildren = listSafDirectoryDirectChildren(
                            context = context,
                            treeUri = treeUri,
                            parentDocId = docId,
                            parentRelativePath = "",
                            posixDir = posixSubfolder
                        )
                        val quickStats = posixSubfolder?.let { computeLocalFolderQuickStats(it) }
                        val totalSize = quickStats?.totalBytes ?: directChildren.sumOf { it.sizeBytes }
                        val totalFileCount = quickStats?.totalFileCount?.takeIf { it > 0 } ?: directChildren.size
                        val hasSubfolders = directChildren.any { it.isDirectory }
                        if (directChildren.isNotEmpty() && (totalSize > 0L || hasSubfolders)) {
                            val precomputedCanonical = posixSubfolder?.let {
                                runCatching { it.canonicalPath }.getOrNull() ?: it.absolutePath
                            }
                            result.add(
                                ExtractedFolderEntry(
                                    id = docId,
                                    name = displayName,
                                    isSaf = true,
                                    localFile = posixSubfolder,
                                    safUri = docUri,
                                    totalSizeBytes = totalSize,
                                    children = directChildren,
                                    locationDescription = "Folder \"${folderInfo.displayName}\" (${folderInfo.readablePath})",
                                    totalFileCount = totalFileCount,
                                    safTreeUri = treeUri,
                                    safDocId = docId,
                                    canonicalPath = precomputedCanonical
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (posixRoot != null && posixRoot.exists() && posixRoot.isDirectory) {
            try {
                posixRoot.listFiles()?.filter { it.isDirectory && it.name.endsWith("_extracted") }?.forEach { folder ->
                    if (result.none { it.name.equals(folder.name, ignoreCase = true) }) {
                        val directChildren = listLocalDirectoryDirectChildren(
                            dir = folder,
                            parentRelativePath = "",
                            isSafBacked = true,
                            safTreeUri = treeUri
                        )
                        val quickStats = computeLocalFolderQuickStats(folder)
                        val totalBytes = quickStats.totalBytes
                        val hasSubfolders = directChildren.any { it.isDirectory }
                        if (directChildren.isNotEmpty() && (totalBytes > 0L || hasSubfolders)) {
                            val precomputedCanonical = runCatching { folder.canonicalPath }.getOrNull() ?: folder.absolutePath
                            result.add(
                                ExtractedFolderEntry(
                                    id = folder.absolutePath,
                                    name = folder.name,
                                    isSaf = true,
                                    localFile = folder,
                                    safUri = null,
                                    totalSizeBytes = totalBytes,
                                    children = directChildren,
                                    locationDescription = "Folder \"${folderInfo.displayName}\" (${folderInfo.readablePath})",
                                    totalFileCount = quickStats.totalFileCount.takeIf { it > 0 } ?: directChildren.size,
                                    safTreeUri = treeUri,
                                    safDocId = null,
                                    canonicalPath = precomputedCanonical
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return result
    }

    fun listFilesFlat(folder: File): List<File> {
        val list = mutableListOf<File>()
        folder.walkTopDown().filter { it.isFile }.forEach { list.add(it) }
        return list
    }

    fun getFolderDisplayName(context: Context, treeUri: Uri): String {
        return parseGrantedFolderUri(treeUri).displayName
    }

    fun resolveMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "")
        return getMimeTypeFromExtension(ext)
    }

    fun listLocalExtractedFolders(context: Context): List<ExtractedFolderEntry> {
        val dir = getExtractedDir(context)
        if (!dir.exists()) return emptyList()
        val result = mutableListOf<ExtractedFolderEntry>()
        dir.listFiles()?.filter { it.isDirectory }?.forEach { folder ->
            val directChildren = listLocalDirectoryDirectChildren(
                dir = folder,
                parentRelativePath = "",
                isSafBacked = false
            )
            val quickStats = computeLocalFolderQuickStats(folder)
            val totalBytes = quickStats.totalBytes
            val hasSubfolders = directChildren.any { it.isDirectory }
            if (directChildren.isEmpty() || (totalBytes <= 0L && !hasSubfolders)) {
                try { folder.deleteRecursively() } catch (_: Exception) {}
            } else {
                val precomputedCanonical = runCatching { folder.canonicalPath }.getOrNull() ?: folder.absolutePath
                result.add(
                    ExtractedFolderEntry(
                        id = folder.absolutePath,
                        name = folder.name,
                        isSaf = false,
                        localFile = folder,
                        safUri = null,
                        totalSizeBytes = totalBytes,
                        children = directChildren,
                        locationDescription = "Local Storage",
                        totalFileCount = quickStats.totalFileCount.takeIf { it > 0 } ?: directChildren.size,
                        canonicalPath = precomputedCanonical
                    )
                )
            }
        }
        return result.sortedByDescending { it.localFile?.lastModified() ?: 0L }
    }

    fun readSafDocumentPreview(
        context: Context,
        uri: Uri,
        fileName: String,
        sizeBytes: Long = 0L,
        maxBytes: Int = 8192
    ): FilePreviewData {
        return try {
            val ext = fileName.substringAfterLast('.', "")
            val mime = getMimeTypeFromExtension(ext)
            val isImg = mime.startsWith("image/") && sizeBytes in 1..(5 * 1024 * 1024)
            val cap = if (isImg) sizeBytes.toInt() else maxBytes.coerceAtLeast(1)

            val buffer = ByteArray(cap)
            var totalRead = 0
            context.contentResolver.openInputStream(uri)?.use { stream ->
                while (totalRead < cap) {
                    val r = stream.read(buffer, totalRead, cap - totalRead)
                    if (r <= 0) break
                    totalRead += r
                }
            }

            val slice = if (totalRead == buffer.size) buffer else buffer.copyOf(totalRead)
            buildPreviewFromBytes(
                fileName = fileName,
                totalSize = sizeBytes.coerceAtLeast(totalRead.toLong()),
                mimeType = mime,
                bytes = if (slice.size > maxBytes) slice.copyOf(maxBytes) else slice,
                isImage = isImg,
                imageBytes = if (isImg) slice else null
            )
        } catch (e: Exception) {
            FilePreviewData(
                fileName = fileName,
                sizeBytes = sizeBytes,
                isBinary = false,
                mimeType = "text/plain",
                contentPreview = "Could not preview file: ${e.message}"
            )
        }
    }

    /**
     * Lists archive files located directly inside the user's granted persistent folder
     * when backed by a POSIX-accessible directory (e.g. Internal Storage / Download / custom folder).
     */
    fun listSafArchivesInGrantedFolder(treeUri: Uri): List<File> {
        val validExts = setOf("zip", "7z", "rar", "apk", "xapk", "apks", "jar", "tar", "gz", "tgz", "001")
        val posixRoot = tryResolveDirectTreeRootDir(treeUri) ?: return emptyList()
        if (!posixRoot.exists() || !posixRoot.isDirectory) return emptyList()
        return try {
            posixRoot.listFiles()?.filter { file ->
                file.isFile &&
                    !file.name.startsWith(".") &&
                    file.length() > 0L &&
                    (file.extension.lowercase(Locale.US) in validExts || file.name.endsWith(".tar.gz", ignoreCase = true))
            } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Resolves a SAF DocumentTree URI to a direct POSIX directory if readable, or stages its
     * contents into [stagingParentDir] so the user can pack any external folder from device storage.
     */
    fun resolveOrStageSafTreeFolder(
        context: Context,
        treeUri: Uri,
        stagingParentDir: File
    ): File? {
        val directDir = tryResolveDirectTreeRootDir(treeUri)
        if (directDir != null && directDir.exists() && directDir.isDirectory && directDir.canRead()) {
            val children = try { directDir.listFiles() } catch (_: Throwable) { null }
            if (children != null) {
                return directDir
            }
        }
        return try {
            val folderInfo = parseGrantedFolderUri(treeUri)
            val safeFolderName = folderInfo.displayName
                .replace(Regex("[^a-zA-Z0-9._\\- ()]"), "_")
                .ifBlank { "Folder_${System.currentTimeMillis() / 1000}" }
            val targetRoot = File(stagingParentDir, safeFolderName)
            if (targetRoot.exists() && !isCompressionFileActive(targetRoot)) {
                cleanupCompressionFile(context, targetRoot, "restage_saf_folder")
            }
            targetRoot.mkdirs()

            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            copySafFolderRecursiveToLocal(context, treeUri, treeDocId, targetRoot)
            if (targetRoot.listFiles()?.isNotEmpty() == true) {
                runCatching {
                    android.util.Log.d(
                        TAG_STORAGE,
                        "Staged SAF folder for compression: path=${targetRoot.absolutePath} bytes=${getDirectorySize(targetRoot)}"
                    )
                }
                targetRoot
            } else {
                cleanupCompressionFile(context, targetRoot, "empty_staged_saf_folder", forceUnregister = true)
                null
            }
        } catch (t: Throwable) {
            runCatching {
                android.util.Log.w(TAG_STORAGE, "Failed staging SAF folder for compression: ${t.message}")
            }
            null
        }
    }

    private fun copySafFolderRecursiveToLocal(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        targetDir: File
    ) {
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol) ?: continue
                    if (name.startsWith(".")) continue
                    val mime = cursor.getString(mimeCol) ?: ""
                    val safeName = name.replace(Regex("[^a-zA-Z0-9._\\- ()]"), "_")
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        val subDir = File(targetDir, safeName).apply { mkdirs() }
                        copySafFolderRecursiveToLocal(context, treeUri, docId, subDir)
                    } else {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        val destFile = File(targetDir, safeName)
                        context.contentResolver.openInputStream(docUri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output, bufferSize = 64 * 1024)
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }
}
