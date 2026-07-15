package dev.vlad.cinematicscroll

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Tuning knobs for [CinematicScroll]. The defaults feel right on a phone;
 * every value is safe to tweak live.
 */
data class CinematicScrollConfig(
    /** Fraction of the remaining distance the scrub covers per frame tick. */
    val smoothing: Float = 0.10f,
    /** Hard cap on scrub speed, in sequence frames per tick — the "catch-up" speed. */
    val maxStepFrames: Float = 4.5f,
    /** How much larger than the viewport the image is drawn; head-room for parallax. */
    val overscan: Float = 1.12f,
    /** Gyroscope parallax on/off. */
    val tiltEnabled: Boolean = true,
    /** How far the image shifts per radian of device turn. */
    val tiltStrength: Float = 3.5f,
    /** Smoothing applied to the tilt offset each tick. */
    val tiltSmoothing: Float = 0.18f,
)

/** Live scrub state, observable from your own HUD / overlays. */
class CinematicScrollState internal constructor() {
    var progress by mutableFloatStateOf(0f)
        internal set
    var frameIndex by mutableIntStateOf(0)
        internal set
    var frameCount by mutableIntStateOf(1)
        internal set
    var isReady by mutableStateOf(false)
        internal set
}

@Composable
fun rememberCinematicScrollState(): CinematicScrollState =
    remember { CinematicScrollState() }

/**
 * Receiver scope for [CinematicScroll] content, in the spirit of LazyColumn:
 * declare full-screen sections one after another with [section].
 */
interface CinematicScrollScope {
    /**
     * Adds one section, exactly one viewport tall. Sections define the scroll
     * length: N sections = N screens of scroll mapped onto the frame sequence.
     *
     * [relative] is the section's position in screens: 0 = centered on screen,
     * -1 = one screen above, +1 = one screen below. Read it inside
     * `graphicsLayer`/draw blocks for parallax and fades — that way the motion
     * never causes recomposition.
     */
    fun section(content: @Composable BoxScope.(relative: () -> Float) -> Unit)
}

private class SectionsBuilder : CinematicScrollScope {
    val sections = mutableListOf<@Composable BoxScope.(relative: () -> Float) -> Unit>()
    override fun section(content: @Composable BoxScope.(relative: () -> Float) -> Unit) {
        sections += content
    }
}

/**
 * A full-screen scroll-scrubbed frame sequence: scrolling
 * moves the "camera" through the pre-rendered sequence with lerp smoothing,
 * and the gyroscope adds a parallax shift on top.
 *
 * Declare content like a LazyColumn — one [CinematicScrollScope.section] per
 * screen of scroll:
 *
 * ```
 * CinematicScroll(source = FrameSource.Assets("frames")) {
 *     section { Hero(it) }
 *     section { Features(it) }
 * }
 * ```
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CinematicScroll(
    source: FrameSource,
    modifier: Modifier = Modifier,
    config: CinematicScrollConfig = CinematicScrollConfig(),
    state: CinematicScrollState = rememberCinematicScrollState(),
    scrollState: ScrollState = rememberScrollState(),
    loading: @Composable BoxScope.(progress: Float) -> Unit = { p -> DefaultLoading(p) },
    content: CinematicScrollScope.() -> Unit,
) {
    val sections = SectionsBuilder().apply(content).sections
    require(sections.isNotEmpty()) { "CinematicScroll needs at least one section {}" }
    val context = LocalContext.current.applicationContext

    var store by remember(source) { mutableStateOf<FrameStore?>(null) }
    var loadProgress by remember(source) { mutableFloatStateOf(0f) }

    LaunchedEffect(source) {
        state.isReady = false
        store = FrameStore(source.resolve(context) { loadProgress = it })
        state.isReady = true
    }
    DisposableEffect(store) {
        val s = store
        onDispose { s?.dispose() }
    }

    val tiltRaw by rememberTilt()
    var tilt by remember { mutableStateOf(Offset.Zero) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(store, config) {
        val s = store ?: return@LaunchedEffect
        state.frameCount = s.frameCount
        s.request(0, 1, fast = false)
        var progress = 0f
        var fastMode = false
        while (true) {
            withFrameNanos { }
            val target = if (scrollState.maxValue > 0) {
                scrollState.value.toFloat() / scrollState.maxValue
            } else 0f
            val maxStep = config.maxStepFrames / (s.frameCount - 1).coerceAtLeast(1)
            val step = ((target - progress) * config.smoothing).coerceIn(-maxStep, maxStep)
            progress += step
            if (config.tiltEnabled) tilt += (tiltRaw - tilt) * config.tiltSmoothing

            val index = (progress * (s.frameCount - 1)).roundToInt()
            val speed = abs(step) * (s.frameCount - 1)
            // hysteresis keeps the low-res tier engaged through the whole fling,
            // so the image never flickers between sharp and soft mid-motion
            fastMode = if (fastMode) speed > 0.4f else speed > 1.2f
            s.request(index, if (target >= progress) 1 else -1, fastMode)

            val bitmap = if (fastMode) {
                s.lowResAt(index) ?: s.anyAt(index)
            } else {
                s.fullAt(index) ?: s.lowResAt(index) ?: s.anyAt(index)
            }
            bitmap?.let { frame = it }
            state.progress = progress
            state.frameIndex = index
        }
    }

    BoxWithConstraints(modifier.background(Color.Black)) {
        val sectionHeight = maxHeight
        val sectionPx = constraints.maxHeight.toFloat()

        Canvas(Modifier.fillMaxSize()) {
            val bitmap = frame ?: return@Canvas
            val scale = max(size.width / bitmap.width, size.height / bitmap.height) * config.overscan
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            val maxShiftX = (w - size.width) / 2f
            val maxShiftY = (h - size.height) / 2f
            val ox = (size.width - w) / 2f +
                (-tilt.x * config.tiltStrength).coerceIn(-1f, 1f) * maxShiftX
            val oy = (size.height - h) / 2f +
                (-tilt.y * config.tiltStrength).coerceIn(-1f, 1f) * maxShiftY
            drawImage(
                image = bitmap.asImageBitmap(),
                dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        }

        // the stock stretch-overscroll fights the lerp at the ends of the scroll
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                sections.forEachIndexed { i, section ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(sectionHeight)
                    ) {
                        section(this) { (scrollState.value - i * sectionPx) / sectionPx }
                    }
                }
            }
        }

        if (!state.isReady) {
            loading(this, loadProgress)
        }
    }
}

@Composable
private fun BoxScope.DefaultLoading(progress: Float) {
    Box(
        Modifier
            .matchParentSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val label = if (progress > 0f) {
            String.format(Locale.US, "PREPARING FRAMES %d%%", (progress * 100).roundToInt())
        } else "PREPARING FRAMES"
        BasicText(
            label,
            style = TextStyle(
                color = Color.White.copy(alpha = 0.6f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                letterSpacing = 3.sp,
            ),
        )
    }
}
