package com.omni.plugin.browser.services

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.omni.hub.api.HostBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

class SiteCacheManager(
    private val context: Context,
    private val bridge: HostBridge
) {
    private val cacheDir = File(context.filesDir, "omni_site_cache").apply { mkdirs() }
    private val enabledDomainsFile = File(cacheDir, "enabled_domains.json")
    private val enabledDomains = mutableSetOf<String>()

    private val staticExtensions = setOf(
        "js", "mjs", "cjs", "css", "woff2", "woff", "ttf", "otf", "eot",
        "wasm", "png", "jpg", "jpeg", "webp", "gif", "svg", "ico", "mp4", "webm"
    )

    init {
        loadEnabledDomains()
    }

    private fun loadEnabledDomains() {
        try {
            if (enabledDomainsFile.exists()) {
                val json = JSONArray(enabledDomainsFile.readText(Charsets.UTF_8))
                for (i in 0 until json.length()) {
                    enabledDomains.add(json.getString(i).lowercase(Locale.US))
                }
            }
        } catch (_: Exception) {}
    }

    private fun saveEnabledDomains() {
        try {
            val json = JSONArray(enabledDomains.toList())
            enabledDomainsFile.writeText(json.toString(), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    private fun sanitizeDomain(domain: String): String {
        return domain.lowercase(Locale.US).replace(Regex("[^a-z0-9._-]"), "_")
    }

    fun isSiteCacheEnabled(domain: String): Boolean {
        if (domain.isEmpty()) return false
        val clean = domain.lowercase(Locale.US).removePrefix("www.")
        return enabledDomains.contains(clean) || enabledDomains.contains(domain.lowercase(Locale.US))
    }

    fun toggleSiteCache(domain: String): Boolean {
        val clean = domain.lowercase(Locale.US).removePrefix("www.")
        val next = !isSiteCacheEnabled(clean)
        if (next) {
            enabledDomains.add(clean)
        } else {
            enabledDomains.remove(clean)
            enabledDomains.remove(domain.lowercase(Locale.US))
        }
        saveEnabledDomains()
        return next
    }

    fun getDomainCacheSize(domain: String): Long {
        val clean = sanitizeDomain(domain.removePrefix("www."))
        val dir = File(cacheDir, clean)
        if (!dir.exists() || !dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile && it.name.endsWith(".bin") }.sumOf { it.length() }
    }

    fun clearDomainCache(domain: String) {
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

    private fun inferMime(path: String, headerMime: String?): String {
        if (!headerMime.isNullOrBlank() && !headerMime.contains("octet-stream")) {
            return headerMime.substringBefore(";").trim()
        }
        val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "wasm" -> "application/wasm"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }

    fun interceptRequest(request: WebResourceRequest?, currentHost: String): WebResourceResponse? {
        if (request == null || !request.method.equals("GET", ignoreCase = true) || request.isForMainFrame) {
            return null
        }

        val reqUri = request.url ?: return null
        val reqUrl = reqUri.toString()
        val reqHost = reqUri.host?.lowercase(Locale.US) ?: ""
        val reqPath = reqUri.path?.lowercase(Locale.US) ?: ""
        val ext = reqPath.substringAfterLast('.', "")

        // Never cache search queries, live websockets or API endpoints without static extension
        if (reqPath.contains("/api/") || reqPath.contains("/graphql") || reqUrl.contains("socket")) {
            return null
        }

        val referer = request.requestHeaders["Referer"] ?: ""
        val refererHost = if (referer.isNotEmpty()) {
            try { Uri.parse(referer).host?.lowercase(Locale.US)?.removePrefix("www.") } catch (_: Exception) { null }
        } else null

        val cleanCurrent = currentHost.lowercase(Locale.US).removePrefix("www.")
        val cleanReqHost = reqHost.removePrefix("www.")

        // Determine if request belongs to an enabled site
        val targetDomain = when {
            isSiteCacheEnabled(cleanCurrent) -> cleanCurrent
            refererHost != null && isSiteCacheEnabled(refererHost) -> refererHost
            isSiteCacheEnabled(cleanReqHost) -> cleanReqHost
            else -> return null
        }

        val isStaticExtension = ext in staticExtensions
        if (!isStaticExtension && !reqPath.contains("/static/") && !reqPath.contains("/assets/")) {
            return null
        }

        val domainDir = File(cacheDir, sanitizeDomain(targetDomain)).apply { mkdirs() }
        val fileHash = hashUrl(reqUrl)
        val binFile = File(domainDir, "$fileHash.bin")
        val metaFile = File(domainDir, "$fileHash.meta")

        val now = System.currentTimeMillis()

        // 1. Check Existing Cache
        if (binFile.exists() && binFile.length() > 0 && metaFile.exists()) {
            try {
                val meta = JSONObject(metaFile.readText(Charsets.UTF_8))
                val mime = meta.optString("mime", "application/javascript")
                val etag = meta.optString("etag", "")
                val lastModified = meta.optString("last_modified", "")
                val isImmutable = meta.optBoolean("is_immutable", false)
                val expiresAt = meta.optLong("expires_at", 0L)

                // A. Immutable or Valid TTL: Serve immediately (0 bytes transferred)
                if (isImmutable || now < expiresAt) {
                    val respHeaders = mutableMapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "X-Omni-Cache" to "HIT-IMMUTABLE"
                    )
                    return WebResourceResponse(mime, "UTF-8", 200, "OK", respHeaders, binFile.inputStream())
                }

                // B. Revalidation Check via ETag or Last-Modified (Fast conditional handshake)
                if (etag.isNotEmpty() || lastModified.isNotEmpty()) {
                    var conn: HttpURLConnection? = null
                    try {
                        conn = (URL(reqUrl).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 6000
                            readTimeout = 10000
                            instanceFollowRedirects = true
                            if (etag.isNotEmpty()) setRequestProperty("If-None-Match", etag)
                            if (lastModified.isNotEmpty()) setRequestProperty("If-Modified-Since", lastModified)
                            request.requestHeaders.forEach { (k, v) ->
                                if (!k.equals("If-None-Match", true) && !k.equals("If-Modified-Since", true)) {
                                    setRequestProperty(k, v)
                                }
                            }
                        }

                        val code = conn.responseCode
                        if (code == HttpURLConnection.HTTP_NOT_MODIFIED || code == 304) {
                            // 304 Not Modified: Refresh TTL for 7 days, reuse disk cache (0 payload data!)
                            meta.put("expires_at", now + (7 * 24 * 60 * 60 * 1000L))
                            metaFile.writeText(meta.toString(), Charsets.UTF_8)
                            val respHeaders = mutableMapOf(
                                "Access-Control-Allow-Origin" to "*",
                                "X-Omni-Cache" to "HIT-REVALIDATED"
                            )
                            return WebResourceResponse(mime, "UTF-8", 200, "OK", respHeaders, binFile.inputStream())
                        } else if (code in 200..299) {
                            // 200 OK: Server updated asset! Update local cache file
                            val newBytes = conn.inputStream.use { it.readBytes() }
                            if (newBytes.isNotEmpty()) {
                                binFile.writeBytes(newBytes)
                                val newEtag = conn.getHeaderField("ETag") ?: ""
                                val newLastMod = conn.getHeaderField("Last-Modified") ?: ""
                                val cc = conn.getHeaderField("Cache-Control") ?: ""
                                val newImmutable = cc.contains("immutable", ignoreCase = true)
                                meta.put("etag", newEtag)
                                meta.put("last_modified", newLastMod)
                                meta.put("is_immutable", newImmutable)
                                meta.put("expires_at", now + (7 * 24 * 60 * 60 * 1000L))
                                metaFile.writeText(meta.toString(), Charsets.UTF_8)
                                val respHeaders = mutableMapOf(
                                    "Access-Control-Allow-Origin" to "*",
                                    "X-Omni-Cache" to "UPDATE"
                                )
                                return WebResourceResponse(mime, "UTF-8", 200, "OK", respHeaders, binFile.inputStream())
                            }
                        }
                    } catch (_: Exception) {
                        // Offline or timeout: Gracefully serve existing cached file
                        val respHeaders = mutableMapOf(
                            "Access-Control-Allow-Origin" to "*",
                            "X-Omni-Cache" to "HIT-OFFLINE-FALLBACK"
                        )
                        return WebResourceResponse(mime, "UTF-8", 200, "OK", respHeaders, binFile.inputStream())
                    } finally {
                        try { conn?.disconnect() } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Initial Fetch & Cache Population
        try {
            val conn = (URL(reqUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 12000
                instanceFollowRedirects = true
                request.requestHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.isNotEmpty() && bytes.size <= 30 * 1024 * 1024) {
                    val rawMime = conn.contentType ?: ""
                    val mime = inferMime(reqPath, rawMime)
                    val etag = conn.getHeaderField("ETag") ?: ""
                    val lastMod = conn.getHeaderField("Last-Modified") ?: ""
                    val cc = conn.getHeaderField("Cache-Control") ?: ""
                    val isImmutable = cc.contains("immutable", ignoreCase = true)

                    binFile.writeBytes(bytes)
                    val meta = JSONObject().apply {
                        put("url", reqUrl)
                        put("mime", mime)
                        put("etag", etag)
                        put("last_modified", lastMod)
                        put("is_immutable", isImmutable)
                        put("expires_at", now + (7 * 24 * 60 * 60 * 1000L))
                    }
                    metaFile.writeText(meta.toString(), Charsets.UTF_8)

                    val respHeaders = mutableMapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "X-Omni-Cache" to "POPULATED"
                    )
                    return WebResourceResponse(mime, "UTF-8", 200, "OK", respHeaders, binFile.inputStream())
                }
            }
        } catch (_: Exception) {}

        return null
    }
}