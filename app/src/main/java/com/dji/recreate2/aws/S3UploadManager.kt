package com.dji.recreate2.aws

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Handles uploading files and byte streams to custom HTTP S3 endpoint (https://asura-s3.stellardata.ai).
 * 
 * Auto-creates date-based folder structures (e.g. YYYY-MM-DD/filename.jpg)
 * and reads the configured S3 endpoint URL from SharedPreferences ("TacticalHUDConfig").
 */
object S3UploadManager {
    private const val TAG = "S3UploadManager"
    private const val PREF_S3_URL_KEY = "s3_server_url"
    const val DEFAULT_S3_URL = "http://192.168.180.99:8000/data-primary/drone/isr_tasking/captured"

    private const val PREF_ACCESS_KEY = "s3_access_key"
    private const val PREF_SECRET_KEY = "s3_secret_key"
    private const val PREF_REGION_KEY = "s3_region"
    private const val PREF_FOLDER_MODE_KEY = "s3_folder_mode"
    private const val PREF_CUSTOM_FOLDER_KEY = "s3_custom_folder"

    const val DEFAULT_ACCESS_KEY = "0RUUD1YOR1DLRQN2WF7H"
    const val DEFAULT_SECRET_KEY = "hfGxYhmhBjNL41NUecqyGev5a77H29JfO0DAEkBs"
    const val DEFAULT_REGION = "BT"

    const val FOLDER_MODE_AUTO = "AUTO"
    const val FOLDER_MODE_CUSTOM = "CUSTOM"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    var presignedUrlProvider: ((String) -> String)? = null

    var onUploadStartListener: ((filename: String, targetUrl: String, fileSize: Long) -> Unit)? = null
    var onUploadSuccessListener: ((filename: String, targetUrl: String, durationMs: Long) -> Unit)? = null
    var onUploadFailedListener: ((filename: String, targetUrl: String, code: Int, error: String) -> Unit)? = null

    private val uploadExecutor = java.util.concurrent.Executors.newFixedThreadPool(4)

    fun getS3ServerUrl(context: Context): String {
        val prefs = context.getSharedPreferences("TacticalHUDConfig", Context.MODE_PRIVATE)
        return prefs.getString(PREF_S3_URL_KEY, DEFAULT_S3_URL) ?: DEFAULT_S3_URL
    }

    fun getAccessKey(context: Context): String {
        val prefs = context.getSharedPreferences("TacticalHUDConfig", Context.MODE_PRIVATE)
        return prefs.getString(PREF_ACCESS_KEY, DEFAULT_ACCESS_KEY) ?: DEFAULT_ACCESS_KEY
    }

    fun getSecretKey(context: Context): String {
        val prefs = context.getSharedPreferences("TacticalHUDConfig", Context.MODE_PRIVATE)
        return prefs.getString(PREF_SECRET_KEY, DEFAULT_SECRET_KEY) ?: DEFAULT_SECRET_KEY
    }

    fun getRegion(context: Context): String {
        val prefs = context.getSharedPreferences("TacticalHUDConfig", Context.MODE_PRIVATE)
        return prefs.getString(PREF_REGION_KEY, DEFAULT_REGION) ?: DEFAULT_REGION
    }

    fun getFolderMode(context: Context): String {
        val prefs = context.getSharedPreferences("TacticalHUDConfig", Context.MODE_PRIVATE)
        return prefs.getString(PREF_FOLDER_MODE_KEY, FOLDER_MODE_AUTO) ?: FOLDER_MODE_AUTO
    }

    fun getCustomFolderName(context: Context): String {
        val prefs = context.getSharedPreferences("TacticalHUDConfig", Context.MODE_PRIVATE)
        return prefs.getString(PREF_CUSTOM_FOLDER_KEY, "") ?: ""
    }

    fun saveS3ServerUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences("TacticalHUDConfig", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_S3_URL_KEY, url).apply()
        Log.d(TAG, "S3 Server URL saved: $url")
    }

    fun saveCredentials(context: Context, accessKey: String, secretKey: String, region: String = DEFAULT_REGION) {
        val prefs = context.getSharedPreferences("TacticalHUDConfig", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PREF_ACCESS_KEY, accessKey)
            .putString(PREF_SECRET_KEY, secretKey)
            .putString(PREF_REGION_KEY, region)
            .apply()
        Log.d(TAG, "S3 Credentials updated for AccessKey: ${accessKey.take(4)}***")
    }

    fun saveFolderConfig(context: Context, mode: String, customFolder: String) {
        val prefs = context.getSharedPreferences("TacticalHUDConfig", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PREF_FOLDER_MODE_KEY, mode)
            .putString(PREF_CUSTOM_FOLDER_KEY, customFolder)
            .apply()
        Log.d(TAG, "S3 Folder config saved: Mode=$mode, CustomFolder=$customFolder")
    }

    /**
     * Creates a remote folder marker object (.keep) in Ceph S3 HTTP Storage.
     */
    fun createRemoteFolder(context: Context, folderName: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val cleanFolder = folderName.trim().replace(" ", "_").replace("\\", "/").removeSurrounding("/")
        if (cleanFolder.isEmpty()) {
            onError("Invalid folder name")
            return
        }

        saveFolderConfig(context, FOLDER_MODE_CUSTOM, cleanFolder)
        val baseUrl = getS3ServerUrl(context).trim().removeSuffix("/")
        val targetUrl = "$baseUrl/$cleanFolder/.keep"

        Log.d(TAG, "Creating remote S3 folder marker at $targetUrl")

        uploadExecutor.submit {
            try {
                val keepContent = "Created by Drone C2 via MQTT at ${Date()}".toByteArray(Charsets.UTF_8)
                val requestBody = keepContent.toRequestBody("text/plain".toMediaTypeOrNull())
                val requestBuilder = Request.Builder()
                    .url(targetUrl)
                    .put(requestBody)

                signRequest(requestBuilder, targetUrl, context, "PUT")

                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Successfully created S3 folder marker: $targetUrl")
                        onSuccess()
                    } else {
                        val msg = "HTTP Error ${response.code}: ${response.message}"
                        Log.e(TAG, msg)
                        onError(msg)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception creating S3 folder marker", e)
                onError(e.message ?: "Folder creation error")
            }
        }
    }

    /**
     * Lists top-level folders (CommonPrefixes) in the configured S3 base URL.
     * Issues an S3 ListObjectsV2 GET request with delimiter=/ and parses the XML response.
     * Returns a list of folder name strings via onSuccess callback.
     */
    fun listRemoteFolders(context: Context, onSuccess: (List<String>) -> Unit, onError: (String) -> Unit) {
        val baseUrl = getS3ServerUrl(context).trim().removeSuffix("/")

        // Parse bucket and prefix from the base URL
        // e.g. http://host:port/bucket/prefix/path  ->  list url = http://host:port/bucket?prefix=prefix/path/&delimiter=/
        val uri = java.net.URI.create(baseUrl)
        val pathParts = uri.path.trimStart('/').split("/", limit = 2)
        val bucket  = pathParts.getOrElse(0) { "" }
        val prefix  = if (pathParts.size > 1) pathParts[1].removeSuffix("/") + "/" else ""

        // Build the list URL: http://host:port/bucket?prefix=...&delimiter=/
        val authority = if (uri.port != -1) "${uri.scheme}://${uri.host}:${uri.port}" else "${uri.scheme}://${uri.host}"
        val encodedPrefix = java.net.URLEncoder.encode(prefix, "UTF-8").replace("+", "%20")
        val listUrl   = "$authority/$bucket?prefix=$encodedPrefix&delimiter=/"

        Log.d(TAG, "Listing S3 folders at: $listUrl")

        uploadExecutor.submit {
            try {
                val requestBuilder = Request.Builder().url(listUrl).get()
                signRequest(requestBuilder, listUrl, context, "GET")

                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        onError("HTTP ${response.code}: ${response.message}")
                        return@use
                    }

                    val body = response.body?.string() ?: ""
                    Log.d(TAG, "S3 List response: ${body.take(500)}")

                    val folders = mutableListOf<String>()

                    // 1. Parse <CommonPrefixes><Prefix>folder/</Prefix></CommonPrefixes>
                    val commonPrefixesRegex = Regex("<CommonPrefixes>\\s*<Prefix>(.*?)</Prefix>\\s*</CommonPrefixes>")
                    for (match in commonPrefixesRegex.findAll(body)) {
                        val raw = match.groupValues[1].trim()
                        val relative = if (prefix.isNotEmpty() && raw.startsWith(prefix)) raw.substring(prefix.length) else raw
                        val name = relative.split("/").firstOrNull { it.isNotEmpty() }?.trim() ?: ""
                        if (name.isNotEmpty() && name != ".keep" && !folders.contains(name)) {
                            folders.add(name)
                        }
                    }

                    // 2. Fallback: Parse <Key> entries for folder paths (e.g. folder/file.jpg)
                    val keyRegex = Regex("<Key>(.*?)</Key>")
                    for (match in keyRegex.findAll(body)) {
                        val rawKey = match.groupValues[1].trim()
                        val relPath = if (prefix.isNotEmpty() && rawKey.startsWith(prefix)) rawKey.substring(prefix.length) else rawKey
                        val parts = relPath.split("/").filter { it.isNotEmpty() }
                        if (parts.size > 1) {
                            val folderName = parts[0]
                            if (folderName != ".keep" && !folders.contains(folderName)) {
                                folders.add(folderName)
                            }
                        }
                    }

                    Log.d(TAG, "Found ${folders.size} S3 folders: $folders")
                    onSuccess(folders.sorted())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception listing S3 folders", e)
                onError(e.message ?: "Folder listing error")
            }
        }
    }

    /**
     * Auto-generates or applies custom destination URL.
     * Example outputs:
     * - AUTO: http://192.168.180.99:8000/data-primary/drone/isr_tasking/captured/2026-08-06/filename.jpg
     * - CUSTOM: http://192.168.180.99:8000/data-primary/drone/isr_tasking/captured/recon_alpha_01/filename.jpg
     */
    fun buildTargetS3Url(context: Context, filename: String): String {
        val customProvider = presignedUrlProvider
        if (customProvider != null) {
            return customProvider.invoke(filename)
        }

        val baseUrl = getS3ServerUrl(context).trim().removeSuffix("/")
        val folderMode = getFolderMode(context)
        val customName = getCustomFolderName(context).trim().replace(" ", "_").replace("\\", "/").removeSurrounding("/")

        val subFolder = if (folderMode == FOLDER_MODE_CUSTOM && customName.isNotEmpty()) {
            customName
        } else {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        }

        val cleanFilename = filename.trim().replace(" ", "_")
        return "$baseUrl/$subFolder/$cleanFilename"
    }

    fun saveToLocalStorage(context: Context, file: File) {
        try {
            val prefs = context.getSharedPreferences("TacticalHUDConfig", Context.MODE_PRIVATE)
            val customLocalPath = prefs.getString("isrLocalFolderPath", "") ?: ""

            val targetDir = if (customLocalPath.isNotEmpty()) {
                File(customLocalPath)
            } else {
                File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "ISR_Local_Storage")
            }

            if (!targetDir.exists()) targetDir.mkdirs()

            val destFile = File(targetDir, file.name)
            if (destFile.absolutePath != file.absolutePath) {
                file.copyTo(destFile, overwrite = true)
                Log.d(TAG, "Saved ISR media copy to local storage: ${destFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save ISR media copy to local storage", e)
        }
    }

    /**
     * Uploads an existing local file to S3 with auto-generated date folder and AWS SigV4 headers. (Used for Mode 2)
     */
    fun uploadFile(context: Context, file: File, onSuccess: () -> Unit, onError: (String) -> Unit) {
        // Ensure local storage copy is preserved
        saveToLocalStorage(context, file)

        val targetUrl = buildTargetS3Url(context, file.name)
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Uploading ${file.name} (${file.length()} bytes) to $targetUrl")
        onUploadStartListener?.invoke(file.name, targetUrl, file.length())

        uploadExecutor.submit {
            try {
                val requestBody = file.asRequestBody("application/octet-stream".toMediaTypeOrNull())
                val requestBuilder = Request.Builder()
                    .url(targetUrl)
                    .put(requestBody)

                signRequest(requestBuilder, targetUrl, context, "PUT")

                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    val duration = System.currentTimeMillis() - startTime
                    if (response.isSuccessful) {
                        Log.d(TAG, "Successfully uploaded ${file.name} to $targetUrl ($duration ms)")
                        onUploadSuccessListener?.invoke(file.name, targetUrl, duration)
                        onSuccess()
                    } else {
                        val msg = "HTTP Error ${response.code}: ${response.message}"
                        Log.e(TAG, msg)
                        onUploadFailedListener?.invoke(file.name, targetUrl, response.code, msg)
                        onError(msg)
                    }
                }
            } catch (e: Exception) {
                val errMsg = e.message ?: "Unknown upload error"
                Log.e(TAG, "S3 Upload Exception for ${file.name}", e)
                onUploadFailedListener?.invoke(file.name, targetUrl, -1, errMsg)
                onError(errMsg)
            }
        }
    }

    /**
     * Uploads a local file to S3 using explicit remoteFolder and remoteFileName.
     * Provides an onProgress callback with percentage (0-100).
     * Used for ISR Mode 1 HUD screenshot captures.
     */
    fun uploadFile(
        context: Context,
        localFile: File,
        remoteFolder: String,
        remoteFileName: String,
        onProgress: ((Int) -> Unit)? = null,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        saveToLocalStorage(context, localFile)
        val cleanFolder   = remoteFolder.trim().replace(" ", "_").removeSurrounding("/")
        val cleanFilename = remoteFileName.trim().replace(" ", "_")
        val baseUrl       = getS3ServerUrl(context).trim().removeSuffix("/")
        val targetUrl     = "$baseUrl/$cleanFolder/$cleanFilename"
        val startTime     = System.currentTimeMillis()
        Log.d(TAG, "ISR upload: ${localFile.name} -> $targetUrl")
        onUploadStartListener?.invoke(remoteFileName, targetUrl, localFile.length())

        uploadExecutor.submit {
            try {
                onProgress?.invoke(20)
                val requestBody = localFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
                val requestBuilder = Request.Builder()
                    .url(targetUrl)
                    .put(requestBody)
                signRequest(requestBuilder, targetUrl, context, "PUT")
                onProgress?.invoke(50)

                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    val duration = System.currentTimeMillis() - startTime
                    onProgress?.invoke(90)
                    if (response.isSuccessful) {
                        Log.d(TAG, "ISR upload OK: $targetUrl ($duration ms)")
                        onUploadSuccessListener?.invoke(remoteFileName, targetUrl, duration)
                        onProgress?.invoke(100)
                        onSuccess()
                    } else {
                        val msg = "HTTP ${response.code}: ${response.message}"
                        Log.e(TAG, "ISR upload failed: $msg")
                        onUploadFailedListener?.invoke(remoteFileName, targetUrl, response.code, msg)
                        onError(msg)
                    }
                }
            } catch (e: Exception) {
                val errMsg = e.message ?: "Unknown upload error"
                Log.e(TAG, "ISR upload exception for $remoteFileName", e)
                onUploadFailedListener?.invoke(remoteFileName, targetUrl, -1, errMsg)
                onError(errMsg)
            }
        }
    }

    /**
     * Uploads a raw byte array directly to S3 with auto-generated date folder and AWS SigV4 headers. (Used for Mode 1)
     */
    fun uploadBytes(context: Context, data: ByteArray, filename: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val targetUrl = buildTargetS3Url(context, filename)
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Uploading stream bytes ($filename, ${data.size} bytes) to $targetUrl")
        onUploadStartListener?.invoke(filename, targetUrl, data.size.toLong())

        uploadExecutor.submit {
            try {
                val requestBody = data.toRequestBody("image/jpeg".toMediaTypeOrNull())
                val requestBuilder = Request.Builder()
                    .url(targetUrl)
                    .put(requestBody)

                signRequest(requestBuilder, targetUrl, context, "PUT")

                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    val duration = System.currentTimeMillis() - startTime
                    if (response.isSuccessful) {
                        Log.d(TAG, "Successfully uploaded bytes ($filename) to $targetUrl ($duration ms)")
                        onUploadSuccessListener?.invoke(filename, targetUrl, duration)
                        onSuccess()
                    } else {
                        val msg = "HTTP Error ${response.code}: ${response.message}"
                        Log.e(TAG, msg)
                        onUploadFailedListener?.invoke(filename, targetUrl, response.code, msg)
                        onError(msg)
                    }
                }
            } catch (e: Exception) {
                val errMsg = e.message ?: "Unknown upload error"
                Log.e(TAG, "S3 Upload Exception for $filename", e)
                onUploadFailedListener?.invoke(filename, targetUrl, -1, errMsg)
                onError(errMsg)
            }
        }
    }

    /**
     * Signs HTTP requests using AWS Signature Version 4 for S3 REST API compliance.
     * @param httpMethod  The HTTP method string: "GET", "PUT", "DELETE", etc.
     */
    private fun signRequest(builder: Request.Builder, urlStr: String, context: Context, httpMethod: String = "PUT") {
        try {
            val accessKey = getAccessKey(context)
            val secretKey = getSecretKey(context)
            val region    = getRegion(context)
            val service   = "s3"

            val uri = java.net.URI.create(urlStr)
            val host = if (uri.port != -1 && uri.port != 80 && uri.port != 443) "${uri.host}:${uri.port}" else uri.host
            val canonicalUri = if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath

            // Build canonical query string: sort params alphabetically, URI-encode keys+values
            val canonicalQueryString = (uri.rawQuery ?: "").split("&")
                .filter { it.isNotEmpty() }
                .map { param ->
                    val idx = param.indexOf('=')
                    if (idx >= 0) {
                        val k = java.net.URLDecoder.decode(param.substring(0, idx), "UTF-8")
                        val v = java.net.URLDecoder.decode(param.substring(idx + 1), "UTF-8")
                        uriEncode(k) to uriEncode(v)
                    } else {
                        uriEncode(java.net.URLDecoder.decode(param, "UTF-8")) to ""
                    }
                }
                .sortedWith(compareBy({ it.first }, { it.second }))
                .joinToString("&") { "${it.first}=${it.second}" }

            val utcZone    = java.util.TimeZone.getTimeZone("UTC")
            val amzDate    = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = utcZone }.format(Date())
            val dateStamp  = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = utcZone }.format(Date())

            // For GET/DELETE use SHA-256 of empty string; for PUT/POST use UNSIGNED-PAYLOAD (Ceph compatible)
            val payloadHash = when (httpMethod.uppercase()) {
                "GET", "DELETE", "HEAD" -> "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                else                    -> "UNSIGNED-PAYLOAD"
            }

            val canonicalHeaders   = "host:$host\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n"
            val signedHeaders       = "host;x-amz-content-sha256;x-amz-date"
            val canonicalRequest    = "$httpMethod\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
            val credentialScope     = "$dateStamp/$region/$service/aws4_request"
            val stringToSign        = "AWS4-HMAC-SHA256\n$amzDate\n$credentialScope\n${sha256Hex(canonicalRequest)}"

            val kDate    = hmacSha256("AWS4$secretKey".toByteArray(Charsets.UTF_8), dateStamp)
            val kRegion  = hmacSha256(kDate, region)
            val kService = hmacSha256(kRegion, service)
            val kSigning = hmacSha256(kService, "aws4_request")

            val signature  = hex(hmacSha256(kSigning, stringToSign))
            val authHeader = "AWS4-HMAC-SHA256 Credential=$accessKey/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

            builder.header("Host", host)
            builder.header("x-amz-date", amzDate)
            builder.header("x-amz-content-sha256", payloadHash)
            builder.header("Authorization", authHeader)

            Log.d(TAG, "SigV4 signed: method=$httpMethod uri=$canonicalUri qs=$canonicalQueryString")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign S3 request with AWS SigV4", e)
        }
    }

    /** URI-encodes a string per AWS SigV4 spec (RFC 3986, no double-encoding). */
    private fun uriEncode(input: String): String {
        val sb = StringBuilder()
        for (ch in input) {
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                sb.append(ch)
            } else {
                ch.toString().toByteArray(Charsets.UTF_8).forEach { b ->
                    sb.append("%").append(String.format("%02X", b))
                }
            }
        }
        return sb.toString()
    }

    private fun sha256Hex(data: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data.toByteArray(Charsets.UTF_8))
        return hex(digest)
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretKeySpec = javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")
        mac.init(secretKeySpec)
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
