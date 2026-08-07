package com.dji.recreate2.sync

import android.content.Context
import android.util.Log

/**
 * Handles WHIP (WebRTC HTTP Ingestion Protocol) direct push streaming via Native Google WebRTC (org.webrtc).
 * Enables ultra-low sub-50ms real-time video transmission over native peer connections.
 */
object WhipWebRtcManager {

    private const val TAG = "WhipWebRtcManager"

    @Volatile
    var isStreaming = false
        internal set

    var statusListener: ((status: String, isSuccess: Boolean) -> Unit)?
        get() = NativeWebRtcStreamManager.statusListener
        set(value) {
            NativeWebRtcStreamManager.statusListener = value
        }

    fun startWhipStream(
        context: Context,
        whipUrl: String,
        username: String = "",
        password: String = "",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        Log.i(TAG, "Starting Native WebRTC WHIP stream to: $whipUrl")
        NativeWebRtcStreamManager.startStream(
            context = context,
            targetUrl = whipUrl,
            onSuccess = {
                isStreaming = true
                onSuccess()
            },
            onError = { err ->
                isStreaming = false
                onError(err)
            }
        )
    }

    fun stopWhipStream(onSuccess: () -> Unit, onError: (String) -> Unit) {
        Log.i(TAG, "Stopping Native WebRTC WHIP stream...")
        NativeWebRtcStreamManager.stopStream(
            onSuccess = {
                isStreaming = false
                onSuccess()
            },
            onError = { err ->
                isStreaming = false
                onError(err)
            }
        )
    }
}
