package com.autocut.videocut

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VideoSegment(val startMs: Long, val endMs: Long)

class VideoAnalyzer(private val context: Context) {

    /**
     * Analyzes the video and returns a list of segments that contain significant motion.
     *
     * @param uri             URI of the input video
     * @param sensitivity     0.0–1.0; higher = more sensitive (keeps more motion segments)
     * @param minSegmentMs    Minimum duration (ms) for a kept segment
     * @param onProgress      Called with 0–100 progress during analysis
     */
    suspend fun findActiveSegments(
        uri: Uri,
        sensitivity: Float = 0.5f,
        minSegmentMs: Long = 500L,
        onProgress: (Int) -> Unit = {}
    ): List<VideoSegment> = withContext(Dispatchers.Default) {

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)

        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLong() ?: 0L

        // Sample every 250 ms
        val sampleIntervalMs = 250L
        val totalSamples = (durationMs / sampleIntervalMs).toInt().coerceAtLeast(1)

        // Threshold: fraction of pixels that must differ between frames.
        // sensitivity 0→1 maps to threshold 0.15→0.01 (lower threshold = easier to trigger motion)
        val diffThreshold = 0.15f - (sensitivity * 0.14f)

        val motionAt = BooleanArray(totalSamples)
        var prevBitmap: Bitmap? = null

        for (i in 0 until totalSamples) {
            val timeUs = i * sampleIntervalMs * 1000L
            val bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

            if (bmp != null && prevBitmap != null) {
                motionAt[i] = hasMotion(prevBitmap!!, bmp, diffThreshold)
            }

            prevBitmap?.recycle()
            prevBitmap = bmp
            onProgress((i * 100) / totalSamples)
        }
        prevBitmap?.recycle()
        retriever.release()
        onProgress(100)

        buildSegments(motionAt, sampleIntervalMs, durationMs, minSegmentMs)
    }

    private fun hasMotion(a: Bitmap, b: Bitmap, threshold: Float): Boolean {
        val w = minOf(a.width, b.width, 64)
        val h = minOf(a.height, b.height, 64)
        val scaledA = Bitmap.createScaledBitmap(a, w, h, false)
        val scaledB = Bitmap.createScaledBitmap(b, w, h, false)

        var diffCount = 0
        val total = w * h
        val pixelThreshold = 30  // per-channel luminance change

        for (x in 0 until w) {
            for (y in 0 until h) {
                val pa = scaledA.getPixel(x, y)
                val pb = scaledB.getPixel(x, y)
                val dr = Math.abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF))
                val dg = Math.abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF))
                val db = Math.abs((pa and 0xFF) - (pb and 0xFF))
                if ((dr + dg + db) / 3 > pixelThreshold) diffCount++
            }
        }

        if (scaledA !== a) scaledA.recycle()
        if (scaledB !== b) scaledB.recycle()

        return diffCount.toFloat() / total > threshold
    }

    private fun buildSegments(
        motionAt: BooleanArray,
        intervalMs: Long,
        durationMs: Long,
        minSegmentMs: Long
    ): List<VideoSegment> {
        val segments = mutableListOf<VideoSegment>()
        var inSegment = false
        var segStart = 0L

        for (i in motionAt.indices) {
            val t = i * intervalMs
            if (motionAt[i] && !inSegment) {
                segStart = (t - intervalMs).coerceAtLeast(0L)
                inSegment = true
            } else if (!motionAt[i] && inSegment) {
                val segEnd = t + intervalMs
                if (segEnd - segStart >= minSegmentMs) {
                    segments.add(VideoSegment(segStart, segEnd))
                }
                inSegment = false
            }
        }

        // Close any open segment at end
        if (inSegment) {
            val segEnd = durationMs
            if (segEnd - segStart >= minSegmentMs) {
                segments.add(VideoSegment(segStart, segEnd))
            }
        }

        return mergeCloseSegments(segments, gapMs = 500L)
    }

    /** Merge segments that are within gapMs of each other to avoid choppy cuts. */
    private fun mergeCloseSegments(segments: List<VideoSegment>, gapMs: Long): List<VideoSegment> {
        if (segments.isEmpty()) return segments
        val merged = mutableListOf(segments[0])
        for (i in 1 until segments.size) {
            val last = merged.last()
            val cur = segments[i]
            if (cur.startMs - last.endMs <= gapMs) {
                merged[merged.lastIndex] = VideoSegment(last.startMs, cur.endMs)
            } else {
                merged.add(cur)
            }
        }
        return merged
    }
}
