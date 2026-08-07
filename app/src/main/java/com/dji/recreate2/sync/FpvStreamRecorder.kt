package com.dji.recreate2.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FpvStreamRecorder records the raw SurfaceView texture of the FPV camera feed
 * into a clean local MP4 video file on the tablet.
 *
 * Supports toggleable green target reticle square and top compass tape overlays.
 * ZERO unwanted HUD buttons, menus, or non-tactical UI overlays.
 */
object FpvStreamRecorder {
    private const val TAG = "FpvStreamRecorder"
    private val isRecording = AtomicBoolean(false)

    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var inputSurface: android.view.Surface? = null
    private var videoTrackIndex = -1
    private var isMuxerStarted = false

    private var recordThread: HandlerThread? = null
    private var recordHandler: Handler? = null
    private var outputFile: File? = null
    private var frameCount = 0L
    private var recordingStartMs = 0L

    var isReticleOverlayEnabled = true
    var isCompassOverlayEnabled = true

    data class TelemetrySample(
        val timestampMs: Long,
        val lat: Double,
        val lon: Double,
        val alt: Double,
        val speed: Double,
        val yaw: Double,
        val gimbalPitch: Double,
        val sats: Int,
        val battery: Int
    )

    private val telemetrySamples = CopyOnWriteArrayList<TelemetrySample>()
    private var telemetrySupplier: (() -> TelemetrySample)? = null

    fun isRecording(): Boolean = isRecording.get()

    /**
     * Starts recording the FPV camera feed (fpvSurface) into an MP4 file
     * while logging telemetry every 1 second for generating a companion .SRT file.
     */
    fun startRecording(
        surfaceView: SurfaceView,
        context: Context,
        fps: Int = 10,
        telemetryProvider: (() -> TelemetrySample)? = null
    ): Boolean {
        if (isRecording.get()) {
            Log.w(TAG, "Already recording FPV stream")
            return false
        }

        val w = surfaceView.width
        val h = surfaceView.height
        if (w <= 0 || h <= 0) {
            Log.e(TAG, "Cannot start recording: SurfaceView width/height invalid ($w x $h)")
            return false
        }

        val width = if (w % 2 != 0) w - 1 else w
        val height = if (h % 2 != 0) h - 1 else h

        try {
            val cacheDir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "ISR_Mode1_Cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val baseName = "isr_raw_video_${System.currentTimeMillis()}"
            val destFile = File(cacheDir, "$baseName.mp4")
            outputFile = destFile

            telemetrySupplier = telemetryProvider
            telemetrySamples.clear()
            recordingStartMs = System.currentTimeMillis()

            val MIME_TYPE = "video/avc" // H.264
            val BIT_RATE = 4_000_000 // 4 Mbps clean video bitrate

            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()

            mediaMuxer = MediaMuxer(destFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            isMuxerStarted = false
            videoTrackIndex = -1
            frameCount = 0L

            recordThread = HandlerThread("FpvRecorderThread").also { it.start() }
            recordHandler = Handler(recordThread!!.looper)

            isRecording.set(true)

            val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val frameDelayMs = (1000 / fps).toLong()

            var lastTelemetryLogMs = 0L

            val recordRunnable = object : Runnable {
                override fun run() {
                    if (!isRecording.get() || surfaceView.width <= 0) return

                    val now = System.currentTimeMillis()
                    var currentTelemetry: TelemetrySample? = null
                    if (now - lastTelemetryLogMs >= 1000L || telemetrySamples.isEmpty()) {
                        lastTelemetryLogMs = now
                        telemetrySupplier?.invoke()?.let { sample ->
                            currentTelemetry = sample
                            telemetrySamples.add(sample)
                        }
                    } else {
                        currentTelemetry = telemetrySamples.lastOrNull()
                    }

                    PixelCopy.request(surfaceView, frameBitmap, { result ->
                        if (result == PixelCopy.SUCCESS && isRecording.get()) {
                            val surface = inputSurface
                            if (surface != null && surface.isValid) {
                                try {
                                    val canvas = surface.lockHardwareCanvas()
                                    canvas.drawBitmap(frameBitmap, 0f, 0f, null)

                                    val yaw = currentTelemetry?.yaw ?: 0.0
                                    if (isReticleOverlayEnabled) {
                                        drawTargetReticle(canvas, width, height)
                                    }
                                    if (isCompassOverlayEnabled) {
                                        drawCompassTape(canvas, width, height, yaw)
                                    }

                                    surface.unlockCanvasAndPost(canvas)
                                    frameCount++
                                    drainEncoder(false)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error rendering frame to encoder surface", e)
                                }
                            }
                        }
                        if (isRecording.get()) {
                            recordHandler?.postDelayed(this, frameDelayMs)
                        }
                    }, recordHandler!!)
                }
            }

            recordHandler?.post(recordRunnable)
            Log.d(TAG, "Started FPV video + SRT recording -> ${destFile.name} ($width x $height @ $fps fps)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FPV video recording", e)
            stopRecording()
            return false
        }
    }

    /**
     * Draws the green target reticle square in the center of the video frame.
     */
    private fun drawTargetReticle(canvas: Canvas, width: Int, height: Int) {
        val cx = width / 2f
        val cy = height / 2f
        val boxSize = (width.coerceAtMost(height) * 0.15f).coerceAtLeast(60f)
        val cornerLen = boxSize * 0.3f

        val paint = Paint().apply {
            color = Color.parseColor("#00FF66")
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }

        val left = cx - boxSize / 2f
        val right = cx + boxSize / 2f
        val top = cy - boxSize / 2f
        val bottom = cy + boxSize / 2f

        // Top-left bracket
        canvas.drawLine(left, top, left + cornerLen, top, paint)
        canvas.drawLine(left, top, left, top + cornerLen, paint)

        // Top-right bracket
        canvas.drawLine(right, top, right - cornerLen, top, paint)
        canvas.drawLine(right, top, right, top + cornerLen, paint)

        // Bottom-left bracket
        canvas.drawLine(left, bottom, left + cornerLen, bottom, paint)
        canvas.drawLine(left, bottom, left, bottom - cornerLen, paint)

        // Bottom-right bracket
        canvas.drawLine(right, bottom, right - cornerLen, bottom, paint)
        canvas.drawLine(right, bottom, right, bottom - cornerLen, paint)

        // Precision center dot
        val dotPaint = Paint().apply {
            color = Color.parseColor("#00FF66")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(cx, cy, 3f, dotPaint)
    }

    /**
     * Draws the top compass heading tape onto the video frame.
     */
    private fun drawCompassTape(canvas: Canvas, width: Int, height: Int, yawDeg: Double) {
        val textPaint = Paint().apply {
            color = Color.parseColor("#00FF66")
            textSize = (13f * (width / 1280f)).coerceAtLeast(10f)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.MONOSPACE
        }

        val linePaint = Paint().apply {
            color = Color.parseColor("#00FF66")
            strokeWidth = 2f
            isAntiAlias = true
        }

        val topY = height * 0.08f
        val tapeWidth = width * 0.45f
        val startX = (width - tapeWidth) / 2f
        val endX = startX + tapeWidth

        // Base line
        canvas.drawLine(startX, topY, endX, topY, linePaint)

        // Center heading marker triangle
        val cx = width / 2f
        val triPath = Path().apply {
            moveTo(cx, topY + 12f)
            lineTo(cx - 6f, topY + 20f)
            lineTo(cx + 6f, topY + 20f)
            close()
        }
        canvas.drawPath(triPath, linePaint)

        val normYaw = ((yawDeg % 360) + 360) % 360
        val pixelsPerDeg = tapeWidth / 60f

        for (deg in (normYaw.toInt() - 30)..(normYaw.toInt() + 30)) {
            val labelDeg = ((deg % 360) + 360) % 360
            val x = cx + (deg - normYaw) * pixelsPerDeg
            if (x in startX..endX) {
                if (labelDeg % 15 == 0) {
                    val label = when (labelDeg) {
                        0 -> "N"
                        45 -> "NE"
                        90 -> "E"
                        135 -> "SE"
                        180 -> "S"
                        225 -> "SW"
                        270 -> "W"
                        315 -> "NW"
                        else -> "$labelDeg"
                    }
                    canvas.drawLine(x.toFloat(), topY - 8f, x.toFloat(), topY, linePaint)
                    canvas.drawText(label, x.toFloat(), topY - 12f, textPaint)
                } else if (labelDeg % 5 == 0) {
                    canvas.drawLine(x.toFloat(), topY - 4f, x.toFloat(), topY, linePaint)
                }
            }
        }
    }

    /**
     * Stops recording and returns a Pair of (MP4 Video File, Companion SRT Subtitle File).
     */
    fun stopRecording(): Pair<File?, File?> {
        if (!isRecording.compareAndSet(true, false)) {
            return Pair(null, null)
        }

        Log.d(TAG, "Stopping FPV stream recording... Recorded $frameCount frames and ${telemetrySamples.size} telemetry SRT samples.")

        var srtFile: File? = null
        try {
            recordHandler?.removeCallbacksAndMessages(null)
            recordThread?.quitSafely()
            recordThread?.join(1000)

            drainEncoder(true)

            try { mediaCodec?.stop() } catch (_: Exception) {}
            try { mediaCodec?.release() } catch (_: Exception) {}
            mediaCodec = null

            inputSurface?.release()
            inputSurface = null

            if (isMuxerStarted) {
                try { mediaMuxer?.stop() } catch (_: Exception) {}
                try { mediaMuxer?.release() } catch (_: Exception) {}
            }
            mediaMuxer = null
            isMuxerStarted = false

            val mp4File = outputFile
            if (mp4File != null && mp4File.exists() && mp4File.length() > 0) {
                val srtPath = mp4File.absolutePath.replace(".mp4", ".srt")
                val srtFileObj = File(srtPath)
                val srtContent = generateSrtContent(telemetrySamples, recordingStartMs)
                srtFileObj.writeText(srtContent, Charsets.UTF_8)
                srtFile = srtFileObj

                Log.d(TAG, "FPV raw video file: ${mp4File.name} (${mp4File.length()} bytes)")
                Log.d(TAG, "Companion SRT file generated: ${srtFileObj.name} (${srtFileObj.length()} bytes)")
                return Pair(mp4File, srtFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping FPV raw video recorder", e)
        }
        return Pair(outputFile, srtFile)
    }

    private fun generateSrtContent(samples: List<TelemetrySample>, startMs: Long): String {
        if (samples.isEmpty()) return ""
        val sb = StringBuilder()
        var index = 1
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)

        for (i in samples.indices) {
            val sample = samples[i]
            val sTimeMs = (sample.timestampMs - startMs).coerceAtLeast(0)
            val eTimeMs = if (i < samples.size - 1) (samples[i + 1].timestampMs - startMs) else sTimeMs + 1000L

            val startTimeStr = formatSrtTime(sTimeMs)
            val endTimeStr = formatSrtTime(eTimeMs.coerceAtLeast(sTimeMs + 200))

            val dateStr = dateFormat.format(java.util.Date(sample.timestampMs))
            val latStr = if (sample.lat.isNaN()) "0.000000" else String.format(java.util.Locale.US, "%.6f", sample.lat)
            val lonStr = if (sample.lon.isNaN()) "0.000000" else String.format(java.util.Locale.US, "%.6f", sample.lon)
            val altStr = if (sample.alt.isNaN()) "0.0" else String.format(java.util.Locale.US, "%.1f", sample.alt)
            val spdStr = String.format(java.util.Locale.US, "%.1f", sample.speed)
            val yawStr = String.format(java.util.Locale.US, "%.1f", sample.yaw)
            val pitchStr = String.format(java.util.Locale.US, "%.1f", sample.gimbalPitch)

            sb.append(index++).append("\n")
            sb.append(startTimeStr).append(" --> ").append(endTimeStr).append("\n")
            sb.append("$dateStr GPS($lonStr, $latStr, ${sample.sats}) ALT:${altStr}m SPD:${spdStr}m/s HDG:${yawStr}° GMB:${pitchStr}° BAT:${sample.battery}%\n")
            sb.append("[latitude: $latStr] [longitude: $lonStr] [altitude: $altStr] [speed: $spdStr] [heading: $yawStr]\n\n")
        }
        return sb.toString()
    }

    private fun formatSrtTime(ms: Long): String {
        val hrs = ms / 3600000
        val mins = (ms % 3600000) / 60000
        val secs = (ms % 60000) / 1000
        val millis = ms % 1000
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", hrs, mins, secs, millis)
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val codec = mediaCodec ?: return
        val muxer = mediaMuxer ?: return

        if (endOfStream) {
            try {
                codec.signalEndOfInputStream()
            } catch (_: Exception) {}
        }

        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000L)
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!isMuxerStarted) {
                    val newFormat = codec.outputFormat
                    videoTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    isMuxerStarted = true
                }
            } else if (outputBufferIndex >= 0) {
                val encodedData = codec.getOutputBuffer(outputBufferIndex) ?: continue

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    bufferInfo.size = 0
                }

                if (bufferInfo.size != 0) {
                    if (isMuxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                    }
                }

                codec.releaseOutputBuffer(outputBufferIndex, false)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    break
                }
            }
        }
    }
}
