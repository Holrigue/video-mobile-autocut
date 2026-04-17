package com.autocut.videocut

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.autocut.videocut.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        viewModel.selectedUri = uri
        val name = uri.lastPathSegment ?: uri.toString()
        binding.tvVideoName.text = name
        binding.btnProcess.isEnabled = true
    }

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pickVideo.launch("video/*")
            else Toast.makeText(this, getString(R.string.permission_needed), Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        observeState()
    }

    private fun setupListeners() {
        binding.btnPickVideo.setOnClickListener { requestVideoPermissionOrPick() }

        binding.seekSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val pct = progress
                binding.tvSensitivityValue.text = "$pct%"
                viewModel.sensitivity = pct / 100f
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        binding.seekMinSegment.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                // 0..40 maps to 0.5s..4.5s
                val sec = 0.5f + progress * 0.1f
                binding.tvMinSegmentValue.text = "%.1fs".format(sec)
                viewModel.minSegmentSec = sec
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        binding.btnProcess.setOnClickListener { viewModel.process() }

        binding.btnShare.setOnClickListener {
            val state = viewModel.state.value
            if (state is ProcessingState.Done) shareFile(state.outputFile)
        }

        binding.btnReset.setOnClickListener {
            viewModel.reset()
            binding.tvVideoName.text = getString(R.string.no_video_selected)
            binding.btnProcess.isEnabled = false
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                binding.layoutProgress.isVisible =
                    state is ProcessingState.Analyzing || state is ProcessingState.Cutting
                binding.layoutResult.isVisible = state is ProcessingState.Done
                binding.btnProcess.isEnabled =
                    state is ProcessingState.Idle && viewModel.selectedUri != null
                binding.cardSettings.isEnabled =
                    state !is ProcessingState.Analyzing && state !is ProcessingState.Cutting
                binding.btnPickVideo.isEnabled =
                    state !is ProcessingState.Analyzing && state !is ProcessingState.Cutting

                when (state) {
                    is ProcessingState.Analyzing -> {
                        binding.tvProgressLabel.text = "Analyse du mouvement… ${state.progress}%"
                        binding.progressBar.progress = state.progress
                    }
                    is ProcessingState.Cutting -> {
                        binding.tvProgressLabel.text = "Découpe en cours… ${state.progress}%"
                        binding.progressBar.progress = state.progress
                    }
                    is ProcessingState.Error -> {
                        Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                        binding.btnProcess.isEnabled = viewModel.selectedUri != null
                    }
                    else -> {}
                }
            }
        }
    }

    private fun requestVideoPermissionOrPick() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            pickVideo.launch("video/*")
        } else {
            requestPermission.launch(permission)
        }
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Partager la vidéo"))
    }
}
