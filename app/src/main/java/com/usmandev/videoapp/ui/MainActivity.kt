package com.usmandev.videoapp.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.LiveData
import androidx.work.*
import com.usmandev.videoapp.R
import com.usmandev.videoapp.databinding.ActivityMainBinding
import com.usmandev.videoapp.utils.Constants.FILENAME_FORMAT
import com.usmandev.videoapp.utils.logDebug
import com.usmandev.videoapp.utils.logError
import com.usmandev.videoapp.utils.logInfo
import com.usmandev.videoapp.utils.showShortToast
import com.usmandev.videoapp.worker.VideoOverlayWorker
import com.usmandev.videoapp.worker.WorkerKeys
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
        ).apply { if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE) }.toTypedArray()
    }

    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var cameraExecutor: ExecutorService

    private lateinit var recordingName: String

    private val fFmpegCommands: ArrayList<String> = arrayListOf()
    private lateinit var videoOverlayWorkerRequestBuilder: OneTimeWorkRequest.Builder
    private lateinit var videoOverlayWorkerRequest: OneTimeWorkRequest
    private lateinit var workManager: WorkManager
    private lateinit var workInfoList: LiveData<List<WorkInfo>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initialize()
        setListeners()
    }

    private fun initialize() {
        handleCameraPermissionsAndStartCamera()

        cameraExecutor = Executors.newSingleThreadExecutor()
        videoOverlayWorkerRequestBuilder = OneTimeWorkRequestBuilder<VideoOverlayWorker>()
        workManager = WorkManager.getInstance(applicationContext)
        workInfoList = workManager.getWorkInfosForUniqueWorkLiveData(WorkerKeys.VIDEO_OVERLAY_WORKER)
        workInfoList.observe(this) { workInfoList ->
            logInfo(workInfoList.toString(), "WORK_INFO_LIST")
        }
    }

    private fun handleCameraPermissionsAndStartCamera() {
        if (allPermissionsGranted())
            startCamera()
        else
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
            } catch (ex: Exception) {
                logError(TAG, "Use case binding failed", ex)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return

        binding.videoCaptureButton.isEnabled = false

        val curRecording = recording
        if (curRecording != null) {
            curRecording.stop()
            recording = null
            return
        }

        recordingName = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val name = recordingName
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, getString(R.string.app_video_dir))
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()
        recording = videoCapture.output.prepareRecording(this, mediaStoreOutputOptions).apply {
            if (PermissionChecker.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO
                ) != PermissionChecker.PERMISSION_GRANTED
            ) return
            withAudioEnabled()
        }.start(ContextCompat.getMainExecutor(this)) { recordEvent ->
            when (recordEvent) {
                is VideoRecordEvent.Start -> {
                    binding.videoCaptureButton.apply {
                        text = getString(R.string.stop_capture)
                        isEnabled = true
                    }
                    showShortToast("Recording Started.")
                }
                is VideoRecordEvent.Finalize -> {
                    applyOverLayFilterOnVideoRecorded(recordEvent.outputResults.outputUri)
                    startOverLayFilterOnRecordedVideoWorker()
                    binding.videoCaptureButton.apply {
                        text = getString(R.string.start_capture)
                        isEnabled = true
                    }
                    if (recordEvent.hasError()) {
                        recording?.close()
                        recording = null
                        logError(TAG, "Video capture ends with error: ${recordEvent.error}")
                        return@start
                    }
                    val msg = "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                    showShortToast(msg)
                    logDebug(TAG, msg)
                }
            }
        }
    }

    private fun applyOverLayFilterOnVideoRecorded(outputUri: Uri) {
        val directory = File(MediaStore.Video.Media.EXTERNAL_CONTENT_URI.path + "/" + getString(R.string.app_video_dir))
        if (!directory.exists()) directory.mkdir()
        val filteredVideoFile = File(directory, "FilteredFile.mp4")
        val command =
            "ffmpeg -i ${directory.absolutePath + "/$recordingName.mp4"} -i logo.png -filter_complex \"overlay=(main_w-overlay_w)/2:(main_h-overlay_h)/2\" -codec:a copy $filteredVideoFile"
        fFmpegCommands.add(command)
    }

    private fun startOverLayFilterOnRecordedVideoWorker() {
        videoOverlayWorkerRequestBuilder.setInputData(Data.Builder().apply {
            putStringArray(WorkerKeys.FFMPEG_COMMANDS, fFmpegCommands.toTypedArray())
        }.build())
        videoOverlayWorkerRequest = videoOverlayWorkerRequestBuilder.build()
        workManager.beginUniqueWork(
            WorkerKeys.VIDEO_OVERLAY_WORKER,
            ExistingWorkPolicy.REPLACE,
            videoOverlayWorkerRequest
        ).enqueue()
    }

    private fun allPermissionsGranted() =
        REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED }

    private fun setListeners() {
        binding.videoCaptureButton.setOnClickListener { captureVideo() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted())
                startCamera()
            else
                showShortToast("Permissions not granted by the user.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

}