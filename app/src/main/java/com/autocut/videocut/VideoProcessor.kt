package com.autocut.videocut

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class VideoProcessor(private val context: Context) {

    /**
     * Cuts the video to keep only [segments], concatenates them, and writes the result
     * to the app's cache directory. Returns the output File or null on failure.
     */
    suspend fun process(
        inputUri: Uri,
        segments: List<VideoSegment>,
        onProgress: (Int) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {

        if (segments.isEmpty()) return@withContext null

        val inputPath = getRealPath(inputUri) ?: return@withContext null
        val cacheDir = context.cacheDir
        val clips = mutableListOf<File>()

        // 1. Extract each active segment as a temporary clip
        segments.forEachIndexed { index, seg ->
            val startSec = seg.startMs / 1000.0
            val durationSec = (seg.endMs - seg.startMs) / 1000.0
            val clipFile = File(cacheDir, "clip_$index.mp4")

            val success = runFFmpeg(
                "-y -ss $startSec -i \"$inputPath\" -t $durationSec " +
                        "-c:v libx264 -preset ultrafast -crf 23 " +
                        "-c:a aac -b:a 128k \"${clipFile.absolutePath}\""
            )

            if (success) clips.add(clipFile)
            onProgress((index + 1) * 80 / segments.size)
        }

        if (clips.isEmpty()) return@withContext null

        // 2. If only one clip, rename it directly
        val outputFile = File(cacheDir, "autocut_output.mp4")
        if (clips.size == 1) {
            clips[0].copyTo(outputFile, overwrite = true)
            clips[0].delete()
            onProgress(100)
            return@withContext outputFile
        }

        // 3. Build concat list file
        val concatList = File(cacheDir, "concat_list.txt")
        concatList.writeText(clips.joinToString("\n") { "file '${it.absolutePath}'" })

        val success = runFFmpeg(
            "-y -f concat -safe 0 -i \"${concatList.absolutePath}\" " +
                    "-c copy \"${outputFile.absolutePath}\""
        )

        concatList.delete()
        clips.forEach { it.delete() }

        onProgress(100)
        if (success) outputFile else null
    }

    private suspend fun runFFmpeg(args: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val session = FFmpegKit.executeAsync(args) { session ->
                cont.resume(ReturnCode.isSuccess(session.returnCode))
            }
            cont.invokeOnCancellation { session.cancel() }
        }

    private fun getRealPath(uri: Uri): String? {
        // Try to resolve content URI to a file path via a temp copy when needed
        if (uri.scheme == "file") return uri.path

        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val tmpFile = File(context.cacheDir, "input_video_tmp.mp4")
            tmpFile.outputStream().use { out -> inputStream.copyTo(out) }
            inputStream.close()
            tmpFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
