package com.ugreen.himarket.voice

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

data class LangItem(
    val code: String,
    val cnName: String,
    val nativeName: String,
)

private val SCREENSHOT_LANGUAGES = listOf(
    LangItem("zh", "中文（简体）", "简体中文"),
    LangItem("de", "德语", "Deutsch"),
    LangItem("en", "英语", "English"),
    LangItem("es", "西班牙语", "Español"),
    LangItem("fr", "法语", "Français"),
    LangItem("id", "印度尼西亚语", "Indonesia"),
    LangItem("it", "意大利语", "Italiano"),
    LangItem("ja", "日语", "日本語"),
)

private val BgGray = Color(0xFFF5F6F8)
private val CardWhite = Color(0xFFFFFFFF)
private val TextPrimary = Color(0xFF18181B)
private val TextSecondary = Color(0xFF71717A)
private val TextMuted = Color(0xFFA1A1AA)
private val AccentGreen = Color(0xFF10B981)
private val StreamGreen = Color(0xFF22C55E)
private val ChipGreenBg = Color(0xFFDDF5EC)
private val ChipGreenFg = Color(0xFF059669)
private val BadgePurpleBg = Color(0xFFEDE9FE)
private val BadgePurpleFg = Color(0xFF8B5CF6)
private val DarkBtn = Color(0xFF18181B)
private val BubbleGray = Color(0xFFEFEFF4)

class MainActivity : ComponentActivity() {

    private var room: Room? = null
    private var localRecorder: LocalWavRecorder? = null
    private var holdMicRecorder: HoldMicRecorder? = null
    private var timerJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null

    // Screens: "home", "lang_picker", "transcribe_live", "detail", "simultaneous", "dialogue", "cascade"
    private val currentScreen = mutableStateOf("home")
    private val langPickerTarget = mutableStateOf("transcribe_start")
    // "transcribe_start", "sim_source", "sim_target", "dlg_phone", "dlg_earphone"
    private val langSearchQuery = mutableStateOf("")
    private val tempPickedLang = mutableStateOf("zh")
    private val recentLangCode = mutableStateOf("zh")

    private val historyFilter = mutableStateOf("all") // "all" | "transcribe" | "simultaneous_interpret"
    private val recordingHistory = mutableStateListOf<LocalRecordingEntry>()
    private val selectedRecording = mutableStateOf<LocalRecordingEntry?>(null)
    private val detailTab = mutableStateOf("summary") // "summary" | "transcript"
    private val selectedSummaryStyle = mutableStateOf("speech") // "speech" | "meeting" | "concise"
    private val isSummarizingState = mutableStateOf(false)
    private val playbackSpeed = mutableStateOf(1.0f)
    private val isPlayingWav = mutableStateOf(false)
    private val playbackPositionMs = mutableStateOf(0L)

    private val allowedLangsState = mutableStateListOf("zh", "de", "en", "es", "fr", "id", "it", "ja")
    private val sourceLangState = mutableStateOf("zh")
    private val targetLangState = mutableStateOf("zh")
    private val simSourceLangState = mutableStateOf("en")
    private val simTargetLangState = mutableStateOf("zh")
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

    private val selectedPersonaState = mutableStateOf(AI_PERSONAS.first())
    private val aiChatSessionId = mutableStateOf<String?>(null)
    private val aiChatMessages = mutableStateListOf<AiChatBubbleItem>()
    private val isAiChatSending = mutableStateOf(false)
    private val isHoldSpeaking = mutableStateOf(false)
    private val freetalkSubtitleState = mutableStateOf(AI_PERSONAS.first().freetalkSubtitle)
    private val freetalkStatusState = mutableStateOf("正在听")

    private val statusText = mutableStateOf("在线模型")
    private val sessionIdState = mutableStateOf("-")
    private val roomNameState = mutableStateOf("-")
    private val tenantIdState = mutableStateOf("tenant_ugreen_01")
    private val apiKeyIdState = mutableStateOf("ak_live_ugreen_01")
    private val usageSummaryState = mutableStateOf("")
    private val chatLogs = mutableStateListOf<String>()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i("HiMarketVoice", "RECORD_AUDIO permission granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        ensureSeededDemoRecordings()
        reloadRecordingHistory()

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    background = BgGray,
                    surface = CardWhite,
                    onBackground = TextPrimary,
                    onSurface = TextPrimary,
                    primary = DarkBtn,
                    onPrimary = Color.White,
                )
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    color = BgGray
                ) {
                    AppRouter()
                }
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

    private fun reloadRecordingHistory() {
        recordingHistory.clear()
        recordingHistory.addAll(LocalRecordingStore.loadAll(this))
    }

    private fun ensureSeededDemoRecordings() {
        val existing = LocalRecordingStore.loadAll(this)
        if (existing.any { it.sessionId == "sess_demo_mbti" }) return

        val mbtiWav = LocalRecordingStore.wavFileForSession(this, "sess_demo_mbti")
        val optWav = LocalRecordingStore.wavFileForSession(this, "sess_demo_optimism")

        val mbtiEntry = LocalRecordingEntry(
            sessionId = "sess_demo_mbti",
            scene = "transcribe",
            title = "关于“做自己”与MBTI测试的自我探索",
            sourceLang = "zh",
            targetLang = "zh",
            audioPath = mbtiWav.absolutePath,
            durationMs = 117_000L, // 01:57
            createdAt = "2026-10-21 14:18",
            segments = mutableListOf(
                TranscriptSegmentItem(
                    turnId = "m1",
                    seq = 1L,
                    speaker = null,
                    sourceText = "一个自我高兴。我说，这题目好啊，它完全精准地击中了我过去最大的执念和当下最坚定的答案。这一个在NFT官网上被描述为最复杂，市场占比最少的人格INFJ。",
                ),
                TranscriptSegmentItem(
                    turnId = "m2",
                    seq = 2L,
                    speaker = null,
                    sourceText = "你若是做我这样的话题，我可能30分钟都哭诉不完，而事实上只是面对着我的电脑屏幕坐了30分钟也没有敲出一个字，因为我一直在思考这样一个问题：我们总说要做自己，那究竟什么才是做自己的？",
                ),
                TranscriptSegmentItem(
                    turnId = "m3",
                    seq = 3L,
                    speaker = null,
                    sourceText = "是去寻找那个命定的我然后成为她，还是去塑造一个理想的不断变化的我呢？曾经我一直在证明着前者的答案。今天我好像，我想和大家分享的是后一个。",
                ),
                TranscriptSegmentItem(
                    turnId = "m4",
                    seq = 4L,
                    speaker = null,
                    sourceText = "我第一次参加MBTI是在高一。那时候，人格学说刚刚开始流行。",
                )
            ),
            summary = LocalSummaryRecord(
                requestId = "sum_demo_mbti",
                title = "关于“做自己”与MBTI测试的自我探索",
                summaryStyle = "speech",
                summaryText = """
# 关于“做自己”与MBTI测试的自我探索

## 核心摘要
演讲者通过分享自己高中时期多次进行MBTI人格测试的经历，探讨了“做自己”的真正含义，提出“做自己”并非去寻找那个命定的自我，而是去塑造一个理想中不断变化的自我。

## 核心观点
1. 真正的“做自己”不是寻找固定的命运答案，而是塑造不断变化的理想自我。
2. 演讲者曾因对测试结果（如表演者、ISFJ等）的解读不满而多次重测，反映了人们在自我认知过程中的执念与探索。
3. 最终测出被认为最复杂、占比最少的“提倡者INFJ”，其“深思熟虑、追求意义”的描述让演讲者产生了强烈的共鸣与认同。

## 演讲结构概述
### 1. 开场与背景
引入“做自己”的话题，提出关于自我认知的核心疑问：是寻找命定的自我，还是塑造变化的自我？并明确表明自己现在倾向于后者。

### 2. 核心论点
- 论点一：早期对人格标签的执念与反复试探。
- 论据/案例：高一初次接触MBTI（误读或识别为NTR/NFT）测试时，因觉得“表演者”不好听、“遵守秩序、传统务实（ISFJ）”不够酷而多次重测，甚至一度测出INFP。
- 论点二：找到契合标签时的强烈自我认同。
- 论据/案例：当叔叔评价其“复杂”后，再次测试得出“INFJ”，看到“精神世界的引路人、深思熟虑、守护和谐”等字眼时，感到极度激动和契合。

### 3. 总结与展望
引导听众放下对静态性格盒子的执念，在每一次具体选择中塑造不断成长的自我。
                """.trimIndent(),
                targetLang = "zh",
            )
        )

        val optEntry = LocalRecordingEntry(
            sessionId = "sess_demo_optimism",
            scene = "simultaneous_interpret",
            title = "乐观偏差的积极效应与心理学机制",
            sourceLang = "en",
            targetLang = "zh",
            audioPath = optWav.absolutePath,
            durationMs = 219_000L, // 03:39
            createdAt = "2026-10-21 14:40",
            segments = mutableListOf(
                TranscriptSegmentItem(
                    turnId = "o1",
                    seq = 1L,
                    speaker = null,
                    sourceText = "How honest are you? And finally, I'm sorry, most of us put ourselves above average on most of these abilities. This is statistically impossible. We can't all be better than everyone else.",
                    translatedText = "你有多诚实？最后，我很抱歉，我们大多数人在大多数这些能力上都把自己置于平均水平之上。这在统计上是不可能的。我们不可能都比其他人更好。",
                )
            ),
            summary = LocalSummaryRecord(
                requestId = "sum_demo_opt",
                title = "乐观偏差的积极效应与心理学机制",
                summaryStyle = "speech",
                summaryText = """
# 乐观偏差的积极效应与心理学机制

## 核心摘要
本次演讲探讨了全球普遍存在的“乐观偏差”现象，反驳了“低预期带来幸福”的传统观点。通过心理学和行为经济学的研究表明，高预期不仅能让人在面对成败时保持更好的心理状态，而且“期待”这一行为本身就能带来纯粹的快乐。

## 核心要点
1. 乐观偏差具有普遍性：大多数人倾向于认为自己高于平均水平，这在统计学上是不可能的，但却是一个跨文化、跨性别的全球现象。
2. 高预期带来更好的心理感受：无论结果如何，高预期者倾向于将成功归因于自身特质，将失败归因于外部环境；而低预期者则完全相反。
3. 期待本身即是快乐：人们愿意为适应的延迟满足（如等待3天）支付更多代价，因为期待过程中的遐想和激动本身就能产生极大的幸福感。

## 演讲结构概述
### 1. 开场与背景
指出大多数人高估自身能力的“乐观偏差”是一个全球普遍现象，并引出核心问题：这种偏差对我们究竟是有益还是有害？

### 2. 核心论点
- 论点一：高预期者无论成败感觉都更好。
- 论据/案例：心理学家玛格丽特·马歇尔（Margaret Marshall）和约翰·布朗（John Brown）的研究表明，高预期学生将成功归因于自身才华，失败归因于考试不公；低预期学生则将失败归因于自身愚蠢，成功归因于题目简单。
- 论点二：期待的行为本身能带来快乐。

### 3. 总结与展望
适度的乐观偏差提升了行动意愿与主观幸福感，是人类心理的重要保护机制。
                """.trimIndent(),
                targetLang = "zh",
            )
        )

        val all = existing.toMutableList()
        all.add(mbtiEntry)
        all.add(optEntry)
        LocalRecordingStore.saveAll(this, all)
    }

    private fun handleIntentCommand(intent: Intent?) {
        val action = intent?.getStringExtra("test_action")
        val prompt = intent?.getStringExtra("voice_prompt")
        if (!action.isNullOrBlank()) {
            lifecycleScope.launch {
                when (action) {
                    "open_home" -> {
                        currentScreen.value = "home"
                    }
                    "open_lang_picker" -> {
                        langPickerTarget.value = "transcribe_start"
                        tempPickedLang.value = sourceLangState.value
                        currentScreen.value = "lang_picker"
                    }
                    "open_transcribe_live" -> {
                        sourceLangState.value = "zh"
                        liveSegments.clear()
                        liveSegments.addAll(
                            listOf(
                                TranscriptSegmentItem("t1", 1, null, "一个自我高兴。我说，这题目好啊，它完全精准地击中了我过去最大的执念和当下最坚定的答案。这一个在NFT官网上被描述为最复杂，市场占比最少的人格INFJ。"),
                                TranscriptSegmentItem("t2", 2, null, "你若是做我这样的话题，我可能30分钟都哭诉不完，而事实上只是面对着我的电脑屏幕坐了30分钟也没有敲出一个字，因为我一直在思考这样一个问题。"),
                                TranscriptSegmentItem("t3", 3, null, "我们总说要做自己。"),
                                TranscriptSegmentItem("t4", 4, null, "那究竟什么才是做自己的？"),
                                TranscriptSegmentItem("t5", 5, null, "是去寻找那个命定的我然后成为她，还是去塑造一个理想的不断变化的我呢？"),
                                TranscriptSegmentItem("t6", 6, null, "曾经我一直在证明着前者的答案。"),
                                TranscriptSegmentItem("t7", 7, null, "今天我好像，我想和大家分享的是后一个。"),
                                TranscriptSegmentItem("t8", 8, null, "我第一次参加NTR是在高一。"),
                                TranscriptSegmentItem("t9", 9, null, "那时候，人格学说刚刚开始流行。")
                            )
                        )
                        interimSourceText.value = "我身边的人每天。。。"
                        elapsedSecondsState.value = 57L
                        isSessionActive.value = true
                        currentScreen.value = "transcribe_live"
                    }
                    "open_detail" -> {
                        reloadRecordingHistory()
                        val targetId = intent.getStringExtra("session_id")
                        val entry = if (!targetId.isNullOrBlank()) {
                            recordingHistory.find { it.sessionId == targetId }
                        } else {
                            recordingHistory.firstOrNull()
                        }
                        if (entry != null) {
                            selectedRecording.value = entry
                            detailTab.value = intent.getStringExtra("tab") ?: "summary"
                            currentScreen.value = "detail"
                            val style = intent.getStringExtra("summary_style")
                            if (!style.isNullOrBlank()) {
                                selectedSummaryStyle.value = style
                                triggerHttpSummaryForEntry(entry, style, speak = false)
                            }
                        }
                    }
                    "open_simultaneous_idle" -> {
                        isSessionActive.value = false
                        liveSegments.clear()
                        interimSourceText.value = ""
                        elapsedSecondsState.value = 0L
                        simSourceLangState.value = "en"
                        simTargetLangState.value = "zh"
                        currentScreen.value = "simultaneous"
                    }
                    "open_simultaneous_live" -> {
                        simSourceLangState.value = "en"
                        simTargetLangState.value = "zh"
                        isSessionActive.value = true
                        elapsedSecondsState.value = 24L
                        liveSegments.clear()
                        liveSegments.add(
                            TranscriptSegmentItem(
                                turnId = "s1",
                                seq = 1L,
                                speaker = null,
                                sourceText = "How honest are you?\n\nAnd finally, I'm sorry, most of us put ourselves above average on most of these abilities. This is statistically impossible. We can't all be better than everyone else.",
                                translatedText = "你有多诚实？\n\n最后，我很抱歉，我们大多数人在大多数这些能力上都把自己置于平均水平之上。这在统计上是不可能的。我们不可能都比其他人更好。",
                                isFinal = true,
                            )
                        )
                        interimSourceText.value = "But if we..."
                        currentScreen.value = "simultaneous"
                    }
                    "open_dialogue_idle" -> {
                        isSessionActive.value = false
                        liveSegments.clear()
                        interimSourceText.value = ""
                        activeDialogueSpeaker.value = null
                        phoneLangState.value = "en"
                        earphoneLangState.value = "zh"
                        currentScreen.value = "dialogue"
                    }
                    "open_dialogue_bubbles" -> {
                        isSessionActive.value = true
                        phoneLangState.value = "en"
                        earphoneLangState.value = "zh"
                        liveSegments.clear()
                        liveSegments.addAll(
                            listOf(
                                TranscriptSegmentItem(
                                    turnId = "d1",
                                    seq = 1L,
                                    speaker = "phone",
                                    sourceText = "Good morning, what's your name?",
                                    translatedText = "早上好，你叫什么名字？",
                                    isFinal = true,
                                ),
                                TranscriptSegmentItem(
                                    turnId = "d2",
                                    seq = 2L,
                                    speaker = "earphone",
                                    sourceText = "早上好，我的名字叫小林。",
                                    translatedText = "Good morning, my name is Kobayashi.",
                                    isFinal = true,
                                ),
                                TranscriptSegmentItem(
                                    turnId = "d3",
                                    seq = 3L,
                                    speaker = "phone",
                                    sourceText = "I'm not alone.\nask for\nSequoia",
                                    translatedText = "我并不孤单。\n请求\n红杉",
                                    isFinal = false,
                                )
                            )
                        )
                        activeDialogueSpeaker.value = "phone"
                        currentScreen.value = "dialogue"
                    }
                    "open_ai_chat_demo" -> {
                        room?.disconnect()
                        room = null
                        isSessionActive.value = false
                        val zhiqiu = AI_PERSONAS.first()
                        selectedPersonaState.value = zhiqiu
                        aiChatMessages.clear()
                        aiChatMessages.addAll(
                            listOf(
                                AiChatBubbleItem("c1", isUser = false, text = zhiqiu.greeting),
                                AiChatBubbleItem("c2", isUser = true, text = "最近工作有些累，我想找个人聊聊。"),
                                AiChatBubbleItem(
                                    "c3",
                                    isUser = false,
                                    text = "听起来，最近的工作让你有些疲惫。我们可以一起理理：是事情太多，还是某件事让你特别消耗？"
                                )
                            )
                        )
                        currentScreen.value = "ai_chat"
                    }
                    "open_ai_chat_live" -> {
                        room?.disconnect()
                        room = null
                        isSessionActive.value = false
                        val zhiqiu = AI_PERSONAS.first()
                        selectedPersonaState.value = zhiqiu
                        aiChatSessionId.value = null
                        aiChatMessages.clear()
                        aiChatMessages.add(
                            AiChatBubbleItem("g1", isUser = false, text = zhiqiu.greeting)
                        )
                        currentScreen.value = "ai_chat"
                        val userMsg = intent.getStringExtra("text") ?: "最近工作有些累，我想找个人聊聊。"
                        sendAiChatMessageHttp(userMsg, speakReply = true)
                    }
                    "open_ai_freetalk" -> {
                        val zhiqiu = AI_PERSONAS.first()
                        selectedPersonaState.value = zhiqiu
                        freetalkSubtitleState.value = zhiqiu.freetalkSubtitle
                        freetalkStatusState.value = "正在听"
                        currentScreen.value = "ai_freetalk"
                        startRoomTaskSession(task = "cascade", scene = "ai_freetalk")
                    }
                    "scene1_transcribe" -> {
                        sourceLangState.value = "zh"
                        currentScreen.value = "transcribe_live"
                        startRoomTaskSession(task = "transcribe", scene = "transcribe")
                        delay(2500)
                        room?.localParticipant?.publishData(
                            JSONObject().apply {
                                put("type", "summary_input")
                                put("text", "第一点，录音转写支持实时绿色中间态字幕和定稿黑字。第二点，转写结束后自动调用大模型提炼标题与结构化演讲纪要。")
                                put("client_input_id", "s1_note_1")
                                put("request_id", "req_s1_1")
                            }.toString().toByteArray(Charsets.UTF_8),
                            DataPublishReliability.RELIABLE
                        )
                        liveSegments.add(
                            TranscriptSegmentItem(
                                turnId = "s1_t1",
                                seq = 1L,
                                speaker = null,
                                sourceText = "第一点，录音转写支持实时绿色中间态字幕和定稿黑字。第二点，转写结束后自动调用大模型提炼标题与结构化演讲纪要。",
                                isFinal = true,
                            )
                        )
                        delay(1500)
                        stopSceneAndFinalize(autoSummarize = true)
                    }
                }
            }
            return
        }
        if (!prompt.isNullOrBlank()) {
            currentScreen.value = "cascade"
            lifecycleScope.launch {
                if (room == null || activeTaskState.value != "cascade") {
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

    private fun langLabel(code: String): String {
        val primary = code.lowercase().split("-", "_").firstOrNull() ?: code
        return SCREENSHOT_LANGUAGES.find { it.code == primary }?.cnName ?: when (primary) {
            "ko" -> "韩语"
            else -> code
        }
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
            val arr = policy.optJSONArray("allowed_langs") ?: JSONArray()
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            if (list.isNotEmpty()) {
                allowedLangsState.clear()
                allowedLangsState.addAll(list)
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

                val sessionResp = withContext(Dispatchers.IO) {
                    requestRoomSessionFromHiMarket(task, scene)
                }

                val sessionId = sessionResp.getString("session_id")
                val roomName = sessionResp.optString("room_name", "-")
                val wsUrl = sessionResp.getString("livekit_ws_url")
                val jwtToken = sessionResp.getString("participant_token")
                val tenantId = sessionResp.getString("tenant_id")
                val apiKeyId = sessionResp.getString("api_key_id")

                sessionIdState.value = sessionId
                roomNameState.value = roomName
                tenantIdState.value = tenantId
                apiKeyIdState.value = apiKeyId

                stopMediaPlayer()
                room?.disconnect()
                val lkRoom = LiveKit.create(applicationContext)
                room = lkRoom

                lifecycleScope.launch {
                    lkRoom.events.collect { event ->
                        when (event) {
                            is RoomEvent.Connected -> {
                                isSessionActive.value = true
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
                            }
                            else -> {}
                        }
                    }
                }

                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
                audioManager.setStreamVolume(
                    AudioManager.STREAM_VOICE_CALL,
                    ((audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) * 3) / 5).coerceAtLeast(1),
                    0,
                )
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    ((audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 3) / 5).coerceAtLeast(1),
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
                        }
                        else -> {
                            freetalkSubtitleState.value = text
                            freetalkStatusState.value = "正在听"
                            if (activeSceneState.value == "ai_freetalk" && isFinal && text.isNotBlank()) {
                                aiChatMessages.add(
                                    AiChatBubbleItem(
                                        id = "ft_${turnId}_${System.currentTimeMillis()}",
                                        isUser = false,
                                        text = text
                                    )
                                )
                            }
                            appendLog("🤖 AI: $text")
                        }
                    }
                }
                "target_lang_updated" -> {
                    val newLang = obj.optString("target_lang", targetLangState.value)
                    targetLangState.value = newLang
                    simTargetLangState.value = newLang
                    appendLog("✓ 目标语言已切至 ${langLabel(newLang)}")
                }
                "agent_speaking" -> {
                    val speaking = obj.optBoolean("speaking", false)
                    isTtsSpeaking.value = speaking
                    if (speaking) {
                        freetalkStatusState.value = "回应中"
                        lifecycleScope.launch {
                            try {
                                room?.localParticipant?.setMicrophoneEnabled(false)
                            } catch (_: Exception) {
                            }
                        }
                    } else {
                        freetalkStatusState.value = "正在听"
                        lifecycleScope.launch {
                            delay(350)
                            if (!isTtsSpeaking.value && isSessionActive.value) {
                                try {
                                    room?.localParticipant?.setMicrophoneEnabled(true)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                }
                "tts_state" -> {
                    val state = obj.optString("state", "")
                    val targetDevice = obj.optString("target_device", "speaker")
                    if (state == "start") {
                        isTtsSpeaking.value = true
                        DialogueAudioRouter.routeForTargetDevice(this, targetDevice)
                        lifecycleScope.launch {
                            try {
                                room?.localParticipant?.setMicrophoneEnabled(false)
                            } catch (_: Exception) {
                            }
                        }
                    } else if (state == "end") {
                        isTtsSpeaking.value = false
                        lifecycleScope.launch {
                            delay(350)
                            if (!isTtsSpeaking.value && isSessionActive.value) {
                                try {
                                    room?.localParticipant?.setMicrophoneEnabled(true)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                }
                "summary_input_ack" -> {
                    val seq = obj.optLong("seq", 0)
                    appendLog("✓ 已记录上下文片段 #$seq")
                }
                "error" -> {
                    val code = obj.optString("code", "error")
                    val msg = obj.optString("message", raw)
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
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.mode = AudioManager.MODE_NORMAL
            } catch (_: Exception) {
            }
            val durMs = localRecorder?.stop() ?: (elapsedSecondsState.value * 1000L)
            localRecorder = null

            if (sid != "-" && (scene == "transcribe" || scene == "simultaneous_interpret")) {
                val count = recordingHistory.count { it.scene == scene } + 1
                val defaultTitle = if (scene == "transcribe") "现场录音$count" else "同声传译$count"
                val wavPath = LocalRecordingStore.wavFileForSession(this@MainActivity, sid).absolutePath
                val srcLang = if (scene == "simultaneous_interpret") simSourceLangState.value else sourceLangState.value
                val tgtLang = if (scene == "simultaneous_interpret") simTargetLangState.value else sourceLangState.value
                val entry = LocalRecordingEntry(
                    sessionId = sid,
                    scene = scene,
                    title = defaultTitle,
                    sourceLang = srcLang,
                    targetLang = tgtLang,
                    audioPath = wavPath,
                    durationMs = durMs.coerceAtLeast(elapsedSecondsState.value * 1000L),
                    createdAt = LocalRecordingStore.nowFormatted(),
                    segments = liveSegments.toMutableList(),
                    summary = liveSummaryRecord.value,
                )
                LocalRecordingStore.upsertEntry(this@MainActivity, entry)
                reloadRecordingHistory()
                selectedRecording.value = entry
                detailTab.value = "summary"
                currentScreen.value = "detail"

                if (autoSummarize && entry.segments.isNotEmpty()) {
                    triggerHttpSummaryForEntry(
                        entry = entry,
                        summaryStyle = selectedSummaryStyle.value,
                        speak = false,
                    )
                }
            }
        }
    }

    private fun sendMidSessionTargetLang(newLang: String) {
        targetLangState.value = newLang
        simTargetLangState.value = newLang
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
        if (room == null) {
            startRoomTaskSession(task = "speech_translate", scene = "dialogue")
        }
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
        }
    }

    private fun triggerHttpSummaryForEntry(
        entry: LocalRecordingEntry,
        summaryStyle: String,
        speak: Boolean,
    ) {
        lifecycleScope.launch {
            val reqId = "sum_${UUID.randomUUID().toString().take(10)}"
            isSummarizingState.value = true
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
                reloadRecordingHistory()
                selectedRecording.value = entry.copy(title = newTitle, summary = rec)
                liveSummaryRecord.value = rec

                appendLog("📝 ✓ 总结已生成：《$newTitle》 -> ${sumText.replace("\n", " ").take(120)}")

                val segCount = data.optInt("segment_count", 0)
                if (speak && segCount > 0 && ttsErr.isNullOrEmpty()) {
                    playSummarySegmentsHttp(entry.sessionId, reqId, segCount)
                }
            } catch (e: Exception) {
                appendLog("❌ 总结失败: ${e.message}")
            } finally {
                isSummarizingState.value = false
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
        var useInline = sessionId.isNullOrBlank() || sessionId.startsWith("sess_demo_")

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
                conn.readTimeout = 30000
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

    private fun runHttpSpeakAndPlay(text: String, targetLang: String, cacheTurnId: String? = null) {
        lifecycleScope.launch {
            if (cacheTurnId != null) {
                val cached = LocalRecordingStore.ttsCacheFile(this@MainActivity, cacheTurnId)
                if (cached.exists() && cached.length() > 44) {
                    playLocalWavFile(cached)
                    return@launch
                }
            }
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
                for (idx in 0 until segCount) {
                    val wavFile = withContext(Dispatchers.IO) {
                        val conn = URL("http://127.0.0.1:8080/api/v1/voice/speak/$spkSid/segments/$idx")
                            .openConnection() as HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 5000
                        conn.readTimeout = 60000
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
                appendLog("🔊 语音播报完成 ($segCount 段, 语言=$targetLang)")
            } catch (e: Exception) {
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
            if (room == null) {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.mode = AudioManager.MODE_NORMAL
            }
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

    private fun requestRoomSessionFromHiMarket(task: String, scene: String): JSONObject {
        val url = URL("http://127.0.0.1:8080/api/v1/voice/sessions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer hm_ak_live_ugreen_2026_voice_key")
        conn.setRequestProperty("Content-Type", "application/json")

        val reqBody = if (task == "cascade") {
            JSONObject().apply {
                if (scene == "ai_freetalk") {
                    put("system_prompt", selectedPersonaState.value.systemPrompt)
                    put("greeting_text", selectedPersonaState.value.freetalkSubtitle)
                }
            }.toString()
        } else {
            val srcLang = when (scene) {
                "dialogue" -> phoneLangState.value
                "simultaneous_interpret" -> simSourceLangState.value
                else -> sourceLangState.value
            }
            val tgtLang = when (scene) {
                "dialogue" -> earphoneLangState.value
                "simultaneous_interpret" -> simTargetLangState.value
                else -> targetLangState.value
            }
            JSONObject().apply {
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

    private fun openAiChatScreen(speakGreeting: Boolean = true) {
        val persona = selectedPersonaState.value
        if (aiChatMessages.isEmpty()) {
            aiChatMessages.add(
                AiChatBubbleItem(
                    id = "greet_${persona.id}",
                    isUser = false,
                    text = persona.greeting,
                )
            )
            if (speakGreeting) {
                runHttpSpeakAndPlay(persona.greeting, "zh")
            }
        }
        currentScreen.value = "ai_chat"
    }

    private fun switchAiPersona(persona: AiPersona) {
        selectedPersonaState.value = persona
        aiChatSessionId.value = null
        aiChatMessages.clear()
        aiChatMessages.add(
            AiChatBubbleItem(
                id = "greet_${persona.id}_${System.currentTimeMillis()}",
                isUser = false,
                text = persona.greeting,
            )
        )
        freetalkSubtitleState.value = persona.freetalkSubtitle
        runHttpSpeakAndPlay(persona.greeting, "zh")
    }

    private fun sendAiChatMessageHttp(userText: String, speakReply: Boolean = true) {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return
        val persona = selectedPersonaState.value
        aiChatMessages.add(
            AiChatBubbleItem(
                id = "usr_${UUID.randomUUID().toString().take(8)}",
                isUser = true,
                text = trimmed,
            )
        )
        isAiChatSending.value = true
        lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    val conn = URL("http://127.0.0.1:8080/api/v1/voice/chat")
                        .openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 5000
                    conn.readTimeout = 30000
                    conn.doOutput = true
                    conn.setRequestProperty("Authorization", "Bearer hm_ak_live_ugreen_2026_voice_key")
                    conn.setRequestProperty("Content-Type", "application/json")
                    val req = JSONObject().apply {
                        put("text", trimmed)
                        put("persona_id", persona.id)
                        put("system_prompt", persona.systemPrompt)
                        put("client_input_id", "chat_${UUID.randomUUID().toString().take(10)}")
                        aiChatSessionId.value?.let { put("session_id", it) }
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
                val reply = data.getString("reply_text")
                aiChatSessionId.value = sid
                val msgId = "ai_${UUID.randomUUID().toString().take(8)}"
                aiChatMessages.add(
                    AiChatBubbleItem(
                        id = msgId,
                        isUser = false,
                        text = reply,
                    )
                )
                appendLog("🤖 ${persona.name}: $reply")
                if (speakReply) {
                    runHttpSpeakAndPlay(reply, "zh", cacheTurnId = msgId)
                }
            } catch (e: Exception) {
                appendLog("❌ AI聊天失败: ${e.message}")
            } finally {
                isAiChatSending.value = false
            }
        }
    }

    private fun startHoldSpeakRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        stopMediaPlayer()
        room?.disconnect()
        room = null
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }
        isHoldSpeaking.value = true
        val rec = holdMicRecorder ?: HoldMicRecorder(16000).also { holdMicRecorder = it }
        rec.start()
        appendLog("🎙 开始按住说话收音...")
    }

    private fun finishHoldSpeakAndSend() {
        if (!isHoldSpeaking.value) return
        isHoldSpeaking.value = false
        val pcmBytes = holdMicRecorder?.stopAndGetPcm() ?: ByteArray(0)
        if (pcmBytes.size < 6400) {
            Toast.makeText(this, "说话时间太短", Toast.LENGTH_SHORT).show()
            appendLog("⚠️ 按住说话时间太短 (${pcmBytes.size} bytes)")
            return
        }
        isAiChatSending.value = true
        lifecycleScope.launch {
            try {
                val recognizedText = withContext(Dispatchers.IO) {
                    val b64 = android.util.Base64.encodeToString(pcmBytes, android.util.Base64.NO_WRAP)
                    val conn = URL("http://127.0.0.1:8080/v1/audio/transcriptions")
                        .openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 5000
                    conn.readTimeout = 25000
                    conn.doOutput = true
                    conn.setRequestProperty("Authorization", "Bearer hm_ak_live_ugreen_2026_voice_key")
                    conn.setRequestProperty("Content-Type", "application/json")
                    val req = JSONObject().apply {
                        put("audio_base64", b64)
                        put("format", "pcm")
                        put("sample_rate", 16000)
                        put("language", "zh")
                    }
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                        it.write(req.toString())
                    }
                    val status = conn.responseCode
                    val respText = (if (status in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.readText().orEmpty()
                    if (status !in 200..299) {
                        throw RuntimeException("语音识别失败($status)")
                    }
                    val json = JSONObject(respText)
                    val rawText = json.optString("text", "").trim()
                    if (rawText.startsWith("[") && rawText.contains("语音识别结果")) {
                        ""
                    } else {
                        rawText
                    }
                }
                isAiChatSending.value = false
                if (recognizedText.isBlank()) {
                    Toast.makeText(this@MainActivity, "未识别到语音，请靠近麦克风重试", Toast.LENGTH_SHORT).show()
                    appendLog("⚠️ 按住说话未识别到有效语音")
                    return@launch
                }
                appendLog("🧑 按住说话识别: $recognizedText")
                sendAiChatMessageHttp(recognizedText, speakReply = true)
            } catch (e: Exception) {
                isAiChatSending.value = false
                Toast.makeText(this@MainActivity, "语音识别失败: ${e.message}", Toast.LENGTH_SHORT).show()
                appendLog("❌ 按住说话识别失败: ${e.message}")
            }
        }
    }

    @Composable
    private fun AppRouter() {
        when (currentScreen.value) {
            "home" -> AiAssistantHomeScreen()
            "lang_picker" -> LanguagePickerScreen()
            "transcribe_live" -> Scene1TranscribeLiveScreen()
            "detail" -> RecordingDetailScreen()
            "simultaneous" -> Scene2SimultaneousScreen()
            "dialogue" -> Scene3DialogueScreen()
            "ai_chat" -> AiChatScreenView(
                selectedPersona = selectedPersonaState.value,
                onSelectPersona = { switchAiPersona(it) },
                messages = aiChatMessages,
                isSending = isAiChatSending.value,
                isRecordingHold = isHoldSpeaking.value,
                onBack = {
                    stopMediaPlayer()
                    currentScreen.value = "home"
                },
                onOpenFreeTalk = {
                    stopMediaPlayer()
                    freetalkSubtitleState.value = selectedPersonaState.value.freetalkSubtitle
                    freetalkStatusState.value = "正在听"
                    currentScreen.value = "ai_freetalk"
                    startRoomTaskSession(task = "cascade", scene = "ai_freetalk")
                },
                onReplayBubble = { text ->
                    runHttpSpeakAndPlay(text, "zh")
                },
                onSendMessage = { text ->
                    sendAiChatMessageHttp(text, speakReply = true)
                },
                onStartHoldSpeak = {
                    startHoldSpeakRecording()
                },
                onFinishHoldSpeak = {
                    finishHoldSpeakAndSend()
                }
            )
            "ai_freetalk" -> AiFreeTalkScreenView(
                persona = selectedPersonaState.value,
                subtitleText = freetalkSubtitleState.value,
                callStateLabel = freetalkStatusState.value,
                onHangup = {
                    room?.disconnect()
                    room = null
                    isSessionActive.value = false
                    currentScreen.value = "ai_chat"
                }
            )
            else -> AiAssistantHomeScreen()
        }
    }

    // =========================================================================
    // Screen 1: AI助手首页 (Top-left in screenshot)
    // =========================================================================
    @Composable
    private fun AiAssistantHomeScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgGray)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Top back chevron
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clickable { finish() },
                contentAlignment = Alignment.CenterStart
            ) {
                Text("‹", fontSize = 26.sp, color = TextPrimary, fontWeight = FontWeight.Light)
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Title row: AI助手 + 在线模型 pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AI助手",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Surface(
                    color = CardWhite,
                    shape = RoundedCornerShape(18.dp),
                    shadowElevation = 0.5.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(AccentGreen),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✓", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("在线模型", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                        Text("⌄", fontSize = 12.sp, color = TextSecondary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Three Scene Cards (录音转写 / 同声传译 / 对话翻译)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HomeSceneCard(
                    title = "录音转写",
                    iconType = "transcribe",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        langPickerTarget.value = "transcribe_start"
                        tempPickedLang.value = sourceLangState.value
                        currentScreen.value = "lang_picker"
                    }
                )
                HomeSceneCard(
                    title = "同声传译",
                    iconType = "simultaneous",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        isSessionActive.value = false
                        liveSegments.clear()
                        interimSourceText.value = ""
                        elapsedSecondsState.value = 0L
                        currentScreen.value = "simultaneous"
                    }
                )
                HomeSceneCard(
                    title = "对话翻译",
                    iconType = "dialogue",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        isSessionActive.value = false
                        liveSegments.clear()
                        interimSourceText.value = ""
                        activeDialogueSpeaker.value = null
                        currentScreen.value = "dialogue"
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // S60 情绪树洞 Cards: AI聊天 (语音/文字) & AI畅聊 (实时电话)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HomeSceneCard(
                    title = "AI聊天",
                    iconType = "ai_chat",
                    modifier = Modifier.weight(1f),
                    onClick = { openAiChatScreen(speakGreeting = true) }
                )
                HomeSceneCard(
                    title = "AI畅聊",
                    iconType = "ai_freetalk",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        stopMediaPlayer()
                        freetalkSubtitleState.value = selectedPersonaState.value.freetalkSubtitle
                        freetalkStatusState.value = "正在听"
                        currentScreen.value = "ai_freetalk"
                        startRoomTaskSession(task = "cascade", scene = "ai_freetalk")
                    }
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "录音记录",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Filter Pills: 全部 | 录音转写 | 同声传译
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "all" to "全部",
                    "transcribe" to "录音转写",
                    "simultaneous_interpret" to "同声传译"
                ).forEach { (key, label) ->
                    val selected = historyFilter.value == key
                    Surface(
                        color = if (selected) ChipGreenBg else CardWhite,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable { historyFilter.value = key }
                    ) {
                        Text(
                            text = label,
                            color = if (selected) ChipGreenFg else TextSecondary,
                            fontSize = 12.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val filtered = recordingHistory.filter {
                historyFilter.value == "all" || it.scene == historyFilter.value
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filtered) { item ->
                    Surface(
                        color = CardWhite,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedRecording.value = item
                                detailTab.value = "summary"
                                currentScreen.value = "detail"
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(BadgePurpleBg),
                                contentAlignment = Alignment.Center
                            ) {
                                SceneMiniIcon(
                                    isTranscribe = item.scene == "transcribe",
                                    tint = BadgePurpleFg
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    color = TextPrimary,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = item.createdAt,
                                    color = TextMuted,
                                    fontSize = 11.5.sp
                                )
                            }
                            Text(
                                text = LocalRecordingStore.formatDuration(item.durationMs),
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "请注意！由于录音文件存储在手机本地，因此 APP 卸载后，所有的录音数据将会被永久删除。",
                color = TextMuted,
                fontSize = 11.sp
            )
        }
    }

    @Composable
    private fun HomeSceneCard(
        title: String,
        iconType: String,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        Surface(
            color = CardWhite,
            shape = RoundedCornerShape(14.dp),
            modifier = modifier
                .height(86.dp)
                .clickable { onClick() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Canvas(modifier = Modifier.size(26.dp)) {
                    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    when (iconType) {
                        "transcribe" -> {
                            drawRoundRect(
                                color = TextPrimary,
                                topLeft = Offset(size.width * 0.18f, size.height * 0.1f),
                                size = Size(size.width * 0.64f, size.height * 0.8f),
                                cornerRadius = CornerRadius(4.dp.toPx()),
                                style = stroke
                            )
                            drawLine(
                                color = TextPrimary,
                                start = Offset(size.width * 0.32f, size.height * 0.38f),
                                end = Offset(size.width * 0.68f, size.height * 0.38f),
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = TextPrimary,
                                start = Offset(size.width * 0.32f, size.height * 0.58f),
                                end = Offset(size.width * 0.56f, size.height * 0.58f),
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                        "simultaneous" -> {
                            drawArc(
                                color = TextPrimary,
                                startAngle = 180f,
                                sweepAngle = 180f,
                                useCenter = false,
                                topLeft = Offset(size.width * 0.15f, size.height * 0.12f),
                                size = Size(size.width * 0.7f, size.height * 0.7f),
                                style = stroke
                            )
                            drawRoundRect(
                                color = TextPrimary,
                                topLeft = Offset(size.width * 0.12f, size.height * 0.48f),
                                size = Size(size.width * 0.18f, size.height * 0.34f),
                                cornerRadius = CornerRadius(3.dp.toPx()),
                                style = stroke
                            )
                            drawRoundRect(
                                color = TextPrimary,
                                topLeft = Offset(size.width * 0.70f, size.height * 0.48f),
                                size = Size(size.width * 0.18f, size.height * 0.34f),
                                cornerRadius = CornerRadius(3.dp.toPx()),
                                style = stroke
                            )
                        }
                        "ai_chat" -> {
                            drawRoundRect(
                                color = TextPrimary,
                                topLeft = Offset(size.width * 0.12f, size.height * 0.16f),
                                size = Size(size.width * 0.76f, size.height * 0.58f),
                                cornerRadius = CornerRadius(6.dp.toPx()),
                                style = stroke
                            )
                            drawCircle(
                                color = TextPrimary,
                                radius = 1.6.dp.toPx(),
                                center = Offset(size.width * 0.36f, size.height * 0.45f)
                            )
                            drawCircle(
                                color = TextPrimary,
                                radius = 1.6.dp.toPx(),
                                center = Offset(size.width * 0.64f, size.height * 0.45f)
                            )
                        }
                        "ai_freetalk" -> {
                            drawRoundRect(
                                color = TextPrimary,
                                topLeft = Offset(size.width * 0.14f, size.height * 0.22f),
                                size = Size(size.width * 0.72f, size.height * 0.16f),
                                cornerRadius = CornerRadius(4.dp.toPx()),
                                style = stroke
                            )
                            drawCircle(
                                color = TextPrimary,
                                radius = size.width * 0.24f,
                                center = Offset(size.width * 0.50f, size.height * 0.64f),
                                style = stroke
                            )
                        }
                        else -> {
                            drawRoundRect(
                                color = TextPrimary,
                                topLeft = Offset(size.width * 0.1f, size.height * 0.15f),
                                size = Size(size.width * 0.52f, size.height * 0.45f),
                                cornerRadius = CornerRadius(4.dp.toPx()),
                                style = stroke
                            )
                            drawRoundRect(
                                color = TextPrimary,
                                topLeft = Offset(size.width * 0.38f, size.height * 0.42f),
                                size = Size(size.width * 0.52f, size.height * 0.45f),
                                cornerRadius = CornerRadius(4.dp.toPx()),
                                style = stroke
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    @Composable
    private fun SceneMiniIcon(isTranscribe: Boolean, tint: Color) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
            if (isTranscribe) {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(size.width * 0.2f, size.height * 0.12f),
                    size = Size(size.width * 0.6f, size.height * 0.76f),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                    style = stroke
                )
            } else {
                drawArc(
                    color = tint,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.15f, size.height * 0.15f),
                    size = Size(size.width * 0.7f, size.height * 0.7f),
                    style = stroke
                )
            }
        }
    }

    // =========================================================================
    // Screen 2: 选择语言页 (Row 2, Col 1 in screenshot)
    // =========================================================================
    @Composable
    private fun LanguagePickerScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgGray)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clickable {
                        currentScreen.value = when (langPickerTarget.value) {
                            "sim_source", "sim_target" -> "simultaneous"
                            "dlg_phone", "dlg_earphone" -> "dialogue"
                            else -> "home"
                        }
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Text("‹", fontSize = 26.sp, color = TextPrimary)
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "选择语言",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Search Box
            Surface(
                color = Color(0xFFEFEFF4),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔍", fontSize = 13.sp, color = TextMuted)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (langSearchQuery.value.isEmpty()) {
                            Text("请输入关键词", color = TextMuted, fontSize = 13.5.sp)
                        }
                        BasicTextField(
                            value = langSearchQuery.value,
                            onValueChange = { langSearchQuery.value = it },
                            textStyle = TextStyle(color = TextPrimary, fontSize = 13.5.sp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("最近使用", color = TextMuted, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(6.dp))

            val recentItem = SCREENSHOT_LANGUAGES.find { it.code == recentLangCode.value }
                ?: SCREENSHOT_LANGUAGES.first()
            Surface(
                color = CardWhite,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { tempPickedLang.value = recentItem.code }
            ) {
                LanguageRowItem(
                    item = recentItem,
                    selected = tempPickedLang.value == recentItem.code
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("所有语言", color = TextMuted, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(6.dp))

            val q = langSearchQuery.value.trim().lowercase()
            val filteredLangs = SCREENSHOT_LANGUAGES.filter {
                q.isEmpty() || it.cnName.lowercase().contains(q) || it.nativeName.lowercase().contains(q)
            }

            Surface(
                color = CardWhite,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredLangs) { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { tempPickedLang.value = item.code }
                        ) {
                            LanguageRowItem(
                                item = item,
                                selected = tempPickedLang.value == item.code
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                thickness = 0.5.dp,
                                color = Color(0xFFF1F1F4)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Bottom full-width black pill button: 选择此语言
            Button(
                onClick = {
                    val chosen = tempPickedLang.value
                    recentLangCode.value = chosen
                    when (langPickerTarget.value) {
                        "transcribe_start" -> {
                            sourceLangState.value = chosen
                            currentScreen.value = "transcribe_live"
                            startRoomTaskSession(task = "transcribe", scene = "transcribe")
                        }
                        "sim_source" -> {
                            simSourceLangState.value = chosen
                            sourceLangState.value = chosen
                            currentScreen.value = "simultaneous"
                        }
                        "sim_target" -> {
                            sendMidSessionTargetLang(chosen)
                            currentScreen.value = "simultaneous"
                        }
                        "dlg_phone" -> {
                            phoneLangState.value = chosen
                            currentScreen.value = "dialogue"
                        }
                        "dlg_earphone" -> {
                            earphoneLangState.value = chosen
                            currentScreen.value = "dialogue"
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = DarkBtn),
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("选择此语言", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    @Composable
    private fun LanguageRowItem(item: LangItem, selected: Boolean) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = item.cnName,
                    color = TextPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.nativeName,
                    color = TextMuted,
                    fontSize = 11.5.sp
                )
            }
            if (selected) {
                Text("✓", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    // =========================================================================
    // Screen 3: 现场录音 - 流式 ASR 进行页 (Row 2, Col 2 in screenshot)
    // =========================================================================
    @Composable
    private fun Scene1TranscribeLiveScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgGray)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clickable {
                            timerJob?.cancel()
                            localRecorder?.stop()
                            room?.disconnect()
                            isSessionActive.value = false
                            currentScreen.value = "home"
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text("‹", fontSize = 26.sp, color = TextPrimary)
                }
                InfoCircleIcon()
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "现场录音",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(liveSegments) { seg ->
                    Text(
                        text = seg.sourceText,
                        color = TextPrimary,
                        fontSize = 14.5.sp,
                        lineHeight = 22.sp
                    )
                }
                if (interimSourceText.value.isNotBlank()) {
                    item {
                        Text(
                            text = interimSourceText.value,
                            color = StreamGreen,
                            fontSize = 14.5.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Bottom centered status + timer + stop button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "• 手机收音中",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = LocalRecordingStore.formatDurationHhMmSs(elapsedSecondsState.value * 1000L),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(DarkBtn)
                        .clickable { stopSceneAndFinalize(autoSummarize = true) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "结束",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }

    // =========================================================================
    // Screen 4: 录音/同传详情 + 倍速播放器 + AI 总结页 (Row 2 Col 3 & Row 3 Col 3)
    // =========================================================================
    @Composable
    private fun RecordingDetailScreen() {
        val entry = selectedRecording.value
        if (entry == null) {
            currentScreen.value = "home"
            return
        }
        var showStyleMenu by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgGray)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Top bar: < on left, Share and ... on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clickable {
                            stopMediaPlayer()
                            currentScreen.value = "home"
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text("‹", fontSize = 26.sp, color = TextPrimary)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("↱", fontSize = 18.sp, color = TextPrimary)
                    Text("⋯", fontSize = 20.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Title
            Text(
                text = entry.title,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Meta row: 现场录音 / 同声传译 + Date + Duration
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SceneMiniIcon(
                        isTranscribe = entry.scene == "transcribe",
                        tint = TextMuted
                    )
                    Text(
                        text = if (entry.scene == "simultaneous_interpret") "同声传译" else "现场录音",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
                Text(
                    text = entry.createdAt.removePrefix("2026-"),
                    color = TextMuted,
                    fontSize = 12.sp
                )
                Text(
                    text = LocalRecordingStore.formatDuration(entry.durationMs),
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Audio Player White Card
            Surface(
                color = CardWhite,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    val progressFraction = if (entry.durationMs > 0) {
                        (playbackPositionMs.value.toFloat() / entry.durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    // Thin custom progress bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0xFFE4E4E7))
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressFraction)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(TextPrimary)
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(TextPrimary)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = LocalRecordingStore.formatDurationHhMmSs(playbackPositionMs.value),
                            color = TextMuted,
                            fontSize = 10.5.sp
                        )
                        Text(
                            text = LocalRecordingStore.formatDurationHhMmSs(entry.durationMs),
                            color = TextMuted,
                            fontSize = 10.5.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.spacedBy(28.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "↺15",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable { seekMediaPlayer(-15_000) }
                            )
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(DarkBtn)
                                    .clickable {
                                        if (isPlayingWav.value) {
                                            stopMediaPlayer()
                                        } else {
                                            playLocalWavFile(File(entry.audioPath), playbackSpeed.value)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isPlayingWav.value) "⏸" else "▶",
                                    color = Color.White,
                                    fontSize = 14.sp
                                )
                            }
                            Text(
                                text = "15↻",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable { seekMediaPlayer(15_000) }
                            )
                        }

                        Text(
                            text = "${playbackSpeed.value}x",
                            color = TextSecondary,
                            fontSize = 12.5.sp,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .clickable {
                                    val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                                    val nextIdx = (speeds.indexOf(playbackSpeed.value) + 1) % speeds.size
                                    updateMediaPlayerSpeed(speeds[nextIdx])
                                }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Content White Card: AI总结 | 转写
            Surface(
                color = CardWhite,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                    // Tab bar: AI总结 | 转写 + Copy icon on right
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { detailTab.value = "summary" }
                            ) {
                                Text(
                                    text = "AI总结",
                                    color = if (detailTab.value == "summary") TextPrimary else TextMuted,
                                    fontSize = 14.5.sp,
                                    fontWeight = if (detailTab.value == "summary") FontWeight.Bold else FontWeight.Normal
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .width(24.dp)
                                        .height(2.5.dp)
                                        .background(
                                            if (detailTab.value == "summary") TextPrimary else Color.Transparent,
                                            RoundedCornerShape(2.dp)
                                        )
                                )
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { detailTab.value = "transcript" }
                            ) {
                                Text(
                                    text = "转写",
                                    color = if (detailTab.value == "transcript") TextPrimary else TextMuted,
                                    fontSize = 14.5.sp,
                                    fontWeight = if (detailTab.value == "transcript") FontWeight.Bold else FontWeight.Normal
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .width(24.dp)
                                        .height(2.5.dp)
                                        .background(
                                            if (detailTab.value == "transcript") TextPrimary else Color.Transparent,
                                            RoundedCornerShape(2.dp)
                                        )
                                )
                            }
                        }

                        // Copy icon
                        Text(
                            text = "❐",
                            color = TextSecondary,
                            fontSize = 16.sp,
                            modifier = Modifier.clickable {
                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val textToCopy = entry.summary?.summaryText ?: ""
                                cm.setPrimaryClip(ClipData.newPlainText("AI总结", textToCopy))
                                Toast.makeText(this@MainActivity, "已复制总结内容", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (detailTab.value == "summary") {
                        // Pill button: [☷ 总结设置 ⌄]
                        Box {
                            Surface(
                                color = Color(0xFFF4F4F5),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.clickable { showStyleMenu = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text("☷", fontSize = 12.sp, color = TextPrimary)
                                    val styleName = when (selectedSummaryStyle.value) {
                                        "meeting" -> "总结设置 · 会议纪要"
                                        "concise" -> "总结设置 · 精简要点"
                                        else -> "总结设置"
                                    }
                                    Text(styleName, fontSize = 12.sp, color = TextPrimary)
                                    Text("⌄", fontSize = 12.sp, color = TextSecondary)
                                }
                            }
                            DropdownMenu(
                                expanded = showStyleMenu,
                                onDismissRequest = { showStyleMenu = false }
                            ) {
                                listOf(
                                    "speech" to "演讲纪要（默认）",
                                    "meeting" to "会议纪要",
                                    "concise" to "精简要点"
                                ).forEach { (styleKey, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label, fontSize = 13.sp) },
                                        onClick = {
                                            showStyleMenu = false
                                            selectedSummaryStyle.value = styleKey
                                            triggerHttpSummaryForEntry(entry, styleKey, speak = false)
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (isSummarizingState.value) {
                                Text("正在生成 AI 总结...", color = TextSecondary, fontSize = 13.5.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            val sum = entry.summary
                            if (sum != null) {
                                StructuredMarkdownSummaryView(sum.summaryText)
                            } else if (!isSummarizingState.value) {
                                Text(
                                    text = "点击上方「总结设置」生成 AI 总结。",
                                    color = TextSecondary,
                                    fontSize = 13.5.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(18.dp))
                            Text(
                                text = "— 由AI生成，仅供参考，不构成任何专业建议 —",
                                color = Color(0xFFC4C4C8),
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(entry.segments) { seg ->
                                Column {
                                    Text(seg.sourceText, color = TextPrimary, fontSize = 14.sp, lineHeight = 21.sp)
                                    if (seg.translatedText.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(seg.translatedText, color = TextSecondary, fontSize = 13.5.sp, lineHeight = 20.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StructuredMarkdownSummaryView(markdown: String) {
        val lines = markdown.lines()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("# ")) continue
                when {
                    line.startsWith("## ") -> {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = line.removePrefix("## ").trim(),
                            color = TextPrimary,
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    line.startsWith("### ") -> {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = line.removePrefix("### ").trim(),
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    line.startsWith("- ") || line.startsWith("* ") -> {
                        val content = line.substring(2).trim().replace("**", "")
                        Row(modifier = Modifier.padding(start = 4.dp)) {
                            Text("· ", color = TextSecondary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = content,
                                color = Color(0xFF3F3F46),
                                fontSize = 13.5.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = line.replace("**", ""),
                            color = Color(0xFF3F3F46),
                            fontSize = 13.5.sp,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }
    }

    // =========================================================================
    // Screen 5 & 6: 同声传译 (待机页 & 实时双栏流式页 - Row 3 Col 1 & Col 2)
    // =========================================================================
    @Composable
    private fun Scene2SimultaneousScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgGray)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clickable {
                            timerJob?.cancel()
                            localRecorder?.stop()
                            room?.disconnect()
                            isSessionActive.value = false
                            currentScreen.value = "home"
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text("‹", fontSize = 26.sp, color = TextPrimary)
                }
                InfoCircleIcon()
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "同声传译",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            if (!isSessionActive.value && liveSegments.isEmpty()) {
                // Idle State (Row 3, Col 1)
                Spacer(modifier = Modifier.weight(1f))

                LanguagePairSelectorBar(
                    leftTopLabel = "原文",
                    leftLangCode = simSourceLangState.value,
                    onLeftClick = {
                        langPickerTarget.value = "sim_source"
                        tempPickedLang.value = simSourceLangState.value
                        currentScreen.value = "lang_picker"
                    },
                    rightTopLabel = "译文",
                    rightLangCode = simTargetLangState.value,
                    onRightClick = {
                        langPickerTarget.value = "sim_target"
                        tempPickedLang.value = simTargetLangState.value
                        currentScreen.value = "lang_picker"
                    },
                    onSwap = {
                        val tmp = simSourceLangState.value
                        simSourceLangState.value = simTargetLangState.value
                        simTargetLangState.value = tmp
                    }
                )

                Spacer(modifier = Modifier.height(28.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "00:00:00",
                        color = TextPrimary,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(DarkBtn)
                            .clickable {
                                startRoomTaskSession(
                                    task = "speech_translate",
                                    scene = "simultaneous_interpret"
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        HeadphoneWhiteIcon()
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("开始传译", color = TextSecondary, fontSize = 12.sp)
                }
            } else {
                // Live State (Row 3, Col 2)
                Spacer(modifier = Modifier.height(12.dp))

                // Upper half: Source text + green interim text
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    liveSegments.forEach { seg ->
                        if (seg.sourceText.isNotBlank()) {
                            Text(
                                text = seg.sourceText,
                                color = TextSecondary,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }
                    if (interimSourceText.value.isNotBlank()) {
                        Text(
                            text = interimSourceText.value,
                            color = StreamGreen,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    thickness = 0.8.dp,
                    color = Color(0xFFE4E4E7)
                )

                // Lower half: Streaming translated text
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    liveSegments.forEach { seg ->
                        if (seg.translatedText.isNotBlank()) {
                            Text(
                                text = seg.translatedText,
                                color = TextPrimary,
                                fontSize = 14.5.sp,
                                lineHeight = 21.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Bottom stop control
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("• 手机收音中", color = TextSecondary, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = LocalRecordingStore.formatDurationHhMmSs(elapsedSecondsState.value * 1000L),
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(DarkBtn)
                            .clickable { stopSceneAndFinalize(autoSummarize = true) },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.White)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("结束传译", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }

    // =========================================================================
    // Screen 7 & 8: 对话翻译 (待机页 & 左右气泡对话页 - Row 4 Col 1 & Col 2)
    // =========================================================================
    @Composable
    private fun Scene3DialogueScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgGray)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clickable {
                            activeDialogueSpeaker.value = null
                            room?.disconnect()
                            room = null
                            isSessionActive.value = false
                            currentScreen.value = "home"
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text("‹", fontSize = 26.sp, color = TextPrimary)
                }
                if (liveSegments.isNotEmpty()) {
                    Text(
                        text = "对话翻译",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("文A", fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    InfoCircleIcon()
                }
            }

            if (liveSegments.isEmpty() && activeDialogueSpeaker.value == null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "对话翻译",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.weight(1f))

                LanguagePairSelectorBar(
                    leftTopLabel = "持手机说话",
                    leftLangCode = phoneLangState.value,
                    onLeftClick = {
                        langPickerTarget.value = "dlg_phone"
                        tempPickedLang.value = phoneLangState.value
                        currentScreen.value = "lang_picker"
                    },
                    rightTopLabel = "戴耳机说话",
                    rightLangCode = earphoneLangState.value,
                    onRightClick = {
                        langPickerTarget.value = "dlg_earphone"
                        tempPickedLang.value = earphoneLangState.value
                        currentScreen.value = "lang_picker"
                    },
                    onSwap = {
                        val tmp = phoneLangState.value
                        phoneLangState.value = earphoneLangState.value
                        earphoneLangState.value = tmp
                    }
                )
                Spacer(modifier = Modifier.height(32.dp))
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(liveSegments) { seg ->
                        val isPhone = seg.speaker != "earphone"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isPhone) Arrangement.Start else Arrangement.End
                        ) {
                            Surface(
                                color = if (isPhone) CardWhite else BubbleGray,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.widthIn(min = 180.dp, max = 265.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = seg.sourceText,
                                        color = if (isPhone) StreamGreen else TextPrimary,
                                        fontSize = 14.5.sp,
                                        lineHeight = 20.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (!isPhone && seg.isFinal) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        SpeakerReplayIcon {
                                            runHttpSpeakAndPlay(
                                                seg.translatedText.ifBlank { seg.sourceText },
                                                phoneLangState.value,
                                                cacheTurnId = seg.turnId
                                            )
                                        }
                                    }
                                    if (seg.translatedText.isNotBlank()) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(vertical = 8.dp),
                                            thickness = 0.5.dp,
                                            color = Color(0xFFE4E4E7)
                                        )
                                        Text(
                                            text = seg.translatedText,
                                            color = if (isPhone) StreamGreen else TextSecondary,
                                            fontSize = 14.sp,
                                            lineHeight = 19.sp
                                        )
                                    }
                                    if (isPhone) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        if (seg.isFinal) {
                                            SpeakerReplayIcon {
                                                runHttpSpeakAndPlay(
                                                    seg.translatedText.ifBlank { seg.sourceText },
                                                    earphoneLangState.value,
                                                    cacheTurnId = seg.turnId
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = "正在收音...",
                                                color = TextMuted,
                                                fontSize = 11.5.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Bottom Dual Circular Buttons: 英语 (phone) | 中文（简体） (earphone)
            val activeSpk = activeDialogueSpeaker.value
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val phoneEnabled = (activeSpk == null || activeSpk == "phone") && !isTtsSpeaking.value
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.alpha(if (phoneEnabled) 1f else 0.38f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(DarkBtn)
                            .clickable(enabled = phoneEnabled) {
                                if (activeSpk == "phone") stopDialogueTurn() else startDialogueTurn("phone")
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (activeSpk == "phone") {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color.White)
                            )
                        } else {
                            HeadphoneWhiteIcon()
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = langLabel(phoneLangState.value),
                        color = TextPrimary,
                        fontSize = 12.sp
                    )
                }

                val earphoneEnabled = (activeSpk == null || activeSpk == "earphone") && !isTtsSpeaking.value
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.alpha(if (earphoneEnabled) 1f else 0.38f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(DarkBtn)
                            .clickable(enabled = earphoneEnabled) {
                                if (activeSpk == "earphone") stopDialogueTurn() else startDialogueTurn("earphone")
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (activeSpk == "earphone") {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color.White)
                            )
                        } else {
                            MicWhiteIcon()
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = langLabel(earphoneLangState.value),
                        color = TextPrimary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }

    @Composable
    private fun SpeakerReplayIcon(onClick: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { onClick() }
                .padding(vertical = 2.dp)
        ) {
            Canvas(modifier = Modifier.size(16.dp)) {
                val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
                val path = Path().apply {
                    moveTo(size.width * 0.15f, size.height * 0.38f)
                    lineTo(size.width * 0.35f, size.height * 0.38f)
                    lineTo(size.width * 0.58f, size.height * 0.18f)
                    lineTo(size.width * 0.58f, size.height * 0.82f)
                    lineTo(size.width * 0.35f, size.height * 0.62f)
                    lineTo(size.width * 0.15f, size.height * 0.62f)
                    close()
                }
                drawPath(path, color = TextSecondary, style = stroke)
                drawArc(
                    color = TextSecondary,
                    startAngle = -45f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.52f, size.height * 0.25f),
                    size = Size(size.width * 0.34f, size.height * 0.5f),
                    style = stroke
                )
            }
        }
    }

    @Composable
    private fun HeadphoneWhiteIcon() {
        Canvas(modifier = Modifier.size(22.dp)) {
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = Color.White,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(size.width * 0.15f, size.height * 0.12f),
                size = Size(size.width * 0.7f, size.height * 0.7f),
                style = stroke
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(size.width * 0.12f, size.height * 0.48f),
                size = Size(size.width * 0.18f, size.height * 0.34f),
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = stroke
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(size.width * 0.70f, size.height * 0.48f),
                size = Size(size.width * 0.18f, size.height * 0.34f),
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = stroke
            )
        }
    }

    @Composable
    private fun MicWhiteIcon() {
        Canvas(modifier = Modifier.size(22.dp)) {
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(size.width * 0.34f, size.height * 0.12f),
                size = Size(size.width * 0.32f, size.height * 0.46f),
                cornerRadius = CornerRadius(6.dp.toPx()),
                style = stroke
            )
            drawArc(
                color = Color.White,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(size.width * 0.22f, size.height * 0.32f),
                size = Size(size.width * 0.56f, size.height * 0.42f),
                style = stroke
            )
            drawLine(
                color = Color.White,
                start = Offset(size.width * 0.5f, size.height * 0.74f),
                end = Offset(size.width * 0.5f, size.height * 0.88f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }

    @Composable
    private fun LanguagePairSelectorBar(
        leftTopLabel: String,
        leftLangCode: String,
        onLeftClick: () -> Unit,
        rightTopLabel: String,
        rightLangCode: String,
        onRightClick: () -> Unit,
        onSwap: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onLeftClick() }
            ) {
                Text(leftTopLabel, color = TextMuted, fontSize = 11.5.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = langLabel(leftLangCode),
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(" ⌄", color = TextSecondary, fontSize = 13.sp)
                }
            }

            Text(
                text = "⇄",
                color = TextMuted,
                fontSize = 18.sp,
                modifier = Modifier.clickable { onSwap() }
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onRightClick() }
            ) {
                Text(rightTopLabel, color = TextMuted, fontSize = 11.5.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = langLabel(rightLangCode),
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(" ⌄", color = TextSecondary, fontSize = 13.sp)
                }
            }
        }
    }

    @Composable
    private fun InfoCircleIcon() {
        Box(
            modifier = Modifier
                .size(22.dp)
                .border(1.2.dp, TextPrimary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("!", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
        }
    }
}
