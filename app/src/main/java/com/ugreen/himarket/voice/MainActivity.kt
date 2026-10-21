package com.ugreen.himarket.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.DataPublishReliability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MainActivity : ComponentActivity() {

    private var room: Room? = null
    private var localRecorder: LocalWavRecorder? = null
    private var timerJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null

    private val currentScreen = mutableStateOf("home")
    private val historyFilter = mutableStateOf("all") // "all" | "transcribe" | "simultaneous_interpret"
    private val recordingHistory = mutableStateListOf<LocalRecordingEntry>()
    private val selectedRecording = mutableStateOf<LocalRecordingEntry?>(null)
    private val detailTab = mutableStateOf("summary") // "summary" | "transcript"
    private val selectedSummaryStyle = mutableStateOf("speech") // "speech" | "meeting" | "concise"
    private val playbackSpeed = mutableStateOf(1.0f)
    private val isPlayingWav = mutableStateOf(false)
    private val playbackPositionMs = mutableStateOf(0L)

    private val allowedLangsState = mutableStateListOf("zh", "en", "ja", "ko")
    private val sourceLangState = mutableStateOf("zh")
    private val targetLangState = mutableStateOf("en")
    private val phoneLangState = mutableStateOf("en")
    private val earphoneLangState = mutableStateOf("zh")

    private val activeTaskState = mutableStateOf("cascade")
    private val activeSceneState = mutableStateOf("")
    private val isSessionActive = mutableStateOf(false)
    private val elapsedSecondsState = mutableStateOf(0L)
    private val activeDialogueSpeaker = mutableStateOf<String?>(null) // "phone" | "earphone" | null
    private val activeDialogueTurnId = mutableStateOf<String?>(null)
    private val isTtsSpeaking = mutableStateOf(false)

    private val liveSegments = mutableStateListOf<TranscriptSegmentItem>()
    private val interimTurnId = mutableStateOf<String?>(null)
    private val interimSpeaker = mutableStateOf<String?>(null)
    private val interimSourceText = mutableStateOf("")
    private val liveSummaryRecord = mutableStateOf<LocalSummaryRecord?>(null)

    private val textTranslateSessionId = mutableStateOf<String?>(null)
    private val textInputState = mutableStateOf("你好，很高兴认识你。")
    private val translatedOutputState = mutableStateOf("")
    private val contextSessionId = mutableStateOf<String?>(null)
    private val noteInputsList = mutableStateListOf<String>()

    private val statusText = mutableStateOf("就绪")
    private val sessionIdState = mutableStateOf("-")
    private val roomNameState = mutableStateOf("-")
    private val tenantIdState = mutableStateOf("tenant_ugreen_01")
    private val apiKeyIdState = mutableStateOf("ak_live_ugreen_01")
    private val usageSummaryState = mutableStateOf("等待计量数据...")
    private val chatLogs = mutableStateListOf<String>()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i("HiMarketVoice", "RECORD_AUDIO permission granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        recordingHistory.clear()
        recordingHistory.addAll(LocalRecordingStore.loadAll(this))

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MainAppScaffold()
            }
        }

        lifecycleScope.launch {
            fetchTenantLanguagePolicy()
        }

        handleIntentCommand(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMediaPlayer()
        localRecorder?.stop()
        room?.disconnect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntentCommand(intent)
    }

    private fun handleIntentCommand(intent: Intent?) {
        val prompt = intent?.getStringExtra("voice_prompt")
        if (!prompt.isNullOrBlank()) {
            currentScreen.value = "cascade"
            lifecycleScope.launch {
                if (room == null) {
                    startRoomTaskSession(
                        task = "cascade",
                        scene = "cascade",
                        initialPrompt = prompt,
                    )
                } else {
                    sendVoicePromptTurn(prompt)
                }
            }
        }
    }

    private fun langLabel(code: String): String = when (code.lowercase()) {
        "zh", "zh-cn" -> "中文（简体）"
        "en", "en-us" -> "英语"
        "ja", "ja-jp" -> "日语"
        "ko", "ko-kr" -> "韩语"
        "de" -> "德语"
        "es" -> "西班牙语"
        "fr" -> "法语"
        "id" -> "印度尼西亚语"
        "it" -> "意大利语"
        else -> code
    }

    private fun appendLog(msg: String) {
        Log.i("HiMarketVoice", msg)
        lifecycleScope.launch(Dispatchers.Main) {
            chatLogs.add(0, msg)
        }
    }

    private suspend fun fetchTenantLanguagePolicy() {
        try {
            val policy = withContext(Dispatchers.IO) {
                val url = URL("http://127.0.0.1:8080/api/v1/voice/language-policy")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Authorization", "Bearer hm_ak_live_ugreen_2026_voice_key")
                val text = conn.inputStream.bufferedReader().readText()
                JSONObject(text).getJSONObject("data")
            }
            val defLang = policy.optString("default_target_lang", "zh")
            val arr = policy.optJSONArray("allowed_langs") ?: JSONArray()
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            if (list.isNotEmpty()) {
                allowedLangsState.clear()
                allowedLangsState.addAll(list)
                if (!list.contains(targetLangState.value)) {
                    targetLangState.value = defLang
                }
            }
        } catch (e: Exception) {
            Log.w("HiMarketVoice", "Language policy load skipped: ${e.message}")
        }
    }

    private fun startRoomTaskSession(
        task: String,
        scene: String,
        initialPrompt: String? = null,
    ) {
        lifecycleScope.launch {
            try {
                activeTaskState.value = task
                activeSceneState.value = scene
                liveSegments.clear()
                interimSourceText.value = ""
                interimTurnId.value = null
                liveSummaryRecord.value = null
                activeDialogueSpeaker.value = null
                activeDialogueTurnId.value = null

                statusText.value = "正在创建 $task 会话..."
                val sessionResp = withContext(Dispatchers.IO) {
                    requestRoomSessionFromHiMarket(task)
                }

                val sessionId = sessionResp.getString("session_id")
                val roomName = sessionResp.optString("room_name", "-")
                val wsUrl = sessionResp.getString("livekit_ws_url")
                val jwtToken = sessionResp.getString("participant_token")
                val tenantId = sessionResp.getString("tenant_id")
                val apiKeyId = sessionResp.getString("api_key_id")
                val voiceFb = sessionResp.optBoolean("voice_fallback", false)
                if (voiceFb) {
                    appendLog("ℹ️ 没有该语言专属音色，已用租户默认音色")
                }

                sessionIdState.value = sessionId
                roomNameState.value = roomName
                tenantIdState.value = tenantId
                apiKeyIdState.value = apiKeyId

                room?.disconnect()
                val lkRoom = LiveKit.create(applicationContext)
                room = lkRoom

                lifecycleScope.launch {
                    lkRoom.events.collect { event ->
                        when (event) {
                            is RoomEvent.Connected -> {
                                isSessionActive.value = true
                                statusText.value = "🟢 会话进行中 ($task)"
                                appendLog("✓ 已连接房间: ${lkRoom.name}")
                            }
                            is RoomEvent.TrackSubscribed -> {
                                appendLog("🔊 已订阅 AI 音轨: ${event.track.name}")
                            }
                            is RoomEvent.DataReceived -> {
                                val raw = String(event.data, Charsets.UTF_8)
                                handleIncomingDataPacket(raw)
                            }
                            is RoomEvent.Disconnected -> {
                                isSessionActive.value = false
                                statusText.value = "⚪ 会话已断开"
                            }
                            else -> {}
                        }
                    }
                }

                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = true
                audioManager.setStreamVolume(
                    AudioManager.STREAM_VOICE_CALL,
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                    0,
                )
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 4) / 5,
                    0,
                )

                lkRoom.connect(
                    url = wsUrl,
                    token = jwtToken,
                    options = ConnectOptions(autoSubscribe = true),
                )

                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    lkRoom.localParticipant.setMicrophoneEnabled(true)
                    // Start local WAV archiving ONLY for Scene 1 (transcribe) and Scene 2 (simultaneous_interpret)
                    if (scene == "transcribe" || scene == "simultaneous_interpret") {
                        localRecorder?.stop()
                        val wavFile = LocalRecordingStore.wavFileForSession(this@MainActivity, sessionId)
                        localRecorder = LocalWavRecorder(wavFile, 16000).also { it.start() }
                    }
                }

                elapsedSecondsState.value = 0L
                timerJob?.cancel()
                timerJob = lifecycleScope.launch {
                    while (isSessionActive.value) {
                        delay(1000)
                        elapsedSecondsState.value += 1L
                    }
                }

                if (task == "cascade" && !initialPrompt.isNullOrBlank()) {
                    delay(2500)
                    sendVoicePromptTurn(initialPrompt)
                }
            } catch (e: Exception) {
                Log.e("HiMarketVoice", "Session error", e)
                statusText.value = "❌ 错误: ${e.message}"
                appendLog("❌ 异常: ${e.message}")
            }
        }
    }

    private fun handleIncomingDataPacket(raw: String) {
        try {
            val obj = JSONObject(raw)
            val type = obj.optString("type", "subtitle")
            when (type) {
                "subtitle" -> {
                    val role = obj.optString("role", "assistant")
                    val turnId = obj.optString("turn_id", UUID.randomUUID().toString().take(8))
                    val text = obj.optString("text", "")
                    val isFinal = obj.optBoolean("final", true)
                    val seq = if (obj.has("seq") && !obj.isNull("seq")) obj.optLong("seq") else null
                    val speaker = obj.optString("speaker", "").ifEmpty { null }
                    val voiceFb = obj.optBoolean("voice_fallback", false)
                    if (voiceFb) {
                        statusText.value = "ℹ️ 没有该语言音色，已用租户默认音色"
                    }

                    when (role) {
                        "user", "source" -> {
                            if (!isFinal) {
                                interimTurnId.value = turnId
                                interimSpeaker.value = speaker
                                interimSourceText.value = text
                            } else {
                                if (interimTurnId.value == turnId) {
                                    interimSourceText.value = ""
                                    interimTurnId.value = null
                                }
                                val existingIdx = liveSegments.indexOfFirst { it.turnId == turnId }
                                if (existingIdx >= 0) {
                                    val prev = liveSegments[existingIdx]
                                    liveSegments[existingIdx] = prev.copy(
                                        seq = seq ?: prev.seq,
                                        speaker = speaker ?: prev.speaker,
                                        sourceText = text,
                                        isFinal = true,
                                    )
                                } else {
                                    liveSegments.add(
                                        TranscriptSegmentItem(
                                            turnId = turnId,
                                            seq = seq,
                                            speaker = speaker,
                                            sourceText = text,
                                            isFinal = true,
                                        )
                                    )
                                }
                                appendLog("🧑 原文($turnId): $text")
                            }
                        }
                        "translation" -> {
                            val idx = liveSegments.indexOfFirst { it.turnId == turnId }
                            if (idx >= 0) {
                                val prev = liveSegments[idx]
                                liveSegments[idx] = prev.copy(
                                    translatedText = text,
                                    isFinal = isFinal,
                                )
                            } else {
                                liveSegments.add(
                                    TranscriptSegmentItem(
                                        turnId = turnId,
                                        seq = seq,
                                        speaker = speaker,
                                        sourceText = "",
                                        translatedText = text,
                                        isFinal = isFinal,
                                    )
                                )
                            }
                            if (isFinal) {
                                appendLog("🌐 译文($turnId): $text")
                                refreshUsageSummary()
                            }
                        }
                        "summary" -> {
                            val title = obj.optString("title", "AI总结")
                            val style = obj.optString("summary_style", "speech")
                            val tLang = obj.optString("target_lang", targetLangState.value)
                            liveSummaryRecord.value = LocalSummaryRecord(
                                requestId = turnId,
                                title = title,
                                summaryStyle = style,
                                summaryText = text,
                                targetLang = tLang,
                            )
                            appendLog("📝 总结($title): ${text.take(80)}...")
                            refreshUsageSummary()
                        }
                        else -> {
                            appendLog("🤖 AI: $text")
                            refreshUsageSummary()
                        }
                    }
                }
                "target_lang_updated" -> {
                    val newLang = obj.optString("target_lang", targetLangState.value)
                    targetLangState.value = newLang
                    val ttsErr = if (obj.isNull("tts_error")) null else obj.optString("tts_error", "")
                    val fb = obj.optBoolean("voice_fallback", false)
                    statusText.value = when {
                        !ttsErr.isNullOrEmpty() -> "⚠️ 已切至 ${langLabel(newLang)} ($ttsErr)"
                        fb -> "ℹ️ 已切至 ${langLabel(newLang)}（使用租户默认音色）"
                        else -> "✓ 目标语言已切至 ${langLabel(newLang)}"
                    }
                    appendLog(statusText.value)
                }
                "tts_state" -> {
                    val state = obj.optString("state", "")
                    val targetDevice = obj.optString("target_device", "speaker")
                    if (state == "start") {
                        isTtsSpeaking.value = true
                        DialogueAudioRouter.routeForTargetDevice(this, targetDevice)
                    } else if (state == "end") {
                        isTtsSpeaking.value = false
                    }
                }
                "summary_input_ack" -> {
                    val seq = obj.optLong("seq", 0)
                    appendLog("✓ 已记录上下文片段 #$seq")
                }
                "error" -> {
                    val code = obj.optString("code", "error")
                    val msg = obj.optString("message", raw)
                    statusText.value = "⚠️ [$code] $msg"
                    appendLog("⚠️ 错误[$code]: $msg")
                }
            }
        } catch (_: Exception) {
            appendLog("📩 数据消息: $raw")
        }
    }

    private fun stopSceneAndFinalize(autoSummarize: Boolean) {
        lifecycleScope.launch {
            val sid = sessionIdState.value
            val scene = activeSceneState.value
            timerJob?.cancel()
            isSessionActive.value = false
            room?.disconnect()
            room = null
            val durMs = localRecorder?.stop() ?: (elapsedSecondsState.value * 1000L)
            localRecorder = null

            if (sid != "-" && (scene == "transcribe" || scene == "simultaneous_interpret")) {
                val count = recordingHistory.count { it.scene == scene } + 1
                val defaultTitle = if (scene == "transcribe") "现场录音$count" else "同声传译$count"
                val wavPath = LocalRecordingStore.wavFileForSession(this@MainActivity, sid).absolutePath
                val entry = LocalRecordingEntry(
                    sessionId = sid,
                    scene = scene,
                    title = defaultTitle,
                    sourceLang = sourceLangState.value,
                    targetLang = if (scene == "transcribe") sourceLangState.value else targetLangState.value,
                    audioPath = wavPath,
                    durationMs = durMs.coerceAtLeast(elapsedSecondsState.value * 1000L),
                    createdAt = LocalRecordingStore.nowFormatted(),
                    segments = liveSegments.toMutableList(),
                    summary = liveSummaryRecord.value,
                )
                LocalRecordingStore.upsertEntry(this@MainActivity, entry)
                recordingHistory.clear()
                recordingHistory.addAll(LocalRecordingStore.loadAll(this@MainActivity))
                selectedRecording.value = entry
                currentScreen.value = "detail"

                if (autoSummarize && entry.segments.isNotEmpty()) {
                    triggerHttpSummaryForEntry(
                        entry = entry,
                        summaryStyle = selectedSummaryStyle.value,
                        speak = false,
                    )
                }
            }
            refreshUsageSummary()
        }
    }

    private fun sendMidSessionTargetLang(newLang: String) {
        targetLangState.value = newLang
        val lkRoom = room ?: return
        if (activeTaskState.value != "speech_translate" && activeTaskState.value != "transcribe") return
        lifecycleScope.launch {
            val payload = JSONObject().apply {
                put("type", "set_target_lang")
                put("target_lang", newLang)
                put("request_id", "lang_${UUID.randomUUID().toString().take(8)}")
            }.toString().toByteArray(Charsets.UTF_8)
            lkRoom.localParticipant.publishData(payload, DataPublishReliability.RELIABLE)
        }
    }

    private fun startDialogueTurn(speaker: String) {
        val lkRoom = room ?: return
        if (activeDialogueSpeaker.value != null || isTtsSpeaking.value) return
        val tid = "dlg_${UUID.randomUUID().toString().take(8)}"
        val srcLang = if (speaker == "phone") phoneLangState.value else earphoneLangState.value
        val tgtLang = if (speaker == "phone") earphoneLangState.value else phoneLangState.value
        activeDialogueSpeaker.value = speaker
        activeDialogueTurnId.value = tid
        DialogueAudioRouter.routeForInputSpeaker(this, speaker)

        lifecycleScope.launch {
            val payload = JSONObject().apply {
                put("type", "start_turn")
                put("turn_id", tid)
                put("speaker", speaker)
                put("source_lang", srcLang)
                put("target_lang", tgtLang)
            }.toString().toByteArray(Charsets.UTF_8)
            lkRoom.localParticipant.publishData(payload, DataPublishReliability.RELIABLE)
            statusText.value = if (speaker == "phone") "🎙️ 正在通过手机麦克风收音..." else "🎧 正在通过蓝牙耳机收音..."
        }
    }

    private fun stopDialogueTurn() {
        val lkRoom = room ?: return
        val tid = activeDialogueTurnId.value ?: return
        activeDialogueSpeaker.value = null
        activeDialogueTurnId.value = null
        lifecycleScope.launch {
            val payload = JSONObject().apply {
                put("type", "stop_turn")
                put("turn_id", tid)
            }.toString().toByteArray(Charsets.UTF_8)
            lkRoom.localParticipant.publishData(payload, DataPublishReliability.RELIABLE)
            statusText.value = "⏳ 正在定稿并翻译播报..."
        }
    }

    private fun sendInRoomSummarize(speak: Boolean) {
        val lkRoom = room ?: return
        lifecycleScope.launch {
            val reqId = "sum_${UUID.randomUUID().toString().take(10)}"
            val payload = JSONObject().apply {
                put("type", "summarize")
                put("request_id", reqId)
                put("speak", speak)
                put("target_lang", targetLangState.value)
                put("summary_style", selectedSummaryStyle.value)
            }.toString().toByteArray(Charsets.UTF_8)
            lkRoom.localParticipant.publishData(payload, DataPublishReliability.RELIABLE)
            statusText.value = "⏳ 已发送房间内总结请求 ($reqId)..."
        }
    }

    private fun triggerHttpSummaryForEntry(
        entry: LocalRecordingEntry,
        summaryStyle: String,
        speak: Boolean,
    ) {
        lifecycleScope.launch {
            val reqId = "sum_${UUID.randomUUID().toString().take(10)}"
            statusText.value = "⏳ 正在生成 AI 总结 ($summaryStyle)..."
            val sumSource = if (entry.scene == "simultaneous_interpret") "translated" else "source"
            val targetLang = if (entry.scene == "simultaneous_interpret") entry.targetLang else entry.sourceLang
            try {
                val data = withContext(Dispatchers.IO) {
                    executeSummaryHttpWithPolling(
                        sessionId = entry.sessionId,
                        requestId = reqId,
                        summarySource = sumSource,
                        summaryStyle = summaryStyle,
                        targetLang = targetLang,
                        speak = speak,
                        fallbackSegments = entry.segments,
                    )
                }
                val newTitle = data.optString("title", entry.title).ifEmpty { entry.title }
                val sumText = data.optString("summary_text", "")
                val ttsErr = if (data.isNull("tts_error")) null else data.optString("tts_error", "")
                val rec = LocalSummaryRecord(
                    requestId = reqId,
                    title = newTitle,
                    summaryStyle = summaryStyle,
                    summaryText = sumText,
                    targetLang = targetLang,
                )
                entry.title = newTitle
                entry.summary = rec
                LocalRecordingStore.upsertEntry(this@MainActivity, entry)
                recordingHistory.clear()
                recordingHistory.addAll(LocalRecordingStore.loadAll(this@MainActivity))
                selectedRecording.value = entry.copy(title = newTitle, summary = rec)
                liveSummaryRecord.value = rec

                statusText.value = if (!ttsErr.isNullOrEmpty()) {
                    "✓ 总结已生成 (⚠️ 音色提示: $ttsErr)"
                } else {
                    "✓ 总结已生成：《$newTitle》"
                }

                val segCount = data.optInt("segment_count", 0)
                if (speak && segCount > 0 && ttsErr.isNullOrEmpty()) {
                    playSummarySegmentsHttp(entry.sessionId, reqId, segCount)
                }
            } catch (e: Exception) {
                statusText.value = "❌ 总结失败: ${e.message}"
                appendLog("❌ 总结失败: ${e.message}")
            }
        }
    }

    private fun executeSummaryHttpWithPolling(
        sessionId: String?,
        requestId: String,
        summarySource: String,
        summaryStyle: String,
        targetLang: String,
        speak: Boolean,
        fallbackSegments: List<TranscriptSegmentItem> = emptyList(),
    ): JSONObject {
        val deadline = System.currentTimeMillis() + 90_000L
        var useInline = sessionId.isNullOrBlank()

        while (true) {
            val endpoint = if (!useInline && !sessionId.isNullOrBlank()) {
                "http://127.0.0.1:8080/api/v1/voice/sessions/$sessionId/summaries"
            } else {
                "http://127.0.0.1:8080/api/v1/voice/summaries"
            }
            val bodyObj = JSONObject().apply {
                put("request_id", requestId)
                put("speak", speak)
                put("target_lang", targetLang)
                put("summary_source", if (useInline) "source" else summarySource)
                put("summary_style", summaryStyle)
                if (useInline && fallbackSegments.isNotEmpty()) {
                    val arr = JSONArray()
                    fallbackSegments.forEachIndexed { idx, seg ->
                        val txt = seg.translatedText.ifBlank { seg.sourceText }
                        if (txt.isNotBlank()) {
                            arr.put(
                                JSONObject().apply {
                                    put("text", txt)
                                    put("client_input_id", "seg_${idx}_${seg.turnId}")
                                }
                            )
                        }
                    }
                    put("inputs", arr)
                }
            }

            try {
                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 30000 // 30s > server 25s LLM timeout
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer hm_ak_live_ugreen_2026_voice_key")
                conn.setRequestProperty("Content-Type", "application/json")
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                    it.write(bodyObj.toString())
                }

                val status = conn.responseCode
                val respText = (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
                val respJson = if (respText.isNotBlank()) JSONObject(respText) else JSONObject()

                if (status == 200) {
                    return respJson.getJSONObject("data")
                }
                if (status == 404 && !useInline && fallbackSegments.isNotEmpty()) {
                    useInline = true
                    continue
                }
                if (status == 409) {
                    val errCode = respJson.optString("error", "")
                    if (errCode == "summary_in_progress") {
                        if (System.currentTimeMillis() >= deadline) {
                            throw RuntimeException("summary_in_progress: 等待总结超时")
                        }
                        val retrySec = conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 2L
                        Thread.sleep((retrySec * 1000L).coerceAtLeast(1000L))
                        continue
                    }
                    // Failed state: do NOT retry with the same request_id
                    throw RuntimeException("${errCode.ifEmpty { "llm_failed" }}: ${respJson.optString("message")}")
                }
                val errCode = respJson.optString("error", "error_$status")
                throw RuntimeException("$errCode: ${respJson.optString("message", respText)}")
            } catch (e: java.net.SocketTimeoutException) {
                if (System.currentTimeMillis() >= deadline) throw e
                Thread.sleep(2000L)
            }
        }
    }

    private fun runTextTranslationHttp(text: String, targetLang: String) {
        lifecycleScope.launch {
            val cid = "tr_${UUID.randomUUID().toString().take(10)}"
            statusText.value = "⏳ 正在翻译文字..."
            try {
                val data = withContext(Dispatchers.IO) {
                    val conn = URL("http://127.0.0.1:8080/api/v1/voice/translations")
                        .openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 5000
                    conn.readTimeout = 30000
                    conn.doOutput = true
                    conn.setRequestProperty("Authorization", "Bearer hm_ak_live_ugreen_2026_voice_key")
                    conn.setRequestProperty("Content-Type", "application/json")
                    val req = JSONObject().apply {
                        put("text", text)
                        put("source_lang", sourceLangState.value)
                        put("target_lang", targetLang)
                        put("client_input_id", cid)
                        textTranslateSessionId.value?.let { put("session_id", it) }
                    }
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                        it.write(req.toString())
                    }
                    val status = conn.responseCode
                    val respText = (if (status in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.readText().orEmpty()
                    val json = JSONObject(respText)
                    if (status != 200) {
                        throw RuntimeException("${json.optString("error")}: ${json.optString("message")}")
                    }
                    json.getJSONObject("data")
                }
                val sid = data.getString("session_id")
                val trText = data.getString("translated_text")
                textTranslateSessionId.value = sid
                sessionIdState.value = sid
                translatedOutputState.value = trText
                statusText.value = "✓ 文字翻译完成 (Session=$sid)"
                appendLog("🌐 文字译文: $trText")
                refreshUsageSummary()
            } catch (e: Exception) {
                statusText.value = "❌ 翻译失败: ${e.message}"
                appendLog("❌ 翻译失败: ${e.message}")
            }
        }
    }

    private fun runHttpSpeakAndPlay(text: String, targetLang: String, cacheTurnId: String? = null) {
        lifecycleScope.launch {
            if (cacheTurnId != null) {
                val cached = LocalRecordingStore.ttsCacheFile(this@MainActivity, cacheTurnId)
                if (cached.exists() && cached.length() > 44) {
                    playLocalWavFile(cached)
                    return@launch
                }
            }
            statusText.value = "⏳ 正在请求语音播报..."
            try {
                val speakMeta = withContext(Dispatchers.IO) {
                    val conn = URL("http://127.0.0.1:8080/api/v1/voice/speak")
                        .openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 5000
                    conn.readTimeout = 30000
                    conn.doOutput = true
                    conn.setRequestProperty("Authorization", "Bearer hm_ak_live_ugreen_2026_voice_key")
                    conn.setRequestProperty("Content-Type", "application/json")
                    val req = JSONObject().apply {
                        put("text", text)
                        put("target_lang", targetLang)
                    }
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                        it.write(req.toString())
                    }
                    val status = conn.responseCode
                    val respText = (if (status in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.readText().orEmpty()
                    val json = JSONObject(respText)
                    if (status != 200) {
                        throw RuntimeException("${json.optString("error")}: ${json.optString("message")}")
                    }
                    json.getJSONObject("data")
                }
                val spkSid = speakMeta.getString("session_id")
                val segCount = speakMeta.optInt("segment_count", 1)
                val voiceFb = speakMeta.optBoolean("voice_fallback", false)
                if (voiceFb) {
                    appendLog("ℹ️ 没有该语言音色，已用租户默认音色")
                }
                for (idx in 0 until segCount) {
                    val wavFile = withContext(Dispatchers.IO) {
                        val conn = URL("http://127.0.0.1:8080/api/v1/voice/speak/$spkSid/segments/$idx")
                            .openConnection() as HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 5000
                        conn.readTimeout = 60000 // 60s for segment synthesis
                        conn.setRequestProperty("Authorization", "Bearer hm_ak_live_ugreen_2026_voice_key")
                        if (conn.responseCode != 200) {
                            val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                            throw RuntimeException("tts_failed: $err")
                        }
                        val bytes = conn.inputStream.readBytes()
                        val out = if (cacheTurnId != null && idx == 0) {
                            LocalRecordingStore.ttsCacheFile(this@MainActivity, cacheTurnId)
                        } else {
                            File(cacheDir, "spk_${spkSid}_$idx.wav")
                        }
                        out.writeBytes(bytes)
                        out
                    }
                    playLocalWavFileSuspend(wavFile)
                }
                statusText.value = "✓ 播报完成 ($segCount 段)"
                refreshUsageSummary()
            } catch (e: Exception) {
                statusText.value = "❌ 播报失败: ${e.message}"
                appendLog("❌ 播报失败: ${e.message}")
            }
        }
    }

    private suspend fun playSummarySegmentsHttp(sessionId: String, requestId: String, segCount: Int) {
        for (idx in 0 until segCount) {
            val wavFile = withContext(Dispatchers.IO) {
                val conn = URL("http://127.0.0.1:8080/api/v1/voice/sessions/$sessionId/summaries/$requestId/audio/$idx")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 60000
                conn.setRequestProperty("Authorization", "Bearer hm_ak_live_ugreen_2026_voice_key")
                if (conn.responseCode != 200) {
                    throw RuntimeException("tts_failed")
                }
                val bytes = conn.inputStream.readBytes()
                val out = File(cacheDir, "sum_${sessionId}_${requestId}_$idx.wav")
                out.writeBytes(bytes)
                out
            }
            playLocalWavFileSuspend(wavFile)
        }
    }

    private fun playLocalWavFile(file: File, speed: Float = playbackSpeed.value) {
        if (!file.exists()) return
        stopMediaPlayer()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mp.playbackParams = PlaybackParams().setSpeed(speed)
            }
            mp.setOnCompletionListener {
                isPlayingWav.value = false
                playbackPositionMs.value = 0L
            }
            mp.start()
            mediaPlayer = mp
            isPlayingWav.value = true
            lifecycleScope.launch {
                while (isPlayingWav.value && mediaPlayer != null) {
                    playbackPositionMs.value = try {
                        mediaPlayer?.currentPosition?.toLong() ?: 0L
                    } catch (_: Exception) {
                        0L
                    }
                    delay(200)
                }
            }
        } catch (e: Exception) {
            Log.w("HiMarketVoice", "WAV playback error: ${e.message}")
        }
    }

    private suspend fun playLocalWavFileSuspend(file: File) {
        if (!file.exists()) return
        withContext(Dispatchers.Main) {
            playLocalWavFile(file, 1.0f)
        }
        while (isPlayingWav.value) {
            delay(150)
        }
    }

    private fun seekMediaPlayer(deltaMs: Int) {
        val mp = mediaPlayer ?: return
        try {
            val next = (mp.currentPosition + deltaMs).coerceIn(0, mp.duration)
            mp.seekTo(next)
            playbackPositionMs.value = next.toLong()
        } catch (_: Exception) {
        }
    }

    private fun updateMediaPlayerSpeed(newSpeed: Float) {
        playbackSpeed.value = newSpeed
        val mp = mediaPlayer ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && mp.isPlaying) {
            try {
                mp.playbackParams = PlaybackParams().setSpeed(newSpeed)
            } catch (_: Exception) {
            }
        }
    }

    private fun stopMediaPlayer() {
        isPlayingWav.value = false
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
    }

    private fun sendVoicePromptTurn(text: String) {
        lifecycleScope.launch {
            val lkRoom = room ?: return@launch
            appendLog("🗣️ 发起语音对讲提问: $text")
            val payload = JSONObject().apply {
                put("type", "voice_turn")
                put("text", text)
            }.toString().toByteArray(Charsets.UTF_8)
            lkRoom.localParticipant.publishData(payload, DataPublishReliability.RELIABLE)
        }
    }

    private fun refreshUsageSummary() {
        lifecycleScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    val url = URL(
                        "http://127.0.0.1:8080/api/v1/usage/summary?tenant_id=${tenantIdState.value}&api_key_id=${apiKeyIdState.value}"
                    )
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    val text = conn.inputStream.bufferedReader().readText()
                    JSONObject(text).getJSONObject("data")
                }
                val totalEvents = summary.optInt("total_events", 0)
                val totalUsd = summary.optString("total_cost_usd", "0.000000")
                val voiceObj = summary.optJSONObject("voice_models")
                val llmObj = summary.optJSONObject("text_llm")
                val trObj = summary.optJSONObject("translate")
                val sumObj = summary.optJSONObject("summary")

                val sttMs = voiceObj?.optLong("stt_audio_duration_ms", 0) ?: 0
                val ttsChars = voiceObj?.optLong("tts_characters", 0) ?: 0
                val llmTok = (llmObj?.optLong("prompt_tokens", 0) ?: 0) +
                    (llmObj?.optLong("completion_tokens", 0) ?: 0)
                val trTok = (trObj?.optLong("prompt_tokens", 0) ?: 0) +
                    (trObj?.optLong("completion_tokens", 0) ?: 0)
                val sumTok = (sumObj?.optLong("prompt_tokens", 0) ?: 0) +
                    (sumObj?.optLong("completion_tokens", 0) ?: 0)

                val line =
                    "事件:$totalEvents | $$totalUsd | STT:${sttMs}ms | 对话:${llmTok}t | 翻译:${trTok}t | 总结:${sumTok}t | TTS:${ttsChars}字"
                usageSummaryState.value = line
            } catch (e: Exception) {
                Log.w("HiMarketVoice", "Failed to query usage summary: ${e.message}")
            }
        }
    }

    private fun requestRoomSessionFromHiMarket(task: String): JSONObject {
        val url = URL("http://127.0.0.1:8080/api/v1/voice/sessions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer hm_ak_live_ugreen_2026_voice_key")
        conn.setRequestProperty("Content-Type", "application/json")

        val reqBody = if (task == "cascade") {
            // Keep exact 4-field cascade body
            JSONObject().apply {
                put("mode", "cascade")
                put("llm_model", "deepseek-v4.1-flash:1017-cloud")
                put("stt_model", "faster-whisper-local")
                put("tts_voice", "piper-local-zh")
            }.toString()
        } else {
            val isDialogue = activeSceneState.value == "dialogue"
            val srcLang = if (isDialogue) phoneLangState.value else sourceLangState.value
            val tgtLang = if (isDialogue) earphoneLangState.value else targetLangState.value
            JSONObject().apply {
                put("mode", "cascade")
                put("task", task)
                put("source_lang", srcLang)
                if (task == "speech_translate" || task == "speak") {
                    put("target_lang", tgtLang)
                    put("speak", true)
                }
            }.toString()
        }

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(reqBody) }
        val status = conn.responseCode
        val respText = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        val json = JSONObject(respText)
        if (status != 200) {
            throw RuntimeException("${json.optString("error")}: ${json.optString("message")}")
        }
        return json.getJSONObject("data")
    }

    @Composable
    private fun MainAppScaffold() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0F19))
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val tabs = listOf(
                    "home" to "AI助手",
                    "transcribe" to "录音转写",
                    "simultaneous" to "同声传译",
                    "dialogue" to "对话翻译",
                    "text_tools" to "文字/播报",
                    "cascade" to "语音对讲",
                )
                tabs.forEach { (id, label) ->
                    val selected = currentScreen.value == id
                    Button(
                        onClick = { currentScreen.value = id },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) Color(0xFF2563EB) else Color(0xFF1E293B)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(label, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${statusText.value}  |  ${usageSummaryState.value}",
                color = Color(0xFF34D399),
                fontSize = 11.5.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (currentScreen.value) {
                    "home" -> AiAssistantHomeScreen()
                    "transcribe" -> Scene1TranscribeScreen()
                    "simultaneous" -> Scene2SimultaneousScreen()
                    "dialogue" -> Scene3DialogueScreen()
                    "text_tools" -> TextTranslateAndSpeakScreen()
                    "detail" -> RecordingDetailScreen()
                    else -> CascadeIntercomScreen()
                }
            }
        }
    }

    @Composable
    private fun AiAssistantHomeScreen() {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "AI助手 (在线模型)",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SceneCard(
                    title = "录音转写",
                    subtitle = "流式ASR + AI总结",
                    modifier = Modifier.weight(1f),
                    onClick = { currentScreen.value = "transcribe" }
                )
                SceneCard(
                    title = "同声传译",
                    subtitle = "流式翻译 + 耳机播报 + 总结",
                    modifier = Modifier.weight(1f),
                    onClick = { currentScreen.value = "simultaneous" }
                )
                SceneCard(
                    title = "对话翻译",
                    subtitle = "手机↔耳机双向交流",
                    modifier = Modifier.weight(1f),
                    onClick = { currentScreen.value = "dialogue" }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "录音记录",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "all" to "全部",
                    "transcribe" to "录音转写",
                    "simultaneous_interpret" to "同声传译"
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = historyFilter.value == key,
                        onClick = { historyFilter.value = key },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }

            val filtered = recordingHistory.filter {
                historyFilter.value == "all" || it.scene == historyFilter.value
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered) { item ->
                    Surface(
                        color = Color(0xFF131B2E),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedRecording.value = item
                                currentScreen.value = "detail"
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, color = Color.White, fontWeight = FontWeight.Medium)
                                Text(
                                    "${if (item.scene == "transcribe") "录音转写" else "同声传译"} · ${item.createdAt}",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp
                                )
                            }
                            Text(
                                LocalRecordingStore.formatDuration(item.durationMs),
                                color = Color(0xFFCBD5E1),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Text(
                text = "请注意！由于录音文件存储在手机本地，因此 APP 卸载后，所有的录音数据将会被永久删除。",
                color = Color(0xFF64748B),
                fontSize = 11.sp
            )
        }
    }

    @Composable
    private fun SceneCard(
        title: String,
        subtitle: String,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        Surface(
            color = Color(0xFF1E293B),
            shape = RoundedCornerShape(12.dp),
            modifier = modifier.clickable { onClick() }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(subtitle, color = Color(0xFF94A3B8), fontSize = 11.sp)
            }
        }
    }

    @Composable
    private fun LanguagePickerRow(
        label: String,
        selected: String,
        onSelect: (String) -> Unit,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            Text(label, color = Color(0xFF94A3B8), fontSize = 12.sp)
            allowedLangsState.forEach { code ->
                FilterChip(
                    selected = selected == code,
                    onClick = { onSelect(code) },
                    label = { Text(langLabel(code), fontSize = 11.5.sp) }
                )
            }
        }
    }

    @Composable
    private fun Scene1TranscribeScreen() {
        Column(modifier = Modifier.fillMaxSize()) {
            Text("场景 1：现场录音转写", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            LanguagePickerRow(
                label = "转写语种:",
                selected = sourceLangState.value,
                onSelect = { sourceLangState.value = it }
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF111827), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(liveSegments) { seg ->
                    Text(seg.sourceText, color = Color.White, fontSize = 14.sp)
                }
                if (interimSourceText.value.isNotBlank()) {
                    item {
                        // Green interim ASR text as shown in screenshot
                        Text(
                            text = interimSourceText.value,
                            color = Color(0xFF22C55E),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "手机收音中 ${LocalRecordingStore.formatDuration(elapsedSecondsState.value * 1000L)}",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isSessionActive.value) {
                        Button(onClick = {
                            startRoomTaskSession(task = "transcribe", scene = "transcribe")
                        }) {
                            Text("开始录音转写")
                        }
                    } else {
                        Button(onClick = { sendInRoomSummarize(speak = false) }) {
                            Text("房间内总结")
                        }
                        Button(
                            onClick = { stopSceneAndFinalize(autoSummarize = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48))
                        ) {
                            Text("结束并生成AI总结")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Scene2SimultaneousScreen() {
        Column(modifier = Modifier.fillMaxSize()) {
            Text("场景 2：同声传译", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            LanguagePickerRow(
                label = "原文:",
                selected = sourceLangState.value,
                onSelect = { sourceLangState.value = it }
            )
            LanguagePickerRow(
                label = "译文:",
                selected = targetLangState.value,
                onSelect = { sendMidSessionTargetLang(it) }
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF111827), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(liveSegments) { seg ->
                    Column {
                        Text(seg.sourceText, color = Color(0xFFCBD5E1), fontSize = 13.5.sp)
                        if (seg.translatedText.isNotBlank()) {
                            Text(
                                text = seg.translatedText,
                                color = if (seg.isFinal) Color(0xFF38BDF8) else Color(0xFF22C55E),
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                if (interimSourceText.value.isNotBlank()) {
                    item {
                        Text(
                            text = interimSourceText.value,
                            color = Color(0xFF22C55E),
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = LocalRecordingStore.formatDuration(elapsedSecondsState.value * 1000L),
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isSessionActive.value) {
                        Button(onClick = {
                            startRoomTaskSession(
                                task = "speech_translate",
                                scene = "simultaneous_interpret"
                            )
                        }) {
                            Text("开始传译")
                        }
                    } else {
                        Button(
                            onClick = { stopSceneAndFinalize(autoSummarize = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48))
                        ) {
                            Text("结束传译并总结")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Scene3DialogueScreen() {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("场景 3：对话翻译（手机↔耳机）", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (!isSessionActive.value) {
                    Button(onClick = {
                        startRoomTaskSession(task = "speech_translate", scene = "dialogue")
                    }) {
                        Text("连接对话房间", fontSize = 12.sp)
                    }
                }
            }

            LanguagePickerRow(
                label = "持手机说话:",
                selected = phoneLangState.value,
                onSelect = { phoneLangState.value = it }
            )
            LanguagePickerRow(
                label = "戴耳机说话:",
                selected = earphoneLangState.value,
                onSelect = { earphoneLangState.value = it }
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Left column: Foreigner using phone app
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color(0xFF111827), RoundedCornerShape(10.dp))
                        .padding(8.dp)
                ) {
                    Text("外国人使用手机 (${langLabel(phoneLangState.value)})", color = Color(0xFF94A3B8), fontSize = 11.5.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(liveSegments.filter { it.speaker == "phone" || it.speaker == null }) { seg ->
                            DialogueBubble(seg, targetLang = earphoneLangState.value)
                        }
                        if (interimSpeaker.value == "phone" && interimSourceText.value.isNotBlank()) {
                            item {
                                Text(interimSourceText.value, color = Color(0xFF22C55E), fontSize = 13.sp)
                            }
                        }
                    }
                }

                // Right column: User wearing earphone
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color(0xFF111827), RoundedCornerShape(10.dp))
                        .padding(8.dp)
                ) {
                    Text("用户使用耳机 (${langLabel(earphoneLangState.value)})", color = Color(0xFF94A3B8), fontSize = 11.5.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(liveSegments.filter { it.speaker == "earphone" }) { seg ->
                            DialogueBubble(seg, targetLang = phoneLangState.value)
                        }
                        if (interimSpeaker.value == "earphone" && interimSourceText.value.isNotBlank()) {
                            item {
                                Text(interimSourceText.value, color = Color(0xFF22C55E), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val activeSpk = activeDialogueSpeaker.value
                Button(
                    onClick = {
                        if (activeSpk == "phone") stopDialogueTurn() else startDialogueTurn("phone")
                    },
                    enabled = isSessionActive.value && (activeSpk == null || activeSpk == "phone") && !isTtsSpeaking.value,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (activeSpk == "phone") Color(0xFFE11D48) else Color(0xFF2563EB)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (activeSpk == "phone") "⏹ 停止收音 (${langLabel(phoneLangState.value)})"
                        else "📱 持手机说话 (${langLabel(phoneLangState.value)})",
                        fontSize = 12.sp
                    )
                }

                Button(
                    onClick = {
                        if (activeSpk == "earphone") stopDialogueTurn() else startDialogueTurn("earphone")
                    },
                    enabled = isSessionActive.value && (activeSpk == null || activeSpk == "earphone") && !isTtsSpeaking.value,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (activeSpk == "earphone") Color(0xFFE11D48) else Color(0xFF0D9488)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (activeSpk == "earphone") "⏹ 停止收音 (${langLabel(earphoneLangState.value)})"
                        else "🎧 戴耳机说话 (${langLabel(earphoneLangState.value)})",
                        fontSize = 12.sp
                    )
                }
            }
        }
    }

    @Composable
    private fun DialogueBubble(seg: TranscriptSegmentItem, targetLang: String) {
        Surface(
            color = Color(0xFF1E293B),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(seg.sourceText, color = Color(0xFF22C55E), fontSize = 12.5.sp)
                if (seg.translatedText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(seg.translatedText, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "🔊 TTS 播报",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.5.sp,
                        modifier = Modifier.clickable {
                            runHttpSpeakAndPlay(seg.translatedText, targetLang, cacheTurnId = seg.turnId)
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun RecordingDetailScreen() {
        val entry = selectedRecording.value
        if (entry == null) {
            Text("未选择录音记录", color = Color.White)
            return
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    stopMediaPlayer()
                    currentScreen.value = "home"
                }) {
                    Text("返回列表")
                }
            }

            // Local WAV audio player card with -15s, Play/Pause, +15s, and speed selector
            Surface(
                color = Color(0xFF131B2E),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            LocalRecordingStore.formatDuration(playbackPositionMs.value),
                            color = Color(0xFF94A3B8),
                            fontSize = 11.5.sp
                        )
                        Text(
                            LocalRecordingStore.formatDuration(entry.durationMs),
                            color = Color(0xFF94A3B8),
                            fontSize = 11.5.sp
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { seekMediaPlayer(-15_000) }) { Text("⏪ 15s") }
                        Button(onClick = {
                            if (isPlayingWav.value) {
                                stopMediaPlayer()
                            } else {
                                playLocalWavFile(File(entry.audioPath), playbackSpeed.value)
                            }
                        }) {
                            Text(if (isPlayingWav.value) "⏸ 暂停" else "▶ 播放本地录音")
                        }
                        TextButton(onClick = { seekMediaPlayer(15_000) }) { Text("15s ⏩") }
                        TextButton(onClick = {
                            val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                            val nextIdx = (speeds.indexOf(playbackSpeed.value) + 1) % speeds.size
                            updateMediaPlayerSpeed(speeds[nextIdx])
                        }) {
                            Text("${playbackSpeed.value}x")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = detailTab.value == "summary",
                        onClick = { detailTab.value = "summary" },
                        label = { Text("AI总结") }
                    )
                    FilterChip(
                        selected = detailTab.value == "transcript",
                        onClick = { detailTab.value = "transcript" },
                        label = { Text("转写") }
                    )
                }

                // 总结设置 (speech / meeting / concise)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        "speech" to "演讲纪要",
                        "meeting" to "会议纪要",
                        "concise" to "精简要点"
                    ).forEach { (styleKey, styleLabel) ->
                        FilterChip(
                            selected = selectedSummaryStyle.value == styleKey,
                            onClick = {
                                selectedSummaryStyle.value = styleKey
                                triggerHttpSummaryForEntry(entry, styleKey, speak = false)
                            },
                            label = { Text(styleLabel, fontSize = 11.sp) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            if (detailTab.value == "summary") {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF111827), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    val sum = entry.summary
                    if (sum == null) {
                        Text("尚未生成总结，点击下方按钮生成。", color = Color(0xFF94A3B8))
                    } else {
                        Text(
                            text = sum.summaryText,
                            color = Color(0xFFE2E8F0),
                            fontSize = 13.5.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            triggerHttpSummaryForEntry(entry, selectedSummaryStyle.value, speak = false)
                        }) {
                            Text("生成/刷新总结")
                        }
                        Button(onClick = {
                            triggerHttpSummaryForEntry(entry, selectedSummaryStyle.value, speak = true)
                        }) {
                            Text("🔊 生成并播报总结")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF111827), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(entry.segments) { seg ->
                        Column {
                            Text(seg.sourceText, color = Color.White, fontSize = 13.5.sp)
                            if (seg.translatedText.isNotBlank()) {
                                Text(seg.translatedText, color = Color(0xFF38BDF8), fontSize = 13.5.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TextTranslateAndSpeakScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("文字翻译 & 语音播报 (HTTP 无房间模式)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            LanguagePickerRow(
                label = "目标语言:",
                selected = targetLangState.value,
                onSelect = { targetLangState.value = it }
            )

            OutlinedTextField(
                value = textInputState.value,
                onValueChange = { textInputState.value = it },
                label = { Text("输入要翻译或朗读的文字 (最长4000字)") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { runTextTranslationHttp(textInputState.value, targetLangState.value) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("文字翻译", fontSize = 12.sp)
                }
                Button(
                    onClick = { runHttpSpeakAndPlay(textInputState.value, targetLangState.value) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("🔊 直接播报原文", fontSize = 12.sp)
                }
            }

            if (translatedOutputState.value.isNotBlank()) {
                Surface(
                    color = Color(0xFF131B2E),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("译文结果:", color = Color(0xFF94A3B8), fontSize = 12.sp)
                        Text(translatedOutputState.value, color = Color(0xFF38BDF8), fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                runHttpSpeakAndPlay(translatedOutputState.value, targetLangState.value)
                            }) {
                                Text("🔊 朗读译文", fontSize = 12.sp)
                            }
                            Button(onClick = {
                                val sid = textTranslateSessionId.value ?: return@Button
                                val tempEntry = LocalRecordingEntry(
                                    sessionId = sid,
                                    scene = "simultaneous_interpret",
                                    title = "文字翻译总结",
                                    sourceLang = sourceLangState.value,
                                    targetLang = targetLangState.value,
                                    audioPath = "",
                                    durationMs = 0L,
                                    createdAt = LocalRecordingStore.nowFormatted(),
                                )
                                triggerHttpSummaryForEntry(tempEntry, selectedSummaryStyle.value, speak = false)
                            }) {
                                Text("📝 总结当前翻译会话", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            liveSummaryRecord.value?.let { sum ->
                Surface(
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("AI总结：《${sum.title}》", color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(sum.summaryText, color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun CascadeIntercomScreen() {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        startRoomTaskSession(
                            task = "cascade",
                            scene = "cascade",
                            initialPrompt = "你好，请用一句话介绍HiMarket语音平台。"
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("启动对讲", fontSize = 12.sp)
                }
                Button(
                    onClick = { sendVoicePromptTurn("请问今天深圳的天气适合出门吗？请简短回答。") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("发送追问", fontSize = 12.sp)
                }
                Button(
                    onClick = { sendInRoomSummarize(speak = true) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("总结通话", fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF0F172A), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(chatLogs) { line ->
                    Text(
                        text = line,
                        color = Color(0xFFE2E8F0),
                        fontSize = 12.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
