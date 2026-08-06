package com.dji.recreate2.sync

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.dji.recreate2.aws.S3UploadManager
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.MediaFile
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles Mode 2 ISR: Native Drone Capture and Post-Flight Sync to S3 (https://asura-s3.stellardata.ai).
 * Listens for the drone to land (motors off) and automatically downloads/uploads media.
 */
object PostFlightS3Sync {

    private const val TAG = "PostFlightS3Sync"
    
    @Volatile
    var isSyncing = false
    
    private var isEnabled = false
    private var currentMissionStartTime = 0L

    fun enableAutoSync(context: Context) {
        val appCtx = context.applicationContext
        isEnabled = true
        Log.d(TAG, "Mode 2 Auto-Sync Enabled. Checking current motor state...")
        
        val motorsOnKey = KeyTools.createKey(FlightControllerKey.KeyAreMotorsOn)
        
        // 1. Check current motor state. If motors are ALREADY OFF, trigger sync immediately!
        val currentMotorsOn = KeyManager.getInstance().getValue(motorsOnKey) ?: false
        if (!currentMotorsOn && isEnabled) {
            Log.d(TAG, "Drone is already landed (motors OFF). Triggering Mode 2 sync now...")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appCtx, "ISR Mode 2 (Landed): Fetching media from drone SD Card...", Toast.LENGTH_LONG).show()
            }
            startSync(appCtx)
        }

        // 2. Listen for future motor state transitions (motors turning OFF after flight)
        KeyManager.getInstance().listen(motorsOnKey, this) { oldValue, newValue ->
            if (oldValue == true && newValue == false) {
                if (isEnabled) {
                    Log.d(TAG, "Motors turned off after flight. Triggering Mode 2 Post-Flight Sync...")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(appCtx, "ISR Mode 2 (Post-Flight): Downloading media from drone SD Card...", Toast.LENGTH_LONG).show()
                    }
                    startSync(appCtx)
                }
            }
        }
    }

    fun disableAutoSync() {
        isEnabled = false
        Log.d(TAG, "Mode 2 Auto-Sync Disabled.")
        val motorsOnKey = KeyTools.createKey(FlightControllerKey.KeyAreMotorsOn)
        KeyManager.getInstance().cancelListen(motorsOnKey, this)
    }

    fun startSync(context: Context) {
        if (isSyncing) {
            Log.w(TAG, "Mode 2 sync already in progress.")
            return
        }
        isSyncing = true

        val mediaManager = MediaDataCenter.getInstance().mediaManager
        if (mediaManager == null) {
            Log.e(TAG, "MediaManager is null. Cannot sync.")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "⚠️ ALERT: Drone camera storage disconnected or empty!", Toast.LENGTH_LONG).show()
            }
            isSyncing = false
            return
        }

        pullMediaListInternal(context, mediaManager)
    }

    private fun pullMediaListInternal(context: Context, mediaManager: dji.v5.manager.interfaces.IMediaManager) {
        val param = dji.v5.manager.datacenter.media.PullMediaFileListParam.Builder().build()
        mediaManager.pullMediaFileListFromCamera(param, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                val fileList = mediaManager.mediaFileListData.data ?: emptyList()
                Log.d(TAG, "Pulled ${fileList.size} media files from camera storage.")

                // Filter for image and video files
                val newFiles = fileList.filter { mediaFile ->
                    val isImage = mediaFile.fileName.endsWith(".jpg", ignoreCase = true) || 
                                  mediaFile.fileName.endsWith(".jpeg", ignoreCase = true) ||
                                  mediaFile.fileName.endsWith(".dng", ignoreCase = true)
                    val isVideo = mediaFile.fileName.endsWith(".mp4", ignoreCase = true) ||
                                  mediaFile.fileName.endsWith(".mov", ignoreCase = true)
                    
                    isImage || isVideo
                }

                if (newFiles.isEmpty()) {
                    Log.d(TAG, "No media files found to sync in camera storage.")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "⚠️ Mode 2: No media files found in drone storage!", Toast.LENGTH_LONG).show()
                    }
                    isSyncing = false
                    return
                }

                Log.d(TAG, "Found ${newFiles.size} media files for Mode 2 sync.")
                downloadAndUploadFiles(context, newFiles)
            }

            override fun onFailure(error: IDJIError) {
                Log.e(TAG, "Failed to pull media file list: ${error.description()}")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "⚠️ Mode 2: Media fetch failed: ${error.description()}", Toast.LENGTH_LONG).show()
                }
                isSyncing = false
            }
        })
    }

    fun getLocalFetchFolderName(context: Context): String {
        val prefs = context.getSharedPreferences("TacticalHUDConfig", Context.MODE_PRIVATE)
        return prefs.getString("local_fetch_folder", "ISR_Mode2_Sync") ?: "ISR_Mode2_Sync"
    }

    fun saveLocalFetchFolderName(context: Context, folderName: String) {
        val prefs = context.getSharedPreferences("TacticalHUDConfig", Context.MODE_PRIVATE)
        val cleanName = folderName.trim().replace("/", "_").replace("\\", "_")
        if (cleanName.isNotEmpty()) {
            prefs.edit().putString("local_fetch_folder", cleanName).apply()
            Log.d(TAG, "Local fetch folder saved: $cleanName")
        }
    }

    fun getLocalFetchDirectory(context: Context): File {
        val folderName = getLocalFetchFolderName(context)
        val storageDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), folderName)
        if (!storageDir.exists()) storageDir.mkdirs()
        return storageDir
    }

    var onSyncProgressUpdate: ((status: String, currentItem: Int, totalItems: Int, progressPct: Int) -> Unit)? = null

    private fun downloadAndUploadFiles(context: Context, droneFiles: List<MediaFile>) {
        val storageDir = getLocalFetchDirectory(context)
        val totalCount = droneFiles.size

        Thread {
            try {
                for ((index, mediaFile) in droneFiles.withIndex()) {
                    val itemIndex = index + 1
                    val destFile = File(storageDir, mediaFile.fileName)
                    val fos = java.io.FileOutputStream(destFile)
                    val isClosed = AtomicBoolean(false)
                    val isAborted = AtomicBoolean(false)
                    val downloadLatch = CountDownLatch(1)
                    
                    fun safeCloseStream() {
                        if (isClosed.compareAndSet(false, true)) {
                            try { fos.close() } catch (e: Exception) {}
                        }
                    }
                    
                    val initialStatus = "[$itemIndex/$totalCount] Downloading ${mediaFile.fileName} from Drone SD Card (0%)..."
                    Log.d(TAG, initialStatus)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, initialStatus, Toast.LENGTH_SHORT).show()
                    }
                    onSyncProgressUpdate?.invoke(initialStatus, itemIndex, totalCount, 0)

                    var lastProgressLogMs = 0L

                    mediaFile.pullOriginalMediaFileFromCamera(0L, object : dji.v5.manager.datacenter.media.MediaFileDownloadListener {
                        override fun onStart() {}
                        override fun onProgress(total: Long, current: Long) {
                            if (total > 0) {
                                val pct = ((current * 100) / total).toInt()
                                val now = System.currentTimeMillis()
                                if (now - lastProgressLogMs >= 2000L || pct == 100) {
                                    lastProgressLogMs = now
                                    val mbCurrent = String.format(java.util.Locale.US, "%.1f", current / (1024.0 * 1024.0))
                                    val mbTotal = String.format(java.util.Locale.US, "%.1f", total / (1024.0 * 1024.0))
                                    val statusStr = "[$itemIndex/$totalCount] SD Download: ${mediaFile.fileName} ($pct% - ${mbCurrent}MB / ${mbTotal}MB)"
                                    Log.d(TAG, statusStr)
                                    onSyncProgressUpdate?.invoke(statusStr, itemIndex, totalCount, pct)
                                }
                            }
                        }
                        override fun onRealtimeDataUpdate(data: ByteArray, position: Long) {
                            if (isAborted.get()) return
                            try { fos.write(data) } catch (e: Exception) {}
                        }
                        override fun onFinish() {
                            if (isAborted.get()) return
                            safeCloseStream()
                            Log.d(TAG, "Downloaded ${mediaFile.fileName} from drone SD card successfully.")
                            downloadLatch.countDown()
                        }
                        override fun onFailure(error: IDJIError) {
                            if (isAborted.get()) return
                            safeCloseStream()
                            Log.e(TAG, "Failed to download ${mediaFile.fileName}: ${error.description()}")
                            downloadLatch.countDown()
                        }
                    })
                    
                    // Wait for download from drone SD card to finish
                    val success = downloadLatch.await(180, TimeUnit.SECONDS)
                    if (!success) {
                        isAborted.set(true)
                        safeCloseStream()
                        Log.e(TAG, "Timeout downloading ${mediaFile.fileName} from drone.")
                        if (destFile.exists()) destFile.delete()
                        continue
                    }
                    safeCloseStream()

                    // Validate downloaded file before uploading
                    if (!destFile.exists() || destFile.length() == 0L) {
                        Log.e(TAG, "Downloaded file is empty or missing: ${mediaFile.fileName}. Skipping upload.")
                        if (destFile.exists()) destFile.delete()
                        continue
                    }

                    // PIPELINING: Start S3 upload asynchronously so next drone SD download can start immediately!
                    val uploadStatus = "[$itemIndex/$totalCount] Uploading ${destFile.name} to S3..."
                    Log.d(TAG, uploadStatus)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, uploadStatus, Toast.LENGTH_SHORT).show()
                    }

                    S3UploadManager.uploadFile(context, destFile,
                        onSuccess = {
                            val doneMsg = "[$itemIndex/$totalCount] ISR ✔ Uploaded ${destFile.name} to S3"
                            Log.d(TAG, doneMsg)
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(context, doneMsg, Toast.LENGTH_SHORT).show()
                            }
                            if (destFile.exists()) destFile.delete()
                        },
                        onError = { err ->
                            Log.e(TAG, "S3 Upload Failed for ${destFile.name}: $err")
                        }
                    )
                }
                
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "ISR Mode 2 All Files Synced & Uploaded to S3!", Toast.LENGTH_LONG).show()
                }
            } finally {
                isSyncing = false
            }
        }.start()
    }
}
