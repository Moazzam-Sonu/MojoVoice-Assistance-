package com.mojo.app.ui.mascot

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private const val IDLE_READ_MS = 30_000L
private const val IDLE_SLEEP_MS = 60_000L
private const val HIT_WINDOW_MS = 2_000L
private const val CALM_DOWN_MS = 2_500L

private enum class Reaction { NONE, POKE, ANGRY }

/** Hand position in viewBox units: centre, scale and opacity. */
private data class HandPose(val x: Float, val y: Float, val s: Float, val a: Float)

/** Per-frame motion that is smoothed in the frame loop (not Compose state). */
private class Motion {
    var gazeX = 0f
    var gazeY = 0f
    var headX = 0f
    var headY = 0f
}

private val PartEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * The MOJO mascot — a Compose port of the React MojoMascot.
 *
 * Tap MOJO to poke it (three quick taps make it angry). Drag a finger near it
 * and it follows with its eyes and head. Left alone it reads, then dozes off.
 */
@Composable
fun MojoMascot(
    state: MojoState,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    val reduceMotion = rememberReduceMotion()
    val currentState by rememberUpdatedState(state)   // the frame loop starts once; read the latest state

    // ── Clock: one frame-synced time value; reading it only redraws the canvas ──
    val clock = remember { mutableFloatStateOf(0f) }
    val motion = remember { Motion() }
    var pointer by remember { mutableStateOf<Offset?>(null) }   // in viewBox units
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // ── Idle mood: reading after 30 s, asleep after 60 s without interaction ──
    var lastInteraction by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var mood by remember { mutableStateOf<MojoState?>(null) }
    val canDoze = interactive && state.canDoze && !reduceMotion
    LaunchedEffect(canDoze, lastInteraction) {
        mood = null
        if (!canDoze) return@LaunchedEffect
        delay(IDLE_READ_MS)
        mood = MojoState.READING
        delay(IDLE_SLEEP_MS - IDLE_READ_MS)
        mood = MojoState.SLEEP
    }
    val shown = mood ?: state

    // ── Poke / angry reaction ──
    val scope = rememberCoroutineScope()
    var reaction by remember { mutableStateOf(Reaction.NONE) }
    val hits = remember { ArrayList<Long>() }
    val reactProgress = remember { Animatable(1f) }
    val calmJob = remember { arrayOfNulls<Job>(1) }
    fun poke() {
        val now = SystemClock.uptimeMillis()
        lastInteraction = now
        hits.removeAll { now - it >= HIT_WINDOW_MS }
        hits.add(now)
        while (hits.size > 3) hits.removeAt(0)
        val angry = reaction == Reaction.ANGRY || hits.size >= 3
        reaction = if (angry) Reaction.ANGRY else Reaction.POKE
        calmJob[0]?.cancel()
        if (!reduceMotion) scope.launch {
            reactProgress.snapTo(0f)
            reactProgress.animateTo(1f, tween(if (angry) 480 else 550, easing = LinearEasing))
        }
        calmJob[0] = scope.launch {
            delay(if (angry) CALM_DOWN_MS else 750L)
            reaction = Reaction.NONE
            if (angry) hits.clear()
        }
    }

    // ── Blink: every 3–7 s, sometimes twice ──
    var blinking by remember { mutableStateOf(false) }
    val canBlink = !reduceMotion && shown != MojoState.SLEEP &&
        shown != MojoState.LISTENING && shown != MojoState.FOCUS
    LaunchedEffect(canBlink) {
        blinking = false
        if (!canBlink) return@LaunchedEffect
        while (true) {
            delay(3_000L + Random.nextLong(4_000L))
            val twice = Random.nextFloat() < 0.18f
            blinking = true; delay(110); blinking = false
            if (twice) { delay(160); blinking = true; delay(110); blinking = false }
        }
    }
    val blinkScale by animateFloatAsState(if (blinking) 0.08f else 1f, tween(85), label = "blink")

    // ── One-shot timelines started when a state begins ──
    val celebrate = remember { Animatable(1f) }   // greeting marks / success sparkles
    val bounce = remember { Animatable(1f) }      // success & excited hop
    LaunchedEffect(shown) {
        val celebrateMs = when (shown) {
            MojoState.GREETING -> 2_800
            MojoState.SUCCESS -> 2_400
            else -> 0
        }
        if (celebrateMs > 0 && !reduceMotion) {
            launch { celebrate.snapTo(0f); celebrate.animateTo(1f, tween(celebrateMs, easing = LinearEasing)) }
        }
        if (!reduceMotion && shown == MojoState.SUCCESS) {
            bounce.snapTo(0f)
            bounce.animateTo(1f, tween(850, easing = LinearEasing))
        } else if (!reduceMotion && shown == MojoState.EXCITED) {
            repeat(2) {
                bounce.snapTo(0f)
                bounce.animateTo(1f, tween(650, easing = LinearEasing))
            }
        }
    }

    // ── Frame loop: advances the clock and smooths gaze/head tracking ──
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        val start = withFrameNanos { it }
        var last = start
        while (true) {
            withFrameNanos { now ->
                val t = (now - start) / 1_000_000_000f
                val dtMs = ((now - last) / 1_000_000f).coerceIn(0f, 64f)
                last = now
                clock.floatValue = t

                val s = mood ?: currentState
                val follow = s.followsPointer && reaction != Reaction.ANGRY
                val p = pointer
                var tx = 0f
                var ty = 0f
                if (follow && p != null) {
                    val dx = (p.x - 160f) / 160f
                    val dy = (p.y - 171f) / 150f
                    val len = max(1f, hypot(dx, dy))
                    tx = dx / len * 8f
                    ty = dy / len * 5f
                } else if (follow) {
                    // On a phone there's no hover, so let MOJO glance around on its own.
                    tx = 3.2f * sin(t * 0.47f) + 1.2f * sin(t * 1.3f)
                    ty = 1.6f * sin(t * 0.71f)
                }
                val mix = 1f - exp(-dtMs / 75f)
                val headMix = 1f - exp(-dtMs / 125f)
                motion.gazeX += (tx - motion.gazeX) * mix
                motion.gazeY += (ty - motion.gazeY) * mix
                motion.headX += (tx * 0.9f - motion.headX) * headMix
                motion.headY += (ty * 0.8f - motion.headY) * headMix
            }
        }
    }

    // ── Eye shapes ──
    val angry = reaction == Reaction.ANGRY
    val happy = reaction == Reaction.POKE ||
        (reaction == Reaction.NONE && (shown == MojoState.GREETING || shown == MojoState.SUCCESS || shown == MojoState.EXCITED))
    val closed = !happy && !angry && (shown == MojoState.SLEEP || shown == MojoState.LISTENING)
    val oval = !happy && !angry && !closed
    val aOval = fade(oval, 200)
    val aHappy = fade(happy, 200)
    val aClosed = fade(closed, 200)
    val aAngry = fade(angry, 200)

    // Eye direction (looking down at keyboard/book, up while thinking)
    val dirX by animateFloatAsState(
        if (!angry && shown == MojoState.THINKING) 5f else 0f, tween(350), label = "dirX")
    val dirY by animateFloatAsState(
        when {
            angry -> 0f
            shown == MojoState.TYPING || shown == MojoState.READING -> 7f
            shown == MojoState.THINKING -> -6f
            else -> 0f
        }, tween(350), label = "dirY")

    // ── Accessories ──
    val calmParts = !angry
    val aKeyboard = fade(calmParts && shown == MojoState.TYPING)
    val aBook = fade(calmParts && shown == MojoState.READING)
    val aDots = fade(calmParts && (shown == MojoState.THINKING || shown == MojoState.TYPING))
    val aWaves = fade(calmParts && (shown == MojoState.LISTENING || shown == MojoState.SPEAKING))
    val aSleep = fade(calmParts && shown == MojoState.SLEEP)
    val aHalo = fade(calmParts && shown == MojoState.FOCUS)
    val aMarksExcited = fade(calmParts && shown == MojoState.EXCITED)
    val aAnger = fade(angry, 180)

    val left = animatedPose(leftHandPose(shown, angry))
    val right = animatedPose(rightHandPose(shown, angry))

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .semantics {
                contentDescription = "MOJO"
                if (interactive) role = Role.Button
            }
            .then(
                if (!interactive) Modifier else Modifier
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { pos ->
                                pointer = toView(pos, canvasSize)
                                lastInteraction = SystemClock.uptimeMillis()
                                tryAwaitRelease()
                                pointer = null
                            },
                            onTap = { poke() },
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { pos -> pointer = toView(pos, canvasSize) },
                            onDragEnd = { pointer = null },
                            onDragCancel = { pointer = null },
                            onDrag = { change, _ ->
                                pointer = toView(change.position, canvasSize)
                                lastInteraction = SystemClock.uptimeMillis()
                            },
                        )
                    }
            )
    ) {
        val t = clock.floatValue
        val scale = min(size.width / MojoArt.VIEW_W, size.height / MojoArt.VIEW_H)
        val offX = (size.width - MojoArt.VIEW_W * scale) / 2f
        val offY = (size.height - MojoArt.VIEW_H * scale) / 2f

        // Looping CSS keyframes, as 0..1 "breath" values
        val floatPeriod = when (shown) {
            MojoState.THINKING -> 8f
            MojoState.SLEEP -> 10f
            else -> 6f
        }
        val still = reduceMotion || shown == MojoState.FOCUS
        val floatK = if (still) 0f else breath(t, floatPeriod)
        val speakAmp = if (shown == MojoState.SPEAKING && !reduceMotion) speechAmp(t) else 0f
        val light = when {
            shown == MojoState.SUCCESS || shown == MojoState.EXCITED || shown == MojoState.FOCUS || reduceMotion -> 1f
            shown == MojoState.SLEEP -> 0.4f
            shown == MojoState.LISTENING -> 0.65f + 0.35f * breath(t, 1.8f)
            shown == MojoState.SPEAKING -> 0.55f + 0.45f * speakAmp
            else -> 0.75f + 0.25f * breath(t, 6f)
        }

        withTransform({
            translate(offX, offY)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            // Ground shadow
            withTransform({ scale(1f - 0.09f * floatK, 1f, pivot = Offset(160f, 273f)) }) {
                withTransform({ scale(1f, 15f / 82f, pivot = Offset(160f, 273f)) }) {
                    drawCircle(MojoArt.shadowBrush, radius = 82f, center = Offset(160f, 273f), alpha = 1f - 0.35f * floatK)
                }
            }

            // Head tracking → poke reaction → bounce → float
            val pokeP = reactProgress.value
            withTransform({
                translate(motion.headX, motion.headY)
                rotate(motion.headX / 1.8f, pivot = Offset(160f, 170f))
                if (reaction == Reaction.ANGRY && pokeP < 1f) {
                    translate(kf(pokeP, 0f, -4f, 4f, -3f, 2f, 0f), 0f)
                    rotate(kf(pokeP, 0f, -3f, 3f, -2f, 1f, 0f), pivot = Offset(160f, 170f))
                } else if (reaction == Reaction.POKE && pokeP < 1f) {
                    translate(0f, kf(pokeP, 0f, 3f, -7f, 1f, 0f))
                    scale(kf(pokeP, 1f, 1.055f, 0.98f, 1.01f, 1f), kf(pokeP, 1f, 0.94f, 1.025f, 0.99f, 1f), pivot = Offset(160f, 170f))
                }
                val b = bounce.value
                if (b < 1f) {
                    translate(0f, bounceY(b))
                    val sq = if (b < 0.7f) kf(b / 0.7f, 0f, 1f, 0f) else 0f
                    scale(1f + 0.015f * sq, 1f - 0.015f * sq, pivot = Offset(160f, 200f))
                }
                translate(0f, 1f - 5f * floatK - 2f * speakAmp)
                scale(1f + 0.006f * floatK, 1f - 0.006f * floatK, pivot = Offset(160f, 170f))
            }) {
                if (aHalo > 0.01f) drawHalo(aHalo * 0.5f)
                drawFace(light)

                // Eyes: gaze → direction → reading scan → blink
                val scan = if (shown == MojoState.READING && !angry && !reduceMotion) 0.5f - 3.5f * cos(2f * PI.toFloat() * t / 3.8f) else 0f
                withTransform({
                    translate(motion.gazeX + dirX + scan, motion.gazeY + dirY)
                    scale(1f, blinkScale, pivot = Offset(160f, 173f))
                }) {
                    drawEyes(aOval, aHappy, aClosed, aAngry)
                }

                // Keyboard & book
                if (aKeyboard > 0.01f) withTransform({ translate(0f, 8f * (1f - aKeyboard)) }) {
                    drawPath(MojoArt.keyboard, MojoArt.keyboardBrush, alpha = aKeyboard)
                    drawPath(MojoArt.keyboard, Color(0xFF9BD7FF), alpha = aKeyboard, style = MojoArt.stroke1_5)
                    drawPath(MojoArt.keyboardKeys, Color(0xFFBCE5FF), alpha = aKeyboard * 0.7f, style = MojoArt.strokeKeys)
                }
                if (aBook > 0.01f) {
                    val pageK = if (reduceMotion) 0f else breath(t, 4f)
                    withTransform({
                        translate(0f, 8f * (1f - aBook) - pageK)
                        rotate(-1f * pageK, pivot = Offset(160f, 245f))
                    }) {
                        drawPath(MojoArt.book, MojoArt.bookBrush, alpha = aBook)
                        drawPath(MojoArt.book, Color(0xFFA1EAFF), alpha = aBook, style = MojoArt.stroke1_5)
                        drawPath(MojoArt.bookLines, Color(0xFFC2EEFF), alpha = aBook * 0.7f, style = MojoArt.stroke1_5)
                    }
                }

                // Hands
                val typing = shown == MojoState.TYPING && !angry && !reduceMotion
                val leftLift = if (typing) -4f * breath(t, 0.48f) else 0f
                val rightLift = if (typing) -4f * breath(t + 0.24f, 0.48f) else 0f
                val celebrateA = celebrateAlpha(celebrate.value, reduceMotion)
                val successHands = shown == MojoState.SUCCESS && !angry
                drawHand(left, 0f, leftLift, if (successHands) left.a * celebrateA else left.a)
                var waveRot = 0f
                var waveLift = 0f
                if (shown == MojoState.GREETING && !angry && !reduceMotion) {
                    val e = celebrate.value * 2_800f - 250f
                    if (e in 0f..2_200f) {
                        val k = breath(e / 1000f, 0.55f)
                        waveRot = -12f + 27f * k
                        waveLift = -4f * k
                    }
                }
                drawHand(right, waveRot, rightLift + waveLift, if (successHands) right.a * celebrateA else right.a)

                // Thinking / typing dots
                if (aDots > 0.01f) {
                    listOf(256f, 270f, 284f).forEachIndexed { i, x ->
                        val k = if (reduceMotion) 1f else breath(t - i * 0.18f, 1.5f)
                        glowDot(Offset(x, 239f), 3f, MojoArt.dots, aDots * (0.3f + 0.7f * k))
                    }
                }

                // Listening / speaking sound waves
                if (aWaves > 0.01f) {
                    val period = if (shown == MojoState.SPEAKING) 0.9f else 1.8f
                    listOf(MojoArt.waveInner, MojoArt.waveOuter).forEachIndexed { i, path ->
                        val k = if (reduceMotion) 1f else breath(t - i * 0.25f, period)
                        val extra = if (shown == MojoState.SPEAKING) 0.3f * speakAmp else 0f
                        withTransform({ scale(0.97f + 0.03f * k, 1f, pivot = Offset(160f, 168f)) }) {
                            glowPath(path, MojoArt.waves, 3f, aWaves * (0.3f + 0.7f * k + extra).coerceAtMost(1f))
                        }
                    }
                }

                // Greeting / excited marks
                val marksA = max(
                    if (shown == MojoState.GREETING && !angry) celebrateA else 0f,
                    aMarksExcited,
                )
                if (marksA > 0.01f) {
                    glowPath(MojoArt.marksRight, MojoArt.marks, 4f, marksA)
                    if (aMarksExcited > 0.01f) glowPath(MojoArt.marksLeft, MojoArt.marks, 4f, aMarksExcited)
                }

                // Success sparkles
                if (shown == MojoState.SUCCESS && !angry && celebrateA > 0.01f) {
                    MojoArt.sparkles.forEach { glowFill(it, MojoArt.sparkle, celebrateA) }
                }

                // Sleeping Zzz
                if (aSleep > 0.01f) {
                    MojoArt.zzz.forEachIndexed { i, z ->
                        val k = if (reduceMotion) 1f else breath(t - i * 0.5f, 4f)
                        withTransform({ translate(0f, 1f - 5f * k) }) {
                            drawPath(z, MojoArt.sleep, alpha = aSleep * 0.35f * (0.3f + 0.7f * k), style = Stroke(9f))
                            drawPath(z, MojoArt.sleep, alpha = aSleep * (0.3f + 0.7f * k), style = MojoArt.strokeZ)
                        }
                    }
                }

                // Anger mark
                if (aAnger > 0.01f) glowPath(MojoArt.angerMark, MojoArt.anger, 3.5f, aAnger)
            }
        }
    }
}

// ── Composable helpers ─────────────────────────────────────────────────────

@Composable
private fun fade(visible: Boolean, ms: Int = 300): Float {
    val v by animateFloatAsState(if (visible) 1f else 0f, tween(ms), label = "fade")
    return v
}

@Composable
private fun animatedPose(target: HandPose): HandPose {
    val x by animateFloatAsState(target.x, tween(400, easing = PartEasing), label = "handX")
    val y by animateFloatAsState(target.y, tween(400, easing = PartEasing), label = "handY")
    val s by animateFloatAsState(target.s, tween(400, easing = PartEasing), label = "handS")
    val a by animateFloatAsState(target.a, tween(300), label = "handA")
    return HandPose(x, y, s, a)
}

@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

private fun leftHandPose(s: MojoState, angry: Boolean): HandPose = when {
    angry -> HandPose(84f, 227f, 0.95f, 1f)
    s == MojoState.TYPING || s == MojoState.EXCITED -> HandPose(91f, 232f, 1f, 1f)
    s == MojoState.READING -> HandPose(94f, 246f, 0.9f, 1f)
    s == MojoState.THINKING -> HandPose(89f, 218f, 0.9f, 1f)
    s == MojoState.SUCCESS -> HandPose(80f, 226f, 1f, 1f)
    else -> HandPose(84f, 217f, 0.4f, 0f)
}

private fun rightHandPose(s: MojoState, angry: Boolean): HandPose = when {
    angry -> HandPose(236f, 227f, 0.95f, 1f)
    s == MojoState.GREETING -> HandPose(268f, 170f, 1f, 1f)
    s == MojoState.TYPING || s == MojoState.EXCITED -> HandPose(229f, 232f, 1f, 1f)
    s == MojoState.READING -> HandPose(226f, 246f, 0.9f, 1f)
    s == MojoState.SUCCESS -> HandPose(240f, 226f, 1f, 1f)
    else -> HandPose(236f, 217f, 0.4f, 0f)
}

/** Converts a touch position on the canvas into viewBox units. */
private fun toView(pos: Offset, size: IntSize): Offset {
    if (size.width == 0 || size.height == 0) return Offset(160f, 170f)
    val scale = min(size.width / MojoArt.VIEW_W, size.height / MojoArt.VIEW_H)
    val offX = (size.width - MojoArt.VIEW_W * scale) / 2f
    val offY = (size.height - MojoArt.VIEW_H * scale) / 2f
    return Offset((pos.x - offX) / scale, (pos.y - offY) / scale)
}

// ── Timing math ────────────────────────────────────────────────────────────

/** 0 → 1 → 0 over [period] seconds, eased like CSS ease-in-out. */
private fun breath(t: Float, period: Float): Float =
    (1f - cos(2f * PI.toFloat() * (t / period))) / 2f

/** Evenly spaced keyframes with smoothstep easing between them. */
private fun kf(p: Float, vararg v: Float): Float {
    if (v.size == 1) return v[0]
    val x = p.coerceIn(0f, 1f) * (v.size - 1)
    val i = x.toInt().coerceAtMost(v.size - 2)
    val f = x - i
    val e = f * f * (3f - 2f * f)
    return v[i] + (v[i + 1] - v[i]) * e
}

/** CSS "bounce": 0 → -8 at 40% → 1 at 70% → 0. */
private fun bounceY(p: Float): Float = when {
    p < 0.4f -> kf(p / 0.4f, 0f, -8f)
    p < 0.7f -> kf((p - 0.4f) / 0.3f, -8f, 1f)
    else -> kf((p - 0.7f) / 0.3f, 1f, 0f)
}

/** CSS "celebrate": fade in by 15%, hold until 70%, fade out. */
private fun celebrateAlpha(p: Float, reduceMotion: Boolean): Float = when {
    reduceMotion -> 1f
    p >= 1f -> 0f
    p < 0.15f -> p / 0.15f
    p < 0.7f -> 1f
    else -> (1f - p) / 0.3f
}

/** Syllable-like loudness while MOJO speaks (TTS gives no live audio level). */
private fun speechAmp(t: Float): Float {
    val syllable = max(0f, sin(t * 11f)) * (0.55f + 0.45f * sin(t * 3.1f))
    val gap = if (sin(t * 1.7f) > -0.6f) 1f else 0.15f
    return (syllable * gap + 0.08f * sin(t * 23f)).coerceIn(0f, 1f)
}

// ── Drawing ────────────────────────────────────────────────────────────────

/** SVG glow filter stand-in: a wide faint stroke under the crisp one. */
private fun DrawScope.glowPath(path: androidx.compose.ui.graphics.Path, color: Color, width: Float, alpha: Float) {
    if (alpha <= 0.01f) return
    drawPath(path, color, alpha = alpha * 0.3f, style = Stroke(width * 3f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    drawPath(path, color, alpha = alpha, style = Stroke(width, cap = androidx.compose.ui.graphics.StrokeCap.Round))
}

private fun DrawScope.glowFill(path: androidx.compose.ui.graphics.Path, color: Color, alpha: Float) {
    if (alpha <= 0.01f) return
    drawPath(path, color, alpha = alpha * 0.3f, style = Stroke(5f))
    drawPath(path, color, alpha = alpha)
}

private fun DrawScope.glowDot(center: Offset, r: Float, color: Color, alpha: Float) {
    drawCircle(color, radius = r * 2.2f, center = center, alpha = alpha * 0.3f)
    drawCircle(color, radius = r, center = center, alpha = alpha)
}

private fun DrawScope.drawHalo(alpha: Float) {
    withTransform({ rotate(-12f, pivot = Offset(160f, 74f)) }) {
        val tl = Offset(160f - 107f, 74f - 39f)
        val sz = Size(214f, 78f)
        drawOval(MojoArt.halo, topLeft = tl, size = sz, alpha = alpha * 0.3f, style = Stroke(7.5f))
        drawOval(MojoArt.halo, topLeft = tl, size = sz, alpha = alpha, style = Stroke(2.5f))
    }
}

private fun DrawScope.drawFace(light: Float) {
    // Headband
    drawPath(MojoArt.band, MojoArt.rimBrush, style = MojoArt.strokeBandOuter)
    drawPath(MojoArt.band, MojoArt.bandNavy, style = MojoArt.strokeBandInner)
    glowPath(MojoArt.bandLight, MojoArt.bandLightColor, 3f, light)

    // Head
    drawPath(MojoArt.head, MojoArt.faceBrush)
    drawPath(MojoArt.head, MojoArt.rimBrush, style = MojoArt.stroke2)
    drawPath(MojoArt.headShine, Color.White, alpha = 0.65f, style = MojoArt.stroke3Round)
    drawPath(MojoArt.chin, Color(0xFF69C8FF), alpha = 0.65f, style = MojoArt.stroke2)

    // Headphones: left, then mirrored right
    drawHeadphone(light)
    withTransform({ scale(-1f, 1f, pivot = Offset(160f, 0f)) }) { drawHeadphone(light) }

    // Headset microphone
    drawPath(MojoArt.micArm, Color(0xFF399EE9), style = MojoArt.stroke7Round)
    drawPath(MojoArt.micArm, MojoArt.navySolid, style = MojoArt.stroke4_5Round)
    withTransform({ rotate(-8f, pivot = Offset(201f, 217f)) }) {
        val tl = Offset(184f, 207f)
        val sz = Size(35f, 21f)
        drawRoundRect(MojoArt.micNavy, topLeft = tl, size = sz, cornerRadius = CornerRadius(10f))
        drawRoundRect(Color(0xFF43BFFF), topLeft = tl, size = sz, cornerRadius = CornerRadius(10f), style = MojoArt.stroke1_5)
        drawPath(MojoArt.micShine, Color(0xFFA6EAFF), alpha = 0.65f, style = MojoArt.stroke1Round)
        drawRoundRect(MojoArt.micLight, topLeft = Offset(189f, 212f), size = Size(25f, 10f), cornerRadius = CornerRadius(5f), alpha = light * 0.35f)
        drawRoundRect(MojoArt.micLight, topLeft = Offset(192f, 215f), size = Size(19f, 4f), cornerRadius = CornerRadius(2f), alpha = light)
    }
}

private fun DrawScope.drawHeadphone(light: Float) {
    val bandTl = Offset(56f, 111f)
    val bandSz = Size(23f, 111f)
    drawRoundRect(MojoArt.earBandNavy, topLeft = bandTl, size = bandSz, cornerRadius = CornerRadius(11.5f))
    drawRoundRect(Color(0xFF5789EF), topLeft = bandTl, size = bandSz, cornerRadius = CornerRadius(11.5f), style = MojoArt.stroke1)
    drawPath(MojoArt.earShell, MojoArt.earBrush)
    drawPath(MojoArt.earShell, Color(0xFFA1C6FF), style = MojoArt.stroke1)
    val cupTl = Offset(20f, 132f)
    val cupSz = Size(24f, 74f)
    drawOval(MojoArt.earCupNavy, topLeft = cupTl, size = cupSz)
    drawOval(Color(0xFF2FA3FF), topLeft = cupTl, size = cupSz, style = MojoArt.stroke2)
    val ringTl = Offset(22f, 137f)
    val ringSz = Size(18f, 64f)
    drawOval(MojoArt.earLight, topLeft = ringTl, size = ringSz, alpha = light * 0.3f, style = Stroke(7f))
    drawOval(MojoArt.earLight, topLeft = ringTl, size = ringSz, alpha = light, style = Stroke(2.5f))
}

private fun DrawScope.drawEyes(oval: Float, happy: Float, closed: Float, angry: Float) {
    val pivot = Offset(160f, 173f)
    if (oval > 0.01f) withTransform({ scale(1f, 0.2f + 0.8f * oval, pivot) }) {
        drawRoundRect(MojoArt.eye, topLeft = Offset(114f, 157f), size = Size(17f, 31f), cornerRadius = CornerRadius(8.5f), alpha = oval)
        drawRoundRect(MojoArt.eye, topLeft = Offset(189f, 157f), size = Size(17f, 31f), cornerRadius = CornerRadius(8.5f), alpha = oval)
    }
    if (happy > 0.01f) withTransform({ scale(1f, 0.6f + 0.4f * happy, pivot) }) {
        drawPath(MojoArt.happyLeft, MojoArt.eye, alpha = happy, style = MojoArt.stroke8Round)
        drawPath(MojoArt.happyRight, MojoArt.eye, alpha = happy, style = MojoArt.stroke8Round)
    }
    if (closed > 0.01f) withTransform({ scale(1f, 0.6f + 0.4f * closed, pivot) }) {
        drawPath(MojoArt.closedLeft, MojoArt.eye, alpha = closed, style = MojoArt.stroke6Round)
        drawPath(MojoArt.closedRight, MojoArt.eye, alpha = closed, style = MojoArt.stroke6Round)
    }
    if (angry > 0.01f) withTransform({ scale(1f, 0.6f + 0.4f * angry, pivot) }) {
        drawPath(MojoArt.angryLeft, MojoArt.eye, alpha = angry)
        drawPath(MojoArt.angryRight, MojoArt.eye, alpha = angry)
    }
}

private fun DrawScope.drawHand(pose: HandPose, rotation: Float, lift: Float, alpha: Float) {
    if (alpha <= 0.01f) return
    withTransform({
        translate(pose.x, pose.y)
        scale(pose.s, pose.s, pivot = Offset.Zero)
        translate(0f, lift)
        rotate(rotation, pivot = Offset.Zero)
    }) {
        val tl = Offset(-20f, -18f)
        val sz = Size(40f, 36f)
        drawOval(MojoArt.handBrush, topLeft = tl, size = sz, alpha = alpha)
        drawOval(MojoArt.handRim, topLeft = tl, size = sz, alpha = alpha, style = MojoArt.stroke1_5)
    }
}
