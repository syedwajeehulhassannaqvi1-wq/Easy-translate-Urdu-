package com.example.audiovideotrimmer

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Pure Android (no third-party) media trimmer using MediaExtractor + MediaMuxer.
 * Supports both audio-only and video+audio files (mp4, m4a, etc.).
 * Works on Android 9 – 16.
 *
 * What it does:
 * 1. Copies the selected content URI to a temporary file (necessary for MediaExtractor on many devices)
 * 2. Extracts tracks between startMs and endMs
 * 3. Muxes them into a new mp4 file
 * 4. Saves the result into the public Downloads/Movies or Music folder via MediaStore
 */
object MediaTrimmer {

    /**
     * Trims the media and returns the display path/name of the saved file, or null on failure.
     */
    fun trim(
        context: Context,
        inputUri: Uri,
        startMs: Long,
        endMs: Long
    ): String? {
        // 1. Copy content URI to a local temp file (MediaExtractor needs a file path or fd reliably)
        val tempInput = File(context.cacheDir, "trim_input_${System.currentTimeMillis()}")
        try {
            context.contentResolver.openInputStream(inputUri)?.use { input ->
                FileOutputStream(tempInput).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

        // Detect if it has video track
        val hasVideo = hasVideoTrack(tempInput.absolutePath)

        val outputFileName = "trimmed_${System.currentTimeMillis()}.mp4"
        val tempOutput = File(context.cacheDir, outputFileName)

        try {
            val success = if (hasVideo) {
                trimVideo(tempInput.absolutePath, tempOutput.absolutePath, startMs, endMs)
            } else {
                trimAudio(tempInput.absolutePath, tempOutput.absolutePath, startMs, endMs)
            }

            if (!success || !tempOutput.exists() || tempOutput.length() < 100) {
                return null
            }

            // 2. Save to MediaStore (Downloads / Movies or Music)
            val savedName = saveToMediaStore(context, tempOutput, hasVideo, outputFileName)
            return savedName
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            // Cleanup
            tempInput.delete()
            tempOutput.delete()
        }
    }

    private fun hasVideoTrack(path: String): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        } finally {
            extractor.release()
        }
    }

    /**
     * Trims a video (or video+audio) file.
     * Uses sample-accurate cutting – best effort without re-encoding (fast & quality preserving).
     */
    private fun trimVideo(
        inputPath: String,
        outputPath: String,
        startMs: Long,
        endMs: Long
    ): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(inputPath)

            // Find video and audio track indices
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") && videoTrackIndex == -1 -> {
                        videoTrackIndex = i
                        videoFormat = format
                    }
                    mime.startsWith("audio/") && audioTrackIndex == -1 -> {
                        audioTrackIndex = i
                        audioFormat = format
                    }
                }
            }

            if (videoTrackIndex == -1) return false

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackMap = mutableMapOf<Int, Int>() // extractor index -> muxer index

            // Add video track
            extractor.selectTrack(videoTrackIndex)
            val muxerVideoIndex = muxer.addTrack(videoFormat!!)
            trackMap[videoTrackIndex] = muxerVideoIndex

            // Add audio track if present
            if (audioTrackIndex != -1 && audioFormat != null) {
                val muxerAudioIndex = muxer.addTrack(audioFormat)
                trackMap[audioTrackIndex] = muxerAudioIndex
            }

            muxer.start()

            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB buffer
            val info = MediaCodec.BufferInfo()

            // Process each selected track separately for correct seeking
            val tracksToProcess = listOfNotNull(
                videoTrackIndex.takeIf { it >= 0 },
                audioTrackIndex.takeIf { it >= 0 }
            )

            for (trackIndex in tracksToProcess) {
                // Deselect all tracks first
                for (i in 0 until extractor.trackCount) {
                    try { extractor.unselectTrack(i) } catch (_: Exception) {}
                }
                extractor.selectTrack(trackIndex)
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    val sampleTime = extractor.sampleTime
                    if (sampleTime > endUs) break

                    if (sampleTime >= startUs) {
                        info.offset = 0
                        info.size = sampleSize
                        info.presentationTimeUs = sampleTime - startUs
                        info.flags = extractor.sampleFlags

                        muxer.writeSampleData(trackMap[trackIndex]!!, buffer, info)
                    }
                    extractor.advance()
                }
            }

            muxer.stop()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            extractor.release()
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Trims an audio-only file and wraps it into an mp4 container (widely compatible).
     */
    private fun trimAudio(
        inputPath: String,
        outputPath: String,
        startMs: Long,
        endMs: Long
    ): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(inputPath)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) return false

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            extractor.selectTrack(audioTrackIndex)
            val startUs = startMs * 1000L
            val endUs = endMs * 1000L

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val buffer = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) break

                if (sampleTime >= startUs) {
                    info.offset = 0
                    info.size = sampleSize
                    info.presentationTimeUs = sampleTime - startUs
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxerTrackIndex, buffer, info)
                }
                extractor.advance()
            }

            muxer.stop()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            extractor.release()
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Saves the trimmed file into public storage using MediaStore so it appears in Gallery / Files app.
     */
    private fun saveToMediaStore(
        context: Context,
        sourceFile: File,
        isVideo: Boolean,
        displayName: String
    ): String? {
        val collection = if (isVideo) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "audio/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    if (isVideo) Environment.DIRECTORY_MOVIES + "/AudioVideoTrimmer"
                    else Environment.DIRECTORY_MUSIC + "/AudioVideoTrimmer"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(output)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            displayName
        } catch (e: Exception) {
            e.printStackTrace()
            resolver.delete(uri, null, null)
            null
        }
    }
}
