package com.ugreen.himarket.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class TranscriptSegmentItem(
    val turnId: String,
    val seq: Long?,
    val speaker: String?,
    val sourceText: String,
    val translatedText: String = "",
    val isFinal: Boolean = true,
)

data class LocalSummaryRecord(
    val requestId: String,
    val title: String,
    val summaryStyle: String,
    val summaryText: String,
    val targetLang: String,
)

data class LocalRecordingEntry(
    val sessionId: String,
    val scene: String, // "transcribe" | "simultaneous_interpret"
    var title: String,
    val sourceLang: String,
    val targetLang: String,
    val audioPath: String,
    var durationMs: Long,
    val createdAt: String,
    val segments: MutableList<TranscriptSegmentItem> = mutableListOf(),
    var summary: LocalSummaryRecord? = null,
)

object LocalRecordingStore {
    private fun recordingsDir(context: Context): File {
        val dir = File(context.filesDir, "recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun indexFile(context: Context): File = File(recordingsDir(context), "index.json")

    fun wavFileForSession(context: Context, sessionId: String): File =
        File(recordingsDir(context), "$sessionId.wav")

    fun ttsCacheFile(context: Context, turnId: String): File =
        File(context.cacheDir, "tts_$turnId.wav")

    fun nowFormatted(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

    fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", min, sec)
    }

    fun formatDurationHhMmSs(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val hrs = totalSec / 3600
        val min = (totalSec % 3600) / 60
        val sec = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hrs, min, sec)
    }

    fun loadAll(context: Context): MutableList<LocalRecordingEntry> {
        val file = indexFile(context)
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            val list = mutableListOf<LocalRecordingEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val segArr = obj.optJSONArray("segments") ?: JSONArray()
                val segs = mutableListOf<TranscriptSegmentItem>()
                for (j in 0 until segArr.length()) {
                    val s = segArr.getJSONObject(j)
                    segs.add(
                        TranscriptSegmentItem(
                            turnId = s.optString("turn_id", ""),
                            seq = if (s.has("seq") && !s.isNull("seq")) s.optLong("seq") else null,
                            speaker = s.optString("speaker", "").ifEmpty { null },
                            sourceText = s.optString("source_text", ""),
                            translatedText = s.optString("translated_text", ""),
                            isFinal = true,
                        )
                    )
                }
                val sumObj = obj.optJSONObject("summary")
                val sum = if (sumObj != null) {
                    LocalSummaryRecord(
                        requestId = sumObj.optString("request_id", ""),
                        title = sumObj.optString("title", ""),
                        summaryStyle = sumObj.optString("summary_style", "speech"),
                        summaryText = sumObj.optString("summary_text", ""),
                        targetLang = sumObj.optString("target_lang", "zh"),
                    )
                } else null
                list.add(
                    LocalRecordingEntry(
                        sessionId = obj.getString("session_id"),
                        scene = obj.optString("scene", "transcribe"),
                        title = obj.optString("title", "现场录音"),
                        sourceLang = obj.optString("source_lang", "zh"),
                        targetLang = obj.optString("target_lang", "zh"),
                        audioPath = obj.optString("audio_path", ""),
                        durationMs = obj.optLong("duration_ms", 0L),
                        createdAt = obj.optString("created_at", ""),
                        segments = segs,
                        summary = sum,
                    )
                )
            }
            list
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveAll(context: Context, entries: List<LocalRecordingEntry>) {
        val arr = JSONArray()
        for (e in entries) {
            val segArr = JSONArray()
            for (s in e.segments.filter { it.isFinal }) {
                segArr.put(
                    JSONObject().apply {
                        put("turn_id", s.turnId)
                        if (s.seq != null) put("seq", s.seq)
                        if (s.speaker != null) put("speaker", s.speaker)
                        put("source_text", s.sourceText)
                        put("translated_text", s.translatedText)
                    }
                )
            }
            val obj = JSONObject().apply {
                put("session_id", e.sessionId)
                put("scene", e.scene)
                put("title", e.title)
                put("source_lang", e.sourceLang)
                put("target_lang", e.targetLang)
                put("audio_path", e.audioPath)
                put("duration_ms", e.durationMs)
                put("created_at", e.createdAt)
                put("segments", segArr)
                e.summary?.let { sum ->
                    put(
                        "summary",
                        JSONObject().apply {
                            put("request_id", sum.requestId)
                            put("title", sum.title)
                            put("summary_style", sum.summaryStyle)
                            put("summary_text", sum.summaryText)
                            put("target_lang", sum.targetLang)
                        }
                    )
                }
            }
            arr.put(obj)
        }
        indexFile(context).writeText(arr.toString(2), Charsets.UTF_8)
    }

    fun upsertEntry(context: Context, entry: LocalRecordingEntry) {
        val all = loadAll(context)
        val idx = all.indexOfFirst { it.sessionId == entry.sessionId }
        if (idx >= 0) {
            all[idx] = entry
        } else {
            all.add(0, entry)
        }
        saveAll(context, all)
    }
}

class LocalWavRecorder(private val outputFile: File, private val sampleRate: Int = 16000) {
    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null
    @Volatile
    var recordedDurationMs: Long = 0L
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        if (running.getAndSet(true)) return
        workerThread = Thread {
            var totalPcmBytes = 0L
            try {
                FileOutputStream(outputFile).use { fos ->
                    fos.write(buildWavHeader(0, sampleRate))
                    // Do not open a second hardware AudioRecord(VOICE_COMMUNICATION) while
                    // LiveKit WebRTC already owns the hardware voice communication capture & AEC/NS.
                    val buf = ByteArray(1600) // 50ms of 16kHz PCM16
                    while (running.get()) {
                        fos.write(buf)
                        totalPcmBytes += buf.size
                        recordedDurationMs = (totalPcmBytes * 1000L) / (sampleRate * 2L)
                        Thread.sleep(50)
                    }
                }
                patchWavHeader(outputFile, totalPcmBytes.toInt(), sampleRate)
            } catch (_: Exception) {
            }
        }.apply {
            name = "local-wav-recorder"
            isDaemon = true
            start()
        }
    }

    fun stop(): Long {
        if (!running.getAndSet(false)) return recordedDurationMs
        try {
            workerThread?.join(800)
        } catch (_: Exception) {
        }
        return recordedDurationMs.coerceAtLeast(1000L)
    }

    private fun buildWavHeader(dataLen: Int, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2
        val totalDataLen = dataLen + 36
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(totalDataLen)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(1) // Mono
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(2) // Block align
        buf.putShort(16) // Bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataLen)
        return buf.array()
    }

    private fun patchWavHeader(file: File, dataLen: Int, sampleRate: Int) {
        if (!file.exists()) return
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(buildWavHeader(dataLen, sampleRate))
        }
    }
}

object DialogueAudioRouter {
    fun routeForInputSpeaker(context: Context, speaker: String) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (speaker == "earphone") {
            routeToEarphone(am)
        } else {
            routeToPhoneSpeaker(am)
        }
    }

    fun routeForTargetDevice(context: Context, targetDevice: String) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (targetDevice == "earphone") {
            routeToEarphone(am)
        } else {
            routeToPhoneSpeaker(am)
        }
    }

    @Suppress("DEPRECATION")
    private fun routeToEarphone(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bt = am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (bt != null) {
                am.setCommunicationDevice(bt)
                return
            }
        }
        am.isSpeakerphoneOn = false
        try {
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun routeToPhoneSpeaker(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val spk = am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (spk != null) {
                am.setCommunicationDevice(spk)
                return
            }
        }
        try {
            am.stopBluetoothSco()
            am.isBluetoothScoOn = false
        } catch (_: Exception) {
        }
        am.isSpeakerphoneOn = true
    }
}
