package com.autocut.videocut

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

class VideoProcessor(private val context: Context) {

    /**
     * Cuts the video to keep only [segments] and concatenates them into one output file.
     * Uses Android's native MediaExtractor + MediaMuxer (no re-encoding — keyframe-accurate cuts).
     */
    suspend fun process(
        inputUri: Uri,
        segments: List<VideoSegment>,
        onProgress: (Int) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {

        if (segments.isEmpty()) return@withContext null

        val inputPath = copyToCache(inputUri) ?: return@withContext null
        val outputFile = File(context.cacheDir, "autocut_output.mp4")
        if (outputFile.exists()) outputFile.delete()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // Use first segment to detect tracks and add them to the muxer
        val probe = MediaExtractor()
        probe.setDataSource(inputPath)
        val trackCount = probe.trackCount
        val muxerTrackMap = mutableMapOf<Int, Int>() // extractor track index -> muxer track index
        for (i in 0 until trackCount) {
            val fmt = probe.getTrackFormat(i)
            muxerTrackMap[i] = muxer.addTrack(fmt)
        }
        probe.release()

        muxer.start()

        val buffer = ByteBuffer.allocate(1024 * 1024)
        val bufferInfo = android.media.MediaCodec.BufferInfo()
        var presentationOffsetUs = 0L

        segments.forEachIndexed { segIndex, seg ->
            val extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            // Find the actual end PTS of this segment via the previous segment's max PTS
            var segMaxPts = 0L

            for (trackIndex in 0 until extractor.trackCount) {
                extractor.selectTrack(trackIndex)
            }

            extractor.seekTo(seg.startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            while (true) {
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > seg.endMs * 1000L) break

                bufferInfo.presentationTimeUs = sampleTimeUs - (seg.startMs * 1000L) + presentationOffsetUs
                bufferInfo.flags = extractor.sampleFlags
                bufferInfo.offset = 0

                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex >= 0 && muxerTrackMap.containsKey(trackIndex)) {
                    muxer.writeSampleData(muxerTrackMap[trackIndex]!!, buffer, bufferInfo)
                }

                if (sampleTimeUs > segMaxPts) segMaxPts = sampleTimeUs
                extractor.advance()
            }

            extractor.release()

            // Offset next segment's timestamps so they follow this one
            val segDurationUs = (seg.endMs - seg.startMs) * 1000L
            presentationOffsetUs += segDurationUs

            onProgress((segIndex + 1) * 100 / segments.size)
        }

        muxer.stop()
        muxer.release()

        File(inputPath).delete()
        outputFile
    }

    private fun copyToCache(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        return try {
            val tmp = File(context.cacheDir, "input_tmp.mp4")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            tmp.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
