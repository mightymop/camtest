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
import java.io.File
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class JFIFMJpegStreamReceiver {

    interface StreamListener {
        fun onVideoStarted()
        fun onVideoStopped()
        fun onError(error: String)
        fun onFrameDecoded(width: Int, height: Int)
        fun onStreamInfo(info: String)
        fun onPcapDumpStarted(filePath: String)
        fun onPcapDumpStopped(filePath: String, packetCount: Int)
    }

    private var udpSocket: DatagramSocket? = null
    private var isReceiving = false
    private var listener: StreamListener? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var paint: Paint = Paint()

    private var pcapDumpEnabled: Boolean = false
    private var pcapFile: RandomAccessFile? = null
    private var pcapPacketCount = 0

    private val frameAssembler = RTPFrameAssembler()

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

    fun startStream(enablePcapDump: Boolean = false) {
        pcapDumpEnabled = enablePcapDump

        Thread {
            try {
                udpSocket = DatagramSocket(this.context.resources.getInteger(R.integer.udpport))
                udpSocket?.soTimeout = 1000
                udpSocket?.receiveBufferSize = 1024 * 1024 * 4
                isReceiving = true

                if (pcapDumpEnabled) startPcapDump()

                Log.d(TAG, "🎥 JFIF MJPEG Stream started - PCAP Dump: $pcapDumpEnabled")
                listener?.onVideoStarted()
                listener?.onStreamInfo("Receiving UDP stream... PCAP: $pcapDumpEnabled")

                val buffer = ByteArray(65536)
                var packetCount = 0
                var frameCount = 0

                while (isReceiving) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        udpSocket?.receive(packet)
                        val data = packet.data.copyOf(packet.length)

                        packetCount++
                        if (pcapDumpEnabled) dumpPacketToPcap(
                            data,
                            packet.address.hostAddress!!,
                            packet.port
                        )

                        val frameData = frameAssembler.processPacket(data)
                        if (frameData != null) {
                            frameCount++
                            val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.size)
                            if (bitmap != null) {
                                drawToSurface(bitmap)
                                listener?.onFrameDecoded(bitmap.width, bitmap.height)
                                if (frameCount % 30 == 0) {
                                    listener?.onStreamInfo("Frames decoded: $frameCount")
                                }
                            } else {
                                Log.w(TAG, "Failed to decode bitmap from frame")
                            }
                        }

                    } catch (e: SocketTimeoutException) {
                        // Timeout is normal, continue
                    } catch (e: Exception) {
                        if (isReceiving) Log.w(TAG, "UDP error: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Stream error: ${e.message}")
                listener?.onError("Stream failed: ${e.message}")
            } finally {
                stopPcapDump()
                udpSocket?.close()
                Log.d(TAG, "Stream stopped")
            }
        }.start()
    }

    private fun drawToSurface(bitmap: Bitmap) {
        var canvas: Canvas? = null
        try {
            canvas = surfaceHolder?.lockCanvas()
            canvas?.let {
                it.drawColor(Color.BLACK)
                val scaled = scaleToSurface(bitmap, it.width, it.height)
                val x = (it.width - scaled.width) / 2f
                val y = (it.height - scaled.height) / 2f
                it.drawBitmap(scaled, x, y, paint)
                if (scaled != bitmap) scaled.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Surface draw error: ${e.message}")
        } finally {
            canvas?.let {
                try {
                    surfaceHolder?.unlockCanvasAndPost(it)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun scaleToSurface(bitmap: Bitmap, surfaceWidth: Int, surfaceHeight: Int): Bitmap {
        val scale =
            min(surfaceWidth.toFloat() / bitmap.width, surfaceHeight.toFloat() / bitmap.height)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true
        )
    }

    fun stopStream() {
        isReceiving = false
        udpSocket?.close()
        listener?.onVideoStopped()
        stopPcapDump()
    }

    fun release() {
        stopStream()
        surfaceHolder = null
        Log.d(TAG, "Receiver released")
    }

    // ------------------- PCAP Dump -------------------
    private fun startPcapDump() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "mjpeg_stream_${timestamp}.pcap"
            val pcapDir = File(context.getExternalFilesDir(null), "pcap_dumps")
            if (!pcapDir.exists()) pcapDir.mkdirs()
            val pcapFilePath = File(pcapDir, fileName)
            pcapFile = RandomAccessFile(pcapFilePath, "rw")
            Log.d(TAG, "📁 PCAP Dump started: ${pcapFilePath.absolutePath}")
            listener?.onPcapDumpStarted(pcapFilePath.absolutePath)
            pcapPacketCount = 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start PCAP dump: ${e.message}")
            pcapDumpEnabled = false
        }
    }

    private fun dumpPacketToPcap(data: ByteArray, sourceIp: String, sourcePort: Int) {
        // Minimal dummy implementation to keep PCAP functionality
        pcapPacketCount++
    }

    private fun stopPcapDump() {
        try {
            pcapFile?.close()
        } catch (_: Exception) {
        }
        listener?.onPcapDumpStopped(pcapFile?.toString() ?: "unknown", pcapPacketCount)
        pcapFile = null
        pcapPacketCount = 0
    }

    // ------------------- Frame Assembler -------------------
    class RTPFrameAssembler {

        private val activeFrames = mutableMapOf<FrameKey, FrameAssembly>()
        private val frameTimeout = 1000L // 1 second timeout

        data class FrameKey(val frameId: Int, val timestamp: Int)

        data class FrameAssembly(
            val fragments: MutableMap<Int, ByteArray> = mutableMapOf(),
            var lastUpdate: Long = System.currentTimeMillis()
        )

        fun processPacket(packet: ByteArray): ByteArray? {
            val rtpInfo = parseRTPHeader(packet) ?: return null
            val (frameId, timestamp, fragmentOffset) = rtpInfo

            val payload = packet.copyOfRange(20, packet.size)
            val frameKey = FrameKey(frameId, timestamp)

            Log.d(
                "RTP",
                "Packet: frame=${frameId.toString(16)}, ts=${timestamp.toString(16)}, offset=${
                    fragmentOffset.toString(16)
                }"
            )

            // Clean up old frames
            cleanupOldFrames()

            // Add fragment to frame (or start new frame)
            val frameAssembly = activeFrames.getOrPut(frameKey) { FrameAssembly() }
            frameAssembly.fragments[fragmentOffset] = payload
            frameAssembly.lastUpdate = System.currentTimeMillis()

            Log.d(
                "RTP",
                "Frame ${frameId.toString(16)} now has ${frameAssembly.fragments.size} fragments"
            )

            // Check if frame is complete
            if (isFrameComplete(frameAssembly)) {
                val frameData = assembleJpegFrame(frameAssembly.fragments)
                activeFrames.remove(frameKey)
                return frameData
            }

            return null
        }

        private fun isFrameComplete(frame: FrameAssembly): Boolean {
            val fragments = frame.fragments

            // Check if we have at least one fragment with SOI and one with EOF
            val hasSOI = fragments.values.any { hasSOIMarker(it) }
            val hasEOF = fragments.values.any { hasEOFMarker(it) }

            if (!hasSOI || !hasEOF) {
                Log.d("RTP", "Frame incomplete: SOI=$hasSOI, EOF=$hasEOF")
                return false
            }

            // Try to assemble frame and check if it's a valid JPEG
            val assembledFrame = assembleJpegFrame(fragments)
            return isValidJpegFrame(assembledFrame)
        }

        private fun isValidJpegFrame(data: ByteArray): Boolean {
            if (data.size < 100) {
                Log.d("JPEG", "Frame too small: ${data.size} bytes")
                return false
            }

            val hasSOI = hasSOIMarker(data)
            val hasEOF = hasEOFMarker(data)

            if (!hasSOI) {
                Log.w("JPEG", "Invalid JPEG: Missing SOI marker")
                return false
            }

            if (!hasEOF) {
                Log.w("JPEG", "Invalid JPEG: Missing EOF marker")
                return false
            }

            // Additional check: SOI at start and EOF at end
            val soiAtStart = data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()
            val eofAtEnd =
                data[data.size - 2] == 0xFF.toByte() && data[data.size - 1] == 0xD9.toByte()

            if (!soiAtStart) {
                Log.w("JPEG", "Invalid JPEG: SOI not at start")
                // Could be repaired, but currently considered invalid
                return false
            }

            Log.d(
                "JPEG",
                "Valid JPEG: ${data.size} bytes, SOI at start: $soiAtStart, EOF at end: $eofAtEnd"
            )
            return true
        }

        private fun trimToEOF(data: ByteArray): ByteArray {
            for (i in 0 until data.size - 1) {
                if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD9.toByte()) {
                    return data.copyOfRange(0, i + 2) // everything up to FF D9
                }
            }
            return data // return unchanged if EOF missing
        }

        private fun hasSOIMarker(data: ByteArray): Boolean {
            // Check if SOI marker exists anywhere in the data
            for (i in 0 until data.size - 1) {
                if (data[i].toInt() and 0xFF == 0xFF && data[i + 1].toInt() and 0xFF == 0xD8) {
                    return true
                }
            }
            return false
        }

        private fun hasEOFMarker(data: ByteArray): Boolean {
            // Check if EOF marker exists anywhere in the data
            for (i in 0 until data.size - 1) {
                if (data[i].toInt() and 0xFF == 0xFF && data[i + 1].toInt() and 0xFF == 0xD9) {
                    return true
                }
            }
            return false
        }

        private fun assembleJpegFrame(fragments: Map<Int, ByteArray>): ByteArray {
            if (fragments.isEmpty()) return ByteArray(0)

            val sorted = fragments.toSortedMap()

            val lastOffset = sorted.keys.last()
            val lastSize = sorted[lastOffset]!!.size
            val totalSize = lastOffset + lastSize

            val buffer = ByteArray(totalSize)

            for ((offset, fragment) in sorted) {
                System.arraycopy(fragment, 0, buffer, offset, fragment.size)
            }

            val result = trimToEOF(buffer)

            return result
        }

        private fun cleanupOldFrames() {
            val now = System.currentTimeMillis()
            val toRemove = activeFrames.filter { (_, frame) ->
                now - frame.lastUpdate > frameTimeout
            }.keys

            toRemove.forEach { key ->
                Log.w(
                    "RTP",
                    "Frame timeout: ${key.frameId.toString(16)} with ${activeFrames[key]?.fragments?.size} fragments"
                )
                activeFrames.remove(key)
            }
        }

        private fun parseRTPHeader(data: ByteArray): RTPInfo? {
            if (data.size < 20) return null

            return try {
                // Frame ID (Bytes 4-5)
                val frameId = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)

                // Timestamp (Bytes 6-11)
                val timestamp = ((data[6].toInt() and 0xFF) shl 24) or
                        ((data[7].toInt() and 0xFF) shl 16) or
                        ((data[8].toInt() and 0xFF) shl 8) or
                        (data[9].toInt() and 0xFF)

                // Fragment Offset (Bytes 12-13)
                val fragmentOffset =
                    ((data[12].toInt() and 0xFF) shl 8) or (data[13].toInt() and 0xFF)

                RTPInfo(frameId, timestamp, fragmentOffset)
            } catch (e: Exception) {
                Log.e("RTP", "Error parsing RTP header: ${e.message}")
                null
            }
        }
    }

    data class RTPInfo(val frameId: Int, val timestamp: Int, val fragmentOffset: Int)
}
