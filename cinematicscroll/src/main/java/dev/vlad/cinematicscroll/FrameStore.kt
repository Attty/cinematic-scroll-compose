package dev.vlad.cinematicscroll

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Collections
import kotlin.math.abs

/**
 * Two-tier frame cache:
 *  - a low-res copy of the WHOLE sequence is decoded once at startup, so any
 *    frame can be shown instantly during a fast fling — motion hides the
 *    softness the way motion blur would;
 *  - full-res frames live in a sliding LRU window around the scrub position
 *    and take over as soon as the motion settles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FrameStore(private val frames: ResolvedFrames) {

    val frameCount: Int get() = frames.count

    private val cache = LruCache<Int, Bitmap>(24)
    private val lowRes = arrayOfNulls<Bitmap>(frames.count)
    private val inFlight = Collections.synchronizedSet(mutableSetOf<Int>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(3))

    // where the scrub is right now; queued decodes that drifted far away are skipped
    @Volatile
    private var focus = 0

    init {
        scope.launch {
            for (i in 0 until frames.count) {
                if (lowRes[i] == null) lowRes[i] = frames.decode(i, sampleSize = 4)
            }
        }
    }

    fun fullAt(index: Int): Bitmap? = cache.get(index.coerceIn(0, frameCount - 1))

    fun lowResAt(index: Int): Bitmap? = lowRes[index.coerceIn(0, frameCount - 1)]

    /** Any bitmap at all — used only while the low-res tier is still warming up. */
    fun anyAt(index: Int): Bitmap? {
        val i = index.coerceIn(0, frameCount - 1)
        fullAt(i)?.let { return it }
        lowRes[i]?.let { return it }
        for (r in 1 until frameCount) {
            if (i - r >= 0) (cache.get(i - r) ?: lowRes[i - r])?.let { return it }
            if (i + r < frameCount) (cache.get(i + r) ?: lowRes[i + r])?.let { return it }
        }
        return null
    }

    /** Warm a window of full-res frames around [index], biased in the scroll [direction]. */
    fun request(index: Int, direction: Int, fast: Boolean) {
        focus = index
        val ahead = if (fast) 18 else 10
        val range = if (direction >= 0) index - 3..index + ahead else index - ahead..index + 3
        for (i in range) {
            val clamped = i.coerceIn(0, frameCount - 1)
            if (cache.get(clamped) == null && inFlight.add(clamped)) {
                scope.launch {
                    try {
                        if (abs(clamped - focus) <= 24) {
                            frames.decode(clamped)?.let { cache.put(clamped, it) }
                        }
                    } finally {
                        inFlight.remove(clamped)
                    }
                }
            }
        }
    }

    fun dispose() {
        scope.cancel()
    }
}
