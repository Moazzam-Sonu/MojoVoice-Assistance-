package com.mojo.app.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mojo.app.ui.mascot.MojoMascot
import com.mojo.app.ui.mascot.MojoState
import com.mojo.app.ui.theme.Aurora

@Composable
fun HomeScreen(
    running: Boolean,
    state: MojoState,
    heard: String,
    reply: String,
    wakeTrained: Boolean,
    accessibilityOn: Boolean,
    batteryUnrestricted: Boolean,
    onToggle: () -> Unit,
    onWakeWord: () -> Unit,
    onSettings: () -> Unit,
    onAccessibility: () -> Unit,
    onBattery: () -> Unit,
) {
    val mascotState = if (running) state else MojoState.SLEEP

    // Aurora glow behind MOJO brightens while it listens or talks
    val glow by animateFloatAsState(
        when (mascotState) {
            MojoState.LISTENING, MojoState.SPEAKING -> 1f
            MojoState.SLEEP -> 0.35f
            else -> 0.65f
        }, tween(600), label = "glow")

    Box(
        Modifier
            .fillMaxSize()
            .background(Aurora.Bg)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height * 0.30f)
            drawRect(
                Brush.radialGradient(
                    0f to Aurora.Cyan.copy(alpha = 0.20f * glow),
                    0.45f to Aurora.Violet.copy(alpha = 0.12f * glow),
                    1f to Color.Transparent,
                    center = c,
                    radius = size.width * 0.95f,
                )
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(running)

            MojoMascot(
                state = mascotState,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(0.88f)
                    .widthIn(max = 360.dp)
                    .aspectRatio(320f / 300f),
            )

            StatusLine(mascotState, running, wakeTrained, reply)

            AnimatedVisibility(
                visible = heard.isNotBlank() && running,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Text(
                    text = "“$heard”",
                    color = Aurora.TextSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Aurora.Card)
                        .border(1.dp, Aurora.Border, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }

            Spacer(Modifier.height(22.dp))

            AnimatedVisibility(visible = !wakeTrained, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                HintCard(
                    title = "Teach MOJO your wake word",
                    body = "Say your word 3 times so MOJO knows when to listen.",
                    action = "Set wake word",
                    onClick = onWakeWord,
                    warning = false,
                )
            }
            AnimatedVisibility(visible = !accessibilityOn, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                HintCard(
                    title = "Turn on Accessibility",
                    body = "MOJO needs it to open apps, make calls, go home or back, scroll and take screenshots. " +
                        "If Android says \u201CRestricted setting\u201D: open App info for MOJO \u2192 \u22EE menu \u2192 " +
                        "Allow restricted settings, then try again.",
                    action = "Enable",
                    onClick = onAccessibility,
                    warning = true,
                )
            }

            AnimatedVisibility(visible = !batteryUnrestricted, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                HintCard(
                    title = "Keep MOJO awake",
                    body = "Some phones stop background apps to save battery. Allow MOJO to keep listening.",
                    action = "Allow",
                    onClick = onBattery,
                    warning = true,
                )
            }

            PrimaryButton(
                text = if (running) "Pause MOJO" else "Start MOJO",
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SecondaryButton("Wake word", onWakeWord, Modifier.weight(1f))
                SecondaryButton("Settings", onSettings, Modifier.weight(1f))
            }

            Text(
                text = "Try: “open YouTube” · “volume up” · “flashlight on”",
                color = Aurora.TextMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun Header(running: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "MOJO",
            color = Aurora.Text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 8.sp,
        )
        StatusPill(running)
    }
}

@Composable
private fun StatusPill(running: Boolean) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "dot",
    )
    val dot by animateColorAsState(if (running) Aurora.Green else Aurora.TextMuted, tween(400), label = "dotColor")
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Aurora.Card)
            .border(1.dp, Aurora.Border, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dot.copy(alpha = if (running) pulse else 1f))
        )
        Text(
            text = if (running) "Active" else "Paused",
            color = Aurora.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun StatusLine(state: MojoState, running: Boolean, wakeTrained: Boolean, reply: String) {
    val line = when {
        !running -> "MOJO is resting"
        state == MojoState.GREETING -> "Yes?"
        state == MojoState.LISTENING -> "Listening…"
        state == MojoState.THINKING -> "Thinking…"
        state == MojoState.SPEAKING || state == MojoState.SUCCESS || state == MojoState.FOCUS ->
            reply.ifBlank { "Done" }
        !wakeTrained -> "Set a wake word to begin"
        else -> "Say your wake word"
    }
    AnimatedContent(
        targetState = line,
        transitionSpec = {
            (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 3 }) togetherWith
                (fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 3 })
        },
        label = "status",
    ) { text ->
        Text(
            text = text,
            color = Aurora.Text,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().heightIn(min = 30.dp),
        )
    }
}

@Composable
private fun HintCard(title: String, body: String, action: String, onClick: () -> Unit, warning: Boolean) {
    val accent = if (warning) Aurora.WarningText else Aurora.Cyan
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (warning) Aurora.WarningBg else Aurora.Card)
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Text(title, color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(body, color = Aurora.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        Text(
            text = "$action →",
            color = accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp, horizontal = 2.dp),
        )
    }
}

/** Springy press feedback shared by both buttons. */
@Composable
private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed) 0.95f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return s
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .scale(pressScale(source))
            .height(58.dp)
            .clip(RoundedCornerShape(29.dp))
            .background(Brush.horizontalGradient(listOf(Aurora.Cyan, Aurora.Violet)))
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = text,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
            label = "primary",
        ) { label ->
            Text(label, color = Aurora.OnCyan, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .scale(pressScale(source))
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Aurora.Card)
            .border(1.dp, Aurora.Border, RoundedCornerShape(26.dp))
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Aurora.Cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
