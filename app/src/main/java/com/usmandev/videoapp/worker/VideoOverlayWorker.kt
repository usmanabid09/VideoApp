package com.usmandev.videoapp.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.github.hiteshsondhi88.libffmpeg.ExecuteBinaryResponseHandler
import com.github.hiteshsondhi88.libffmpeg.FFmpeg
import com.github.hiteshsondhi88.libffmpeg.LoadBinaryResponseHandler
import com.usmandev.videoapp.utils.logInfo

class VideoOverlayWorker(
    private val context: Context,
    private val workerParameters: WorkerParameters
) : CoroutineWorker(context, workerParameters) {

    private lateinit var fFmpeg: FFmpeg
    private lateinit var commands: Array<String>

    override suspend fun doWork(): Result {
        commands = workerParameters.inputData.getStringArray(WorkerKeys.FFMPEG_COMMANDS)
            ?: return Result.failure(workDataOf(WorkerKeys.FFMPEG_ERROR to WorkerKeys.FFMPEG_COMMANDS_NOT_FOUND_ERROR))

        loadFFMpegService()
        executeFFMpegCommands()
        return Result.success()
    }

    private fun loadFFMpegService() {
        if (this::fFmpeg.isInitialized) return
        fFmpeg = FFmpeg.getInstance(context)
        fFmpeg.loadBinary(object : LoadBinaryResponseHandler() {
            override fun onSuccess() {
                super.onSuccess()
                logInfo("onSuccess", "FFMPEG_LOAD")
            }

            override fun onFailure() {
                super.onFailure()
                logInfo("onFailure", "FFMPEG_LOAD")
            }
        })
    }

    private fun executeFFMpegCommands() {
        logInfo("executeFFMpegCommands: ${commands.contentToString()}", "FFMPEG_EXECUTE")
        fFmpeg.execute(commands, object : ExecuteBinaryResponseHandler() {
            override fun onStart() {
                super.onStart()
                logInfo("onStart", "FFMPEG_EXECUTE")
            }

            override fun onProgress(message: String?) {
                super.onProgress(message)
                logInfo("onProgress", "FFMPEG_EXECUTE")
            }

            override fun onSuccess(message: String?) {
                super.onSuccess(message)
                logInfo("onSuccess", "FFMPEG_EXECUTE")
            }

            override fun onFailure(message: String?) {
                super.onFailure(message)
                logInfo("onFailure", "FFMPEG_EXECUTE")
            }

            override fun onFinish() {
                super.onFinish()
                logInfo("onFinish", "FFMPEG_EXECUTE")
            }
        })
    }

}