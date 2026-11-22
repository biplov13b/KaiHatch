package com.biplov.kaihatch.updater

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.CancellationException

data class UpdateHttpResponse(
    val statusCode: Int,
    val body: String?,
    val etag: String? = null
)

interface UpdateHttpTransport {
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): UpdateHttpResponse

    @Throws(IOException::class)
    fun download(
        url: String,
        headers: Map<String, String>,
        targetFile: File,
        expectedTotalBytes: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean
    )
}

class HttpUrlConnectionUpdateTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000
) : UpdateHttpTransport {

    override fun get(url: String, headers: Map<String, String>): UpdateHttpResponse {
        val connection = openVerifiedConnection(url, headers)
        return try {
            val code = connection.responseCode
            val etag = connection.getHeaderField("ETag")
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }
            }
            UpdateHttpResponse(statusCode = code, body = body, etag = etag)
        } finally {
            connection.disconnect()
        }
    }

    override fun download(
        url: String,
        headers: Map<String, String>,
        targetFile: File,
        expectedTotalBytes: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val connection = openVerifiedConnection(url, headers)
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code while downloading update APK")
            }
            val contentLength = connection.contentLengthLong.takeIf { it > 0L } ?: expectedTotalBytes
            targetFile.parentFile?.mkdirs()

            BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var totalRead = 0L
                    var lastProgressEmit = 0L
                    onProgress(0L, contentLength)
                    while (true) {
                        if (isCancelled()) {
                            throw CancellationException("Update download cancelled")
                        }
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        totalRead += read
                        val now = System.currentTimeMillis()
                        if (now - lastProgressEmit >= 120L || (contentLength > 0L && totalRead >= contentLength)) {
                            lastProgressEmit = now
                            onProgress(totalRead, contentLength)
                        }
                    }
                    output.flush()
                    try {
                        output.fd.sync()
                    } catch (_: Exception) {
                        // Best-effort sync before verification
                    }
                    onProgress(totalRead, if (contentLength > 0L) contentLength else totalRead)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openVerifiedConnection(
        initialUrl: String,
        headers: Map<String, String>,
        maxRedirects: Int = 5
    ): HttpURLConnection {
        var currentUrl = initialUrl
        repeat(maxRedirects + 1) {
            if (!GitHubReleaseClient.isAllowedGitHubUrl(currentUrl)) {
                throw IOException("Refused untrusted update URL: $currentUrl")
            }
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = connection.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) {
                    throw IOException("Redirect without Location header")
                }
                currentUrl = URI(currentUrl).resolve(location).toString()
            } else {
                return connection
            }
        }
        throw IOException("Too many redirects while contacting GitHub")
    }

    companion object {
        private const val BUFFER_SIZE = 32 * 1024
    }
}

class GitHubReleaseClient(
    private val transport: UpdateHttpTransport = HttpUrlConnectionUpdateTransport(),
    private val userAgent: String = DEFAULT_USER_AGENT
) {

    fun fetchLatestStableRelease(prefs: UpdatePreferences): GitHubReleaseInfo? {
        val headers = linkedMapOf(
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
            "User-Agent" to userAgent
        )
        val cachedEtag = prefs.cachedReleaseEtag
        val cachedJson = prefs.cachedReleaseJson
        if (!cachedEtag.isNullOrBlank() && !cachedJson.isNullOrBlank()) {
            headers["If-None-Match"] = cachedEtag
        }

        val latestResp = transport.get(LATEST_RELEASE_API_URL, headers)
        val releaseJsonObject: JSONObject? = when (latestResp.statusCode) {
            304 -> {
                cachedJson?.let { raw ->
                    try {
                        JSONObject(raw)
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            200 -> {
                val body = latestResp.body ?: return null
                if (!latestResp.etag.isNullOrBlank()) {
                    prefs.cachedReleaseEtag = latestResp.etag
                    prefs.cachedReleaseJson = body
                }
                JSONObject(body)
            }
            404 -> {
                // Repository may not have a marked "latest" release yet; inspect recent releases.
                fetchBestStableFromList()
            }
            else -> throw IOException("GitHub API returned HTTP ${latestResp.statusCode}")
        }

        val parsed = releaseJsonObject?.let { parseStableReleaseObject(it) }
            ?: if (latestResp.statusCode != 404) {
                fetchBestStableFromList()?.let { parseStableReleaseObject(it) }
            } else {
                null
            }
            ?: return null

        val resolvedSha256 = parsed.expectedSha256 ?: fetchSha256FromChecksumAsset(parsed)
        return parsed.copy(expectedSha256 = resolvedSha256)
    }

    private fun fetchBestStableFromList(): JSONObject? {
        val headers = mapOf(
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
            "User-Agent" to userAgent
        )
        val response = transport.get(RELEASES_LIST_API_URL, headers)
        if (response.statusCode !in 200..299 || response.body.isNullOrBlank()) {
            if (response.statusCode == 404) return null
            throw IOException("GitHub releases list returned HTTP ${response.statusCode}")
        }
        val array = JSONArray(response.body)
        var bestObj: JSONObject? = null
        var bestVersion: String? = null
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val candidate = parseStableReleaseObject(obj) ?: continue
            if (bestVersion == null || compareVersions(candidate.versionName, bestVersion) > 0) {
                bestObj = obj
                bestVersion = candidate.versionName
            }
        }
        return bestObj
    }

    private fun fetchSha256FromChecksumAsset(release: GitHubReleaseInfo): String? {
        val checksumUrl = release.checksumAssetUrl ?: return null
        if (!isAllowedGitHubUrl(checksumUrl)) return null
        return try {
            val response = transport.get(
                url = checksumUrl,
                headers = mapOf("User-Agent" to userAgent)
            )
            if (response.statusCode in 200..299 && !response.body.isNullOrBlank()) {
                extractSha256FromText(response.body, release.apkAssetName)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun downloadApk(
        release: GitHubReleaseInfo,
        targetFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean
    ) {
        if (!isAllowedGitHubUrl(release.apkDownloadUrl)) {
            throw IOException("Refused untrusted APK download URL")
        }
        transport.download(
            url = release.apkDownloadUrl,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Accept" to "application/octet-stream"
            ),
            targetFile = targetFile,
            expectedTotalBytes = release.apkSizeBytes,
            onProgress = onProgress,
            isCancelled = isCancelled
        )
    }

    companion object {
        const val GITHUB_OWNER = "biplov13b"
        const val GITHUB_REPO = "KaiHatch"
        const val LATEST_RELEASE_API_URL =
            "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
        const val RELEASES_LIST_API_URL =
            "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases?per_page=10"
        private const val DEFAULT_USER_AGENT = "KaiHatch-Android-Updater"

        private val SHA256_HEX_REGEX = Regex("""\b([0-9a-fA-F]{64})\b""")
        private val SHA256_LABELED_REGEX =
            Regex("""(?i)sha-?256\s*[:=*\-` ]+\s*([0-9a-fA-F]{64})\b""")
        private val ALLOWED_EXACT_HOSTS = setOf(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com"
        )

        fun isAllowedGitHubUrl(url: String): Boolean {
            return try {
                val uri = URI(url.trim())
                val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
                val host = uri.host?.lowercase(Locale.US) ?: return false
                if (scheme != "https") return false
                host in ALLOWED_EXACT_HOSTS || host.endsWith(".githubusercontent.com")
            } catch (_: Exception) {
                false
            }
        }

        fun normalizeVersionName(raw: String): String {
            return raw.trim().removePrefix("v").removePrefix("V").trim()
        }

        fun compareVersions(v1: String, v2: String): Int {
            val parts1 = extractNumericVersionParts(v1)
            val parts2 = extractNumericVersionParts(v2)
            val maxLen = maxOf(parts1.size, parts2.size)
            for (i in 0 until maxLen) {
                val p1 = parts1.getOrElse(i) { 0L }
                val p2 = parts2.getOrElse(i) { 0L }
                if (p1 != p2) return p1.compareTo(p2)
            }
            return 0
        }

        fun isNewerVersion(candidate: String, current: String): Boolean {
            return compareVersions(candidate, current) > 0
        }

        private fun extractNumericVersionParts(version: String): List<Long> {
            val cleaned = normalizeVersionName(version)
                .substringBefore('-')
                .substringBefore('+')
            if (cleaned.isBlank()) return listOf(0L)
            return cleaned.split('.').map { segment ->
                segment.filter { it.isDigit() }.toLongOrNull() ?: 0L
            }
        }

        fun parseStableReleaseObject(obj: JSONObject): GitHubReleaseInfo? {
            val isDraft = obj.optBoolean("draft", false)
            val isPrerelease = obj.optBoolean("prerelease", false)
            if (isDraft || isPrerelease) return null

            val tagName = obj.optString("tag_name").trim()
            if (tagName.isEmpty()) return null

            val versionName = normalizeVersionName(tagName)
            if (versionName.isEmpty()) return null

            val assets = obj.optJSONArray("assets") ?: return null
            val apkCandidates = mutableListOf<JSONObject>()
            var checksumAssetUrl: String? = null

            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name").trim()
                val downloadUrl = asset.optString("browser_download_url").trim()
                if (name.isEmpty() || downloadUrl.isEmpty() || !isAllowedGitHubUrl(downloadUrl)) {
                    continue
                }
                val lower = name.lowercase(Locale.US)
                when {
                    lower.endsWith(".apk") -> apkCandidates.add(asset)
                    lower.endsWith(".sha256") ||
                        lower.endsWith(".sha256sum") ||
                        lower.contains("sha256") ||
                        lower.contains("checksum") -> {
                        if (checksumAssetUrl == null || lower.contains(".apk")) {
                            checksumAssetUrl = downloadUrl
                        }
                    }
                }
            }

            if (apkCandidates.isEmpty()) return null

            val selectedApk = selectBestApkAsset(apkCandidates) ?: return null
            val apkAssetName = selectedApk.optString("name").trim()
            val apkDownloadUrl = selectedApk.optString("browser_download_url").trim()
            val apkSizeBytes = selectedApk.optLong("size", -1L)

            val releaseBody = obj.optString("body", "").trim()
            val assetDigest = parseGitHubAssetDigest(selectedApk.optString("digest"))
            val bodyDigest = extractSha256FromText(releaseBody, apkAssetName)

            val releaseName = obj.optString("name").trim().ifEmpty { tagName }
            val htmlUrl = obj.optString("html_url").trim()
                .takeIf { isAllowedGitHubUrl(it) }
                ?: "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/tag/$tagName"

            return GitHubReleaseInfo(
                tagName = tagName,
                versionName = versionName,
                releaseName = releaseName,
                releaseNotes = releaseBody,
                htmlUrl = htmlUrl,
                publishedAt = obj.optString("published_at", "").trim(),
                apkAssetName = apkAssetName,
                apkDownloadUrl = apkDownloadUrl,
                apkSizeBytes = apkSizeBytes,
                expectedSha256 = assetDigest ?: bodyDigest,
                checksumAssetUrl = checksumAssetUrl,
                isDraft = false,
                isPrerelease = false
            )
        }

        private fun selectBestApkAsset(candidates: List<JSONObject>): JSONObject? {
            return candidates.maxByOrNull { asset ->
                val name = asset.optString("name").lowercase(Locale.US)
                var score = 0
                if (name.contains("kaihatch")) score += 4
                if (name.contains("release")) score += 3
                if (name.contains("universal")) score += 2
                if (name.contains("arm64")) score += 1
                if (name.contains("debug")) score -= 10
                if (asset.optString("digest").startsWith("sha256:", ignoreCase = true)) score += 2
                score
            }
        }

        internal fun parseGitHubAssetDigest(rawDigest: String?): String? {
            if (rawDigest.isNullOrBlank()) return null
            val trimmed = rawDigest.trim()
            val hex = if (trimmed.startsWith("sha256:", ignoreCase = true)) {
                trimmed.substringAfter(':').trim()
            } else {
                return null
            }
            return hex.lowercase(Locale.US).takeIf { isValidSha256Hex(it) }
        }

        internal fun extractSha256FromText(text: String, apkAssetName: String): String? {
            if (text.isBlank()) return null
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

            // 1. Prefer a line mentioning the exact APK asset name
            for (line in lines) {
                if (line.contains(apkAssetName, ignoreCase = true)) {
                    SHA256_HEX_REGEX.find(line)?.groupValues?.getOrNull(1)?.let { hex ->
                        return hex.lowercase(Locale.US)
                    }
                }
            }

            // 2. Explicit "SHA-256: <64-hex>" label
            SHA256_LABELED_REGEX.find(text)?.groupValues?.getOrNull(1)?.let { hex ->
                return hex.lowercase(Locale.US)
            }

            // 3. Single standalone checksum file (e.g., "<64-hex>" or "<64-hex>  app.apk")
            if (lines.size <= 2) {
                SHA256_HEX_REGEX.find(lines.first())?.groupValues?.getOrNull(1)?.let { hex ->
                    return hex.lowercase(Locale.US)
                }
            }

            return null
        }

        fun isValidSha256Hex(value: String?): Boolean {
            if (value == null || value.length != 64) return false
            return value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }
    }
}
