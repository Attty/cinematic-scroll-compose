package dev.vlad.cinematicscroll

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Where the frame sequence comes from.
 */
sealed interface FrameSource {

    /** Frames pre-baked into APK assets, sorted by file name (webp/jpg/png). */
    data class Assets(val directory: String) : FrameSource

    /** Frames in a directory on disk, sorted by file name. */
    data class Files(val directory: File) : FrameSource

    /**
     * A video shipped with (or downloaded by) the app. On first use it is
     * sliced into evenly spaced frames (scaled to [targetHeight]) and cached
     * in the app's cache dir, so every following launch is instant.
     *
     * [frameCount] = null means auto: ~12 frames per second of footage,
     * clamped to 60..360 — dense enough that neighbouring frames are close
     * in time, which is what makes the scrub feel smooth.
     */
    data class Video(
        val uri: Uri,
        val frameCount: Int? = null,
        val targetHeight: Int = 1280,
    ) : FrameSource
}

/** A resolved, ready-to-decode sequence. */
internal class ResolvedFrames(
    val count: Int,
    private val open: (Int) -> InputStream,
) {
    fun decode(index: Int, sampleSize: Int = 1): Bitmap? = runCatching {
        open(index).use { stream ->
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = sampleSize
            }
            BitmapFactory.decodeStream(stream, null, options)
        }
    }.getOrNull()
}

internal suspend fun FrameSource.resolve(
    context: Context,
    onProgress: (Float) -> Unit,
): ResolvedFrames = withContext(Dispatchers.IO) {
    when (val source = this@resolve) {
        is FrameSource.Assets -> {
            val names = (context.assets.list(source.directory) ?: emptyArray())
                .filter { it.isFrameFile() }
                .sorted()
            require(names.isNotEmpty()) { "No frames found in assets/${source.directory}" }
            ResolvedFrames(names.size) { i ->
                context.assets.open("${source.directory}/${names[i]}")
            }
        }

        is FrameSource.Files -> {
            val files = (source.directory.listFiles() ?: emptyArray())
                .filter { it.name.isFrameFile() }
                .sortedBy { it.name }
            require(files.isNotEmpty()) { "No frames found in ${source.directory}" }
            ResolvedFrames(files.size) { i -> files[i].inputStream() }
        }

        is FrameSource.Video -> {
            val key = "${source.uri}|${source.frameCount}|${source.targetHeight}"
                .hashCode().toUInt().toString(16)
            val dir = File(context.cacheDir, "cinematic_scroll/$key")
            val marker = File(dir, "complete")
            val cached = (dir.listFiles() ?: emptyArray())
                .filter { it.name.isFrameFile() }
                .sortedBy { it.name }
            val files = if (marker.exists() && cached.size == marker.readText().toIntOrNull()) {
                cached
            } else {
                extractFrames(context, source, dir, onProgress)
                    .also { marker.writeText(it.size.toString()) }
            }
            ResolvedFrames(files.size) { i -> files[i].inputStream() }
        }
    }
}

private fun String.isFrameFile() =
    endsWith(".webp") || endsWith(".jpg") || endsWith(".jpeg") || endsWith(".png")

/** Slices the video into evenly spaced frames and caches them as WebP. */
private fun extractFrames(
    context: Context,
    source: FrameSource.Video,
    dir: File,
    onProgress: (Float) -> Unit,
): List<File> {
    dir.deleteRecursively()
    dir.mkdirs()

    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, source.uri)
        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: error("Video has no duration metadata")
        val width = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

        val (frameW, frameH) = if (rotation == 90 || rotation == 270) height to width else width to height
        val scale = if (frameH > 0) source.targetHeight.toFloat() / frameH else 1f
        val scaledW = (frameW * scale).roundToInt().coerceAtLeast(2)
        val scaledH = (frameH * scale).roundToInt().coerceAtLeast(2)

        // auto density: sample close in time — that's what makes the scrub smooth
        val frameCount = source.frameCount
            ?: (durationMs / 1000f * 12).roundToInt().coerceIn(60, 360)

        val result = ArrayList<File>(frameCount)
        var lastGood: Bitmap? = null
        for (i in 0 until frameCount) {
            // stay just inside the clip so the last frame isn't black
            val timeUs = durationMs * 1000L * i / frameCount + 1_000L
            val bitmap = if (Build.VERSION.SDK_INT >= 27 && scale < 1f) {
                retriever.getScaledFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST, scaledW, scaledH,
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?.let { full ->
                        if (scale < 1f) {
                            Bitmap.createScaledBitmap(full, scaledW, scaledH, true)
                                .also { if (it !== full) full.recycle() }
                        } else full
                    }
            } ?: lastGood ?: continue

            val file = File(dir, String.format(Locale.US, "frame_%04d.webp", i))
            file.outputStream().use { out ->
                @Suppress("DEPRECATION")
                bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
            }
            result += file
            if (bitmap !== lastGood) {
                lastGood?.recycle()
                lastGood = bitmap
            }
            onProgress((i + 1f) / frameCount)
        }
        lastGood?.recycle()
        require(result.isNotEmpty()) { "Could not extract any frames from ${source.uri}" }
        return result
    } finally {
        retriever.release()
    }
}
