package com.autocut.videocut

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class ProcessingState {
    object Idle : ProcessingState()
    data class Analyzing(val progress: Int) : ProcessingState()
    data class Cutting(val progress: Int) : ProcessingState()
    data class Done(val outputFile: File) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val analyzer = VideoAnalyzer(app)
    private val processor = VideoProcessor(app)

    private val _state = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val state: StateFlow<ProcessingState> = _state

    var selectedUri: Uri? = null
    var sensitivity: Float = 0.5f
    var minSegmentSec: Float = 0.5f

    fun process() {
        val uri = selectedUri ?: return
        viewModelScope.launch {
            try {
                _state.value = ProcessingState.Analyzing(0)
                val segments = analyzer.findActiveSegments(
                    uri = uri,
                    sensitivity = sensitivity,
                    minSegmentMs = (minSegmentSec * 1000).toLong(),
                    onProgress = { _state.value = ProcessingState.Analyzing(it) }
                )

                if (segments.isEmpty()) {
                    _state.value = ProcessingState.Error("Aucun segment avec mouvement détecté. Baissez la sensibilité.")
                    return@launch
                }

                _state.value = ProcessingState.Cutting(0)
                val output = processor.process(
                    inputUri = uri,
                    segments = segments,
                    onProgress = { _state.value = ProcessingState.Cutting(it) }
                )

                _state.value = if (output != null) ProcessingState.Done(output)
                else ProcessingState.Error("Erreur lors du traitement FFmpeg.")
            } catch (e: Exception) {
                _state.value = ProcessingState.Error(e.message ?: "Erreur inconnue")
            }
        }
    }

    fun reset() {
        _state.value = ProcessingState.Idle
        selectedUri = null
    }
}
