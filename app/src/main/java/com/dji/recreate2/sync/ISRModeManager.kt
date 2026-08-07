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

    /**
     * Triggered manually or by a C2 mission event in Mode 1.
     * Takes a built-in high-res photo, downloads original MediaFile from drone camera,
     * and streams it to S3.
     */
    fun triggerMode1Capture(context: Context? = null) {
        val ctx = context?.applicationContext ?: appContext ?: run {
            Log.e(TAG, "Cannot trigger Mode 1 capture: App Context is null")
            return
        }

        if (!isProcessingCapture.compareAndSet(false, true)) {
            Log.w(TAG, "Mode 1 capture already in progress. Skipping.")
            return
        }

        val captureStartTime = System.currentTimeMillis()
        Log.d(TAG, "Mode 1 Triggered: Executing built-in photo capture at $captureStartTime...")
        showToast(ctx, "ISR Target Capture Initiated...")

        // Step 1: Trigger built-in camera photo shoot
        val shootKey = KeyTools.createKey(
            CameraKey.KeyStartShootPhoto,
            ComponentIndexType.LEFT_OR_MAIN
        )

        KeyManager.getInstance().performAction(shootKey, object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(msg: EmptyMsg?) {
                Log.d(TAG, "Built-in Photo Shoot Action Succeeded. Fetching media file...")
                // Step 2: Download original MediaFile & Upload to S3 on background thread
                isrExecutor.submit {
                    downloadAndUploadLatestPhoto(ctx, captureStartTime)
                }
            }

            override fun onFailure(error: IDJIError) {
                Log.e(TAG, "Failed to shoot photo: ${error.description()} (Code: ${error.errorCode()})")
                showToast(ctx, "ISR Capture Error: ${error.description()}")
                isProcessingCapture.set(false)
            }
        })
    }

    /**
     * Pulls the latest MediaFile from camera storage, downloads the full original JPEG,
     * and uploads it to S3.
     */
    private fun downloadAndUploadLatestPhoto(context: Context, captureStartTime: Long = 0L) {
        try {
            val mediaManager = MediaDataCenter.getInstance().mediaManager
            if (mediaManager == null) {
                Log.e(TAG, "MediaManager is null. Cannot download photo.")
                isProcessingCapture.set(false)
                return
            }

            var latestFile: MediaFile? = null
            var attempts = 0
            val maxAttempts = 3

            while (attempts < maxAttempts && latestFile == null) {
                attempts++
                Thread.sleep(if (attempts == 1) 1500L else 1000L) // Wait for media indexer

                val pullLatch = CountDownLatch(1)
                var pulledFiles: List<MediaFile> = emptyList()

                val param = PullMediaFileListParam.Builder().build()
                mediaManager.pullMediaFileListFromCamera(param, object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        pulledFiles = mediaManager.mediaFileListData.data ?: emptyList()
                        pullLatch.countDown()
                    }

                    override fun onFailure(error: IDJIError) {
                        Log.e(TAG, "Failed to pull media file list (Attempt $attempts): ${error.description()}")
                        pullLatch.countDown()
                    }
                })

                val listOk = pullLatch.await(10, TimeUnit.SECONDS)
                if (listOk && pulledFiles.isNotEmpty()) {
                    val candidatePhotos = pulledFiles.filter {
                        it.fileName.endsWith(".jpg", ignoreCase = true) ||
                        it.fileName.endsWith(".jpeg", ignoreCase = true) ||
                        it.fileName.endsWith(".dng", ignoreCase = true)
                    }
                    if (candidatePhotos.isNotEmpty()) {
                        latestFile = candidatePhotos.last()
                    }
                }
            }

            if (latestFile == null) {
                Log.e(TAG, "Media file list is empty or timed out after $maxAttempts attempts.")
                isProcessingCapture.set(false)
                return
            }

            Log.d(TAG, "Downloading latest high-res photo: ${latestFile.fileName} (${latestFile.fileSize} bytes)...")

            // Download original file to local cache
            val cacheDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "ISR_Mode1_Cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val destFile = File(cacheDir, "isr_${System.currentTimeMillis()}_${latestFile.fileName}")
            val fos = FileOutputStream(destFile)
            val downloadLatch = CountDownLatch(1)
            val isAborted = AtomicBoolean(false)

            latestFile.pullOriginalMediaFileFromCamera(0L, object : MediaFileDownloadListener {
                override fun onStart() {}
                override fun onProgress(total: Long, current: Long) {}
                override fun onRealtimeDataUpdate(data: ByteArray, position: Long) {
                    if (isAborted.get()) return
                    try { fos.write(data) } catch (e: Exception) {}
                }
                override fun onFinish() {
                    if (isAborted.get()) return
                    try { fos.close() } catch (e: Exception) {}
                    Log.d(TAG, "Downloaded high-res file ${latestFile.fileName} successfully.")
                    downloadLatch.countDown()
                }
                override fun onFailure(error: IDJIError) {
                    if (isAborted.get()) return
                    try { fos.close() } catch (e: Exception) {}
                    Log.e(TAG, "Download failed for ${latestFile.fileName}: ${error.description()}")
                    downloadLatch.countDown()
                }
            })

            val downloadOk = downloadLatch.await(60, TimeUnit.SECONDS)
            if (!downloadOk || !destFile.exists() || destFile.length() == 0L) {
                isAborted.set(true)
                Log.e(TAG, "Download timed out or produced an empty file.")
                if (destFile.exists()) destFile.delete()
                isProcessingCapture.set(false)
                return
            }

            // Upload high-res photo file to S3
            Log.d(TAG, "Uploading high-res photo ${destFile.name} (${destFile.length()} bytes) to S3...")
            S3UploadManager.uploadFile(context, destFile,
                onSuccess = {
                    Log.d(TAG, "Mode 1 S3 High-Res Upload SUCCESS for ${destFile.name}!")
                    showToast(context, "ISR Photo Uploaded to S3!")
                    if (destFile.exists()) destFile.delete()
                    isProcessingCapture.set(false)
                },
                onError = { err ->
                    Log.e(TAG, "Mode 1 S3 High-Res Upload FAILED: $err")
                    showToast(context, "ISR S3 Upload Failed: $err")
                    isProcessingCapture.set(false)
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error in Mode 1 photo capture pipeline", e)
            isProcessingCapture.set(false)
        }
    }

    /**
     * Triggered when recording stops in ISR Mode 1.
     * Downloads the newly recorded MP4 video file from camera storage and streams it to S3.
     */
    fun triggerMode1VideoUpload(context: Context? = null) {
        val ctx = context?.applicationContext ?: appContext ?: run {
            Log.e(TAG, "Cannot trigger Mode 1 video upload: App Context is null")
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
        try {
            // Give camera storage 2.0s to finalize the MP4 file index
            Thread.sleep(2000)

            val mediaManager = MediaDataCenter.getInstance().mediaManager
            if (mediaManager == null) {
                Log.e(TAG, "MediaManager is null. Cannot download video.")
                isProcessingCapture.set(false)
                return
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
                    isProcessingCapture.set(false)
                },
                onError = { err ->
                    Log.e(TAG, "Mode 1 S3 Video Upload FAILED: $err")
                    showToast(context, "ISR ✗ Video Upload Failed: $err")
                    isProcessingCapture.set(false)
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error in Mode 1 video upload pipeline", e)
            isProcessingCapture.set(false)
        }
    }

    private fun showToast(context: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
