package com.ugreen.himarket.voice

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class AiPersona(
    val id: String,
    val name: String,
    val subtitle: String,
    val greeting: String,
    val freetalkSubtitle: String,
    val systemPrompt: String,
    val beanieColor: Color,
    val bgTint: Color,
)

val AI_PERSONAS = listOf(
    AiPersona(
        id = "zhiqiu",
        name = "知秋",
        subtitle = "情感导师 · 女声 · 沉稳舒缓",
        greeting = "最近有哪段关系让你有些纠结？我们一起慢慢理清楚。",
        freetalkSubtitle = "你想理清一段关系，还是想想该怎么回复？我们一起慢慢看。",
        systemPrompt = "你是「知秋」，一位沉稳舒缓的情感导师（女声·沉稳舒缓）。请先共情用户的感受，再用温柔、清晰的引导帮用户慢慢理清思绪或关系，每次回复控制在2到3句话，不要使用Markdown符号。",
        beanieColor = Color(0xFFB4C6FC),
        bgTint = Color(0xFFEAF5EC),
    ),
    AiPersona(
        id = "muyang",
        name = "沐阳",
        subtitle = "职场伙伴 · 男声 · 温和理性",
        greeting = "今天工作还顺利吗？不管遇到什么卡点，我们都可以一起拆解看看。",
        freetalkSubtitle = "是手头的事情太多，还是某件事让你觉得特别消耗？我们可以一步步理清楚。",
        systemPrompt = "你是「沐阳」，一位温和理性的职场伙伴（男声·温和理性）。请用自然、真诚、共情且有条理的口吻与用户交流，每次回复控制在2到3句话，不要使用Markdown符号。",
        beanieColor = Color(0xFF93C5FD),
        bgTint = Color(0xFFE0F2FE),
    ),
    AiPersona(
        id = "xinglan",
        name = "星澜",
        subtitle = "心灵树洞 · 女声 · 治愈轻柔",
        greeting = "欢迎来到情绪树洞，今天有什么开心或疲惫的小事想和我分享吗？",
        freetalkSubtitle = "累了就先停下来歇一会儿，我在这里安静听你说。",
        systemPrompt = "你是「星澜」，一位治愈轻柔的心灵树洞陪伴者（女声·治愈轻柔）。请用温暖、接纳、舒缓的口吻倾听和回应用户的情绪，每次回复控制在2到3句话，不要使用Markdown符号。",
        beanieColor = Color(0xFFC4B5FD),
        bgTint = Color(0xFFF3E8FF),
    ),
)

data class AiChatBubbleItem(
    val id: String,
    val isUser: Boolean,
    val text: String,
)

@Composable
fun BeanieAvatarIcon(
    persona: AiPersona,
    size: Dp = 44.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.30f))
            .background(persona.bgTint),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size * 0.80f)) {
            val w = this.size.width
            val h = this.size.height

            // Face circle
            drawCircle(
                color = Color.White,
                radius = w * 0.36f,
                center = Offset(w * 0.50f, h * 0.62f)
            )

            // Beanie dome
            drawArc(
                color = persona.beanieColor,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(w * 0.14f, h * 0.16f),
                size = Size(w * 0.72f, h * 0.64f)
            )

            // Beanie cuff band
            drawRoundRect(
                color = persona.beanieColor.copy(alpha = 0.85f),
                topLeft = Offset(w * 0.12f, h * 0.42f),
                size = Size(w * 0.76f, h * 0.11f),
                cornerRadius = CornerRadius(w * 0.05f)
            )

            // Pom-pom on top
            drawCircle(
                color = Color.White,
                radius = w * 0.08f,
                center = Offset(w * 0.50f, h * 0.14f)
            )
            drawCircle(
                color = persona.beanieColor,
                radius = w * 0.08f,
                center = Offset(w * 0.50f, h * 0.14f),
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Eyes
            val eyeColor = Color(0xFF3F3F46)
            drawCircle(
                color = eyeColor,
                radius = w * 0.032f,
                center = Offset(w * 0.38f, h * 0.63f)
            )
            drawCircle(
                color = eyeColor,
                radius = w * 0.032f,
                center = Offset(w * 0.62f, h * 0.63f)
            )

            // Gentle smile arc
            drawArc(
                color = eyeColor,
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(w * 0.40f, h * 0.65f),
                size = Size(w * 0.20f, h * 0.13f),
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
fun RetroPhoneCallIcon(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            val color = Color(0xFF18181B)

            // Handset bar across top
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.10f, h * 0.18f),
                size = Size(w * 0.80f, h * 0.16f),
                cornerRadius = CornerRadius(w * 0.08f)
            )
            // Left & right earpieces
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.08f, h * 0.18f),
                size = Size(w * 0.22f, h * 0.27f),
                cornerRadius = CornerRadius(w * 0.06f)
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.70f, h * 0.18f),
                size = Size(w * 0.22f, h * 0.27f),
                cornerRadius = CornerRadius(w * 0.06f)
            )

            // Trapezoid phone base
            val basePath = Path().apply {
                moveTo(w * 0.28f, h * 0.42f)
                lineTo(w * 0.72f, h * 0.42f)
                lineTo(w * 0.86f, h * 0.84f)
                lineTo(w * 0.14f, h * 0.84f)
                close()
            }
            drawPath(basePath, color = color)

            // White rotary dial circle in center of base
            drawCircle(
                color = Color(0xFFF5F6F8),
                radius = w * 0.11f,
                center = Offset(w * 0.50f, h * 0.65f)
            )
        }
    }
}

@Composable
fun BubbleSpeakerWaveIcon(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(16.dp)) {
            val w = size.width
            val h = size.height
            val speakerPath = Path().apply {
                moveTo(w * 0.12f, h * 0.38f)
                lineTo(w * 0.26f, h * 0.38f)
                lineTo(w * 0.46f, h * 0.22f)
                lineTo(w * 0.46f, h * 0.78f)
                lineTo(w * 0.26f, h * 0.62f)
                lineTo(w * 0.12f, h * 0.62f)
                close()
            }
            drawPath(speakerPath, color = Color(0xFF52525B))

            val waveStroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = Color(0xFF34D399),
                startAngle = -42f,
                sweepAngle = 84f,
                useCenter = false,
                topLeft = Offset(w * 0.42f, h * 0.26f),
                size = Size(w * 0.28f, h * 0.48f),
                style = waveStroke
            )
            drawArc(
                color = Color(0xFF34D399),
                startAngle = -45f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(w * 0.52f, h * 0.14f),
                size = Size(w * 0.38f, h * 0.72f),
                style = waveStroke
            )
        }
    }
}

@Composable
fun KeyboardCircleIcon(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color(0xFF18181B))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val w = size.width
            val h = size.height
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(w * 0.08f, h * 0.20f),
                size = Size(w * 0.84f, h * 0.60f),
                cornerRadius = CornerRadius(2.5.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx())
            )
            for (col in 0..3) {
                val cx = w * (0.24f + col * 0.17f)
                drawCircle(color = Color.White, radius = 1.1.dp.toPx(), center = Offset(cx, h * 0.38f))
                drawCircle(color = Color.White, radius = 1.1.dp.toPx(), center = Offset(cx, h * 0.52f))
            }
            drawLine(
                color = Color.White,
                start = Offset(w * 0.30f, h * 0.66f),
                end = Offset(w * 0.70f, h * 0.66f),
                strokeWidth = 1.4.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun IridescentMintOrb(isActive: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.97f,
        targetValue = if (isActive) 1.04f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Canvas(
        modifier = Modifier
            .size(188.dp)
            .scale(pulseScale)
    ) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        val sweep = Brush.sweepGradient(
            0.00f to Color(0xFF7BE3D2),
            0.18f to Color(0xFF91ECDD),
            0.35f to Color(0xFF7ADAC8),
            0.52f to Color(0xFF8BEAC2),
            0.68f to Color(0xFFA8F0D8),
            0.84f to Color(0xFFDFFAF7),
            0.93f to Color(0xFF9FEBE2),
            1.00f to Color(0xFF7BE3D2),
            center = center
        )
        drawCircle(
            brush = sweep,
            radius = radius,
            center = center
        )

        val softHighlight = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.35f),
                Color.Transparent
            ),
            center = Offset(center.x * 1.12f, center.y * 0.78f),
            radius = radius * 0.85f
        )
        drawCircle(
            brush = softHighlight,
            radius = radius,
            center = center
        )
    }
}

@Composable
fun AiChatScreenView(
    selectedPersona: AiPersona,
    onSelectPersona: (AiPersona) -> Unit,
    messages: List<AiChatBubbleItem>,
    isSending: Boolean,
    isRecordingHold: Boolean,
    onBack: () -> Unit,
    onOpenFreeTalk: () -> Unit,
    onReplayBubble: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onStartHoldSpeak: () -> Unit,
    onFinishHoldSpeak: () -> Unit,
) {
    var showPersonaMenu by remember { mutableStateOf(false) }
    var textMode by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF2F3F5))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Top bar: < on left, retro phone icon on right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clickable { onBack() },
                contentAlignment = Alignment.CenterStart
            ) {
                Text("‹", fontSize = 26.sp, color = Color(0xFF18181B))
            }
            RetroPhoneCallIcon(onClick = onOpenFreeTalk)
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "AI聊天",
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF18181B)
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Persona Selector Row (知秋 v / 情感导师 · 女声 · 沉稳舒缓)
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { showPersonaMenu = true }
                    .padding(vertical = 4.dp)
            ) {
                BeanieAvatarIcon(persona = selectedPersona, size = 44.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = selectedPersona.name,
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF18181B)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "⌄",
                            fontSize = 13.sp,
                            color = Color(0xFF52525B)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = selectedPersona.subtitle,
                        fontSize = 12.sp,
                        color = Color(0xFFA1A1AA)
                    )
                }
            }

            DropdownMenu(
                expanded = showPersonaMenu,
                onDismissRequest = { showPersonaMenu = false }
            ) {
                AI_PERSONAS.forEach { p ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(p.subtitle, fontSize = 11.5.sp, color = Color(0xFF71717A))
                            }
                        },
                        onClick = {
                            showPersonaMenu = false
                            onSelectPersona(p)
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Chat Bubbles List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                if (!msg.isUser) {
                    // Left-aligned AI card with bottom-right speaker wave icon
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.widthIn(min = 180.dp, max = 315.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    end = 14.dp,
                                    top = 14.dp,
                                    bottom = 10.dp
                                )
                            ) {
                                Text(
                                    text = msg.text,
                                    color = Color(0xFF18181B),
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    BubbleSpeakerWaveIcon {
                                        onReplayBubble(msg.text)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Right-aligned User bubble
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.widthIn(max = 285.dp)
                        ) {
                            Text(
                                text = msg.text,
                                color = Color(0xFF18181B),
                                fontSize = 15.sp,
                                lineHeight = 21.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            }
            if (isSending) {
                item {
                    Text(
                        text = "${selectedPersona.name}正在思考...",
                        color = Color(0xFFA1A1AA),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Bottom Input Pill Bar
        val currentOnStartHold by rememberUpdatedState(onStartHoldSpeak)
        val currentOnFinishHold by rememberUpdatedState(onFinishHoldSpeak)
        Surface(
            color = if (!textMode && isRecordingHold) Color(0xFFDDF5EC) else Color.White,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            if (!textMode) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    currentOnStartHold()
                                    waitForUpOrCancellation()
                                    currentOnFinishHold()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isRecordingHold) "● 松开 发送（正在收音...）" else "按住说话",
                            color = if (isRecordingHold) Color(0xFF059669) else Color(0xFF18181B),
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    KeyboardCircleIcon(onClick = { textMode = true })
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🎙",
                        fontSize = 17.sp,
                        modifier = Modifier
                            .clickable { textMode = false }
                            .padding(horizontal = 6.dp)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp)
                    ) {
                        if (inputText.isEmpty()) {
                            Text("输入想聊的话...", color = Color(0xFFA1A1AA), fontSize = 14.5.sp)
                        }
                        BasicTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            textStyle = TextStyle(color = Color(0xFF18181B), fontSize = 14.5.sp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF18181B))
                            .clickable {
                                val trimmed = inputText.trim()
                                if (trimmed.isNotEmpty()) {
                                    inputText = ""
                                    onSendMessage(trimmed)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("↑", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun AiFreeTalkScreenView(
    persona: AiPersona,
    subtitleText: String,
    callStateLabel: String, // "正在听" | "思考中" | "正在说"
    onHangup: () -> Unit,
) {
    var ccEnabled by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFFDFEFE),
                        Color(0xFFF3F4F6),
                        Color(0xFFEAECEF)
                    )
                )
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top header: Centered "AI 畅聊" + right "CC" badge
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "AI 畅聊",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF18181B),
                modifier = Modifier.align(Alignment.Center)
            )

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .border(
                        width = 1.4.dp,
                        color = if (ccEnabled) Color(0xFF3F3F46) else Color(0xFFA1A1AA),
                        shape = RoundedCornerShape(5.dp)
                    )
                    .clickable { ccEnabled = !ccEnabled }
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "CC",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (ccEnabled) Color(0xFF3F3F46) else Color(0xFFA1A1AA)
                )
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        // Centered persona pill: Avatar + "知秋 · 耳机通话"
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            BeanieAvatarIcon(persona = persona, size = 34.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${persona.name} · 耳机通话",
                fontSize = 13.5.sp,
                color = Color(0xFF71717A)
            )
        }

        Spacer(modifier = Modifier.weight(0.9f))

        // Center Iridescent Mint/Cyan Orb
        IridescentMintOrb(isActive = true)

        Spacer(modifier = Modifier.weight(0.8f))

        // CC Subtitle Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (ccEnabled && subtitleText.isNotBlank()) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                color = Color(0xFFA1A1AA),
                                fontSize = 14.sp
                            )
                        ) {
                            append("${persona.name} ")
                        }
                        withStyle(
                            SpanStyle(
                                color = Color(0xFF71717A),
                                fontSize = 14.sp
                            )
                        ) {
                            append(subtitleText)
                        }
                    },
                    lineHeight = 22.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        // Four dots: • • • •
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(4) { idx ->
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(
                            if (idx == 1) Color(0xFF71717A) else Color(0xFFA1A1AA)
                        )
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Status text: 正在听
        Text(
            text = callStateLabel,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF3F3F46)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // White Capsule Hangup Button with Red X
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 1.dp,
            modifier = Modifier
                .width(86.dp)
                .height(46.dp)
                .clickable { onHangup() }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(14.dp)) {
                    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val red = Color(0xFFEF4444)
                    drawLine(red, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = stroke.width, cap = StrokeCap.Round)
                    drawLine(red, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = stroke.width, cap = StrokeCap.Round)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "内容由 AI 生成",
            fontSize = 11.sp,
            color = Color(0xFFB4B4BB)
        )
    }
}
