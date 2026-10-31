package com.example.audiovideotrimmer

import android.content.ContentValues
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.audiovideotrimmer.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * MainActivity for Audio & Video Trimmer.
 * Features:
 * - Select media via Storage Access Framework (no storage permission needed for pick)
 * - Play / Pause / Rewind / Forward with configurable seconds
 * - Set Start & End markers while playing
 * - Trim using MediaExtractor + MediaMuxer (native, no heavy FFmpeg)
 * - Fully accessible (contentDescription on all interactive elements)
 * - Supports Android 9 (API 28) to Android 16
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private var currentUri: Uri? = null
    private var mediaDurationMs: Long = 0L
    private var startMarkerMs: Long = 0L
    private var endMarkerMs: Long = 0L
    private var isSeeking = false
    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            updateHandler.postDelayed(this, 200)
        }
    }

    // File picker launcher (SAF - works on all Android 9+)
    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // Take persistable permission so we can access later if needed
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers don't support persistable
            }
            loadMedia(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPlayer()
        setupListeners()
        updateMarkersUI()
    }

    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        mediaDurationMs = exo.duration.coerceAtLeast(0)
                        endMarkerMs = mediaDurationMs
                        updateMarkersUI()
                        updateDurationText()
                    }
                    updatePlayPauseButton()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPauseButton()
                }
            })
        }
    }

    private fun setupListeners() {
        // Select media
        binding.btnSelectMedia.setOnClickListener {
            pickMediaLauncher.launch(arrayOf("video/*", "audio/*"))
        }

        // Play / Pause
        binding.btnPlayPause.setOnClickListener {
            player?.let {
                if (it.isPlaying) it.pause() else it.play()
            }
        }

        // Rewind
        binding.btnRewind.setOnClickListener {
            val seconds = getSeekSeconds()
            player?.let {
                val newPos = (it.currentPosition - seconds * 1000L).coerceAtLeast(0)
                it.seekTo(newPos)
            }
        }

        // Forward
        binding.btnForward.setOnClickListener {
            val seconds = getSeekSeconds()
            player?.let {
                val newPos = (it.currentPosition + seconds * 1000L).coerceAtMost(mediaDurationMs)
                it.seekTo(newPos)
            }
        }

        // Set Start marker
        binding.btnSetStart.setOnClickListener {
            player?.let {
                startMarkerMs = it.currentPosition.coerceAtMost(endMarkerMs - 100)
                updateMarkersUI()
                Toast.makeText(this, "Start set to ${formatTime(startMarkerMs)}", Toast.LENGTH_SHORT).show()
            }
        }

        // Set End marker
        binding.btnSetEnd.setOnClickListener {
            player?.let {
                endMarkerMs = it.currentPosition.coerceAtLeast(startMarkerMs + 100)
                updateMarkersUI()
                Toast.makeText(this, "End set to ${formatTime(endMarkerMs)}", Toast.LENGTH_SHORT).show()
            }
        }

        // Trim
        binding.btnTrim.setOnClickListener {
            if (currentUri == null) {
                Toast.makeText(this, getString(R.string.no_media_selected), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (startMarkerMs >= endMarkerMs) {
                Toast.makeText(this, getString(R.string.invalid_range), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startTrim()
        }

        // SeekBar
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && mediaDurationMs > 0) {
                    val pos = (progress / 1000.0 * mediaDurationMs).toLong()
                    binding.tvCurrentPosition.text = getString(R.string.current_position, formatTime(pos))
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                player?.let {
                    val progress = binding.seekBar.progress
                    val pos = (progress / 1000.0 * mediaDurationMs).toLong()
                    it.seekTo(pos)
                }
            }
        })
    }

    private fun loadMedia(uri: Uri) {
        currentUri = uri
        val name = getFileName(uri) ?: "Unknown file"
        binding.tvMediaInfo.text = getString(R.string.media_info, name)

        // Reset markers
        startMarkerMs = 0L
        endMarkerMs = 0L
        mediaDurationMs = 0L

        player?.apply {
            stop()
            clearMediaItems()
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }

        // Try to get duration early via MetadataRetriever for better UX
        lifecycleScope.launch {
            val duration = withContext(Dispatchers.IO) {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(this@MainActivity, uri)
                    val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    retriever.release()
                    d
                } catch (e: Exception) {
                    0L
                }
            }
            if (duration > 0) {
                mediaDurationMs = duration
                endMarkerMs = duration
                updateMarkersUI()
                updateDurationText()
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun getSeekSeconds(): Int {
        val text = binding.etSeekSeconds.text?.toString()?.trim()
        return text?.toIntOrNull()?.coerceIn(1, 60) ?: 5
    }

    private fun updateProgress() {
        player?.let {
            if (!isSeeking && mediaDurationMs > 0) {
                val pos = it.currentPosition
                val progress = ((pos.toDouble() / mediaDurationMs) * 1000).toInt().coerceIn(0, 1000)
                binding.seekBar.progress = progress
                binding.tvCurrentPosition.text = getString(R.string.current_position, formatTime(pos))
            }
        }
    }

    private fun updatePlayPauseButton() {
        val isPlaying = player?.isPlaying == true
        binding.btnPlayPause.text = if (isPlaying) getString(R.string.pause) else getString(R.string.play)
        // Icon would ideally change too, but text is enough for accessibility
    }

    private fun updateMarkersUI() {
        binding.tvStartTime.text = getString(R.string.start_time, formatTime(startMarkerMs))
        binding.tvEndTime.text = getString(R.string.end_time, formatTime(endMarkerMs))
    }

    private fun updateDurationText() {
        binding.tvDuration.text = getString(R.string.duration, formatTime(mediaDurationMs))
    }

    private fun formatTime(ms: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun startTrim() {
        val uri = currentUri ?: return
        binding.progressBar.visibility = View.VISIBLE
        binding.btnTrim.isEnabled = false

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    MediaTrimmer.trim(
                        context = this@MainActivity,
                        inputUri = uri,
                        startMs = startMarkerMs,
                        endMs = endMarkerMs
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }

            binding.progressBar.visibility = View.GONE
            binding.btnTrim.isEnabled = true

            if (result != null) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.trim_success) + "\n" + result,
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this@MainActivity, getString(R.string.trim_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        updateHandler.post(updateRunnable)
    }

    override fun onStop() {
        super.onStop()
        updateHandler.removeCallbacks(updateRunnable)
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
