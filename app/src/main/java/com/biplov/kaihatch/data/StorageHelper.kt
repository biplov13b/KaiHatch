package com.biplov.kaihatch.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.core.content.FileProvider
import com.biplov.kaihatch.engine.ZipCryptoCipher
import com.biplov.kaihatch.engine.ZipParser
import com.biplov.kaihatch.model.ZipEntryInfo
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
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
    val isSaf: Boolean = false
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
    val locationDescription: String = ""
)

data class TargetOutput(
    val outputStream: java.io.OutputStream,
    val channel: FileChannel,
    val localFile: File? = null,
    val documentUri: Uri? = null,
    val close: () -> Unit
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

    fun getTempDir(context: Context): File {
        val dir = File(context.cacheDir, "compress_temp")
        if (!dir.exists()) dir.mkdirs()
        return dir
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
     * Returns the currently granted persistent folder (if any), checking both SharedPreferences
     * and Android's persisted DocumentTree URI permissions.
     */
    fun getGrantedFolderInfo(context: Context): GrantedFolderInfo? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedUriStr = prefs.getString(KEY_SELECTED_FOLDER_URI, null)
        if (!savedUriStr.isNullOrEmpty()) {
            return parseGrantedFolderUri(Uri.parse(savedUriStr), true)
        }

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
                    Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
                    return
                }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
            } ?: run {
                Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
                return
            }

            val ext = fileName.substringAfterLast('.', "")
            val mimeType = getMimeTypeFromExtension(ext)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, targetUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Share $fileName").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to share file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
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
                    "Unable to open $fileName: file is missing or inaccessible.",
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
                    "No compatible app installed to open $fileName.",
                    Toast.LENGTH_SHORT
                ).show()
                return false
            }

            val chooser = Intent.createChooser(effectiveIntent, "Open $fileName").apply {
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
                "No compatible app installed to open $fileName.",
                Toast.LENGTH_SHORT
            ).show()
            false
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Unable to open $fileName: ${e.localizedMessage ?: "unknown error"}",
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

    fun tryResolveDirectTreeRootDir(treeUri: Uri): File? {
        return try {
            if (treeUri.scheme == "file") {
                val p = treeUri.path ?: return null
                val dir = File(p)
                if (!dir.exists()) dir.mkdirs()
                return if (dir.exists() && dir.isDirectory) dir else null
            }
            val treeDocId = try {
                DocumentsContract.getTreeDocumentId(treeUri)
            } catch (_: Throwable) {
                val raw = URLDecoder.decode(treeUri.toString(), "UTF-8")
                raw.substringAfter("/tree/", "").substringBefore("/document/")
            }
            if (!treeDocId.contains(':')) return null
            val vol = treeDocId.substringBefore(':').lowercase(Locale.US)
            val rel = treeDocId.substringAfter(':').trim('/')
            val rootDir = when (vol) {
                "primary" -> Environment.getExternalStorageDirectory()
                "home" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                else -> return null
            }
            val parentDir = if (rel.isEmpty()) rootDir else File(rootDir, rel)
            if (!parentDir.exists()) {
                parentDir.mkdirs()
            }
            if (parentDir.exists() && parentDir.isDirectory) parentDir else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Resolves a direct POSIX [File] path for a subfolder inside a `primary:` DocumentTree URI
     * (e.g., `/storage/emulated/0/Download/<subfolderName>`) if the kernel FUSE mount permits
     * direct POSIX file creation by this app inside the app-created subfolder.
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

            // Verify POSIX write access inside the subfolder with a non-media probe file
            val probe = File(candidateDir, ".kaihatch_io_probe.bin")
            FileOutputStream(probe).use { fos ->
                fos.write(1)
                fos.flush()
            }
            probe.delete()
            candidateDir
        } catch (_: Throwable) {
            null
        }
    }

    fun openTargetOutput(
        context: Context?,
        targetDir: File?,
        targetTreeUri: Uri?,
        targetFolderDocUri: Uri?,
        relativePath: String,
        expectedBytes: Long
    ): TargetOutput {
        val isAppInternalFallback = context != null && targetDir != null && (
            targetDir.absolutePath.startsWith(context.cacheDir.absolutePath) ||
                targetDir.absolutePath.startsWith(context.filesDir.absolutePath)
            )
        // Fast path: if targetDir is a direct-writable POSIX directory (or local extraction without SAF),
        // try direct POSIX FileOutputStream first to avoid per-file SAF Binder IPC overhead.
        if (targetDir != null && (targetTreeUri == null || !isAppInternalFallback)) {
            try {
                val destFile = resolveSafeDestFile(targetDir, relativePath)
                val parent = destFile.parentFile
                if (parent != null && !parent.exists()) {
                    synchronized(posixDirLock) {
                        if (!parent.exists()) parent.mkdirs()
                    }
                }
                val fos = FileOutputStream(destFile)
                return TargetOutput(
                    outputStream = fos,
                    channel = fos.channel,
                    localFile = destFile,
                    documentUri = null,
                    close = {
                        try { fos.flush() } catch (_: Exception) {}
                        try { fos.fd.sync() } catch (_: Throwable) {}
                        try { fos.close() } catch (_: Exception) {}
                    }
                )
            } catch (_: Throwable) {
                // Fall through to SAF ContentResolver if FUSE blocks direct POSIX creation for this file
            }
        }

        if (context != null && targetTreeUri != null && targetFolderDocUri != null) {
            val safOut = openSafTargetOutput(context, targetTreeUri, targetFolderDocUri, relativePath, expectedBytes)
            if (safOut != null) return safOut
        }
        val safeDir = targetDir ?: File("/tmp")
        val destFile = resolveSafeDestFile(safeDir, relativePath)
        val parent = destFile.parentFile
        if (parent != null && !parent.exists()) {
            synchronized(posixDirLock) {
                if (!parent.exists()) parent.mkdirs()
            }
        }
        val fos = FileOutputStream(destFile)
        return TargetOutput(
            outputStream = fos,
            channel = fos.channel,
            localFile = destFile,
            documentUri = null,
            close = {
                try { fos.flush() } catch (_: Exception) {}
                try { fos.fd.sync() } catch (_: Throwable) {}
                try { fos.close() } catch (_: Exception) {}
            }
        )
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
        expectedBytes: Long
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

            // Create document directly without an O(N) pre-delete directory cursor scan;
            // only fall back to deleteChildIfPresent if createDocument returns null.
            var fileDocUri = try {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    currentParent,
                    mimeType,
                    fileName
                )
            } catch (_: Throwable) {
                null
            }
            if (fileDocUri == null) {
                deleteChildIfPresent(context, treeUri, currentParent, fileName)
                fileDocUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    currentParent,
                    mimeType,
                    fileName
                ) ?: return null
            }

            // Open in sequential write-only ("w") mode first so FUSE uses write-behind page caching
            val pfd = try {
                context.contentResolver.openFileDescriptor(fileDocUri, "w")
            } catch (_: Throwable) {
                null
            } ?: context.contentResolver.openFileDescriptor(fileDocUri, "rw")
                ?: return null

            val fos = FileOutputStream(pfd.fileDescriptor)
            TargetOutput(
                outputStream = fos,
                channel = fos.channel,
                localFile = null,
                documentUri = fileDocUri,
                close = {
                    try { fos.flush() } catch (_: Exception) {}
                    try { fos.close() } catch (_: Exception) {}
                    try { pfd.close() } catch (_: Exception) {}
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
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
                        break
                    }
                }
            }
        } catch (_: Exception) {}
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

        val tempWorkingFile = File(getTempDir(context), fileName)
        if (tempWorkingFile.exists()) {
            tempWorkingFile.delete()
        }
        return GrantedFolderArchiveTarget(
            workingFile = tempWorkingFile,
            directPosixFile = null,
            documentUri = createdDocUri,
            folderDisplayLabel = folderInfo.displayName,
            requiresSafStreamOnFinish = createdDocUri != null
        )
    }

    fun finalizeArchiveTargetInGrantedFolder(
        context: Context,
        target: GrantedFolderArchiveTarget
    ): Long {
        if (!target.requiresSafStreamOnFinish) {
            return target.workingFile.length()
        }
        val docUri = target.documentUri ?: return target.workingFile.length()
        val finalSize = target.workingFile.length()
        try {
            exportFileToUri(context, target.workingFile, docUri)
        } finally {
            try {
                target.workingFile.delete()
            } catch (_: Throwable) {
            }
        }
        return finalSize
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
                        val safChildren = listSafFolderChildren(context, treeUri, docId)
                        val posixSubfolder = posixRoot?.let { File(it, displayName) }?.takeIf { it.exists() && it.isDirectory }
                        val posixChildren = if (posixSubfolder != null) {
                            val safMapByName = safChildren.associateBy { it.name }
                            listFilesFlat(posixSubfolder).filter { !it.name.startsWith(".kaihatch_") }.map { f ->
                                val matchSaf = safMapByName[f.name]
                                ExtractedFileEntry(
                                    id = matchSaf?.id ?: f.absolutePath,
                                    name = f.name,
                                    sizeBytes = f.length().coerceAtLeast(matchSaf?.sizeBytes ?: 0L),
                                    mimeType = getMimeTypeFromExtension(f.extension),
                                    localFile = f,
                                    safUri = matchSaf?.safUri,
                                    isSaf = true
                                )
                            }
                        } else emptyList()

                        val mergedChildren = if (posixChildren.size >= safChildren.size && posixChildren.isNotEmpty()) {
                            posixChildren
                        } else {
                            safChildren
                        }
                        val totalSize = mergedChildren.sumOf { it.sizeBytes }
                        if (mergedChildren.isNotEmpty() && totalSize > 0L) {
                            result.add(
                                ExtractedFolderEntry(
                                    id = docId,
                                    name = displayName,
                                    isSaf = true,
                                    localFile = posixSubfolder,
                                    safUri = docUri,
                                    totalSizeBytes = totalSize,
                                    children = mergedChildren,
                                    locationDescription = "Folder \"${folderInfo.displayName}\" (${folderInfo.readablePath})"
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
                        val files = listFilesFlat(folder).filter { !it.name.startsWith(".kaihatch_") }.map { f ->
                            ExtractedFileEntry(
                                id = f.absolutePath,
                                name = f.name,
                                sizeBytes = f.length(),
                                mimeType = getMimeTypeFromExtension(f.extension),
                                localFile = f,
                                safUri = null,
                                isSaf = true
                            )
                        }
                        val totalBytes = files.sumOf { it.sizeBytes }
                        if (files.isNotEmpty() && totalBytes > 0L) {
                            result.add(
                                ExtractedFolderEntry(
                                    id = folder.absolutePath,
                                    name = folder.name,
                                    isSaf = true,
                                    localFile = folder,
                                    safUri = null,
                                    totalSizeBytes = totalBytes,
                                    children = files,
                                    locationDescription = "Folder \"${folderInfo.displayName}\" (${folderInfo.readablePath})"
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

    private fun listSafFolderChildren(
        context: Context,
        treeUri: Uri,
        parentDocId: String
    ): List<ExtractedFileEntry> {
        val result = mutableListOf<ExtractedFileEntry>()
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
                    val docId = cursor.getString(idCol)
                    val displayName = cursor.getString(nameCol) ?: continue
                    val mime = cursor.getString(mimeCol) ?: "application/octet-stream"
                    val size = if (sizeCol != -1 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L

                    if (mime != DocumentsContract.Document.MIME_TYPE_DIR) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        result.add(
                            ExtractedFileEntry(
                                id = docId,
                                name = displayName,
                                sizeBytes = size,
                                mimeType = mime,
                                localFile = null,
                                safUri = docUri,
                                isSaf = true
                            )
                        )
                    } else {
                        // Recursively add nested files
                        result.addAll(listSafFolderChildren(context, treeUri, docId))
                    }
                }
            }
        } catch (_: Exception) {}
        return result.sortedBy { it.name.lowercase() }
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
            val files = listFilesFlat(folder).map { f ->
                ExtractedFileEntry(
                    id = f.absolutePath,
                    name = f.name,
                    sizeBytes = f.length(),
                    mimeType = getMimeTypeFromExtension(f.extension),
                    localFile = f,
                    safUri = null,
                    isSaf = false
                )
            }
            val totalBytes = files.sumOf { it.sizeBytes }
            if (files.isEmpty() || totalBytes <= 0L) {
                try { folder.deleteRecursively() } catch (_: Exception) {}
            } else {
                result.add(
                    ExtractedFolderEntry(
                        id = folder.absolutePath,
                        name = folder.name,
                        isSaf = false,
                        localFile = folder,
                        safUri = null,
                        totalSizeBytes = totalBytes,
                        children = files,
                        locationDescription = "Local Storage"
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
            if (targetRoot.exists()) targetRoot.deleteRecursively()
            targetRoot.mkdirs()

            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            copySafFolderRecursiveToLocal(context, treeUri, treeDocId, targetRoot)
            if (targetRoot.listFiles()?.isNotEmpty() == true) targetRoot else null
        } catch (_: Throwable) {
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
