package com.mojo.app.ui.mascot

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser

/**
 * MOJO artwork, ported 1:1 from the React MojoMascot SVG (viewBox 320 x 300).
 * All coordinates below are in that viewBox; MojoMascot scales them to the canvas.
 */
internal object MojoArt {
    const val VIEW_W = 320f
    const val VIEW_H = 300f

    private fun p(d: String): Path = PathParser().parsePathString(d).toPath()

    // ── Face ────────────────────────────────────────────────────────────
    val band = p("M64 137 C52 10 268 10 256 137")
    val bandLight = p("M72 99 C92 40 228 40 248 99")
    val head = p("M160 86 C94 86 63 109 63 165 C63 220 92 245 160 245 C228 245 257 220 257 165 C257 109 226 86 160 86Z")
    val headShine = p("M79 135 C88 101 126 93 166 94")
    val chin = p("M97 230 Q160 253 222 229")
    val earShell = p("M56 120 C27 119 27 143 27 169 C27 195 31 214 54 215 L60 207 L60 128Z")
    val micArm = p("M260 182 C255 201 241 210 215 213")
    val micShine = p("M190 211 Q192 209 198 209 H210")

    // ── Eyes ────────────────────────────────────────────────────────────
    val happyLeft = p("M108 177 Q122 153 137 177")
    val happyRight = p("M183 177 Q198 153 212 177")
    val closedLeft = p("M109 173 Q122 187 136 173")
    val closedRight = p("M184 173 Q198 187 211 173")
    val angryLeft = p("M111 159 L133 169 L132 178 Q122 187 113 177Z")
    val angryRight = p("M209 159 L187 169 L188 178 Q198 187 207 177Z")
    val angerMark = p("M264 78 Q265 88 255 88 M274 78 Q273 88 283 88 M255 98 Q265 98 264 108 M283 98 Q273 98 274 108")

    // ── Accessories ─────────────────────────────────────────────────────
    val keyboard = p("M97 239 L216 239 L237 253 L81 253Z")
    val keyboardKeys = p("M107 243 H209 M99 247 H218")
    val book = p("M160 222 Q132 207 92 210 L101 258 Q134 256 160 271 Q186 256 219 258 L228 210 Q188 207 160 222Z")
    val bookLines = p("M160 222 V267 M105 219 Q132 219 148 228 M108 228 Q132 228 147 236 M173 228 Q194 219 215 219 M174 237 Q192 230 210 230")
    val waveInner = p("M15 143 Q4 166 15 190 M305 143 Q316 166 305 190")
    val waveOuter = p("M8 133 Q-7 165 8 201 M312 133 Q327 165 312 201")
    val marksRight = p("M278 98 L285 86 M286 110 L300 105 M267 90 L268 79")
    val marksLeft = p("M42 98 L35 86 M34 110 L20 105 M53 90 L52 79")
    val sparkles = listOf(
        p("M38 88 Q40 99 49 101 Q40 103 38 114 Q36 103 27 101 Q36 99 38 88Z"),
        p("M279 74 Q281 85 290 87 Q281 89 279 100 Q277 89 268 87 Q277 85 279 74Z"),
        p("M265 234 Q267 242 274 244 Q267 246 265 254 Q263 246 256 244 Q263 242 265 234Z"),
    )
    val zzz = listOf(
        p("M260 82 H269 L260 92 H269"),
        p("M274 60 H287 L274 74 H287"),
        p("M291 32 H307 L291 50 H307"),
    )

    // ── Colors ──────────────────────────────────────────────────────────
    val eye = Color(0xFF030915)
    val navySolid = Color(0xFF0B1A3D)
    val bandLightColor = Color(0xFF43DFFF)
    val earLight = Color(0xFF51E4FF)
    val micLight = Color(0xFF61F0FF)
    val halo = Color(0xFF57D9FF)
    val dots = Color(0xFF63DDFF)
    val waves = Color(0xFF50D9FF)
    val marks = Color(0xFF56DFFF)
    val sparkle = Color(0xFFE3FAFF)
    val sleep = Color(0xFF59DFFF)
    val anger = Color(0xFF65DDFF)

    // ── Gradients (from MojoPaint.tsx, mapped to each shape's bounding box) ─
    val faceBrush = Brush.radialGradient(
        0f to Color(0xFFFFFFFF),
        0.52f to Color(0xFFE9EDF9),
        0.82f to Color(0xFFC0CCEE),
        0.96f to Color(0xFF789EEB),
        1f to Color(0xFF4798FF),
        center = Offset(134.8f, 122.6f),
        radius = 140f,
    )

    private val rimStops = arrayOf(
        0f to Color(0xFFFFFFFF),
        0.5f to Color(0xFFA7BDFF),
        1f to Color(0xFF1C78FF),
    )
    val rimBrush = Brush.linearGradient(*rimStops, start = Offset(60f, 30f), end = Offset(120f, 245f))

    private val navyStops = arrayOf(
        0f to Color(0xFF8BAAFF),
        0.18f to Color(0xFF203D8D),
        0.5f to Color(0xFF081633),
        0.85f to Color(0xFF132655),
        1f to Color(0xFF557AE0),
    )
    fun navy(x0: Float, y0: Float, x1: Float, y1: Float): Brush =
        Brush.linearGradient(*navyStops, start = Offset(x0, y0), end = Offset(x1, y1))

    val bandNavy = navy(52f, 40f, 268f, 137f)
    val earBandNavy = navy(56f, 111f, 79f, 126f)
    val earCupNavy = navy(20f, 132f, 44f, 148f)
    val micNavy = navy(184f, 207f, 219f, 221f)

    val earBrush = Brush.linearGradient(
        0f to Color(0xFF749CFF),
        0.25f to Color(0xFFFCFFFF),
        0.63f to Color(0xFFE0EAFF),
        1f to Color(0xFF2A6AE4),
        start = Offset(27f, 160f),
        end = Offset(60f, 170f),
    )

    private val bookStops = arrayOf(
        0f to Color(0xFFA9ECFF),
        0.28f to Color(0xFF559CFB),
        1f to Color(0xFF1C42B2),
    )
    val bookBrush = Brush.linearGradient(*bookStops, start = Offset(92f, 207f), end = Offset(119f, 271f))
    val keyboardBrush = Brush.linearGradient(*bookStops, start = Offset(81f, 239f), end = Offset(112f, 253f))

    /** Hands are drawn around (0,0), so their gradients are local too. */
    val handBrush = Brush.radialGradient(
        0f to Color(0xFFFFFFFF),
        0.52f to Color(0xFFE9EDF9),
        0.82f to Color(0xFFC0CCEE),
        0.96f to Color(0xFF789EEB),
        1f to Color(0xFF4798FF),
        center = Offset(-5.2f, -9.7f),
        radius = 30f,
    )
    val handRim = Brush.linearGradient(*rimStops, start = Offset(-20f, -18f), end = Offset(-8f, 18f))

    val shadowBrush = Brush.radialGradient(
        0f to Color(0x801677FF),
        1f to Color(0x001266FF),
        center = Offset(160f, 273f),
        radius = 82f,
    )

    // ── Strokes (reused every frame) ────────────────────────────────────
    val strokeBandOuter = Stroke(width = 19f)
    val strokeBandInner = Stroke(width = 15f)
    val stroke1 = Stroke(width = 1f)
    val stroke1Round = Stroke(width = 1f, cap = StrokeCap.Round)
    val stroke1_5 = Stroke(width = 1.5f)
    val stroke2 = Stroke(width = 2f)
    val stroke3Round = Stroke(width = 3f, cap = StrokeCap.Round)
    val stroke3_5Round = Stroke(width = 3.5f, cap = StrokeCap.Round)
    val stroke4Round = Stroke(width = 4f, cap = StrokeCap.Round)
    val stroke4_5Round = Stroke(width = 4.5f, cap = StrokeCap.Round)
    val stroke6Round = Stroke(width = 6f, cap = StrokeCap.Round)
    val stroke7Round = Stroke(width = 7f, cap = StrokeCap.Round)
    val stroke8Round = Stroke(width = 8f, cap = StrokeCap.Round)
    val strokeZ = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val strokeKeys = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
}
