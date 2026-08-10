package com.dji.recreate2.sync

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.dji.recreate2.aws.S3UploadManager
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.MediaFile
import dji.v5.manager.datacenter.media.MediaFileDownloadListener
import dji.v5.manager.datacenter.media.PullMediaFileListParam
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages ISR (Intelligence, Surveillance, and Reconnaissance) Modes.
 *
 * Mode 1: Built-In High-Res Photo Capture & S3 Direct Upload
 *   - Triggers the drone's built-in camera to take a full-resolution photo.
 *   - Downloads the original high-res MediaFile directly from camera storage.
 *   - Uploads the full-res JPEG to S3.
 *
 * Mode 2: Post-Flight Automatic S3 Batch Sync
 *   - Automatically pulls all mission photos/videos upon landing and uploads to S3.
 */
object ISRModeManager {

    private const val TAG = "ISRModeManager"

    // Valid modes: "NONE", "MODE1", "MODE2"
    var currentMode = "NONE"
        private set

    private val isrExecutor = Executors.newSingleThreadExecutor()
    private val isProcessingCapture = AtomicBoolean(false)
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Enables a specific ISR mode and disables others.
     */
    fun setMode(mode: String, context: Context) {
        appContext = context.applicationContext
        if (mode == currentMode) return

        // Clean up previous mode
        when (currentMode) {
            "MODE2" -> PostFlightS3Sync.disableAutoSync()
            "MODE1" -> {
                // Leaving Mode 1 must stop the tablet-side recorder, otherwise it keeps
                // capturing frames (and holding the encoder) after the mode is off.
                if (FpvStreamRecorder.isRecording()) {
                    Log.d(TAG, "Leaving MODE1: stopping FPV stream recorder.")
                    FpvStreamRecorder.stopRecording()
                }
                // Release any capture lock stranded by a callback that never fired.
                isProcessingCapture.set(false)
            }
        }

        currentMode = mode

        // Setup new mode
        when (currentMode) {
            "MODE1" -> {
                showToast(context, "ISR Mode 1 Enabled: High-Res S3 Capture")
                Log.d(TAG, "ISR Mode 1 Enabled (High-Res Built-In Photo Capture)")
            }
            "MODE2" -> {
                PostFlightS3Sync.enableAutoSync(context)
                showToast(context, "ISR Mode 2 Enabled: Post-Flight S3 Sync")
                Log.d(TAG, "ISR Mode 2 Enabled (Post-Flight Auto Sync)")
            }
            else -> {
                showToast(context, "ISR Modes Disabled")
                Log.d(TAG, "ISR Modes Disabled")
            }
        }
    }


    // NOTE: triggerMode1Capture() and downloadAndUploadLatestPhoto() were removed.
    //
    // They pulled the full-resolution original off the camera, which needs the media manager in
    // download mode. That puts the camera into playback and removes the pilot's live video, so it
    // must never run in flight. Nothing called them.
    //
    // Mode 1 photo capture is handled in MainActivity.capturePhoto(): it copies the FPV frame with
    // PixelCopy, writes the telemetry EXIF and sends that to S3 for immediate intel, and separately
    // triggers the aircraft shutter so the full-resolution original lands on the SD card. Mode 2
    // collects those originals after landing. Neither step disturbs the video feed.

    /**
     * Triggered when recording stops in ISR Mode 1.
     * Downloads the newly recorded MP4 video file from camera storage and streams it to S3.
     */
    fun triggerMode1VideoUpload(context: Context? = null) {
        val ctx = context?.applicationContext ?: appContext ?: run {
            Log.e(TAG, "Cannot trigger Mode 1 video upload: App Context is null")
            return
        }

        // Pulling a file off the camera needs media-download mode, which puts the camera into
        // playback and removes the pilot's live video. Never do that in flight - the recording
        // stays on the SD card and Mode 2 collects it after landing.
        if (areMotorsRunning()) {
            Log.d(TAG, "Airborne: leaving the recording on the card for the post-flight sync.")
            showToast(ctx, "ISR: video stays on the card. Mode 2 collects it after landing.")
            return
        }

        if (!isProcessingCapture.compareAndSet(false, true)) {
            Log.w(TAG, "Mode 1 media upload already in progress. Skipping.")
            return
        }

        Log.d(TAG, "Mode 1 Video Upload Triggered: Fetching recorded MP4 video...")
        showToast(ctx, "ISR Mode 1: Fetching video from drone storage...")

        isrExecutor.submit {
            downloadAndUploadLatestVideo(ctx)
        }
    }

    private fun downloadAndUploadLatestVideo(context: Context) {
        // Ensures the camera leaves playback on every exit path, so live video comes back.
        var mediaEnabled = false
        fun leaveDownloadMode() {
            if (!mediaEnabled) return
            mediaEnabled = false
            try {
                MediaDataCenter.getInstance().mediaManager?.disable(null)
            } catch (e: Exception) {
                Log.w(TAG, "Could not leave media download mode: ${e.message}")
            }
        }

        try {
            // Give camera storage 2.0s to finalize the MP4 file index
            Thread.sleep(2000)

            val mediaManager = MediaDataCenter.getInstance().mediaManager
            if (mediaManager == null) {
                Log.e(TAG, "MediaManager is null. Cannot download video.")
                leaveDownloadMode()

                isProcessingCapture.set(false)
                return
            }

            // Same lifecycle Mode 2 needs: the camera has to be in media-download mode and the
            // read source has to be named, or the list comes back empty. Safe here because this
            // path is now gated on the motors being off.
            val enableLatch = CountDownLatch(1)
            val enableOk = AtomicBoolean(false)
            mediaManager.enable(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    enableOk.set(true)
                    enableLatch.countDown()
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "Could not enter media download mode: ${error.description()}")
                    enableLatch.countDown()
                }
            })
            val enableReturned = enableLatch.await(10, TimeUnit.SECONDS)
            mediaEnabled = enableOk.get()
            if (!enableReturned || !mediaEnabled) {
                showToast(context, "ISR: camera refused media download mode.")
                leaveDownloadMode()

                isProcessingCapture.set(false)
                return
            }

            try {
                mediaManager.setMediaFileDataSource(
                    dji.v5.manager.datacenter.media.MediaFileListDataSource.Builder()
                        .setLocation(dji.sdk.keyvalue.value.camera.CameraStorageLocation.SDCARD)
                        .setIndexType(ComponentIndexType.LEFT_OR_MAIN)
                        .build()
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not set the media data source: ${e.message}")
            }

            val pullLatch = CountDownLatch(1)
            var pulledFiles: List<MediaFile> = emptyList()

            val param = PullMediaFileListParam.Builder().build()
            mediaManager.pullMediaFileListFromCamera(param, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    pulledFiles = mediaManager.mediaFileListData.data ?: emptyList()
                    pullLatch.countDown()
                }

                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "Failed to pull media file list: ${error.description()}")
                    pullLatch.countDown()
                }
            })

            val listOk = pullLatch.await(10, TimeUnit.SECONDS)
            if (!listOk || pulledFiles.isEmpty()) {
                Log.e(TAG, "Media file list is empty or timed out.")
                leaveDownloadMode()

                isProcessingCapture.set(false)
                return
            }

            // Get the latest MP4 video file
            val latestVideo = pulledFiles.filter {
                it.fileName.endsWith(".mp4", ignoreCase = true) ||
                it.fileName.endsWith(".mov", ignoreCase = true)
            }.lastOrNull()

            if (latestVideo == null) {
                Log.w(TAG, "No MP4 video file found in camera storage.")
                showToast(context, "ISR: No video file found to upload.")
                leaveDownloadMode()

                isProcessingCapture.set(false)
                return
            }

            Log.d(TAG, "Downloading latest video file: ${latestVideo.fileName} (${latestVideo.fileSize} bytes)...")

            val cacheDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "ISR_Mode1_Cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val destFile = File(cacheDir, "isr_${System.currentTimeMillis()}_${latestVideo.fileName}")
            val fos = FileOutputStream(destFile)
            val downloadLatch = CountDownLatch(1)
            val isAborted = AtomicBoolean(false)

            latestVideo.pullOriginalMediaFileFromCamera(0L, object : MediaFileDownloadListener {
                override fun onStart() {}
                override fun onProgress(total: Long, current: Long) {
                    Log.d(TAG, "Video download progress: $current / $total")
                }
                override fun onRealtimeDataUpdate(data: ByteArray, position: Long) {
                    if (isAborted.get()) return
                    try { fos.write(data) } catch (e: Exception) {}
                }
                override fun onFinish() {
                    if (isAborted.get()) return
                    try { fos.close() } catch (e: Exception) {}
                    Log.d(TAG, "Downloaded video file ${latestVideo.fileName} successfully.")
                    downloadLatch.countDown()
                }
                override fun onFailure(error: IDJIError) {
                    if (isAborted.get()) return
                    try { fos.close() } catch (e: Exception) {}
                    Log.e(TAG, "Video download failed: ${error.description()}")
                    downloadLatch.countDown()
                }
            })

            val downloadOk = downloadLatch.await(120, TimeUnit.SECONDS)
            if (!downloadOk || !destFile.exists() || destFile.length() == 0L) {
                isAborted.set(true)
                Log.e(TAG, "Video download timed out or produced an empty file.")
                if (destFile.exists()) destFile.delete()
                leaveDownloadMode()

                isProcessingCapture.set(false)
                return
            }

            Log.d(TAG, "Uploading video ${destFile.name} (${destFile.length()} bytes) to S3...")
            showToast(context, "ISR: Uploading MP4 Video to S3...")

            S3UploadManager.uploadFile(context, destFile,
                onSuccess = {
                    Log.d(TAG, "Mode 1 S3 Video Upload SUCCESS for ${destFile.name}!")
                    showToast(context, "ISR ✔ Video Uploaded to S3!")
                    if (destFile.exists()) destFile.delete()
                    leaveDownloadMode()

                    isProcessingCapture.set(false)
                },
                onError = { err ->
                    Log.e(TAG, "Mode 1 S3 Video Upload FAILED: $err")
                    showToast(context, "ISR ✗ Video Upload Failed: $err")
                    leaveDownloadMode()

                    isProcessingCapture.set(false)
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error in Mode 1 video upload pipeline", e)
            leaveDownloadMode()

            isProcessingCapture.set(false)
        }
    }

    private fun areMotorsRunning(): Boolean = try {
        KeyManager.getInstance().getValue(
            KeyTools.createKey(dji.sdk.keyvalue.key.FlightControllerKey.KeyAreMotorsOn)
        ) ?: false
    } catch (e: Exception) {
        false
    }

    private fun showToast(context: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
