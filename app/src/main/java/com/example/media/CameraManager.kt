package com.example.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

class CameraManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var isFrontCamera = true

    private val _isCameraActive = MutableStateFlow(true)
    val isCameraActive: StateFlow<Boolean> = _isCameraActive.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    // Recording state
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSeconds = MutableStateFlow(0L)
    val recordingDurationSeconds: StateFlow<Long> = _recordingDurationSeconds.asStateFlow()

    private var audioJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var recordingTimerJob: Job? = null

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onReady: () -> Unit = {}
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                rebindCamera(lifecycleOwner, previewView)
                onReady()
            } catch (e: Exception) {
                Log.e("CameraManager", "Failed to bind camera", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun rebindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return
        try {
            provider.unbindAll()

            if (!_isCameraActive.value) {
                return
            }

            val cameraSelector = if (isFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.HD, Quality.SD, Quality.LOWEST),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.LOWEST)
            )
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            val vc = VideoCapture.withOutput(recorder)
            videoCapture = vc

            provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, vc)
        } catch (e: Exception) {
            Log.e("CameraManager", "Error in rebindCamera", e)
        }
    }

    fun toggleCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        _isCameraActive.value = !_isCameraActive.value
        rebindCamera(lifecycleOwner, previewView)
    }

    fun switchLens(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        isFrontCamera = !isFrontCamera
        rebindCamera(lifecycleOwner, previewView)
    }

    fun toggleMic() {
        _isMicMuted.value = !_isMicMuted.value
    }

    fun setInitialMuteState(muted: Boolean, videoOff: Boolean, lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        _isMicMuted.value = muted
        _isCameraActive.value = !videoOff
        rebindCamera(lifecycleOwner, previewView)
    }

    // --- Audio Metering ---

    fun startAudioMonitoring(scope: CoroutineScope) {
        stopAudioMonitoring()
        audioJob = scope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 44100
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

                if (bufferSize > 0) {
                    val record = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )
                    audioRecord = record

                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        record.startRecording()
                        val buffer = ShortArray(bufferSize / 2)

                        while (isActive) {
                            if (_isMicMuted.value) {
                                _audioLevel.value = 0f
                                delay(100)
                                continue
                            }

                            val read = record.read(buffer, 0, buffer.size)
                            if (read > 0) {
                                var max = 0
                                for (i in 0 until read) {
                                    val absVal = abs(buffer[i].toInt())
                                    if (absVal > max) max = absVal
                                }
                                val normalized = (max.toFloat() / 32767f).coerceIn(0f, 1f)
                                _audioLevel.value = normalized
                            }
                            delay(60)
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.w("CameraManager", "Microphone permission not granted for level monitoring")
            } catch (e: Exception) {
                Log.w("CameraManager", "Audio monitoring error: ${e.message}")
            }
        }
    }

    fun stopAudioMonitoring() {
        audioJob?.cancel()
        audioJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        _audioLevel.value = 0f
    }

    // --- Video/Audio Call Recording via MediaStore ---

    fun startRecording(
        scope: CoroutineScope,
        onDurationUpdate: (Long) -> Unit = {},
        onRecordingStarted: () -> Unit = {},
        onRecordingFinalized: (Uri?) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val vc = videoCapture
        if (vc == null) {
            onError("Camera recorder is not ready")
            return
        }

        if (activeRecording != null) {
            stopRecording()
        }

        val name = "VideoCall_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoCall")
            }
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        val pending = vc.output.prepareRecording(context, mediaStoreOutput)

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            try {
                pending.withAudioEnabled()
            } catch (e: SecurityException) {
                Log.w("CameraManager", "Cannot enable audio recording without permission", e)
            }
        }

        val recordingStartMillis = System.currentTimeMillis()
        activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    _isRecording.value = true
                    _recordingDurationSeconds.value = 0L
                    onDurationUpdate(0L)
                    onRecordingStarted()

                    recordingTimerJob?.cancel()
                    recordingTimerJob = scope.launch {
                        while (isActive && _isRecording.value) {
                            delay(1000)
                            val elapsed = (System.currentTimeMillis() - recordingStartMillis) / 1000
                            _recordingDurationSeconds.value = elapsed
                            onDurationUpdate(elapsed)
                        }
                    }
                }
                is VideoRecordEvent.Finalize -> {
                    _isRecording.value = false
                    recordingTimerJob?.cancel()
                    activeRecording = null
                    if (!event.hasError()) {
                        val uri = event.outputResults.outputUri
                        onRecordingFinalized(uri)
                    } else {
                        Log.e("CameraManager", "Video recording finalized with error: ${event.error}")
                        onError("Recording failed: ${event.error}")
                    }
                }
            }
        }
    }

    fun stopRecording() {
        recordingTimerJob?.cancel()
        activeRecording?.stop()
        activeRecording = null
        _isRecording.value = false
    }

    fun release() {
        stopRecording()
        stopAudioMonitoring()
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        cameraProvider = null
    }
}
