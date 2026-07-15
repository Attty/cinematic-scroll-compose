package dev.vlad.cinematicscroll.demo

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vlad.cinematicscroll.CinematicScroll
import dev.vlad.cinematicscroll.FrameSource
import dev.vlad.cinematicscroll.rememberCinematicScrollState
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // hidden flag used to record the README demo deterministically:
        // adb shell am start ... --ez autopilot true
        val autopilot = intent.getBooleanExtra("autopilot", false)
        setContent { DemoScreen(autopilot) }
    }
}

private data class Caption(val title: String, val subtitle: String)

private val tunnelCaptions = listOf(
    Caption("CINEMATIC\nSCROLL", "scroll to fly"),
    Caption("150 FRAMES", "pre-rendered offline\ninto a webp sequence"),
    Caption("LERP", "scroll is a timeline,\nnot a position"),
    Caption("TILT\nTHE PHONE", "gyroscope = parallax"),
    Caption("COMPOSE\n+ CANVAS", "no video player.\njust frames and math"),
)

@Composable
private fun DemoScreen(autopilot: Boolean = false) {
    val state = rememberCinematicScrollState()
    val scrollState = androidx.compose.foundation.rememberScrollState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var source by remember { mutableStateOf<FrameSource?>(null) }

    // the demo ships its footage as one small mp4 and lets the library
    // slice it on first launch — same path an app using FrameSource.Video takes
    LaunchedEffect(Unit) {
        source = FrameSource.Video(
            uri = rawToCacheFile(context, R.raw.tunnel, "tunnel.mp4"),
            frameCount = 150,
            targetHeight = 1440,
        )
    }

    if (autopilot) {
        LaunchedEffect(source, state.isReady) {
            if (source == null || !state.isReady) return@LaunchedEffect
            kotlinx.coroutines.delay(900)
            val max = scrollState.maxValue
            fun spec(ms: Int) = androidx.compose.animation.core.tween<Float>(
                ms, easing = androidx.compose.animation.core.FastOutSlowInEasing,
            )
            scrollState.animateScrollTo((max * 0.30f).toInt(), spec(2600))
            kotlinx.coroutines.delay(350)
            scrollState.animateScrollTo(max, spec(2400))
            kotlinx.coroutines.delay(600)
            scrollState.animateScrollTo((max * 0.12f).toInt(), spec(2800))
            kotlinx.coroutines.delay(400)
            scrollState.animateScrollTo((max * 0.55f).toInt(), spec(2200))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        source?.let { src ->
            CinematicScroll(
                source = src,
                state = state,
                scrollState = scrollState,
                modifier = Modifier.fillMaxSize(),
            ) {
                tunnelCaptions.forEach { caption ->
                    section { relative -> CaptionSection(caption, relative) }
                }
            }

            if (state.isReady) {
                Hud(state = state, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

private suspend fun rawToCacheFile(context: Context, resId: Int, name: String): Uri =
    withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, name)
        if (!file.exists()) {
            context.resources.openRawResource(resId).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        Uri.fromFile(file)
    }

@Composable
private fun BoxScope.CaptionSection(caption: Caption, relative: () -> Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .align(Alignment.Center)
            .graphicsLayer {
                val rel = relative()
                translationY = rel * size.height * 0.35f
                alpha = 1f - (abs(rel) * 1.8f).coerceIn(0f, 1f)
            },
    ) {
        Text(
            caption.title,
            color = Color.White,
            fontSize = 44.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            letterSpacing = 6.sp,
            lineHeight = 52.sp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            caption.subtitle,
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            letterSpacing = 2.sp,
            lineHeight = 22.sp,
        )
    }
}

@Composable
private fun Hud(state: dev.vlad.cinematicscroll.CinematicScrollState, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(bottom = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            String.format(
                Locale.US, "FRAME %03d / %03d",
                state.frameIndex, (state.frameCount - 1).coerceAtLeast(0),
            ),
            color = Color.White.copy(alpha = 0.55f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .width(180.dp)
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.15f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(state.progress.coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(Color.White.copy(alpha = 0.8f))
            )
        }
    }
}
