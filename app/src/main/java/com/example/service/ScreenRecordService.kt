package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ScreenRecordState {
    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _durationSeconds = MutableStateFlow(0L)
    val durationSeconds = _durationSeconds.asStateFlow()

    private val _lastSavedUri = MutableStateFlow<String?>(null)
    val lastSavedUri = _lastSavedUri.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    fun setRecording(recording: Boolean) {
        _isRecording.value = recording
        if (!recording) {
            _durationSeconds.value = 0L
        }
    }

    fun setDuration(seconds: Long) {
        _durationSeconds.value = seconds
    }

    fun setSavedUri(uri: String?) {
        _lastSavedUri.value = uri
    }

    fun clearSavedUri() {
        _lastSavedUri.value = null
    }

    fun setError(error: String?) {
        _errorMessage.value = error
    }
}

class ScreenRecordService : Service() {

    companion object {
        const val TAG = "ScreenRecordService"
        const val CHANNEL_ID = "screen_recording_channel"
        const val NOTIFICATION_ID = 4001

        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_DENSITY = "extra_density"

        fun startService(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            width: Int,
            height: Int,
            density: Int
        ) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_DENSITY, density)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentFileDescriptor: ParcelFileDescriptor? = null
    private var currentMediaUri: Uri? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val width = intent.getIntExtra(EXTRA_WIDTH, 1080)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 1920)
                val density = intent.getIntExtra(EXTRA_DENSITY, 320)

                if (resultCode != 0 && resultData != null) {
                    startRecording(resultCode, resultData, width, height, density)
                } else {
                    Log.e(TAG, "Invalid result code or intent data for screen recording")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(
        resultCode: Int,
        resultData: Intent,
        width: Int,
        height: Int,
        density: Int
    ) {
        val initialNotification = buildNotification(0L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            if (mediaProjection == null) {
                ScreenRecordState.setError("Failed to initialize MediaProjection")
                stopSelf()
                return
            }

            // Target dimensions (ensure even numbers for encoders)
            val recordWidth = if (width % 2 == 0) width else width - 1
            val recordHeight = if (height % 2 == 0) height else height - 1

            setupMediaRecorder(recordWidth, recordHeight)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "VideoCallScreenRecord",
                recordWidth,
                recordHeight,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface,
                null,
                null
            )

            mediaRecorder?.start()
            ScreenRecordState.setRecording(true)
            ScreenRecordState.setError(null)

            startDurationTimer()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen recording", e)
            ScreenRecordState.setError("Error starting recording: ${e.message}")
            stopRecording()
            stopSelf()
        }
    }

    private fun setupMediaRecorder(width: Int, height: Int) {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mediaRecorder = recorder

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "VideoCall_Screen_$timeStamp.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoCall")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        currentMediaUri = uri

        if (uri == null) {
            throw IOException("Unable to create MediaStore video entry")
        }

        val pfd = contentResolver.openFileDescriptor(uri, "rw")
        currentFileDescriptor = pfd

        if (pfd == null) {
            throw IOException("Unable to open file descriptor for recording output")
        }

        // Configure audio & video sources
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        } catch (e: Exception) {
            Log.w(TAG, "Could not set audio source MIC, continuing without audio", e)
        }

        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setOutputFile(pfd.fileDescriptor)
        recorder.setVideoSize(width, height)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

        try {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128000)
            recorder.setAudioSamplingRate(44100)
        } catch (_: Exception) {}

        recorder.setVideoEncodingBitRate(4000000) // 4 Mbps
        recorder.setVideoFrameRate(30)

        recorder.prepare()
    }

    private fun startDurationTimer() {
        val startTime = System.currentTimeMillis()
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                ScreenRecordState.setDuration(elapsed)
                updateNotification(elapsed)
            }
        }
    }

    private fun updateNotification(elapsedSeconds: Long) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(elapsedSeconds))
    }

    private fun buildNotification(elapsedSeconds: Long): Notification {
        val mins = elapsedSeconds / 60
        val secs = elapsedSeconds % 60
        val timeString = String.format("%02d:%02d", mins, secs)

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording Video Call ($timeString)")
            .setContentText("Screen and audio recording is active in Movies/VideoCall")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingOpenApp)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop Recording",
                pendingStop
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Call Screen Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active screen and audio recording status and controls"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun stopRecording() {
        timerJob?.cancel()
        timerJob = null

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaRecorder", e)
        }
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null

        try {
            currentFileDescriptor?.close()
        } catch (_: Exception) {}
        currentFileDescriptor = null

        // Mark as no longer pending in MediaStore
        currentMediaUri?.let { uri ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                try {
                    contentResolver.update(uri, updateValues, null, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update IS_PENDING on recorded uri", e)
                }
            }
            ScreenRecordState.setSavedUri(uri.toString())
        }

        ScreenRecordState.setRecording(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}
