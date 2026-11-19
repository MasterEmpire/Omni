package com.omni.plugin.browser.services

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import com.omni.hub.api.HostBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Pure-Kotlin TLS WebSocket + AudioTrack streaming client for Gemini Live API (models/gemini-3.8-live).
 * Bypasses browser CSP restrictions and continues playback cleanly in the background.
 */
class GeminiLiveVoiceEngine(
    private val bridge: HostBridge,
    private val scope: CoroutineScope,
    private val onStateChanged: (state: String, turnId: String?, progress: String) -> Unit
) {
    companion object {
        private const val HOST = "generativelanguage.googleapis.com"
        private const val PORT = 443
        private const val MODEL = "models/gemini-3.8-live"
        private const val SAMPLE_RATE = 24000

        private const val READ_ALOUD_SYSTEM_INSTRUCTION =
            "You are a verbatim text-to-speech reader. Read the user's text aloud word-for-word clearly, naturally, and at a steady pace. " +
            "Do NOT add any greeting, intro, outro, summary, or commentary. Do NOT answer or converse with the text. " +
            "If any raw source code, XML/HTML tags, JSON blobs, stack traces, file paths, or URLs slipped into the text, skip them or briefly say 'code snippet' and continue reading the surrounding prose."
    }

    private var activeJob: Job? = null
    @Volatile private var activeSocket: SSLSocket? = null
    @Volatile private var activeAudioTrack: AudioTrack? = null
    @Volatile var currentTurnId: String? = null
        private set
    @Volatile var currentState: String = "IDLE"
        private set

    fun speak(rawText: String, apiKey: String, voiceName: String = "Default", turnId: String? = null) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            bridge.showToast("⚠️ Set your Gemini API Key in General Settings first")
            updateState("IDLE", null, "")
            return
        }

        val sanitized = sanitizeTextForSpeech(rawText)
        if (sanitized.isBlank()) {
            bridge.showToast("Nothing readable in this response (code/tags only)")
            updateState("IDLE", null, "")
            return
        }

        val chunks = splitIntoSpeechChunks(sanitized, maxChars = 750)
        if (chunks.isEmpty()) {
            updateState("IDLE", null, "")
            return
        }

        stopInternal(notifyIdle = false)
        currentTurnId = turnId
        updateState("CONNECTING", turnId, "1/${chunks.size}")
        bridge.acquireWakeLock("GeminiLiveVoice")

        activeJob = scope.launch(Dispatchers.IO) {
            var socket: SSLSocket? = null
            var audioTrack: AudioTrack? = null
            val pcmChannel = Channel<ByteArray>(Channel.UNLIMITED)
            var audioWriterJob: Job? = null

            try {
                audioTrack = createAudioTrack()
                activeAudioTrack = audioTrack
                audioTrack.play()

                audioWriterJob = launch(Dispatchers.IO) {
                    try {
                        for (pcm in pcmChannel) {
                            if (!isActive) break
                            var offset = 0
                            while (offset < pcm.size && isActive) {
                                val written = audioTrack.write(pcm, offset, pcm.size - offset)
                                if (written <= 0) break
                                offset += written
                            }
                        }
                    } catch (_: Exception) {}
                }

                socket = openWebSocket(cleanKey)
                activeSocket = socket
                val dis = DataInputStream(BufferedInputStream(socket.inputStream, 32768))
                val out = BufferedOutputStream(socket.outputStream, 16384)

                val setupJson = buildSetupPayload(voiceName)
                sendFrame(out, 0x01, setupJson.toByteArray(Charsets.UTF_8))
                bridge.log("GEMINI_VOICE", "Sent setup for $MODEL (chunks=${chunks.size}, voice=$voiceName)")

                var setupDone = false
                var chunkIndex = 0
                var receivedAudioInTurn = false
                val fragBuffer = ByteArrayOutputStream()
                var fragOpcode = -1

                while (isActive && !socket.isClosed) {
                    val b0: Int
                    val b1: Int
                    try {
                        b0 = dis.readUnsignedByte()
                        b1 = dis.readUnsignedByte()
                    } catch (_: EOFException) {
                        break
                    }

                    val fin = (b0 and 0x80) != 0
                    val opcode = b0 and 0x0F
                    val masked = (b1 and 0x80) != 0
                    var payloadLen = (b1 and 0x7F).toLong()
                    if (payloadLen == 126L) {
                        payloadLen = dis.readUnsignedShort().toLong()
                    } else if (payloadLen == 127L) {
                        payloadLen = dis.readLong()
                    }
                    if (payloadLen < 0L || payloadLen > 32L * 1024L * 1024L) {
                        throw IllegalStateException("Unexpected WebSocket frame size: $payloadLen")
                    }

                    val maskKey = if (masked) ByteArray(4).also { dis.readFully(it) } else null
                    val frameBytes = ByteArray(payloadLen.toInt())
                    if (frameBytes.isNotEmpty()) {
                        dis.readFully(frameBytes)
                        if (maskKey != null) {
                            for (i in frameBytes.indices) {
                                frameBytes[i] = (frameBytes[i].toInt() xor maskKey[i and 3].toInt()).toByte()
                            }
                        }
                    }

                    // Control frames can arrive interleaved between fragments
                    if (opcode == 0x08) {
                        val closeCode = if (frameBytes.size >= 2) ((frameBytes[0].toInt() and 0xFF) shl 8) or (frameBytes[1].toInt() and 0xFF) else 1000
                        val closeReason = if (frameBytes.size > 2) String(frameBytes, 2, frameBytes.size - 2, Charsets.UTF_8) else ""
                        bridge.log("GEMINI_VOICE", "Server closed WS (code=$closeCode, reason='$closeReason')")
                        if (closeCode != 1000 && closeReason.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                bridge.showToast("Gemini Live: $closeReason")
                            }
                        }
                        break
                    } else if (opcode == 0x09) {
                        sendFrame(out, 0x0A, frameBytes)
                        continue
                    } else if (opcode == 0x0A) {
                        continue
                    }

                    val messageBytes: ByteArray? = if (opcode == 0x01 || opcode == 0x02) {
                        if (fin) {
                            frameBytes
                        } else {
                            fragBuffer.reset()
                            fragBuffer.write(frameBytes)
                            fragOpcode = opcode
                            null
                        }
                    } else if (opcode == 0x00 && fragOpcode != -1) {
                        fragBuffer.write(frameBytes)
                        if (fin) {
                            fragOpcode = -1
                            fragBuffer.toByteArray()
                        } else {
                            null
                        }
                    } else {
                        null
                    }

                    if (messageBytes == null) continue
                    val jsonStr = String(messageBytes, Charsets.UTF_8)
                    val msg = try { JSONObject(jsonStr) } catch (_: Exception) { continue }

                    if (msg.has("error")) {
                        val errObj = msg.optJSONObject("error")
                        val errMsg = errObj?.optString("message") ?: msg.optString("error", "Unknown API error")
                        bridge.log("GEMINI_VOICE_ERR", "API Error: $errMsg")
                        withContext(Dispatchers.Main) {
                            bridge.showToast("Gemini Live Error: $errMsg")
                        }
                        break
                    }

                    if (!setupDone && msg.has("setupComplete")) {
                        setupDone = true
                        updateState("SPEAKING", turnId, "1/${chunks.size}")
                        val turnJson = buildClientTurnPayload(chunks[0], isFirst = true, total = chunks.size)
                        sendFrame(out, 0x01, turnJson.toByteArray(Charsets.UTF_8))
                        bridge.log("GEMINI_VOICE", "SetupComplete -> dispatched chunk 1/${chunks.size} (${chunks[0].length} chars)")
                        continue
                    }

                    val serverContent = msg.optJSONObject("serverContent") ?: continue
                    val modelTurn = serverContent.optJSONObject("modelTurn")
                    if (modelTurn != null) {
                        val parts = modelTurn.optJSONArray("parts")
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val part = parts.optJSONObject(i) ?: continue
                                val inlineData = part.optJSONObject("inlineData") ?: continue
                                val b64 = inlineData.optString("data", "")
                                if (b64.isNotEmpty()) {
                                    receivedAudioInTurn = true
                                    val pcm = Base64.decode(b64, Base64.DEFAULT)
                                    if (pcm.isNotEmpty()) {
                                        pcmChannel.trySend(pcm)
                                    }
                                }
                            }
                        }
                    }

                    if (serverContent.optBoolean("turnComplete", false)) {
                        if (!receivedAudioInTurn && chunkIndex == 0) {
                            bridge.log("GEMINI_VOICE_WARN", "Turn completed with 0 audio bytes")
                        }
                        chunkIndex++
                        if (chunkIndex < chunks.size) {
                            receivedAudioInTurn = false
                            updateState("SPEAKING", turnId, "${chunkIndex + 1}/${chunks.size}")
                            val nextJson = buildClientTurnPayload(chunks[chunkIndex], isFirst = false, total = chunks.size)
                            sendFrame(out, 0x01, nextJson.toByteArray(Charsets.UTF_8))
                            bridge.log("GEMINI_VOICE", "Dispatched chunk ${chunkIndex + 1}/${chunks.size} (${chunks[chunkIndex].length} chars)")
                        } else {
                            bridge.log("GEMINI_VOICE", "All ${chunks.size} chunk(s) streamed. Draining audio buffer...")
                            try { sendFrame(out, 0x08, byteArrayOf(0x03, 0xE8.toByte())) } catch (_: Exception) {}
                            break
                        }
                    }
                }

                pcmChannel.close()
                audioWriterJob.join()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                bridge.log("GEMINI_VOICE_ERR", "Voice session failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    bridge.showToast("Read Aloud failed: ${e.message?.take(80) ?: "Connection error"}")
                }
            } finally {
                pcmChannel.close()
                try { audioWriterJob?.cancel() } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
                if (activeSocket === socket) activeSocket = null
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                } catch (_: Exception) {}
                if (activeAudioTrack === audioTrack) activeAudioTrack = null
                bridge.releaseWakeLock()
                if (currentTurnId == turnId) {
                    updateState("IDLE", null, "")
                }
            }
        }
    }

    fun stop() {
        stopInternal(notifyIdle = true)
    }

    private fun stopInternal(notifyIdle: Boolean) {
        activeJob?.cancel()
        activeJob = null
        try { activeSocket?.close() } catch (_: Exception) {}
        activeSocket = null
        try {
            activeAudioTrack?.pause()
            activeAudioTrack?.flush()
            activeAudioTrack?.release()
        } catch (_: Exception) {}
        activeAudioTrack = null
        bridge.releaseWakeLock()
        if (notifyIdle) {
            updateState("IDLE", null, "")
        }
    }

    private fun updateState(state: String, turnId: String?, progress: String) {
        currentState = state
        currentTurnId = turnId
        scope.launch(Dispatchers.Main) {
            onStateChanged(state, turnId, progress)
        }
    }

    private fun createAudioTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val bufferSize = maxOf(minBuf * 4, SAMPLE_RATE * 2)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun openWebSocket(apiKey: String): SSLSocket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val socket = (factory.createSocket() as SSLSocket).apply {
            soTimeout = 45000
            connect(InetSocketAddress(HOST, PORT), 12000)
            startHandshake()
        }

        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val wsKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        val encodedApiKey = URLEncoder.encode(apiKey, "UTF-8")
        val path = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$encodedApiKey"

        val handshake = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(HOST).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("\r\n")
        }

        val out = socket.outputStream
        out.write(handshake.toByteArray(Charsets.US_ASCII))
        out.flush()

        val input = socket.inputStream
        val headerBuf = ByteArrayOutputStream()
        var state = 0
        while (headerBuf.size() < 16384) {
            val b = input.read()
            if (b == -1) throw EOFException("Server closed connection during WebSocket handshake")
            headerBuf.write(b)
            state = when {
                (state == 0 || state == 2) && b == '\r'.code -> state + 1
                state == 1 && b == '\n'.code -> 2
                state == 3 && b == '\n'.code -> 4
                else -> 0
            }
            if (state == 4) break
        }

        val headersText = String(headerBuf.toByteArray(), Charsets.UTF_8)
        val statusLine = headersText.lineSequence().firstOrNull() ?: ""
        if (!statusLine.contains(" 101 ")) {
            val errSnippet = try {
                val avail = input.available().coerceAtMost(512)
                if (avail > 0) {
                    val buf = ByteArray(avail)
                    val r = input.read(buf)
                    if (r > 0) String(buf, 0, r, Charsets.UTF_8) else ""
                } else ""
            } catch (_: Exception) { "" }
            socket.close()
            throw IllegalStateException("WebSocket handshake rejected: $statusLine $errSnippet".trim())
        }

        return socket
    }

    private fun sendFrame(out: BufferedOutputStream, opcode: Int, payload: ByteArray) {
        val len = payload.size
        val header = ByteArrayOutputStream()
        header.write(0x80 or (opcode and 0x0F))
        when {
            len < 126 -> header.write(0x80 or len)
            len <= 65535 -> {
                header.write(0x80 or 126)
                header.write((len ushr 8) and 0xFF)
                header.write(len and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                val l = len.toLong()
                for (shift in 56 downTo 0 step 8) {
                    header.write(((l ushr shift) and 0xFFL).toInt())
                }
            }
        }
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        header.write(mask)
        val masked = ByteArray(len)
        for (i in 0 until len) {
            masked[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        }
        synchronized(out) {
            out.write(header.toByteArray())
            out.write(masked)
            out.flush()
        }
    }

    private fun buildSetupPayload(voiceName: String): String {
        val genConfig = JSONObject().apply {
            put("responseModalities", JSONArray().put("AUDIO"))
            if (voiceName.isNotBlank() && !voiceName.equals("Default", ignoreCase = true)) {
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", voiceName)
                        })
                    })
                })
            }
        }

        val setup = JSONObject().apply {
            put("model", MODEL)
            put("generationConfig", genConfig)
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", READ_ALOUD_SYSTEM_INSTRUCTION)
                }))
            })
        }
        return JSONObject().put("setup", setup).toString()
    }

    private fun buildClientTurnPayload(text: String, isFirst: Boolean, total: Int): String {
        val directive = when {
            total <= 1 -> "Read the following text aloud verbatim without commentary:\n\n$text"
            isFirst -> "Read this first segment aloud verbatim with zero intro:\n\n$text"
            else -> "Continue reading this next segment aloud verbatim with zero intro or transition filler:\n\n$text"
        }
        val part = JSONObject().put("text", directive)
        val turn = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(part))
        }
        val clientContent = JSONObject().apply {
            put("turns", JSONArray().put(turn))
            put("turnComplete", true)
        }
        return JSONObject().put("clientContent", clientContent).toString()
    }

    fun sanitizeTextForSpeech(raw: String): String {
        if (raw.isBlank()) return ""
        var text = raw

        // Strip CXP / Omni action XML blocks completely
        val cxpRegex = Regex("<" + "(?:cxp|omni_cxp|patch)\\b[\\s\\S]*?<" + "/(?:cxp|omni_cxp|patch)>", RegexOption.IGNORE_CASE)
        val pullRegex = Regex("<" + "(?:pull_files|execute_python|omni_action)\\b[\\s\\S]*?(?:\\/>|<" + "/(?:pull_files|execute_python|omni_action)>)", RegexOption.IGNORE_CASE)
        text = text.replace(cxpRegex, " [patch omitted] ")
        text = text.replace(pullRegex, " ")

        // Strip fenced markdown code blocks
        text = text.replace(Regex("```[\\s\\S]*?```"), " [code block skipped] ")
        // Strip long inline backtick code snippets (> 36 chars) and unquote short identifiers
        text = text.replace(Regex("`([^`]{36,})`"), " [code snippet] ")
        text = text.replace("`", "")

        // Collapse raw HTTP(S) URLs to clean hostnames
        text = text.replace(Regex("https?://([^/\\s)]+)[^\\s)]*")) { m ->
            m.groupValues[1].removePrefix("www.")
        }

        // Strip markdown table dividers, headings, bold/italic markers, bullet prefixes
        text = text.replace(Regex("^[|\\s:-]+$", RegexOption.MULTILINE), " ")
        text = text.replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
        text = text.replace(Regex("[*_~]{1,3}"), "")
        text = text.replace(Regex("^\\s*[-*•]\\s+", RegexOption.MULTILINE), "")
        text = text.replace(Regex("<[^>]+>"), " ")
        text = text.replace(Regex("\\s+"), " ").trim()

        return text
    }

    private fun splitIntoSpeechChunks(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val sentences = Regex("[^.!?\\n]+[.!?\\n]*").findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        for (sentence in sentences) {
            if (sentence.length > maxChars) {
                if (current.isNotEmpty()) {
                    chunks.add(current.toString().trim())
                    current.setLength(0)
                }
                var start = 0
                while (start < sentence.length) {
                    val end = (start + maxChars).coerceAtMost(sentence.length)
                    chunks.add(sentence.substring(start, end).trim())
                    start = end
                }
                continue
            }

            if (current.length + sentence.length + 1 > maxChars && current.isNotEmpty()) {
                chunks.add(current.toString().trim())
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        if (current.isNotEmpty()) {
            chunks.add(current.toString().trim())
        }
        return chunks
    }
}