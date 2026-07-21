package com.dji.recreate2

import android.content.Context
import android.os.Environment
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.MediaFile
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError

object WebODMAutoUpload {

    private const val TAG = "WebODMAutoUpload"
    private const val PREFS_NAME = "WebODMConfig"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_PROJECT_ID = "project_id"
    
    var statusListener: ((String, Boolean) -> Unit)? = null
    
    @Volatile
    private var isDownloading = false
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(600, TimeUnit.SECONDS) // 10 minutes timeout for bulk upload
        .writeTimeout(600, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false) // Fix BUG-20: Prevent body buffering and memory overhead
        .build()

    fun getServerUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_URL, "http://192.168.1.100:8000") ?: "http://192.168.1.100:8000"
    }
    
    fun getUsername(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_USERNAME, "") ?: ""
    }
    
    fun getPassword(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PASSWORD, "") ?: ""
    }
    
    fun getProjectId(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_PROJECT_ID, -1)
    }

    fun saveServerConfig(context: Context, url: String, username: String, pass: String, projectId: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SERVER_URL, url)
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, pass)
            .putInt(KEY_PROJECT_ID, projectId)
            .apply()
    }

    fun startSyncProcess(context: Context, isQuickMode: Boolean, missionStartTime: Long = 0L, missionEndTime: Long = Long.MAX_VALUE) {
        if (isDownloading) return
        isDownloading = true
        
        if (isQuickMode) {
            Log.d(TAG, "Starting QUICK sync process (Uploading local screenshots)")
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Recreate2_QuickMap")
            val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg", true) } ?: emptyList()
            if (files.isEmpty()) {
                Log.d(TAG, "No quick map screenshots found.")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, "No Quick Map screenshots to upload.", android.widget.Toast.LENGTH_SHORT).show()
                }
                isDownloading = false
                return
            }
            
            Thread {
                try {
                    createWebOdmTask(context, files)
                } finally {
                    isDownloading = false
                }
            }.start()
            return
        }
        
        Log.d(TAG, "Starting NATIVE sync process from Drone -> Phone -> WebODM")
        
        val mediaDataCenter = MediaDataCenter.getInstance()
        val mediaManager = mediaDataCenter.mediaManager
        
        if (mediaManager == null) {
            Log.e(TAG, "MediaManager is null. Drone not connected or doesn't support playback.")
            isDownloading = false
            return
        }
        
        val param = dji.v5.manager.datacenter.media.PullMediaFileListParam.Builder().build()
        mediaManager.pullMediaFileListFromCamera(param, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                val fileList = mediaManager.mediaFileListData.data
                val photoFiles = fileList.filter { mediaFile -> 
                    val isImage = mediaFile.fileName.endsWith(".jpg", ignoreCase = true) || mediaFile.fileName.endsWith(".jpeg", ignoreCase = true)
                    
                    var fileTimestamp = 0L
                    try {
                        val timeStr = mediaFile.javaClass.getMethod("getCreateTime").invoke(mediaFile) as? String
                        if (timeStr != null) {
                            val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                            val date = format.parse(timeStr)
                            if (date != null) {
                                fileTimestamp = date.time
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Gagal mendapatkan timestamp file: ${mediaFile.fileName}")
                    }
                    
                    val isFromThisMission = if (missionStartTime > 0L && fileTimestamp > 0L) {
                        fileTimestamp in missionStartTime..missionEndTime
                    } else {
                        true
                    }
                    
                    isImage && isFromThisMission
                }
                
                if (photoFiles.isEmpty()) {
                    Log.d(TAG, "No photos found on drone.")
                    isDownloading = false
                    return
                }
                
                Log.d(TAG, "Found ${photoFiles.size} photos. Starting download...")
                downloadAndUploadPhotos(context, photoFiles)
            }
            
            override fun onFailure(error: IDJIError) {
                Log.e(TAG, "Failed to pull media file list: ${error.description()}")
                isDownloading = false
            }
        })
    }

    private fun downloadAndUploadPhotos(context: Context, droneFiles: List<MediaFile>) {
        val storageDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "MappingMissions")
        if (!storageDir.exists()) storageDir.mkdirs()
        val downloadedFiles = java.util.concurrent.CopyOnWriteArrayList<File>()

        Thread {
            try {
                for (mediaFile in droneFiles) {
                    val destFile = File(storageDir, mediaFile.fileName)
                    val fos = java.io.FileOutputStream(destFile)
                    
                    val isAborted = java.util.concurrent.atomic.AtomicBoolean(false) // Fix BUG-21: Prevent background streams writing to closed files on timeout
                    val downloadLatch = java.util.concurrent.CountDownLatch(1)
                    mediaFile.pullOriginalMediaFileFromCamera(0L, object : dji.v5.manager.datacenter.media.MediaFileDownloadListener {
                        override fun onStart() {}
                        override fun onProgress(total: Long, current: Long) {}
                        override fun onRealtimeDataUpdate(data: ByteArray, position: Long) {
                            if (isAborted.get()) return
                            try {
                                fos.write(data)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error writing media chunk: ${e.message}")
                            }
                        }
                        override fun onFinish() {
                            if (isAborted.get()) return
                            try { fos.close() } catch (e: Exception) {}
                            Log.d(TAG, "Downloaded ${mediaFile.fileName} successfully.")
                            downloadedFiles.add(destFile)
                            downloadLatch.countDown()
                        }
                        override fun onFailure(error: IDJIError) {
                            if (isAborted.get()) return
                            try { fos.close() } catch (e: Exception) {}
                            Log.e(TAG, "Failed to download ${mediaFile.fileName}: ${error.description()}")
                            downloadLatch.countDown()
                        }
                    })
                    
                    val success = downloadLatch.await(90, TimeUnit.SECONDS) 
                    if (!success) {
                        isAborted.set(true)
                        Log.e(TAG, "Timeout downloading ${mediaFile.fileName}. Aborting remaining files to prevent queue buildup.")
                        try { fos.close() } catch (e: Exception) {}
                        if (destFile.exists()) {
                            destFile.delete()
                        }
                        break
                    }
                }
                Log.d(TAG, "All photos downloaded. Triggering WebODM Task Creation...")
                createWebOdmTask(context, downloadedFiles)
            } finally {
                isDownloading = false
            }
        }.start()
    }

    // Fix BUG-20: Scale large screenshots/images before upload to prevent OutOfMemoryError
    private fun compressImageIfNecessary(context: Context, file: File): File {
        try {
            if (file.length() < 1024 * 1024) return file // Under 1MB: Keep original
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return file
            val maxDim = 1280
            val width = bitmap.width
            val height = bitmap.height
            if (width <= maxDim && height <= maxDim) return file
            
            val scale = maxDim.toFloat() / Math.max(width, height)
            val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, (width * scale).toInt(), (height * scale).toInt(), true)
            
            val tempFile = File(context.cacheDir, "compressed_" + file.name)
            val fos = java.io.FileOutputStream(tempFile)
            scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, fos)
            fos.close()
            
            bitmap.recycle()
            scaledBitmap.recycle()
            return tempFile
        } catch (e: Exception) {
            Log.w(TAG, "Gagal mengompresi gambar: ${file.name}", e)
            return file
        }
    }

    private fun createWebOdmTask(context: Context, images: List<File>) {
        if (images.isEmpty()) {
            return
        }
        
        val url = getServerUrl(context)
        val user = getUsername(context)
        val pass = getPassword(context)
        val projectId = getProjectId(context)
        
        if (url.isEmpty() || user.isEmpty() || pass.isEmpty() || projectId < 0) {
            Log.e(TAG, "WebODM Config incomplete. Cannot create task.")
            return
        }

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val startMsg = "WebODM Sync Started: Processing ${images.size} images..."
        handler.post {
            android.widget.Toast.makeText(context, startMsg, android.widget.Toast.LENGTH_LONG).show()
        }
        statusListener?.invoke(startMsg, false)

        val authBody = okhttp3.FormBody.Builder()
            .add("username", user)
            .add("password", pass)
            .build()
        val authReq = Request.Builder()
            .url("$url/api/token-auth/")
            .post(authBody)
            .build()
            
        try {
            val authRes = httpClient.newCall(authReq).execute()
            val authResStr = authRes.body?.string() ?: ""
            if (!authRes.isSuccessful) {
                Log.e(TAG, "WebODM Auth Failed: ${authRes.code}")
                val err = "WebODM Sync Failed: Auth Error ${authRes.code}"
                handler.post { android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show() }
                statusListener?.invoke(err, true)
                return
            }
            val tokenObj = org.json.JSONObject(authResStr)
            val token = tokenObj.optString("token")
            if (token.isEmpty()) {
                Log.e(TAG, "WebODM Token Empty")
                val err = "WebODM Sync Failed: Invalid Token"
                handler.post { android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show() }
                statusListener?.invoke(err, true)
                return
            }
            
            val projectName = "Recreate2_Mission_${System.currentTimeMillis()}"
            val projectReqBody = okhttp3.FormBody.Builder()
                .add("name", projectName)
                .add("description", "Proyek dibuat otomatis dari Drone SDK")
                .build()
                
            val projectReq = Request.Builder()
                .url("$url/api/projects/")
                .header("Authorization", "JWT $token")
                .post(projectReqBody)
                .build()
                
            val projectRes = httpClient.newCall(projectReq).execute()
            val projectResStr = projectRes.body?.string() ?: ""
            if (!projectRes.isSuccessful) {
                Log.e(TAG, "WebODM Project Creation Failed: ${projectRes.code}")
                val err = "WebODM Sync Failed: Project Creation Error"
                handler.post { android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show() }
                statusListener?.invoke(err, true)
                return
            }
            
            val projectObj = org.json.JSONObject(projectResStr)
            val newProjectId = projectObj.optInt("id", -1)
            if (newProjectId == -1) {
                Log.e(TAG, "WebODM Project ID is invalid")
                val err = "WebODM Sync Failed: Invalid Project ID"
                handler.post { android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show() }
                statusListener?.invoke(err, true)
                return
            }
            
            Log.d(TAG, "WebODM Project Created Successfully with ID: $newProjectId")
            
            val multipartBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            multipartBuilder.addFormDataPart("name", projectName)
            
            // Process and add compressed files to prevent OOM
            val processedFiles = mutableListOf<File>()
            for (img in images) {
                val processed = compressImageIfNecessary(context, img)
                processedFiles.add(processed)
                multipartBuilder.addFormDataPart("images", processed.name, processed.asRequestBody("image/jpeg".toMediaTypeOrNull()))
            }
            
            val processingOptions = org.json.JSONArray()
            processingOptions.put(org.json.JSONObject().put("name", "auto-boundary").put("value", true))
            processingOptions.put(org.json.JSONObject().put("name", "dsm").put("value", true))
            processingOptions.put(org.json.JSONObject().put("name", "dem-resolution").put("value", 4.0))
            processingOptions.put(org.json.JSONObject().put("name", "orthophoto-resolution").put("value", 4.0))
            processingOptions.put(org.json.JSONObject().put("name", "max-concurrency").put("value", 8))
            
            multipartBuilder.addFormDataPart("options", processingOptions.toString())
            
            val requestBody = multipartBuilder.build()
            
            val taskReq = Request.Builder()
                .url("$url/api/projects/$newProjectId/tasks/")
                .header("Authorization", "JWT $token")
                .post(requestBody)
                .build()
                
            Log.d(TAG, "Uploading ${images.size} images to new WebODM Project $newProjectId...")
            val taskRes = httpClient.newCall(taskReq).execute()
            try {
                if (taskRes.isSuccessful) {
                    val successMsg = "WebODM Task Created Successfully!"
                    Log.d(TAG, successMsg)
                    statusListener?.invoke(successMsg, false)
                    
                    var deletedCount = 0
                    for (i in images.indices) {
                        val orig = images[i]
                        val proc = processedFiles[i]
                        
                        // Delete compressed temp file
                        if (proc != orig && proc.exists()) {
                            proc.delete()
                        }
                        // Clean original cache file
                        if (orig.exists()) {
                            val deleted = orig.delete()
                            if (deleted) deletedCount++
                        }
                    }
                    Log.d(TAG, "Auto-Cleanup: Deleted $deletedCount / ${images.size} cached images from phone storage.")
                    handler.post { android.widget.Toast.makeText(context, "WebODM Sync Sukses! $deletedCount file cache dihapus dari HP.", android.widget.Toast.LENGTH_LONG).show() }
                } else {
                    val errorBody = taskRes.body?.string()
                    val errMsg = "WebODM Task Creation Failed: ${taskRes.code} - $errorBody"
                    Log.e(TAG, errMsg)
                    handler.post { android.widget.Toast.makeText(context, "WebODM Sync Failed: HTTP ${taskRes.code}", android.widget.Toast.LENGTH_LONG).show() }
                    statusListener?.invoke(errMsg, true)
                }
            } finally {
                taskRes.close()
            }
        } catch (e: Exception) {
            val exMsg = "Exception during WebODM upload: ${e.message}"
            Log.e(TAG, exMsg)
            handler.post { android.widget.Toast.makeText(context, "WebODM Sync Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
            statusListener?.invoke(exMsg, true)
        }
    }

    fun cleanup() {
        statusListener = null
    }
}

