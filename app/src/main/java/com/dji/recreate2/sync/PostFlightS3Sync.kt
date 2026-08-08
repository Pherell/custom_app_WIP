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

    /** Watchdog bound on the media-list pull, so a silent SDK callback cannot wedge sync. */
    private const val MEDIA_LIST_TIMEOUT_MS = 30_000L

    /** Upper bound on waiting for pipelined S3 uploads to drain before reporting completion. */
    private const val UPLOAD_DRAIN_TIMEOUT_MS = 300_000L

    // AtomicBoolean, not a @Volatile Boolean: the old `if (isSyncing) return; isSyncing = true`
    // was check-then-act, so the motor-off listener and a manual SYNC NOW could both pass.
    private val syncing = java.util.concurrent.atomic.AtomicBoolean(false)

    val isSyncing: Boolean
        get() = syncing.get()

    private var isEnabled = false

    private const val PREF_SYNCED_FILES = "isr_mode2_synced_files"

    /** Names of media files already pulled from the camera, so landings do not re-sync them. */
    private fun loadSyncedFileNames(context: Context): MutableSet<String> {
        val prefs = context.getSharedPreferences("TacticalHUDConfig", Context.MODE_PRIVATE)
        return HashSet(prefs.getStringSet(PREF_SYNCED_FILES, emptySet()) ?: emptySet())
    }

    private fun markFileSynced(context: Context, fileName: String) {
        val prefs = context.getSharedPreferences("TacticalHUDConfig", Context.MODE_PRIVATE)
        val updated = loadSyncedFileNames(context)
        updated.add(fileName)
        prefs.edit().putStringSet(PREF_SYNCED_FILES, updated).apply()
    }

    /** Clears the synced-file ledger so the next sync re-pulls everything. */
    fun resetSyncedFileLedger(context: Context) {
        context.getSharedPreferences("TacticalHUDConfig", Context.MODE_PRIVATE)
            .edit().remove(PREF_SYNCED_FILES).apply()
        Log.d(TAG, "Mode 2 synced-file ledger cleared.")
    }

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
        if (!syncing.compareAndSet(false, true)) {
            Log.w(TAG, "Mode 2 sync already in progress.")
            return
        }

        val mediaManager = MediaDataCenter.getInstance().mediaManager
        if (mediaManager == null) {
            Log.e(TAG, "MediaManager is null. Cannot sync.")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "⚠️ ALERT: Drone camera storage disconnected or empty!", Toast.LENGTH_LONG).show()
            }
            syncing.set(false)
            return
        }

        // Watchdog: if neither pullMediaFileListFromCamera callback ever fires, the flag would
        // stay true and sync would be dead for the rest of the process lifetime.
        val watchdogFor = mediaManager
        Handler(Looper.getMainLooper()).postDelayed({
            if (syncing.get() && watchdogFor === MediaDataCenter.getInstance().mediaManager) {
                Log.e(TAG, "Mode 2 media list pull timed out; releasing sync lock.")
                Toast.makeText(context, "⚠️ Mode 2: Media list timed out.", Toast.LENGTH_LONG).show()
                syncing.set(false)
            }
        }, MEDIA_LIST_TIMEOUT_MS)

        pullMediaListInternal(context, mediaManager)
    }

    private fun pullMediaListInternal(context: Context, mediaManager: dji.v5.manager.interfaces.IMediaManager) {
        val param = dji.v5.manager.datacenter.media.PullMediaFileListParam.Builder()
            .count(-1)
            .build()

        Log.d(TAG, "Pulling media file list from camera storage...")
        mediaManager.pullMediaFileListFromCamera(param, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                val fileList = mediaManager.mediaFileListData.data ?: emptyList()
                Log.d(TAG, "Successfully pulled ${fileList.size} media files from camera storage.")
                processMediaFileList(context, fileList)
            }

            override fun onFailure(error: IDJIError) {
                Log.w(TAG, "Primary pullMediaFileList failed (${error.description()}). Retrying with default param...")
                val defaultParam = dji.v5.manager.datacenter.media.PullMediaFileListParam.Builder().build()
                mediaManager.pullMediaFileListFromCamera(defaultParam, object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        val fileList = mediaManager.mediaFileListData.data ?: emptyList()
                        processMediaFileList(context, fileList)
                    }

                    override fun onFailure(err2: IDJIError) {
                        Log.e(TAG, "All media file list pulls failed: ${err2.description()}")
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "⚠️ Mode 2: Media fetch failed: [${err2.errorCode()}] ${err2.description()}", Toast.LENGTH_LONG).show()
                        }
                        syncing.set(false)
                    }
                })
            }
        })
    }

    private fun processMediaFileList(context: Context, fileList: List<MediaFile>) {
        Log.d(TAG, "Pulled ${fileList.size} raw media files from camera storage.")

        // Skip anything already pulled in a previous sync. Without this ledger the filter below
        // was "new" in name only, and EVERY landing re-downloaded and re-uploaded the entire
        // SD card. Use resetSyncedFileLedger(context) to force a full re-sync.
        val alreadySynced = loadSyncedFileNames(context)

        val mediaFiles = fileList.filter { mediaFile ->
            val isImage = mediaFile.fileName.endsWith(".jpg", ignoreCase = true) ||
                          mediaFile.fileName.endsWith(".jpeg", ignoreCase = true) ||
                          mediaFile.fileName.endsWith(".dng", ignoreCase = true)
            val isVideo = mediaFile.fileName.endsWith(".mp4", ignoreCase = true) ||
                          mediaFile.fileName.endsWith(".mov", ignoreCase = true)

            isImage || isVideo
        }
        val newFiles = mediaFiles.filter { it.fileName !in alreadySynced }
        val skipped = mediaFiles.size - newFiles.size
        if (skipped > 0) {
            Log.d(TAG, "Skipping $skipped media file(s) already synced in a previous run.")
        }

        if (newFiles.isEmpty()) {
            val msg = if (mediaFiles.isEmpty()) {
                "⚠️ Mode 2: No media files found in drone storage!"
            } else {
                "Mode 2: All ${mediaFiles.size} media file(s) already synced."
            }
            Log.d(TAG, msg)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
            syncing.set(false)
            return
        }

        Log.d(TAG, "Found ${newFiles.size} new media files for Mode 2 sync.")
        downloadAndUploadFiles(context, newFiles)
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

        val pendingUploads = java.util.concurrent.atomic.AtomicInteger(0)
        val uploadsDone = java.util.concurrent.Semaphore(0)

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

                    pendingUploads.incrementAndGet()
                    val sourceName = mediaFile.fileName
                    S3UploadManager.uploadFile(context, destFile,
                        onSuccess = {
                            val doneMsg = "[$itemIndex/$totalCount] ISR ✔ Uploaded ${destFile.name} to S3"
                            Log.d(TAG, doneMsg)
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(context, doneMsg, Toast.LENGTH_SHORT).show()
                            }
                            // Only record it as synced once S3 has actually accepted it, so a
                            // failed upload is retried on the next run.
                            markFileSynced(context, sourceName)
                            if (destFile.exists()) destFile.delete()
                            pendingUploads.decrementAndGet()
                            uploadsDone.release()
                        },
                        onError = { err ->
                            Log.e(TAG, "S3 Upload Failed for ${destFile.name}: $err")
                            pendingUploads.decrementAndGet()
                            uploadsDone.release()
                        }
                    )
                }

                // Wait for the pipelined uploads before declaring completion. The success
                // toast used to fire (and the sync lock used to release) while uploads were
                // still in flight.
                val deadline = System.currentTimeMillis() + UPLOAD_DRAIN_TIMEOUT_MS
                while (pendingUploads.get() > 0 && System.currentTimeMillis() < deadline) {
                    uploadsDone.tryAcquire(1, TimeUnit.SECONDS)
                }

                val stillPending = pendingUploads.get()
                Handler(Looper.getMainLooper()).post {
                    val msg = if (stillPending > 0) {
                        "ISR Mode 2: synced, but $stillPending upload(s) did not complete."
                    } else {
                        "ISR Mode 2 All Files Synced & Uploaded to S3!"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            } finally {
                syncing.set(false)
            }
        }.start()
    }
}
