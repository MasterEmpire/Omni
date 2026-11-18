package com.omni.plugin.browser.services

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.omni.hub.api.HostBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class SiteCacheManager(
    private val context: Context,
    private val bridge: HostBridge
) {
    companion object {
        private val initLock = Any()
        @Volatile
        private var initialized = false
        private val sharedEnabledDomains: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        private val fileLocks = ConcurrentHashMap<String, Any>()

        private fun ensureLoaded(enabledFile: File) {
            if (initialized) return
            synchronized(initLock) {
                if (initialized) return
                try {
                    if (enabledFile.exists()) {
                        val json = JSONArray(enabledFile.readText(Charsets.UTF_8))
                        for (i in 0 until json.length()) {
                            sharedEnabledDomains.add(json.getString(i).lowercase(Locale.US))
                        }
                    }
                } catch (_: Exception) {}
                initialized = true
            }
        }
    }

    private val cacheDir = File(context.filesDir, "omni_site_cache").apply { mkdirs() }
    private val enabledDomainsFile = File(cacheDir, "enabled_domains.json")

    private val staticExtensions = setOf(
        "js", "mjs", "cjs", "css", "woff2", "woff", "ttf", "otf", "eot",
        "wasm", "png", "jpg", "jpeg", "webp", "gif", "svg", "ico", "mp4", "webm"
    )

    private val trackerHosts = setOf(
        "googletagmanager.com", "google-analytics.com", "analytics.google.com",
        "doubleclick.net", "googleadservices.com", "googlesyndication.com",
        "datadoghq.com", "clarity.ms", "hotjar.com", "scorecardresearch.com",
        "facebook.net", "csp.withgoogle.com", "play.google.com"
    )

    private val dynamicApiHosts = setOf(
        "alkalimakersuite-pa.clients6.google.com", "waa-pa.clients6.google.com",
        "people-pa.clients6.google.com", "ogads-pa.clients6.google.com",
        "content.googleapis.com"
    )

    init {
        ensureLoaded(enabledDomainsFile)
    }

    private fun saveEnabledDomains() {
        synchronized(initLock) {
            try {
                val snapshot = synchronized(sharedEnabledDomains) { sharedEnabledDomains.toList() }
                val json = JSONArray(snapshot)
                enabledDomainsFile.writeText(json.toString(), Charsets.UTF_8)
            } catch (_: Exception) {}
        }
    }

    private fun sanitizeDomain(domain: String): String {
        return domain.lowercase(Locale.US).replace(Regex("[^a-z0-9._-]"), "_")
    }

    fun isSiteCacheEnabled(domain: String): Boolean {
        if (domain.isEmpty()) return false
        val clean = domain.lowercase(Locale.US).removePrefix("www.")
        synchronized(sharedEnabledDomains) {
            return sharedEnabledDomains.contains(clean) || sharedEnabledDomains.contains(domain.lowercase(Locale.US))
        }
    }

    fun toggleSiteCache(domain: String): Boolean {
        val clean = domain.lowercase(Locale.US).removePrefix("www.")
        if (clean.isEmpty()) return false
        val next = !isSiteCacheEnabled(clean)
        synchronized(sharedEnabledDomains) {
            if (next) {
                sharedEnabledDomains.add(clean)
            } else {
                sharedEnabledDomains.remove(clean)
                sharedEnabledDomains.remove(domain.lowercase(Locale.US))
            }
        }
        saveEnabledDomains()
        return next
    }

    fun getDomainCacheSize(domain: String): Long {
        if (domain.isEmpty()) return 0L
        val clean = sanitizeDomain(domain.removePrefix("www."))
        val dir = File(cacheDir, clean)
        if (!dir.exists() || !dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile && it.name.endsWith(".bin") }.sumOf { it.length() }
    }

    fun clearDomainCache(domain: String) {
        if (domain.isEmpty()) return
        val clean = sanitizeDomain(domain.removePrefix("www."))
        val dir = File(cacheDir, clean)
        if (dir.exists()) {
            dir.deleteRecursively()
            bridge.log("SITE_CACHE", "Purged cache for domain: $clean")
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    private fun hashUrl(url: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(url.toByteArray(Charsets.UTF_8))
            bytes.take(16).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            url.hashCode().toUInt().toString(16)
        }
    }

    private fun getCookies(profileId: String, url: String): String? {
        return try {
            if (profileId != "default" && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().getOrCreateProfile(profileId).cookieManager.getCookie(url)
            } else {
                CookieManager.getInstance().getCookie(url)
            }
        } catch (_: Exception) {
            try { CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null }
        }
    }

    private fun inferMime(path: String, headerMime: String?): String {
        if (!headerMime.isNullOrBlank() && !headerMime.contains("octet-stream")) {
            return headerMime.substringBefore(";").trim()
        }
        val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "js", "mjs", "cjs" -> "application/javascript"
            "css" -> "text/css"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "wasm" -> "application/wasm"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            else -> if (path.contains("/_/js/") || path.contains("/_/scs/")) "application/javascript"
            else if (path.contains("/_/ss/")) "text/css"
            else "application/octet-stream"
        }
    }

    private fun isCacheableAssetResponse(rawMime: String, ext: String, cacheControl: String): Boolean {
        val cc = cacheControl.lowercase(Locale.US)
        if (cc.contains("no-store")) return false
        val lowerMime = rawMime.lowercase(Locale.US)
        if (lowerMime.contains("text/html") || lowerMime.contains("application/xhtml")) return false
        return lowerMime.contains("javascript") ||
            lowerMime.contains("ecmascript") ||
            lowerMime.contains("text/css") ||
            lowerMime.contains("font") ||
            lowerMime.contains("image/") ||
            lowerMime.contains("application/wasm") ||
            lowerMime.contains("video/") ||
            lowerMime.contains("audio/") ||
            (lowerMime.isEmpty() && ext in staticExtensions)
    }

    private fun computeExpiryMs(cacheControl: String, now: Long): Pair<Boolean, Long> {
        val cc = cacheControl.lowercase(Locale.US)
        val isImmutable = cc.contains("immutable")
        if (cc.contains("no-cache") || cc.contains("max-age=0")) {
            return Pair(false, 0L)
        }
        val maxAgeMatch = Regex("max-age\\s*=\\s*(\\d+)").find(cc)
        val maxAgeSec = maxAgeMatch?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        val ttlMs = when {
            isImmutable -> 30L * 24 * 60 * 60 * 1000L
            maxAgeSec > 0L -> minOf(maxAgeSec * 1000L, 30L * 24 * 60 * 60 * 1000L)
            else -> 24L * 60 * 60 * 1000L
        }
        return Pair(isImmutable || maxAgeSec >= 86400L, now + ttlMs)
    }

    private fun isEligibleAssetUrl(reqUrl: String, reqHost: String, reqPath: String, ext: String): Boolean {
        if (ext == "html" || ext == "htm" || ext == "map") return false
        if (trackerHosts.any { reqHost == it || reqHost.endsWith(".$it") }) return false
        if (dynamicApiHosts.any { reqHost == it || reqHost.endsWith(".$it") }) return false

        val lowerUrl = reqUrl.lowercase(Locale.US)
        if (reqPath.contains("/api/") ||
            reqPath.contains("/backend-api/") ||
            reqPath.contains("/graphql") ||
            reqPath.contains("/\$rpc/") ||
            reqPath.contains("/batchexecute") ||
            reqPath.contains("/ces/") ||
            reqPath.contains("/sentinel/") ||
            reqPath.contains("/realtime/") ||
            reqPath.contains("/cdn-cgi/") ||
            reqPath.contains("/cspreport") ||
            reqPath.contains("/generate_204") ||
            reqPath.contains("/onboarding/auth_warmup") ||
            lowerUrl.contains("socket") ||
            lowerUrl.contains("webchannel") ||
            lowerUrl.contains("streamgenerate") ||
            lowerUrl.contains("generatecontent")
        ) {
            return false
        }

        if (ext in staticExtensions) return true

        // Extensionless CDN & Google Boq / Closure / GAPI bundle patterns
        if (reqPath.contains("/_/js/") ||
            reqPath.contains("/_/ss/") ||
            reqPath.contains("/_/scs/") ||
            reqPath.contains("/_/mss/") ||
            reqPath.contains("/js/bg/") ||
            reqPath.contains("/cdn/assets/") ||
            reqPath.contains("/static/") ||
            reqPath.contains("/assets/") ||
            lowerUrl.contains("cb=gapi.loaded")
        ) {
            return true
        }

        // Known static CDN hosts (excluding bare root '/' and telemetry already filtered above)
        if (reqPath.length > 2 && (
                reqHost.endsWith("gstatic.com") ||
                reqHost.endsWith("oaistatic.com") ||
                reqHost == "fonts.googleapis.com" ||
                reqHost == "apis.google.com" ||
                reqHost == "cdn.jsdelivr.net" ||
                reqHost == "cdnjs.cloudflare.com" ||
                reqHost == "unpkg.com"
            )
        ) {
            return true
        }

        return false
    }

    private fun readAllBytesCapped(conn: HttpURLConnection, capBytes: Int = 30 * 1024 * 1024): ByteArray? {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        var total = 0
        conn.inputStream.use { input ->
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                total += read
                if (total > capBytes) return null
                baos.write(buf, 0, read)
            }
        }
        return baos.toByteArray()
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, "${target.name}.${Thread.currentThread().id}.tmp")
        tmp.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    fun interceptRequest(
        request: WebResourceRequest?,
        currentPageHost: String,
        profileId: String = "default"
    ): WebResourceResponse? {
        if (request == null || !request.method.equals("GET", ignoreCase = true) || request.isForMainFrame) {
            return null
        }

        val reqUri = request.url ?: return null
        val scheme = reqUri.scheme?.lowercase(Locale.US) ?: ""
        if (scheme != "http" && scheme != "https") return null

        val reqUrl = reqUri.toString()
        val reqHost = reqUri.host?.lowercase(Locale.US) ?: return null
        val reqPath = reqUri.path?.lowercase(Locale.US) ?: ""
        val ext = reqPath.substringAfterLast('.', "")

        val referer = request.requestHeaders["Referer"] ?: ""
        val refererHost = if (referer.isNotEmpty()) {
            try { Uri.parse(referer).host?.lowercase(Locale.US)?.removePrefix("www.") } catch (_: Exception) { null }
        } else null

        val cleanPageHost = currentPageHost.lowercase(Locale.US).removePrefix("www.")
        val cleanReqHost = reqHost.removePrefix("www.")

        val targetDomain = when {
            cleanPageHost.isNotEmpty() && isSiteCacheEnabled(cleanPageHost) -> cleanPageHost
            !refererHost.isNullOrEmpty() && isSiteCacheEnabled(refererHost) -> refererHost
            isSiteCacheEnabled(cleanReqHost) -> cleanReqHost
            else -> return null
        }

        if (!isEligibleAssetUrl(reqUrl, reqHost, reqPath, ext)) {
            return null
        }

        val domainDir = File(cacheDir, sanitizeDomain(targetDomain)).apply { mkdirs() }
        val fileHash = hashUrl(reqUrl)
        val lock = fileLocks.getOrPut("$targetDomain/$fileHash") { Any() }
        val binFile = File(domainDir, "$fileHash.bin")
        val metaFile = File(domainDir, "$fileHash.meta")
        val now = System.currentTimeMillis()

        // 1. Check Existing Cache
        var cachedMeta: JSONObject? = null
        if (binFile.exists() && binFile.length() > 0 && metaFile.exists()) {
            try {
                cachedMeta = JSONObject(metaFile.readText(Charsets.UTF_8))
                val mime = cachedMeta.optString("mime", "application/javascript")
                val isImmutable = cachedMeta.optBoolean("is_immutable", false)
                val expiresAt = cachedMeta.optLong("expires_at", 0L)

                if (isImmutable || now < expiresAt) {
                    val respHeaders = mutableMapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "X-Omni-Cache" to "HIT-IMMUTABLE"
                    )
                    return WebResourceResponse(mime, "UTF-8", 200, "OK", respHeaders, binFile.inputStream())
                }
            } catch (_: Exception) {
                cachedMeta = null
            }
        }

        // 2. Conditional Revalidation (if expired & we have ETag/Last-Modified) or Fresh Fetch
        val etag = cachedMeta?.optString("etag", "") ?: ""
        val lastModified = cachedMeta?.optString("last_modified", "") ?: ""
        val cachedMime = cachedMeta?.optString("mime", "application/javascript") ?: "application/javascript"

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(reqUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 12000
                instanceFollowRedirects = true
                request.requestHeaders.forEach { (k, v) ->
                    if (!k.equals("If-None-Match", true) && !k.equals("If-Modified-Since", true) && !k.equals("Accept-Encoding", true)) {
                        setRequestProperty(k, v)
                    }
                }
                val cookie = getCookies(profileId, reqUrl)
                if (!cookie.isNullOrEmpty()) {
                    setRequestProperty("Cookie", cookie)
                }
                if (etag.isNotEmpty()) setRequestProperty("If-None-Match", etag)
                if (lastModified.isNotEmpty()) setRequestProperty("If-Modified-Since", lastModified)
            }

            val code = conn.responseCode
            if ((code == HttpURLConnection.HTTP_NOT_MODIFIED || code == 304) && binFile.exists() && cachedMeta != null) {
                val cc = conn.getHeaderField("Cache-Control") ?: ""
                val (imm, exp) = computeExpiryMs(cc, now)
                synchronized(lock) {
                    cachedMeta.put("is_immutable", imm)
                    cachedMeta.put("expires_at", maxOf(exp, now + 86400_000L))
                    writeAtomic(metaFile, cachedMeta.toString().toByteArray(Charsets.UTF_8))
                }
                val respHeaders = mutableMapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "X-Omni-Cache" to "HIT-304"
                )
                return WebResourceResponse(cachedMime, "UTF-8", 200, "OK", respHeaders, binFile.inputStream())
            }

            if (code in 200..299) {
                val rawMime = conn.contentType ?: ""
                val cc = conn.getHeaderField("Cache-Control") ?: ""
                val bytes = readAllBytesCapped(conn) ?: return null
                if (bytes.isEmpty()) return null

                val mime = inferMime(reqPath, rawMime)
                val respHeaders = mutableMapOf("Access-Control-Allow-Origin" to "*")

                if (isCacheableAssetResponse(rawMime, ext, cc)) {
                    val newEtag = conn.getHeaderField("ETag") ?: ""
                    val newLastMod = conn.getHeaderField("Last-Modified") ?: ""
                    val (imm, exp) = computeExpiryMs(cc, now)

                    synchronized(lock) {
                        writeAtomic(binFile, bytes)
                        val meta = JSONObject().apply {
                            put("url", reqUrl)
                            put("mime", mime)
                            put("etag", newEtag)
                            put("last_modified", newLastMod)
                            put("is_immutable", imm)
                            put("expires_at", exp)
                        }
                        writeAtomic(metaFile, meta.toString().toByteArray(Charsets.UTF_8))
                    }
                    respHeaders["X-Omni-Cache"] = if (cachedMeta != null) "UPDATED" else "POPULATED"
                } else {
                    respHeaders["X-Omni-Cache"] = "BYPASS-MIME"
                }

                return WebResourceResponse(mime, "UTF-8", 200, "OK", respHeaders, ByteArrayInputStream(bytes))
            }
        } catch (_: Exception) {
            if (binFile.exists() && binFile.length() > 0 && cachedMeta != null) {
                val respHeaders = mutableMapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "X-Omni-Cache" to "HIT-OFFLINE"
                )
                return WebResourceResponse(cachedMime, "UTF-8", 200, "OK", respHeaders, binFile.inputStream())
            }
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }

        return null
    }
}