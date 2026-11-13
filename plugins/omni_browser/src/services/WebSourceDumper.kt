package com.omni.plugin.browser.services

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.omni.hub.api.HostBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class HarvestedResourceRef(
    val url: String,
    val initiatorType: String,
    val source: String
)

data class DownloadedAsset(
    val url: String,
    val localPath: String,
    val mimeType: String,
    val category: String, // "CODE", "STYLE", "DATA", "HTML", "IMAGE", "FONT", "MEDIA", "OTHER"
    val isTracker: Boolean,
    val bytes: ByteArray,
    val statusCode: Int,
    val initiatorType: String
)

data class SourceDumpResult(
    val outerZipFile: File,
    val displayPath: String,
    val totalAssets: Int,
    val automationAssets: Int,
    val sourceMapsUnpacked: Int,
    val totalBytes: Long
)

class WebSourceDumper(
    private val context: Context,
    private val bridge: HostBridge
) {
    companion object {
        private const val MAX_ASSET_BYTES = 25 * 1024 * 1024 // 25 MB per file cap
        private const val MAX_TOTAL_BYTES = 160 * 1024 * 1024L // 160 MB total cap
        private const val MAX_ASSETS_TO_FETCH = 450

        private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "avif", "tiff", "svg")
        private val FONT_EXTS = setOf("woff", "woff2", "ttf", "otf", "eot")
        private val MEDIA_EXTS = setOf("mp4", "webm", "m4v", "mov", "avi", "mp3", "wav", "m4a", "ogg", "flac", "aac")
        private val CODE_EXTS = setOf("js", "mjs", "cjs", "ts", "tsx", "jsx", "wasm")
        private val STYLE_EXTS = setOf("css", "scss", "less")
        private val DATA_EXTS = setOf("json", "xml", "txt", "yaml", "yml", "webmanifest", "map")
        private val HTML_EXTS = setOf("html", "htm", "xhtml")

        private val TRACKER_DOMAINS = setOf(
            "googletagmanager.com",
            "google-analytics.com",
            "analytics.google.com",
            "doubleclick.net",
            "googlesyndication.com",
            "adservice.google.",
            "facebook.net",
            "connect.facebook.net",
            "clarity.ms",
            "hotjar.com",
            "scorecardresearch.com",
            "criteo.com",
            "taboola.com",
            "outbrain.com",
            "cookienotificationbar"
        )

        val RESOURCE_HARVESTER_SCRIPT = """
            (function() {
                try {
                    var result = {
                        pageUrl: window.location.href,
                        title: document.title || 'Untitled',
                        userAgent: navigator.userAgent || '',
                        domHtml: '',
                        domSlimHtml: '',
                        resources: [],
                        inlineScripts: [],
                        inlineStyles: [],
                        interactiveElements: [],
                        customTags: {},
                        storageKeys: { localStorage: [], sessionStorage: [] },
                        iframes: []
                    };

                    var seenUrls = new Set();
                    function addRes(rawUrl, initiator, source) {
                        if (!rawUrl || typeof rawUrl !== 'string') return;
                        var trimmed = rawUrl.trim();
                        if (!trimmed || trimmed.indexOf('javascript:') === 0 || trimmed.indexOf('data:') === 0 || trimmed.indexOf('about:') === 0 || trimmed.indexOf('mailto:') === 0) return;
                        try {
                            var abs = new URL(trimmed, window.location.href).href.split('#')[0];
                            if (!abs || seenUrls.has(abs)) return;
                            seenUrls.add(abs);
                            result.resources.push({
                                url: abs,
                                initiatorType: initiator || 'other',
                                source: source || 'dom'
                            });
                        } catch(_) {}
                    }

                    // 1. Live DOM Snapshot with <base>
                    try {
                        var clone = document.documentElement.cloneNode(true);
                        var head = clone.querySelector('head');
                        if (head && !head.querySelector('base')) {
                            var base = document.createElement('base');
                            base.href = window.location.href;
                            head.insertBefore(base, head.firstChild);
                        }
                        result.domHtml = '<!DOCTYPE html>\n' + clone.outerHTML;
                    } catch(e) {
                        result.domHtml = document.documentElement.outerHTML || '';
                    }

                    // 2. Slim Automation DOM (strips style blocks, inline SVG geometry, base64 data URIs)
                    try {
                        var slim = document.documentElement.cloneNode(true);
                        slim.querySelectorAll('style').forEach(function(s) {
                            s.textContent = '/* stripped style (' + (s.textContent || '').length + ' chars) */';
                        });
                        slim.querySelectorAll('svg').forEach(function(svg) {
                            svg.innerHTML = '<!-- stripped svg -->';
                        });
                        slim.querySelectorAll('[src^="data:"], [href^="data:"]').forEach(function(el) {
                            if (el.hasAttribute('src')) el.setAttribute('src', 'data:[stripped]');
                            if (el.hasAttribute('href')) el.setAttribute('href', 'data:[stripped]');
                        });
                        slim.querySelectorAll('script:not([src])').forEach(function(sc) {
                            if ((sc.textContent || '').length > 600) {
                                sc.textContent = '/* inline script (' + sc.textContent.length + ' chars) - see inline_scripts/ */';
                            }
                        });
                        result.domSlimHtml = '<!DOCTYPE html>\n' + slim.outerHTML;
                    } catch(_) {}

                    // 3. PerformanceResourceTiming (catches lazy chunks, XHR, fetch, fonts, preloads)
                    try {
                        if (window.performance && performance.getEntriesByType) {
                            var perfEntries = performance.getEntriesByType('resource') || [];
                            for (var i = 0; i < perfEntries.length; i++) {
                                var entry = perfEntries[i];
                                if (entry && entry.name) {
                                    addRes(entry.name, entry.initiatorType || 'other', 'performance');
                                }
                            }
                        }
                    } catch(_) {}

                    // 4. DOM Resource Tag Crawl
                    function harvestDoc(doc, docLabel) {
                        if (!doc) return;
                        try {
                            doc.querySelectorAll('script[src]').forEach(function(el) {
                                addRes(el.getAttribute('src'), 'script', docLabel + ':script');
                            });
                            doc.querySelectorAll('link[href]').forEach(function(el) {
                                var rel = (el.getAttribute('rel') || '').toLowerCase();
                                var it = rel.indexOf('stylesheet') >= 0 ? 'css' : (el.getAttribute('as') || rel || 'link');
                                addRes(el.getAttribute('href'), it, docLabel + ':link');
                            });
                            doc.querySelectorAll('img[src], source[src], video[src], audio[src], track[src], embed[src]').forEach(function(el) {
                                addRes(el.getAttribute('src'), el.tagName.toLowerCase(), docLabel + ':' + el.tagName.toLowerCase());
                            });
                            doc.querySelectorAll('[poster]').forEach(function(el) {
                                addRes(el.getAttribute('poster'), 'img', docLabel + ':poster');
                            });
                            doc.querySelectorAll('object[data]').forEach(function(el) {
                                addRes(el.getAttribute('data'), 'other', docLabel + ':object');
                            });
                            doc.querySelectorAll('img[srcset], source[srcset]').forEach(function(el) {
                                var ss = el.getAttribute('srcset') || '';
                                ss.split(',').forEach(function(part) {
                                    var urlPart = part.trim().split(/\s+/)[0];
                                    if (urlPart) addRes(urlPart, 'img', docLabel + ':srcset');
                                });
                            });
                            doc.querySelectorAll('[style]').forEach(function(el) {
                                var st = el.getAttribute('style') || '';
                                var re = /url\(\s*['"]?([^'")]+)['"]?\s*\)/gi;
                                var m;
                                while ((m = re.exec(st)) !== null) {
                                    addRes(m[1], 'css_url', docLabel + ':inline_style');
                                }
                            });
                            doc.querySelectorAll('iframe[src], frame[src]').forEach(function(ifr) {
                                var src = ifr.getAttribute('src');
                                addRes(src, 'iframe', docLabel + ':iframe');
                                result.iframes.push(src);
                            });

                            // Accessible CSS rules (@import / url())
                            if (doc.styleSheets) {
                                for (var sIdx = 0; sIdx < doc.styleSheets.length; sIdx++) {
                                    var sheet = doc.styleSheets[sIdx];
                                    if (sheet.href) addRes(sheet.href, 'css', docLabel + ':stylesheet');
                                    try {
                                        var rules = sheet.cssRules || sheet.rules;
                                        if (rules) {
                                            for (var rIdx = 0; rIdx < rules.length; rIdx++) {
                                                var rule = rules[rIdx];
                                                if (rule.href) addRes(rule.href, 'css', docLabel + ':css_import');
                                                var cssText = rule.cssText || '';
                                                if (cssText.indexOf('url(') >= 0) {
                                                    var uRe = /url\(\s*['"]?([^'")]+)['"]?\s*\)/gi;
                                                    var um;
                                                    while ((um = uRe.exec(cssText)) !== null) {
                                                        addRes(um[1], 'css_url', docLabel + ':css_rule');
                                                    }
                                                }
                                            }
                                        }
                                    } catch(_) {}
                                }
                            }
                        } catch(_) {}
                    }

                    harvestDoc(document, 'main');

                    // Accessible same-origin iframes
                    document.querySelectorAll('iframe').forEach(function(ifr, idx) {
                        try {
                            if (ifr.contentDocument) harvestDoc(ifr.contentDocument, 'iframe_' + idx);
                        } catch(_) {}
                    });

                    // 5. Inline Scripts & JSON Payloads
                    document.querySelectorAll('script:not([src])').forEach(function(sc, idx) {
                        var txt = (sc.textContent || '').trim();
                        if (txt.length > 0) {
                            result.inlineScripts.push({
                                index: idx,
                                id: sc.id || '',
                                type: sc.getAttribute('type') || 'text/javascript',
                                content: txt
                            });
                        }
                    });

                    // 6. Inline Styles & Adopted StyleSheets
                    document.querySelectorAll('style').forEach(function(st, idx) {
                        var txt = (st.textContent || '').trim();
                        if (txt.length > 0) {
                            result.inlineStyles.push({
                                index: idx,
                                id: st.id || '',
                                source: 'style_tag',
                                content: txt
                            });
                        }
                    });
                    try {
                        if (document.adoptedStyleSheets && document.adoptedStyleSheets.length > 0) {
                            for (var aIdx = 0; aIdx < document.adoptedStyleSheets.length; aIdx++) {
                                var aSheet = document.adoptedStyleSheets[aIdx];
                                var cssAcc = [];
                                if (aSheet.cssRules) {
                                    for (var ar = 0; ar < aSheet.cssRules.length; ar++) {
                                        cssAcc.push(aSheet.cssRules[ar].cssText);
                                    }
                                }
                                if (cssAcc.length > 0) {
                                    result.inlineStyles.push({
                                        index: aIdx,
                                        id: 'adopted_' + aIdx,
                                        source: 'adoptedStyleSheet',
                                        content: cssAcc.join('\n')
                                    });
                                }
                            }
                        }
                    } catch(_) {}

                    // 7. Automation Selector Map & Custom Web Components
                    try {
                        var allEls = document.querySelectorAll('*');
                        var customMap = {};
                        for (var eIdx = 0; eIdx < allEls.length; eIdx++) {
                            var tagName = (allEls[eIdx].tagName || '').toLowerCase();
                            if (tagName.indexOf('-') > 0) {
                                customMap[tagName] = (customMap[tagName] || 0) + 1;
                            }
                        }
                        result.customTags = customMap;

                        var interactive = document.querySelectorAll('button, a[href], input, textarea, select, [role="button"], [role="textbox"], [role="menuitem"], [role="option"], [contenteditable="true"], [data-testid], [data-test-id], [formcontrolname]');
                        var cap = Math.min(interactive.length, 250);
                        for (var k = 0; k < cap; k++) {
                            var el = interactive[k];
                            var cls = (typeof el.className === 'string') ? el.className.trim().replace(/\s+/g, '.') : '';
                            result.interactiveElements.push({
                                tag: el.tagName.toLowerCase(),
                                id: el.id || '',
                                classes: cls.slice(0, 120),
                                type: el.getAttribute('type') || '',
                                name: el.getAttribute('name') || '',
                                formcontrolname: el.getAttribute('formcontrolname') || '',
                                role: el.getAttribute('role') || '',
                                ariaLabel: el.getAttribute('aria-label') || '',
                                placeholder: el.getAttribute('placeholder') || '',
                                testId: el.getAttribute('data-testid') || el.getAttribute('data-test-id') || el.getAttribute('data-test') || '',
                                text: ((el.innerText || el.textContent || '').trim().replace(/\s+/g, ' ')).slice(0, 80)
                            });
                        }
                    } catch(_) {}

                    // 8. Storage Keys Metadata
                    try {
                        for (var ls = 0; ls < localStorage.length; ls++) {
                            var lKey = localStorage.key(ls);
                            var lVal = localStorage.getItem(lKey) || '';
                            result.storageKeys.localStorage.push({ key: lKey, length: lVal.length });
                        }
                    } catch(_) {}
                    try {
                        for (var ss = 0; ss < sessionStorage.length; ss++) {
                            var sKey = sessionStorage.key(ss);
                            var sVal = sessionStorage.getItem(sKey) || '';
                            result.storageKeys.sessionStorage.push({ key: sKey, length: sVal.length });
                        }
                    } catch(_) {}

                    return JSON.stringify(result);
                } catch(fatal) {
                    return JSON.stringify({ error: fatal.message || String(fatal) });
                }
            })();
        """.trimIndent()
    }

    private fun getCookiesForUrl(profileId: String, url: String): String? {
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

    private fun isTrackerUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return TRACKER_DOMAINS.any { lower.contains(it) }
    }

    private fun inferExtension(url: String, mimeType: String, initiatorType: String): String {
        val cleanPath = try { Uri.parse(url).path ?: "" } catch (_: Exception) { url.substringBefore("?") }
        val lastSeg = cleanPath.substringAfterLast('/')
        val urlExt = if (lastSeg.contains('.')) lastSeg.substringAfterLast('.').lowercase(Locale.US) else ""

        if (urlExt.length in 1..6 && urlExt.all { it.isLetterOrDigit() }) {
            return urlExt
        }

        val mime = mimeType.lowercase(Locale.US).substringBefore(";").trim()
        return when {
            mime.contains("javascript") || mime == "application/ecmascript" || initiatorType == "script" -> "js"
            mime.contains("text/css") || initiatorType == "css" -> "css"
            mime.contains("text/html") || initiatorType == "iframe" -> "html"
            mime.contains("application/json") || mime.endsWith("+json") -> "json"
            mime.contains("application/wasm") -> "wasm"
            mime.contains("image/svg+xml") -> "svg"
            mime.contains("image/png") -> "png"
            mime.contains("image/jpeg") -> "jpg"
            mime.contains("image/webp") -> "webp"
            mime.contains("image/gif") -> "gif"
            mime.contains("image/x-icon") || mime.contains("vnd.microsoft.icon") -> "ico"
            mime.contains("font/woff2") -> "woff2"
            mime.contains("font/woff") -> "woff"
            mime.contains("font/ttf") || mime.contains("x-font-ttf") -> "ttf"
            mime.contains("text/plain") -> "txt"
            mime.contains("text/xml") || mime.contains("application/xml") -> "xml"
            else -> "bin"
        }
    }

    private fun classifyCategory(url: String, ext: String, mimeType: String, initiatorType: String): String {
        val mime = mimeType.lowercase(Locale.US)
        val lowerUrl = url.lowercase(Locale.US)
        if (lowerUrl.contains("fonts.googleapis.com") || lowerUrl.contains("fonts.gstatic.com")) {
            return if (ext in FONT_EXTS || mime.contains("font")) "FONT" else "FONT_CSS"
        }
        return when {
            ext in IMAGE_EXTS || mime.startsWith("image/") -> "IMAGE"
            ext in FONT_EXTS || mime.startsWith("font/") || mime.contains("font") -> "FONT"
            ext in MEDIA_EXTS || mime.startsWith("video/") || mime.startsWith("audio/") -> "MEDIA"
            ext in CODE_EXTS || mime.contains("javascript") || mime.contains("ecmascript") || mime.contains("wasm") || initiatorType == "script" -> "CODE"
            ext in STYLE_EXTS || mime.contains("text/css") || initiatorType == "css" -> "STYLE"
            ext in HTML_EXTS || mime.contains("text/html") -> "HTML"
            ext in DATA_EXTS || mime.contains("json") || mime.contains("xml") || mime.startsWith("text/") -> "DATA"
            else -> "OTHER"
        }
    }

    private fun isAutomationRelevant(asset: DownloadedAsset): Boolean {
        if (asset.isTracker) return false
        return asset.category in setOf("CODE", "STYLE", "DATA", "HTML")
    }

    private fun shortHash(input: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
            digest.take(4).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            input.hashCode().toUInt().toString(16)
        }
    }

    private fun buildLocalAssetPath(url: String, ext: String, usedPaths: MutableSet<String>): String {
        val uri = try { Uri.parse(url) } catch (_: Exception) { null }
        val host = (uri?.host ?: "unknown_host").replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val rawPath = (uri?.path ?: "").removePrefix("/")
        val segments = rawPath.split("/").filter { it.isNotBlank() }.map { seg ->
            seg.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64)
        }.toMutableList()

        if (segments.isEmpty()) {
            segments.add("index.$ext")
        } else {
            val last = segments.last()
            if (!last.contains(".") || last.endsWith(".")) {
                segments[segments.lastIndex] = "${last.trimEnd('.')}.$ext"
            }
        }

        val query = uri?.query
        var candidate = "assets/$host/${segments.joinToString("/")}"
        if (!query.isNullOrEmpty() || !usedPaths.add(candidate)) {
            val dir = candidate.substringBeforeLast("/")
            val file = candidate.substringAfterLast("/")
            val base = file.substringBeforeLast(".")
            val fExt = file.substringAfterLast(".", ext)
            val hash = shortHash(url)
            candidate = "$dir/${base}_$hash.$fExt"
            var counter = 1
            while (!usedPaths.add(candidate)) {
                candidate = "$dir/${base}_${hash}_$counter.$fExt"
                counter++
            }
        }
        return candidate
    }

    private fun fetchUrlBytes(
        urlStr: String,
        profileId: String,
        userAgent: String,
        referer: String,
        localServerPort: Int
    ): Triple<Int, String, ByteArray> {
        // Localhost vault interception support
        try {
            val uri = Uri.parse(urlStr)
            val host = uri.host?.lowercase(Locale.US) ?: ""
            val port = if (uri.port != -1) uri.port else 80
            if ((host == "localhost" || host == "127.0.0.1") && port == localServerPort) {
                val rawPath = uri.path?.removePrefix("/") ?: ""
                val pluginDir = File(bridge.getPluginDir())
                val targetFile = when {
                    rawPath.isEmpty() || rawPath == "index.html" -> File(pluginDir, "ide/index.html")
                    rawPath.startsWith("vault_") -> File(pluginDir, "ide/$rawPath")
                    rawPath.startsWith("ide/") -> File(pluginDir, rawPath)
                    else -> {
                        val ideCandidate = File(pluginDir, "ide/$rawPath")
                        if (ideCandidate.exists()) ideCandidate else File(pluginDir, rawPath)
                    }
                }
                val finalFile = if (targetFile.isDirectory) File(targetFile, "index.html") else targetFile
                if (finalFile.exists() && finalFile.isFile && finalFile.length() <= MAX_ASSET_BYTES) {
                    val mime = com.omni.plugin.browser.utils.resolveMimeType(finalFile)
                    return Triple(200, mime, finalFile.readBytes())
                }
                return Triple(404, "", ByteArray(0))
            }
        } catch (_: Exception) {}

        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 12_000
                readTimeout = 20_000
                requestMethod = "GET"
                if (userAgent.isNotEmpty()) setRequestProperty("User-Agent", userAgent)
                if (referer.isNotEmpty()) setRequestProperty("Referer", referer)
                setRequestProperty("Accept", "*/*")
                val cookies = getCookiesForUrl(profileId, urlStr)
                if (!cookies.isNullOrEmpty()) {
                    setRequestProperty("Cookie", cookies)
                }
            }
            val code = conn.responseCode
            val contentType = conn.contentType ?: ""
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (code in 200..299 && stream != null) {
                val baos = ByteArrayOutputStream()
                val buf = ByteArray(16384)
                var total = 0
                stream.use { input ->
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_ASSET_BYTES) {
                            return Triple(code, contentType, ByteArray(0))
                        }
                        baos.write(buf, 0, read)
                    }
                }
                Triple(code, contentType, baos.toByteArray())
            } else {
                Triple(code, contentType, ByteArray(0))
            }
        } catch (_: Exception) {
            Triple(-1, "", ByteArray(0))
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun resolveRelativeUrl(baseUrl: String, ref: String): String? {
        val trimmed = ref.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("data:") || trimmed.startsWith("blob:") || trimmed.startsWith("javascript:")) {
            return null
        }
        return try {
            URI(baseUrl).resolve(trimmed).toString().substringBefore("#")
        } catch (_: Exception) {
            null
        }
    }

    private fun extractCssSubUrls(cssText: String, baseUrl: String): List<HarvestedResourceRef> {
        val found = mutableListOf<HarvestedResourceRef>()
        val urlRegex = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""", RegexOption.IGNORE_CASE)
        urlRegex.findAll(cssText).forEach { m ->
            resolveRelativeUrl(baseUrl, m.groupValues[1])?.let { abs ->
                found.add(HarvestedResourceRef(abs, "css_url", "css_recursive"))
            }
        }
        val importRegex = Regex("""@import\s+(?:url\(\s*)?['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        importRegex.findAll(cssText).forEach { m ->
            resolveRelativeUrl(baseUrl, m.groupValues[1])?.let { abs ->
                found.add(HarvestedResourceRef(abs, "css", "css_import"))
            }
        }
        return found
    }

    private fun extractSourceMappingUrl(codeText: String): String? {
        val regex = Regex("""(?:\/\/|\/\*)[#@]\s*sourceMappingURL\s*=\s*([^\s*]+)""")
        val matches = regex.findAll(codeText).toList()
        return matches.lastOrNull()?.groupValues?.get(1)?.trim()
    }

    private fun sanitizeSourceMapPath(rawPath: String): String {
        var clean = rawPath
            .replace("webpack:///", "")
            .replace("webpack://", "")
            .replace("ng:///", "")
            .replace("file:///", "")
            .replace("\\", "/")
            .trimStart('/')

        val segments = clean.split("/").filter { it.isNotBlank() && it != "." && it != ".." }
            .map { it.replace(Regex("[^a-zA-Z0-9._-]"), "_") }
        return if (segments.isEmpty()) "source_${shortHash(rawPath)}.txt" else segments.joinToString("/")
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.US, "%.2f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    private fun buildSkeletonTree(title: String, entries: List<Pair<String, Int>>): String {
        class TreeNode(val name: String) {
            val children = sortedMapOf<String, TreeNode>()
            var sizeBytes: Int? = null
        }

        val root = TreeNode(title)
        for ((path, size) in entries) {
            val parts = path.split("/").filter { it.isNotBlank() }
            var curr = root
            parts.forEachIndexed { idx, part ->
                curr = curr.children.getOrPut(part) { TreeNode(part) }
                if (idx == parts.lastIndex) {
                    curr.sizeBytes = size
                }
            }
        }

        val sb = StringBuilder()
        sb.appendLine("$title (${entries.size} files, ${formatSize(entries.sumOf { it.second.toLong() })})")

        fun walk(node: TreeNode, prefix: String) {
            val keys = node.children.keys.toList()
            keys.forEachIndexed { idx, key ->
                val child = node.children[key]!!
                val isLast = idx == keys.lastIndex
                val connector = if (isLast) "└── " else "├── "
                val sizeLabel = child.sizeBytes?.let { " (${formatSize(it.toLong())})" } ?: "/"
                sb.appendLine("$prefix$connector${child.name}$sizeLabel")
                if (child.children.isNotEmpty()) {
                    val childPrefix = prefix + if (isLast) "    " else "│   "
                    walk(child, childPrefix)
                }
            }
        }
        walk(root, "")
        return sb.toString()
    }

    private fun rewriteHtmlForOffline(domHtml: String, urlToLocalMap: Map<String, String>, pageUrl: String): String {
        var out = domHtml
        // Remove <base> tag in offline copy so relative ./assets/ links resolve locally
        out = out.replace(Regex("""<base\b[^>]*>""", RegexOption.IGNORE_CASE), "")
        for ((absUrl, localPath) in urlToLocalMap) {
            out = out.replace(absUrl, "./$localPath")
            try {
                val uri = URI(absUrl)
                val pathOnly = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
                if (pathOnly.length > 2) {
                    out = out.replace("\"$pathOnly\"", "\"./$localPath\"")
                    out = out.replace("'$pathOnly'", "'./$localPath'")
                }
            } catch (_: Exception) {}
        }
        return out
    }

    private fun saveOuterZipToDownloads(tempZipFile: File, filename: String): Pair<File, String> {
        var resolvedFile: File? = null
        var displayPath = ""

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OmniSnapshots")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        FileInputStream(tempZipFile).use { input -> input.copyTo(os) }
                    }
                    displayPath = "Downloads/OmniSnapshots/$filename"
                    try {
                        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                            if (c.moveToFirst()) {
                                val p = c.getString(0)
                                if (!p.isNullOrEmpty()) {
                                    val f = File(p)
                                    if (f.exists()) resolvedFile = f
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            bridge.log("SOURCE_DUMP_WARN", "MediaStore zip write failed: ${e.message}")
        }

        if (resolvedFile == null || !resolvedFile!!.exists()) {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadsDir, "OmniSnapshots").apply { mkdirs() }
                val directFile = File(targetDir, filename)
                tempZipFile.copyTo(directFile, overwrite = true)
                resolvedFile = directFile
                displayPath = "Downloads/OmniSnapshots/$filename"
            } catch (e: Exception) {
                val fallbackDir = File(bridge.getPluginDir(), "snapshots").apply { mkdirs() }
                val fallbackFile = File(fallbackDir, filename)
                tempZipFile.copyTo(fallbackFile, overwrite = true)
                resolvedFile = fallbackFile
                displayPath = "snapshots/$filename"
            }
        }

        return Pair(resolvedFile!!, displayPath)
    }

    suspend fun captureFullSourceBundle(
        webView: WebView,
        profileId: String,
        localServerPort: Int,
        onStatus: (String) -> Unit
    ): Result<SourceDumpResult> = withContext(Dispatchers.Main) {
        try {
            onStatus("Harvesting live DOM & resource list...")
            val rawJson = kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                webView.evaluateJavascript(RESOURCE_HARVESTER_SCRIPT) { res ->
                    if (!cont.isActive) return@evaluateJavascript
                    if (res.isNullOrEmpty() || res == "null") {
                        cont.resume(null) {}
                    } else {
                        val unquoted = if (res.startsWith("\"") && res.endsWith("\"")) {
                            try { JSONObject("{\"d\":$res}").getString("d") } catch (_: Exception) { res }
                        } else res
                        cont.resume(unquoted) {}
                    }
                }
            }

            if (rawJson.isNullOrEmpty()) {
                return@withContext Result.failure(IllegalStateException("Harvester returned empty payload."))
            }

            val rootJson = JSONObject(rawJson)
            if (rootJson.has("error")) {
                return@withContext Result.failure(IllegalStateException("Harvester JS error: ${rootJson.getString("error")}"))
            }

            val pageUrl = rootJson.optString("pageUrl", webView.url ?: "")
            val pageTitle = rootJson.optString("title", "WebApp")
            val userAgent = rootJson.optString("userAgent", webView.settings.userAgentString ?: "")
            val domHtml = rootJson.optString("domHtml", "")
            val domSlimHtml = rootJson.optString("domSlimHtml", "")
            val inlineScripts = rootJson.optJSONArray("inlineScripts") ?: JSONArray()
            val inlineStyles = rootJson.optJSONArray("inlineStyles") ?: JSONArray()
            val interactiveElements = rootJson.optJSONArray("interactiveElements") ?: JSONArray()
            val customTags = rootJson.optJSONObject("customTags") ?: JSONObject()
            val storageKeys = rootJson.optJSONObject("storageKeys") ?: JSONObject()
            val initialResources = rootJson.optJSONArray("resources") ?: JSONArray()

            withContext(Dispatchers.IO) {
                val hostRaw = try { Uri.parse(pageUrl).host ?: "webapp" } catch (_: Exception) { "webapp" }
                val safeHost = hostRaw.removePrefix("www.").replace(Regex("[^a-zA-Z0-9._-]"), "_").ifEmpty { "webapp" }
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

                onStatus("Fetching raw server HTML...")
                val (_, rawHtmlMime, rawHtmlBytes) = fetchUrlBytes(pageUrl, profileId, userAgent, pageUrl, localServerPort)

                val queue = mutableListOf<HarvestedResourceRef>()
                val queuedUrls = mutableSetOf<String>()
                for (i in 0 until initialResources.length()) {
                    val item = initialResources.getJSONObject(i)
                    val u = item.optString("url", "")
                    if (u.isNotEmpty() && queuedUrls.add(u)) {
                        queue.add(HarvestedResourceRef(u, item.optString("initiatorType", "other"), item.optString("source", "dom")))
                    }
                }

                val downloadedAssets = mutableListOf<DownloadedAsset>()
                val failedAssets = mutableListOf<Pair<String, Int>>()
                val usedPaths = mutableSetOf<String>()
                var cumulativeBytes = 0L

                // Download Batch Pass 1 (Main resources) + Pass 2 (Recursive CSS sub-assets)
                var currentBatch = queue.take(MAX_ASSETS_TO_FETCH)
                var pass = 1

                while (currentBatch.isNotEmpty() && pass <= 2 && downloadedAssets.size < MAX_ASSETS_TO_FETCH) {
                    val nextBatch = mutableListOf<HarvestedResourceRef>()
                    val totalInPass = currentBatch.size
                    var completedInPass = 0

                    currentBatch.chunked(6).forEach { chunk ->
                        if (cumulativeBytes >= MAX_TOTAL_BYTES) return@forEach
                        val results = coroutineScope {
                            chunk.map { ref ->
                                async(Dispatchers.IO) {
                                    val (code, mime, bytes) = fetchUrlBytes(ref.url, profileId, userAgent, pageUrl, localServerPort)
                                    Pair(ref, Triple(code, mime, bytes))
                                }
                            }.awaitAll()
                        }

                        for ((ref, resp) in results) {
                            completedInPass++
                            val (code, mime, bytes) = resp
                            if (code in 200..299 && bytes.isNotEmpty()) {
                                if (cumulativeBytes + bytes.size > MAX_TOTAL_BYTES) continue
                                cumulativeBytes += bytes.size

                                val ext = inferExtension(ref.url, mime, ref.initiatorType)
                                val cat = classifyCategory(ref.url, ext, mime, ref.initiatorType)
                                val localPath = buildLocalAssetPath(ref.url, ext, usedPaths)
                                val tracker = isTrackerUrl(ref.url)

                                downloadedAssets.add(
                                    DownloadedAsset(
                                        url = ref.url,
                                        localPath = localPath,
                                        mimeType = mime,
                                        category = cat,
                                        isTracker = tracker,
                                        bytes = bytes,
                                        statusCode = code,
                                        initiatorType = ref.initiatorType
                                    )
                                )

                                // Recurse into CSS sub-resources during Pass 1
                                if (pass == 1 && (cat == "STYLE" || cat == "FONT_CSS")) {
                                    val cssText = String(bytes, Charsets.UTF_8)
                                    extractCssSubUrls(cssText, ref.url).forEach { subRef ->
                                        if (queuedUrls.add(subRef.url) && (downloadedAssets.size + nextBatch.size) < MAX_ASSETS_TO_FETCH) {
                                            nextBatch.add(subRef)
                                        }
                                    }
                                }
                            } else {
                                failedAssets.add(Pair(ref.url, code))
                            }
                        }

                        withContext(Dispatchers.Main) {
                            onStatus("Catching assets (pass $pass: $completedInPass/$totalInPass)...")
                        }
                    }

                    currentBatch = nextBatch
                    pass++
                }

                // Pass 3: Check JS/CSS bundles for Source Maps (.map) & unpack original source if exposed
                withContext(Dispatchers.Main) {
                    onStatus("Checking for exposed source maps...")
                }

                val sourceMapFiles = mutableListOf<Pair<String, ByteArray>>()
                val unpackedSourceFiles = mutableListOf<Pair<String, ByteArray>>()
                val seenMapUrls = mutableSetOf<String>()
                val seenUnpackedPaths = mutableSetOf<String>()

                val codeAssets = downloadedAssets.filter { (it.category == "CODE" || it.category == "STYLE") && !it.isTracker }
                val mapJobs = mutableListOf<Pair<DownloadedAsset, String>>()

                for (asset in codeAssets) {
                    if (asset.bytes.size > 15 * 1024 * 1024) continue
                    val text = String(asset.bytes, Charsets.UTF_8)
                    val mapRef = extractSourceMappingUrl(text) ?: continue
                    if (mapRef.startsWith("data:")) {
                        if (mapRef.contains("base64,")) {
                            try {
                                val b64 = mapRef.substringAfter("base64,")
                                val decoded = Base64.decode(b64, Base64.DEFAULT)
                                val mapName = "sourcemaps/${asset.localPath.removePrefix("assets/")}.map"
                                sourceMapFiles.add(Pair(mapName, decoded))
                            } catch (_: Exception) {}
                        }
                    } else {
                        val absMapUrl = resolveRelativeUrl(asset.url, mapRef)
                        if (absMapUrl != null && seenMapUrls.add(absMapUrl)) {
                            mapJobs.add(Pair(asset, absMapUrl))
                        }
                    }
                }

                mapJobs.chunked(4).forEach { chunk ->
                    val mapResults = coroutineScope {
                        chunk.map { (asset, mapUrl) ->
                            async(Dispatchers.IO) {
                                val (code, _, bytes) = fetchUrlBytes(mapUrl, profileId, userAgent, pageUrl, localServerPort)
                                Triple(asset, code, bytes)
                            }
                        }.awaitAll()
                    }
                    for ((asset, code, bytes) in mapResults) {
                        if (code in 200..299 && bytes.isNotEmpty()) {
                            val mapName = "sourcemaps/${asset.localPath.removePrefix("assets/")}.map"
                            sourceMapFiles.add(Pair(mapName, bytes))
                        }
                    }
                }

                // Unpack sourcesContent from retrieved source maps
                for ((_, mapBytes) in sourceMapFiles) {
                    try {
                        val mapObj = JSONObject(String(mapBytes, Charsets.UTF_8))
                        val sources = mapObj.optJSONArray("sources")
                        val contents = mapObj.optJSONArray("sourcesContent")
                        if (sources != null && contents != null) {
                            val count = minOf(sources.length(), contents.length())
                            for (i in 0 until count) {
                                val srcPath = sources.optString(i, "")
                                val srcBody = contents.optString(i, "")
                                if (srcPath.isNotBlank() && srcBody.isNotBlank() && srcBody != "null") {
                                    val cleanPath = "src_unpacked/${sanitizeSourceMapPath(srcPath)}"
                                    if (seenUnpackedPaths.add(cleanPath)) {
                                        unpackedSourceFiles.add(Pair(cleanPath, srcBody.toByteArray(Charsets.UTF_8)))
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                withContext(Dispatchers.Main) {
                    onStatus("Building Dual-ZIP bundle & SKELETON.txt...")
                }

                // Prepare Inline Scripts & Inline Styles entries
                val inlineScriptEntries = mutableListOf<Pair<String, ByteArray>>()
                for (i in 0 until inlineScripts.length()) {
                    val obj = inlineScripts.getJSONObject(i)
                    val idPart = obj.optString("id", "").replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val typePart = obj.optString("type", "").lowercase(Locale.US)
                    val ext = if (typePart.contains("json")) "json" else "js"
                    val name = if (idPart.isNotEmpty()) "inline_scripts/${i}_$idPart.$ext" else "inline_scripts/inline_$i.$ext"
                    inlineScriptEntries.add(Pair(name, obj.optString("content", "").toByteArray(Charsets.UTF_8)))
                }

                val inlineStyleEntries = mutableListOf<Pair<String, ByteArray>>()
                for (i in 0 until inlineStyles.length()) {
                    val obj = inlineStyles.getJSONObject(i)
                    val idPart = obj.optString("id", "").replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val name = if (idPart.isNotEmpty()) "inline_styles/${i}_$idPart.css" else "inline_styles/style_$i.css"
                    inlineStyleEntries.add(Pair(name, obj.optString("content", "").toByteArray(Charsets.UTF_8)))
                }

                // Runtime metadata files
                val selectorsJsonBytes = JSONObject().apply {
                    put("pageUrl", pageUrl)
                    put("title", pageTitle)
                    put("customWebComponents", customTags)
                    put("interactiveElements", interactiveElements)
                }.toString(2).toByteArray(Charsets.UTF_8)

                val networkEndpointsArr = JSONArray()
                for (i in 0 until initialResources.length()) {
                    val r = initialResources.getJSONObject(i)
                    val it = r.optString("initiatorType", "")
                    if (it in setOf("fetch", "xmlhttprequest", "script", "iframe", "other")) {
                        networkEndpointsArr.put(r)
                    }
                }
                val endpointsJsonBytes = networkEndpointsArr.toString(2).toByteArray(Charsets.UTF_8)
                val storageJsonBytes = storageKeys.toString(2).toByteArray(Charsets.UTF_8)

                val urlToLocalMap = downloadedAssets.associate { it.url to it.localPath }
                val offlineHtml = rewriteHtmlForOffline(domHtml, urlToLocalMap, pageUrl)

                // Manifests
                fun buildManifestJson(assetsList: List<DownloadedAsset>, onlyAutomation: Boolean): ByteArray {
                    val arr = JSONArray()
                    assetsList.forEach { a ->
                        arr.put(JSONObject().apply {
                            put("url", a.url)
                            put("localPath", a.localPath)
                            put("category", a.category)
                            put("mimeType", a.mimeType)
                            put("sizeBytes", a.bytes.size)
                            put("statusCode", a.statusCode)
                            put("isTracker", a.isTracker)
                        })
                    }
                    val failArr = JSONArray()
                    failedAssets.forEach { (u, code) ->
                        failArr.put(JSONObject().apply {
                            put("url", u)
                            put("statusCode", code)
                        })
                    }
                    return JSONObject().apply {
                        put("pageUrl", pageUrl)
                        put("title", pageTitle)
                        put("capturedAt", timestamp)
                        put("profileId", profileId)
                        put("automationOnly", onlyAutomation)
                        put("downloadedCount", assetsList.size)
                        put("sourceMapsCount", sourceMapFiles.size)
                        put("unpackedSourceFilesCount", unpackedSourceFiles.size)
                        put("assets", arr)
                        if (!onlyAutomation) put("failedRequests", failArr)
                    }.toString(2).toByteArray(Charsets.UTF_8)
                }

                // Track entries for SKELETON.txt
                val fullTreeEntries = mutableListOf<Pair<String, Int>>()
                val autoTreeEntries = mutableListOf<Pair<String, Int>>()

                val tempFullZip = File(context.cacheDir, "${safeHost}_full_$timestamp.zip")
                val tempAutoZip = File(context.cacheDir, "${safeHost}_auto_$timestamp.zip")
                val tempOuterZip = File(context.cacheDir, "${safeHost}_${timestamp}_source.zip")

                try {
                    // 1. Write Inner ZIP #1: FULL CAPTURE (everything caught)
                    ZipOutputStream(FileOutputStream(tempFullZip)).use { zos ->
                        fun addEntry(path: String, data: ByteArray) {
                            if (data.isEmpty()) return
                            zos.putNextEntry(ZipEntry(path))
                            zos.write(data)
                            zos.closeEntry()
                            fullTreeEntries.add(Pair(path, data.size))
                        }

                        if (rawHtmlBytes.isNotEmpty()) addEntry("raw_index.html", rawHtmlBytes)
                        if (domHtml.isNotEmpty()) addEntry("dom_snapshot.html", domHtml.toByteArray(Charsets.UTF_8))
                        if (offlineHtml.isNotEmpty()) addEntry("offline_index.html", offlineHtml.toByteArray(Charsets.UTF_8))
                        inlineScriptEntries.forEach { (p, d) -> addEntry(p, d) }
                        inlineStyleEntries.forEach { (p, d) -> addEntry(p, d) }
                        downloadedAssets.forEach { a -> addEntry(a.localPath, a.bytes) }
                        sourceMapFiles.forEach { (p, d) -> addEntry(p, d) }
                        unpackedSourceFiles.forEach { (p, d) -> addEntry(p, d) }
                        addEntry("runtime/dom_selectors.json", selectorsJsonBytes)
                        addEntry("runtime/network_endpoints.json", endpointsJsonBytes)
                        addEntry("runtime/storage_keys.json", storageJsonBytes)
                        addEntry("manifest.json", buildManifestJson(downloadedAssets, false))
                    }

                    // 2. Write Inner ZIP #2: AUTOMATION CORE (no images, fonts, video/audio, trackers)
                    val automationAssets = downloadedAssets.filter { isAutomationRelevant(it) }
                    val excludedAssets = downloadedAssets.filter { !isAutomationRelevant(it) }

                    ZipOutputStream(FileOutputStream(tempAutoZip)).use { zos ->
                        fun addEntry(path: String, data: ByteArray) {
                            if (data.isEmpty()) return
                            zos.putNextEntry(ZipEntry(path))
                            zos.write(data)
                            zos.closeEntry()
                            autoTreeEntries.add(Pair(path, data.size))
                        }

                        if (domSlimHtml.isNotEmpty()) addEntry("dom_structure_slim.html", domSlimHtml.toByteArray(Charsets.UTF_8))
                        if (domHtml.isNotEmpty()) addEntry("dom_snapshot.html", domHtml.toByteArray(Charsets.UTF_8))
                        if (rawHtmlBytes.isNotEmpty()) addEntry("raw_index.html", rawHtmlBytes)
                        inlineScriptEntries.forEach { (p, d) -> addEntry(p, d) }
                        automationAssets.forEach { a -> addEntry(a.localPath, a.bytes) }
                        unpackedSourceFiles.forEach { (p, d) -> addEntry(p, d) }
                        addEntry("runtime/dom_selectors.json", selectorsJsonBytes)
                        addEntry("runtime/network_endpoints.json", endpointsJsonBytes)
                        addEntry("runtime/storage_keys.json", storageJsonBytes)
                        addEntry("manifest_automation.json", buildManifestJson(automationAssets, true))
                    }

                    // 3. Build Root SKELETON.txt
                    val fullZipName = "${safeHost}_full_capture.zip"
                    val autoZipName = "${safeHost}_automation_core.zip"

                    val skeleton = StringBuilder().apply {
                        appendLine("==============================================================================")
                        appendLine("OMNI CHROME - FULL WEB APP SOURCE CAPTURE SKELETON")
                        appendLine("==============================================================================")
                        appendLine("Target URL         : $pageUrl")
                        appendLine("Page Title         : $pageTitle")
                        appendLine("Captured At        : $timestamp")
                        appendLine("Browser Profile    : $profileId")
                        appendLine("Total Assets Caught: ${downloadedAssets.size} (${failedAssets.size} failed/403)")
                        appendLine("Automation Assets  : ${automationAssets.size} (Excluded ${excludedAssets.size} media/font/tracker files)")
                        appendLine("Source Maps Found  : ${sourceMapFiles.size} (${unpackedSourceFiles.size} unpacked original files)")
                        appendLine()
                        appendLine("PACKAGE CONTENTS AT ROOT:")
                        appendLine("├── SKELETON.txt               (This inspection blueprint)")
                        appendLine("├── $autoZipName   (Lean code, scripts, selectors, endpoints — USE THIS FOR AUTOMATION)")
                        appendLine("└── $fullZipName       (Complete archive including images, fonts, CSS, offline HTML, sourcemaps)")
                        appendLine()
                        appendLine("------------------------------------------------------------------------------")
                        appendLine("1. AUTOMATION CORE ARCHIVE ($autoZipName)")
                        appendLine("------------------------------------------------------------------------------")
                        append(buildSkeletonTree(autoZipName, autoTreeEntries))
                        appendLine()
                        appendLine("------------------------------------------------------------------------------")
                        appendLine("2. CUSTOM WEB COMPONENTS & AUTOMATION CHEAT-SHEET")
                        appendLine("------------------------------------------------------------------------------")
                        val tagKeys = customTags.keys().asSequence().toList()
                            .sortedByDescending { customTags.optInt(it, 0) }
                            .take(40)
                        if (tagKeys.isNotEmpty()) {
                            appendLine("Top Custom Elements (Angular/Lit/Web Components):")
                            tagKeys.forEach { tag ->
                                appendLine("  - <$tag> (x${customTags.optInt(tag)})")
                            }
                            appendLine()
                        }
                        appendLine("Interactive Elements Sample (first 30 of ${interactiveElements.length()} in runtime/dom_selectors.json):")
                        val sampleCap = minOf(30, interactiveElements.length())
                        for (i in 0 until sampleCap) {
                            val el = interactiveElements.getJSONObject(i)
                            val tag = el.optString("tag")
                            val aria = el.optString("ariaLabel")
                            val fc = el.optString("formcontrolname")
                            val id = el.optString("id")
                            val ph = el.optString("placeholder")
                            val txt = el.optString("text")
                            val attrs = listOfNotNull(
                                id.takeIf { it.isNotEmpty() }?.let { "#$it" },
                                fc.takeIf { it.isNotEmpty() }?.let { "[formcontrolname=\"$it\"]" },
                                aria.takeIf { it.isNotEmpty() }?.let { "[aria-label=\"$it\"]" },
                                ph.takeIf { it.isNotEmpty() }?.let { "[placeholder=\"$it\"]" }
                            ).joinToString("")
                            appendLine("  [$i] <$tag$attrs> ${if (txt.isNotEmpty()) "\"$txt\"" else ""}")
                        }
                        appendLine()
                        appendLine("------------------------------------------------------------------------------")
                        appendLine("3. FULL CAPTURE ARCHIVE ($fullZipName)")
                        appendLine("------------------------------------------------------------------------------")
                        append(buildSkeletonTree(fullZipName, fullTreeEntries))
                        if (failedAssets.isNotEmpty()) {
                            appendLine()
                            appendLine("------------------------------------------------------------------------------")
                            appendLine("4. FAILED / SKIPPED URLS (${failedAssets.size})")
                            appendLine("------------------------------------------------------------------------------")
                            failedAssets.take(40).forEach { (u, code) ->
                                appendLine("  [$code] $u")
                            }
                        }
                    }.toString()

                    // 4. Pack Outer ZIP (SKELETON.txt + automation_core.zip + full_capture.zip)
                    ZipOutputStream(FileOutputStream(tempOuterZip)).use { outerZos ->
                        outerZos.putNextEntry(ZipEntry("SKELETON.txt"))
                        outerZos.write(skeleton.toByteArray(Charsets.UTF_8))
                        outerZos.closeEntry()

                        outerZos.putNextEntry(ZipEntry(autoZipName))
                        FileInputStream(tempAutoZip).use { it.copyTo(outerZos) }
                        outerZos.closeEntry()

                        outerZos.putNextEntry(ZipEntry(fullZipName))
                        FileInputStream(tempFullZip).use { it.copyTo(outerZos) }
                        outerZos.closeEntry()
                    }

                    val outerName = "${safeHost}_${timestamp}_source.zip"
                    val (savedFile, displayPath) = saveOuterZipToDownloads(tempOuterZip, outerName)
                    bridge.log("SOURCE_DUMP", "✅ Exported Full Source Bundle -> $displayPath (${formatSize(savedFile.length())}, assets=${downloadedAssets.size}, auto=${automationAssets.size}, maps=${sourceMapFiles.size})")

                    Result.success(
                        SourceDumpResult(
                            outerZipFile = savedFile,
                            displayPath = displayPath,
                            totalAssets = downloadedAssets.size,
                            automationAssets = automationAssets.size,
                            sourceMapsUnpacked = unpackedSourceFiles.size,
                            totalBytes = savedFile.length()
                        )
                    )
                } finally {
                    try { tempFullZip.delete() } catch (_: Exception) {}
                    try { tempAutoZip.delete() } catch (_: Exception) {}
                    try { tempOuterZip.delete() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            bridge.log("SOURCE_DUMP_ERR", "Full source export failed: ${e.message}\n${e.stackTraceToString()}")
            Result.failure(e)
        }
    }
}