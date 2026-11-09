package com.omni.plugin.player

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class SubtitlePreset(
    val id: Int,
    val name: String,
    val textColor: Color,
    val bgColor: Color,
    val hasOutline: Boolean
)

data class SubtitleResult(
    val fileId: Long,
    val releaseName: String,
    val language: String,
    val downloadCount: Int,
    val rating: Double,
    val fileName: String
)

val SUBTITLE_PRESETS = listOf(
    SubtitlePreset(0, "Default Box", Color.White, Color(0xCC000000), false),
    SubtitlePreset(1, "Solid Black", Color.White, Color.Black, false),
    SubtitlePreset(2, "Cinema Yellow", Color(0xFFFFEB3B), Color(0xCC000000), false),
    SubtitlePreset(3, "Outline", Color.White, Color.Transparent, true)
)

val SUBTITLE_PALETTE = listOf(
    Color(0xFFFFFFFF), // White
    Color(0xFF000000), // Black
    Color(0xFFFFEB3B), // Yellow
    Color(0xFF00E676), // Green
    Color(0xFFFF1744), // Red
    Color(0xFF00E5FF), // Cyan
    Color(0xFF2979FF), // Blue
    Color(0xFFFFC107), // Amber/Gold
    Color(0xFFFF9100), // Orange
    Color(0xFFD500F9)  // Purple
)

class SubtitleState {
    var isEnabled by mutableStateOf(true)
    var currentSubtitleName by mutableStateOf<String?>(null)
    var rawSrtContent by mutableStateOf<String?>(null)
    var cues by mutableStateOf<List<SubtitleCue>>(emptyList())
    var delaySec by mutableFloatStateOf(0.0f)
    var yFraction by mutableFloatStateOf(0.08f) // Vertical offset from bottom (0.04 to 0.85)

    // Styling State (Screenshot 1)
    var selectedPresetId by mutableIntStateOf(0)
    var fontColor by mutableStateOf(Color.White)
    var fontSizeSp by mutableFloatStateOf(22f)
    var hasOutline by mutableStateOf(false)
    var boxColor by mutableStateOf(Color(0xCC000000))

    fun applyPreset(preset: SubtitlePreset) {
        selectedPresetId = preset.id
        fontColor = preset.textColor
        boxColor = preset.bgColor
        hasOutline = preset.hasOutline
    }

    fun selectColor(color: Color) {
        fontColor = color
        selectedPresetId = -1
    }

    fun resetSize() {
        fontSizeSp = 22f
    }

    fun loadSrtText(text: String, name: String) {
        rawSrtContent = text
        cues = SrtParser.parse(text)
        currentSubtitleName = name
        isEnabled = true
    }

    fun clearSubtitle() {
        cues = emptyList()
        currentSubtitleName = null
        rawSrtContent = null
        delaySec = 0.0f
        yFraction = 0.08f
    }

    fun getCurrentCue(currentPositionMs: Long): String? {
        if (!isEnabled || cues.isEmpty()) return null
        val effectivePosition = currentPositionMs + (delaySec * 1000f).toLong()
        return cues.firstOrNull { effectivePosition in it.startMs..it.endMs }?.text
    }

    fun getSurroundingContext(currentPositionMs: Long, radius: Int = 2): String {
        if (cues.isEmpty()) return ""
        val effectivePosition = currentPositionMs + (delaySec * 1000f).toLong()
        val currentIndex = cues.indexOfFirst { effectivePosition in it.startMs..it.endMs }
        if (currentIndex < 0) return ""

        val startIdx = (currentIndex - radius).coerceAtLeast(0)
        val endIdx = (currentIndex + radius).coerceAtMost(cues.lastIndex)
        return cues.subList(startIdx, endIdx + 1).joinToString("\n") { cue ->
            val prefix = if (cue == cues[currentIndex]) "▶ (Current Line) " else "- "
            "$prefix${cue.text.replace("\n", " ")}"
        }
    }
}

object GroqAiService {
    private const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL_NAME = "qwen/qwen3.8-27b"
    private const val KEY_FILE_NAME = "groq_api_key.txt"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun getApiKey(context: Context): String? {
        return try {
            val file = File(context.filesDir, KEY_FILE_NAME)
            if (file.exists()) file.readText(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() } else null
        } catch (_: Exception) { null }
    }

    fun saveApiKey(context: Context, key: String) {
        try {
            val file = File(context.filesDir, KEY_FILE_NAME)
            file.writeText(key.trim(), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    fun isKeyConfigured(context: Context): Boolean = !getApiKey(context).isNullOrBlank()

    suspend fun explainDialogue(context: Context, currentCue: String, surroundingContext: String): String = withContext(Dispatchers.IO) {
        val prompt = """
            Scene Dialogue Context:
            $surroundingContext

            Specific Spoken Line:
            "$currentCue"

            Explain what this character's line means in this scene. Focus on the subtext, emotion, idioms, or underlying intent. Be concise, direct, and punchy (1 to 2 sentences max). No introductory fluff.
        """.trimIndent()
        queryGroq(context, prompt)
    }

    suspend fun explainWord(context: Context, targetWord: String, currentCue: String, surroundingContext: String): String = withContext(Dispatchers.IO) {
        val prompt = """
            Scene Context:
            $surroundingContext

            Dialogue Line:
            "$currentCue"

            Target Word:
            "$targetWord"

            Explain this word in 2 short bullet points:
            1. General Meaning: Standard definition (1 short line).
            2. In-Context Meaning: Exactly how the speaker is using it here (slang, sarcasm, tone, or specific nuance in this scene).
        """.trimIndent()
        queryGroq(context, prompt)
    }

    private fun queryGroq(context: Context, userPrompt: String): String {
        val apiKey = getApiKey(context) ?: throw IllegalStateException("Groq API key not set. Please enter your key.")

        val root = JSONObject().apply {
            put("model", MODEL_NAME)
            put("temperature", 0.3)
            put("max_tokens", 250)

            val messages = org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are an expert cinema dialogue interpreter. Provide instant, punchy, contextual explanations. No filler text.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            }
            put("messages", messages)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = root.toString().toRequestBody(mediaType)

        val req = Request.Builder()
            .url(GROQ_ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val resp = httpClient.newCall(req).execute()
        val respBody = resp.body?.string() ?: ""

        if (!resp.isSuccessful) {
            val errorMsg = try {
                JSONObject(respBody).optJSONObject("error")?.optString("message")
            } catch (_: Exception) { null }
            throw IllegalStateException(errorMsg ?: "Groq Error (HTTP ${resp.code})")
        }

        val json = JSONObject(respBody)
        val choices = json.optJSONArray("choices")
        return if (choices != null && choices.length() > 0) {
            val msgObj = choices.getJSONObject(0).getJSONObject("message")
            msgObj.getString("content").trim()
        } else {
            "No explanation returned."
        }
    }
}

object PlaybackHistoryManager {
    private const val HISTORY_FILE_NAME = "omni_player_history.json"
    private val cache = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private var isLoaded = false

    private fun ensureLoaded(context: Context) {
        if (isLoaded) return
        try {
            val file = File(context.filesDir, HISTORY_FILE_NAME)
            if (file.exists()) {
                val json = JSONObject(file.readText(Charsets.UTF_8))
                json.keys().forEach { key ->
                    cache[key] = json.getJSONObject(key)
                }
            }
            isLoaded = true
        } catch (_: Exception) {}
    }

    fun saveVideoState(
        context: Context,
        videoId: String,
        positionMs: Long,
        subtitleName: String?,
        rawSrtText: String?,
        delaySec: Float,
        yFraction: Float,
        isEnabled: Boolean
    ) {
        ensureLoaded(context)
        try {
            var localSrtPath: String? = null
            if (!rawSrtText.isNullOrBlank()) {
                val subDir = File(context.filesDir, "saved_subtitles")
                if (!subDir.exists()) subDir.mkdirs()
                val subFile = File(subDir, "sub_${videoId}.srt")
                subFile.writeText(rawSrtText, Charsets.UTF_8)
                localSrtPath = subFile.absolutePath
            }

            val obj = JSONObject().apply {
                put("positionMs", positionMs)
                put("subtitleName", subtitleName ?: "")
                put("localSrtPath", localSrtPath ?: "")
                put("delaySec", delaySec.toDouble())
                put("yFraction", yFraction.toDouble())
                put("isEnabled", isEnabled)
                put("updatedAt", System.currentTimeMillis())
            }
            cache[videoId] = obj

            val root = JSONObject()
            cache.forEach { (k, v) -> root.put(k, v) }
            File(context.filesDir, HISTORY_FILE_NAME).writeText(root.toString(2), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    fun getSavedState(context: Context, videoId: String): JSONObject? {
        ensureLoaded(context)
        return cache[videoId]
    }

    fun getSavedSubtitle(context: Context, videoId: String): Pair<String, String>? {
        val state = getSavedState(context, videoId) ?: return null
        val path = state.optString("localSrtPath", "")
        val name = state.optString("subtitleName", "")
        if (path.isNotEmpty()) {
            val file = File(path)
            if (file.exists() && file.length() > 0) {
                return Pair(name.ifEmpty { file.name }, file.readText(Charsets.UTF_8))
            }
        }
        return null
    }
}

object SrtParser {
    fun parse(text: String): List<SubtitleCue> {
        val list = mutableListOf<SubtitleCue>()
        val blocks = text.replace("\r\n", "\n").replace("\r", "\n").split("\n\n")
        for (block in blocks) {
            val lines = block.trim().lines()
            if (lines.size < 2) continue
            val timeIdx = if (lines[0].toIntOrNull() != null && lines.size >= 3) 1 else 0
            val timeLine = lines[timeIdx]
            if (!timeLine.contains("-->")) continue

            val parts = timeLine.split("-->")
            if (parts.size != 2) continue
            val startMs = parseTimestamp(parts[0].trim())
            val endMs = parseTimestamp(parts[1].trim())
            if (startMs < 0 || endMs < 0) continue

            val textLines = lines.subList(timeIdx + 1, lines.size)
            val cleanText = textLines.joinToString("\n").replace(Regex("<[^>]*>"), "").trim()
            if (cleanText.isNotEmpty()) {
                list.add(SubtitleCue(startMs, endMs, cleanText))
            }
        }
        return list.sortedBy { it.startMs }
    }

    private fun parseTimestamp(raw: String): Long {
        return try {
            val clean = raw.replace(',', '.')
            val parts = clean.split(":")
            if (parts.size == 3) {
                val h = parts[0].toLong()
                val m = parts[1].toLong()
                val s = parts[2].toDouble()
                (h * 3600_000L + m * 60_000L + (s * 1000.0).toLong())
            } else if (parts.size == 2) {
                val m = parts[0].toLong()
                val s = parts[1].toDouble()
                (m * 60_000L + (s * 1000.0).toLong())
            } else -1L
        } catch (_: Exception) {
            -1L
        }
    }
}

object OpenSubtitlesService {
    private const val API_KEY = "VuCEklcyZnuRhoVIdcIsosQJgrehqeE2"
    private const val USER_AGENT = "OmniPlayer/1.0"
    private const val BASE_URL = "https://api.opensubtitles.com/api/v1"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun cleanTitle(rawName: String): String {
        var name = rawName.substringBeforeLast(".")
        val junk = Regex("(?i)\\b(720p|1080p|2160p|4k|bluray|blu-ray|web-dl|webrip|hdrip|hdtv|x264|x265|hevc|aac|dts|ac3|10bit|remux)\\b")
        name = junk.replace(name, " ")
        name = name.replace(Regex("[._\\-]+"), " ")
        return name.replace(Regex("\\s+"), " ").trim()
    }

    suspend fun search(query: String, langCode: String): List<SubtitleResult> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SubtitleResult>()
        try {
            val encodedQ = URLEncoder.encode(query, "UTF-8")
            val langParam = if (langCode == "all") "" else "&languages=$langCode"
            val url = "$BASE_URL/subtitles?query=$encodedQ$langParam"

            val req = Request.Builder()
                .url(url)
                .addHeader("Api-Key", API_KEY)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept", "application/json")
                .build()

            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val json = JSONObject(resp.body?.string() ?: "{}")
                val arr = json.optJSONArray("data")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val attr = item.optJSONObject("attributes") ?: continue
                        val filesArr = attr.optJSONArray("files")
                        val firstFile = filesArr?.optJSONObject(0)
                        val fileId = firstFile?.optLong("file_id") ?: continue
                        val fileName = firstFile.optString("file_name", "sub.srt")
                        val release = attr.optString("release", attr.optString("feature_details_title", fileName))
                        val lang = attr.optString("language", "en")
                        val dlCount = attr.optInt("download_count", 0)
                        val rating = attr.optDouble("ratings", 0.0)

                        list.add(
                            SubtitleResult(
                                fileId = fileId,
                                releaseName = release,
                                language = lang,
                                downloadCount = dlCount,
                                rating = rating,
                                fileName = fileName
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        list
    }

    suspend fun downloadSubtitleText(fileId: Long): String? = withContext(Dispatchers.IO) {
        try {
            val jsonReq = JSONObject().apply { put("file_id", fileId) }
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = jsonReq.toString().toRequestBody(mediaType)

            val req = Request.Builder()
                .url("$BASE_URL/download")
                .addHeader("Api-Key", API_KEY)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept", "application/json")
                .post(body)
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null
            val respJson = JSONObject(resp.body?.string() ?: "{}")
            val link = respJson.optString("link", "")
            if (link.isEmpty()) return@withContext null

            val fileReq = Request.Builder().url(link).build()
            val fileResp = httpClient.newCall(fileReq).execute()
            fileResp.body?.string()
        } catch (_: Exception) {
            null
        }
    }
}