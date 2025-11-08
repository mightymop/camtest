package local.test.camtest.protocol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.SurfaceHolder
import local.test.camtest.R
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

class JFIFMJpegStreamReceiver {

    interface StreamListener {
        fun onVideoStarted()
        fun onVideoStopped()
        fun onError(error: String)
        fun onFrameDecoded(width: Int, height: Int)
        fun onStreamInfo(info: String)
    }

    private var udpSocket: DatagramSocket? = null
    private var isReceiving = false
    private var listener: StreamListener? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var paint: Paint = Paint()

    private val SOI_MARKER = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val EOF_MARKER = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private lateinit var context: Context
    companion object {
        private const val TAG = "JFIFMJpegStreamReceiver"

    }

    fun initialize(holder: SurfaceHolder, listener: StreamListener, context: Context) {
        this.surfaceHolder = holder
        this.listener = listener
        this.context = context

        paint.isFilterBitmap = true
        paint.isAntiAlias = false

        Log.d(TAG, "JFIF MJPEG Receiver initialized")
        listener.onStreamInfo("JFIF MJPEG Ready")
    }

    fun startStream() {
        Thread {
            try {
                udpSocket = DatagramSocket(this.context.resources.getInteger(R.integer.udpport))
                udpSocket?.soTimeout = 1000
                udpSocket?.receiveBufferSize = 1024 * 1024 * 4
                isReceiving = true

                Log.d(TAG, "🎥 JFIF MJPEG Stream started")
                listener?.onVideoStarted()
                listener?.onStreamInfo("Receiving UDP stream...")

                val buffer = ByteArray(65536)
                var packetCount = 0
                var frameCount = 0
                var successFrameCount = 0
                val frameAssembler = FrameAssembler()

                while (isReceiving) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        udpSocket?.receive(packet)

                        val data = packet.data.copyOf(packet.length)
                        packetCount++

                        val completeFrame = frameAssembler.processPacket(data)

                        if (completeFrame != null) {
                            frameCount++
                            val bitmap = processMJpegFrame(completeFrame, frameCount)
                            if (bitmap != null) {
                                successFrameCount++
                                drawToSurface(bitmap)
                                if (successFrameCount % 30 == 0) {
                                    val successRate = (successFrameCount * 100 / frameCount)
                                    listener?.onStreamInfo("Frames: $successFrameCount/$frameCount ($successRate% success)")
                                }
                            }
                        }

                    } catch (e: SocketTimeoutException) {
                        // Normal, continue
                    } catch (e: Exception) {
                        if (isReceiving) {
                            Log.w(TAG, "UDP error: ${e.message}")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Stream error: ${e.message}")
                listener?.onError("Stream failed: ${e.message}")
            } finally {
                udpSocket?.close()
                Log.d(TAG, "Stream stopped")
            }
        }.start()
    }

    private fun processMJpegFrame(frameData: ByteArray, frameCount: Int): Bitmap? {
        return try {
            // Versuche verschiedene Decodierungsmethoden
            val bitmap = decodeWithErrorHandling(frameData)

            if (bitmap != null) {
                if (frameCount % 30 == 0) {
                    Log.d(TAG, "✅ Frame $frameCount: ${bitmap.width}x${bitmap.height} from ${frameData.size} bytes")
                }
                listener?.onFrameDecoded(bitmap.width, bitmap.height)
            } else {
                if (frameCount <= 20) {
                    Log.w(TAG, "❌ All decode methods failed for frame $frameCount")
                    analyzeFrameData(frameData, frameCount)
                }
            }

            bitmap
        } catch (e: Exception) {
            if (frameCount <= 10) {
                Log.e(TAG, "Error processing frame $frameCount: ${e.message}")
            }
            null
        }
    }

    private fun decodeWithErrorHandling(data: ByteArray): Bitmap? {

        var bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
        if (bitmap != null) return bitmap

        bitmap = extractCompleteJpeg(data)
        if (bitmap != null) return bitmap

        bitmap = tryOffsets(data)
        if (bitmap != null) return bitmap

        return tryRepairJpeg(data)
    }

    private fun extractCompleteJpeg(data: ByteArray): Bitmap? {
        val soiPos = findSequence(data, SOI_MARKER)
        if (soiPos == -1) return null

        val eofPos = findSequence(data, EOF_MARKER, soiPos)
        if (eofPos == -1) return null

        val jpegEnd = eofPos + EOF_MARKER.size
        if (jpegEnd > data.size) return null

        val jpegData = data.copyOfRange(soiPos, jpegEnd)
        return BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
    }

    private fun tryOffsets(data: ByteArray): Bitmap? {

        val offsets = intArrayOf(0, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64, 128)

        for (offset in offsets) {
            if (offset >= data.size - 100) break

            try {
                val bitmap = BitmapFactory.decodeByteArray(data, offset, data.size - offset)
                if (bitmap != null) {
                    Log.d(TAG, "✅ Found JPEG at offset $offset")
                    return bitmap
                }
            } catch (e: Exception) {
                Log.e(TAG,e.message,e)
            }
        }
        return null
    }

    private fun tryRepairJpeg(data: ByteArray): Bitmap? {

        val soiPos = findSequence(data, SOI_MARKER)
        if (soiPos == -1) return null

        val repairedData = ByteArrayOutputStream()
        repairedData.write(data, soiPos, data.size - soiPos)

        if (!endsWithEOF(repairedData.toByteArray())) {
            repairedData.write(EOF_MARKER)
        }

        val finalData = repairedData.toByteArray()
        return BitmapFactory.decodeByteArray(finalData, 0, finalData.size)
    }

    private fun endsWithEOF(data: ByteArray): Boolean {
        if (data.size < 2) return false
        return data[data.size - 2] == EOF_MARKER[0] &&
                data[data.size - 1] == EOF_MARKER[1]
    }

    private fun analyzeFrameData(data: ByteArray, frameCount: Int) {
        Log.d(TAG, "Frame $frameCount analysis:")
        Log.d(TAG, "  Total size: ${data.size} bytes")
        Log.d(TAG, "  SOI found: ${findSequence(data, SOI_MARKER) != -1}")
        Log.d(TAG, "  EOF found: ${findSequence(data, EOF_MARKER) != -1}")

        val firstBytes = data.take(16).joinToString(" ") { String.format("%02X", it) }
        val lastBytes = data.takeLast(8).joinToString(" ") { String.format("%02X", it) }
        Log.d(TAG, "  First 16 bytes: $firstBytes")
        Log.d(TAG, "  Last 8 bytes: $lastBytes")
    }

    private fun drawToSurface(bitmap: Bitmap) {
        var canvas: Canvas? = null
        try {
            canvas = surfaceHolder?.lockCanvas()
            canvas?.let { canvas ->
                canvas.drawColor(Color.BLACK)

                val scaledBitmap = scaleToSurface(bitmap, canvas.width, canvas.height)
                val x = (canvas.width - scaledBitmap.width) / 2f
                val y = (canvas.height - scaledBitmap.height) / 2f

                canvas.drawBitmap(scaledBitmap, x, y, paint)

                if (scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Surface draw error: ${e.message}")
        } finally {
            canvas?.let {
                try {
                    surfaceHolder?.unlockCanvasAndPost(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Error unlocking canvas: ${e.message}")
                }
            }
        }
    }

    private fun scaleToSurface(bitmap: Bitmap, surfaceWidth: Int, surfaceHeight: Int): Bitmap {
        if (bitmap.width <= surfaceWidth && bitmap.height <= surfaceHeight) {
            return bitmap
        }

        val scaleX = surfaceWidth.toFloat() / bitmap.width
        val scaleY = surfaceHeight.toFloat() / bitmap.height
        val scale = minOf(scaleX, scaleY)

        val scaledWidth = (bitmap.width * scale).toInt()
        val scaledHeight = (bitmap.height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
    }

    private fun findSequence(data: ByteArray, sequence: ByteArray, startIndex: Int = 0): Int {
        if (sequence.isEmpty() || data.size < sequence.size) return -1

        for (i in startIndex..(data.size - sequence.size)) {
            var found = true
            for (j in sequence.indices) {
                if (data[i + j] != sequence[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }

    fun stopStream() {
        isReceiving = false
        udpSocket?.close()
        listener?.onVideoStopped()
        Log.d(TAG, "Stream stopped")
    }

    fun release() {
        stopStream()
        surfaceHolder = null
        Log.d(TAG, "Receiver released")
    }
}

class FrameAssembler {
    private val frameBuffer = ByteArrayOutputStream()
    private var isAssembling = false
    private var lastPacketTime = System.currentTimeMillis()
    private var currentFrameSize = 0

    fun processPacket(data: ByteArray): ByteArray? {
        val now = System.currentTimeMillis()


        if (isAssembling && now - lastPacketTime > 100) {
            Log.w("FrameAssembler", "Frame assembly timeout - resetting")
            reset()
            return null
        }

        lastPacketTime = now


        val headerSize = determineHeaderSize(data)
        val payload = if (data.size > headerSize) data.copyOfRange(headerSize, data.size) else data

        if (payload.size < 4) return null

        val hasSOI = hasSOIMarker(payload)
        val hasEOF = hasEOFMarker(payload)

        return when {
            hasSOI && hasEOF -> {

                Log.v("FrameAssembler", "Complete frame in single packet")
                reset()
                payload
            }
            hasSOI -> {

                Log.v("FrameAssembler", "Starting new frame assembly")
                reset()
                frameBuffer.write(payload)
                isAssembling = true
                currentFrameSize = payload.size
                null
            }
            hasEOF && isAssembling -> {

                frameBuffer.write(payload)
                val frame = frameBuffer.toByteArray()
                Log.v("FrameAssembler", "Frame completed: ${frame.size} bytes")
                reset()
                frame
            }
            isAssembling -> {

                frameBuffer.write(payload)
                currentFrameSize += payload.size


                if (currentFrameSize > 500000) {
                    Log.w("FrameAssembler", "Frame too large ($currentFrameSize) - resetting")
                    reset()
                }
                null
            }
            else -> {

                if (!isAssembling) {
                    payload
                } else {
                    null
                }
            }
        }
    }

    private fun determineHeaderSize(data: ByteArray): Int {
        if (data.size < 12) return 0


        val version = (data[0].toInt() and 0xC0) shr 6
        if (version == 2) {
            // RTP Version 2
            val hasExtension = (data[0].toInt() and 0x10) != 0
            val csrcCount = data[0].toInt() and 0x0F
            var size = 12 + csrcCount * 4
            if (hasExtension && data.size >= size + 4) {
                val extensionLength = ((data[size + 2].toInt() and 0xFF) shl 8) or
                        (data[size + 3].toInt() and 0xFF)
                size += 4 + extensionLength * 4
            }
            return minOf(size, data.size - 100)
        }


        return when {
            data.size > 20 -> 20
            data.size > 16 -> 16
            data.size > 12 -> 12
            else -> 0
        }
    }

    private fun hasSOIMarker(data: ByteArray): Boolean {
        for (i in 0..(data.size - 2)) {
            if (data[i].toInt() and 0xFF == 0xFF && data[i + 1].toInt() and 0xFF == 0xD8) {
                return true
            }
        }
        return false
    }

    private fun hasEOFMarker(data: ByteArray): Boolean {
        for (i in 0..(data.size - 2)) {
            if (data[i].toInt() and 0xFF == 0xFF && data[i + 1].toInt() and 0xFF == 0xD9) {
                return true
            }
        }
        return false
    }

    private fun reset() {
        frameBuffer.reset()
        isAssembling = false
        currentFrameSize = 0
    }
}