package local.test.camtest.protocol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.util.Log
import android.view.SurfaceHolder
import local.test.camtest.R
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    private val isReceiving = AtomicBoolean(false)
    private var listener: StreamListener? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var paint: Paint = Paint()

    // Performance Optimierungen
    private val packetQueue = LinkedBlockingQueue<ByteArray>(100) // Buffer für Pakete
    private lateinit var frameAssembler: RTPFrameAssembler
    private lateinit var context: Context

    // Frame-Rate Limiting
    private var lastFrameTime = 0L
    private val targetFrameTime = 33L // ~30 FPS
    private var framesProcessed = 0
    private var framesSkipped = 0

    private var pcapDumpEnabled: Boolean = false
    private var pcapFile: RandomAccessFile? = null
    private var pcapPacketCount = 0

    private val DEBUG = false

    companion object {
        private const val TAG = "JFIFMJpegStreamReceiver"
        private const val BUFFER_SIZE = 65536
    }

    init {
        paint.isFilterBitmap = true
        paint.isAntiAlias = false
    }

    fun initialize(holder: SurfaceHolder, listener: StreamListener, context: Context) {
        this.surfaceHolder = holder
        this.listener = listener
        this.context = context
        this.frameAssembler = RTPFrameAssembler(context)

        Log.d(TAG, "JFIF MJPEG Receiver initialized")
        listener.onStreamInfo("JFIF MJPEG Ready - Performance Optimized")
    }

    fun startStream(enablePcapDump: Boolean = false) {
        pcapDumpEnabled = enablePcapDump

        if (isReceiving.get()) {
            Log.w(TAG, "Stream already running")
            return
        }

        if (pcapDumpEnabled) startPcapDump()

        isReceiving.set(true)
        packetQueue.clear()
        framesProcessed = 0
        framesSkipped = 0

        // Starte separate Threads für Empfang und Verarbeitung
        startUdpReceiver()
        startFrameProcessor()

        Log.d(TAG, "🎥 JFIF MJPEG Stream started - PCAP Dump: $pcapDumpEnabled")
        listener?.onVideoStarted()
        listener?.onStreamInfo("Receiving UDP stream...")
    }

    private fun startUdpReceiver() {
        Thread {
            var socket: DatagramSocket? = null
            val buffer = ByteArray(BUFFER_SIZE)

            try {
                socket = DatagramSocket(context.resources.getInteger(R.integer.udpport))
                socket.soTimeout = 50 // Kürzeres Timeout für bessere Responsiveness
                socket.receiveBufferSize = 1024 * 1024 * 4 // 4MB Buffer

                Log.d(TAG, "UDP Receiver started")

                while (isReceiving.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)

                        val data = buffer.copyOfRange(0, packet.length)

                        if (pcapDumpEnabled) {
                            dumpPacketToPcap(
                                data,
                                packet.address.hostAddress!!,
                                packet.port
                            )
                        }

                        // Header-Check optimiert
                        if (!isValidHeader(data)) {
                            continue
                        }

                        // Non-blocking insert mit Timeout
                        val inserted = packetQueue.offer(data, 10, TimeUnit.MILLISECONDS)
                        if (!inserted) {
                            framesSkipped++
                            if (framesSkipped % 50 == 0) {
                                Log.w(TAG, "Queue full - skipped $framesSkipped packets")
                            }
                        }

                    } catch (e: SocketTimeoutException) {
                        // Erwartet - continue
                    } catch (e: Exception) {
                        if (isReceiving.get()) {
                            Log.e(TAG, "UDP receive error: ${e.message}")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "UDP receiver failed: ${e.message}")
                listener?.onError("UDP failed: ${e.message}")
            } finally {
                socket?.close()
                Log.d(TAG, "UDP Receiver stopped")
            }
        }.start()
    }

    private fun startFrameProcessor() {
        Thread {
            Log.d(TAG, "Frame Processor started")

            // Pre-allocated objects to reduce GC
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inMutable = true // Verhindert Hardware Bitmaps
                inSampleSize = 1
                inJustDecodeBounds = false
            }

            while (isReceiving.get()) {
                try {
                    // Non-blocking packet fetch
                    val packet = packetQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (packet != null) {
                        processPacket(packet, options)
                    }

                    // Performance stats
                    if (framesProcessed % 60 == 0) {
                        logPerformanceStats()
                    }

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Frame processing error: ${e.message}")
                }
            }

            Log.d(TAG, "Frame Processor stopped")
        }.start()
    }

    private fun processPacket(packet: ByteArray, options: BitmapFactory.Options) {

        val frameData = frameAssembler.processPacket(packet)
        if (frameData != null) {
            // Frame-Rate Limiting
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFrameTime < targetFrameTime) {
                return // Frame skipping für stabile Framerate
            }
            lastFrameTime = currentTime

            framesProcessed++
            decodeAndDisplayFrame(frameData, options)
        }
    }

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

    private fun createEthernetHeader(): ByteArray {
        // 14 bytes
        val buffer = ByteBuffer.allocate(14)
        // Destination MAC (Broadcast)
        repeat(6) { buffer.put(0xFF.toByte()) }
        // Source MAC (Fake)
        buffer.put(0x12.toByte()); buffer.put(0x34.toByte()); buffer.put(0x56.toByte())
        buffer.put(0x78.toByte()); buffer.put(0x9A.toByte()); buffer.put(0xBC.toByte())
        // EtherType IPv4 0x0800
        buffer.put(0x08.toByte()); buffer.put(0x00.toByte())
        return buffer.array()
    }

    private fun createIpHeader(dataLength: Int, sourceIp: String): ByteArray {
        // IP header 20 bytes (no options)
        val totalLength = 20 + 8 + dataLength // IP + UDP + payload
        val buffer = ByteBuffer.allocate(20)
        buffer.order(ByteOrder.BIG_ENDIAN) // network byte order

        // Version(4) + IHL(5 -> 20 bytes)
        buffer.put(0x45.toByte())
        // DSCP+ECN
        buffer.put(0x00.toByte())
        // Total length (unsigned short)
        buffer.putShort((totalLength and 0xFFFF).toShort())
        // Identification
        buffer.putShort(0x1234.toShort())
        // Flags (DF) + Fragment offset
        buffer.putShort(0x4000.toShort()) // Don't fragment
        // TTL
        buffer.put(64.toByte())
        // Protocol (UDP)
        buffer.put(17.toByte())
        // Header checksum placeholder (0 -> compute later)
        buffer.putShort(0x0000.toShort())

        // Source IP
        val src = ipStringToBytes(sourceIp)
        buffer.put(src)

        // Destination IP (fake)
        buffer.put(192.toByte()); buffer.put(168.toByte()); buffer.put(1.toByte()); buffer.put(100.toByte())

        // Compute checksum over the 20 byte header
        val headerBytes = buffer.array()
        val checksum = ipChecksum(headerBytes)
        // write checksum (big-endian) into bytes 10..11
        headerBytes[10] = ((checksum ushr 8) and 0xFF).toByte()
        headerBytes[11] = (checksum and 0xFF).toByte()

        return headerBytes
    }

    private fun createUdpHeader(dataLength: Int, sourcePort: Int): ByteArray {
        // UDP header 8 bytes
        val buffer = ByteBuffer.allocate(8)
        buffer.order(ByteOrder.BIG_ENDIAN)

        val destPort = try {
            // fallback if resource not available; ersetze durch deine Resource-Lookup-Variante falls vorhanden
            context.resources.getInteger(R.integer.udpport)
        } catch (ex: Exception) {
            5004 // default
        }

        buffer.putShort((sourcePort and 0xFFFF).toShort())
        buffer.putShort((destPort and 0xFFFF).toShort())
        buffer.putShort(((8 + dataLength) and 0xFFFF).toShort())
        buffer.putShort(0x0000.toShort()) // checksum 0 (optional for IPv4)

        return buffer.array()
    }

    // --- Hilfsfunktionen ---
    private fun ipStringToBytes(ip: String): ByteArray {
        val parts = ip.split(".")
        val out = ByteArray(4)
        for (i in 0 until 4) {
            val v = if (i < parts.size) parts[i].toIntOrNull() ?: 0 else 0
            out[i] = (v and 0xFF).toByte()
        }
        return out
    }

    /** Berechnet die IP-Header-Checksumme (16-bit ones complement sum) */
    private fun ipChecksum(header: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i < header.size) {
            val hi = header[i].toInt() and 0xFF
            val lo = header[i + 1].toInt() and 0xFF
            val word = (hi shl 8) or lo
            sum += word
            if (sum > 0xFFFF) {
                sum = (sum and 0xFFFF) + (sum ushr 16)
            }
            i += 2
        }
        // one's complement
        sum = sum.inv() and 0xFFFF
        return sum
    }

    private fun dumpPacketToPcap(data: ByteArray, sourceIp: String, sourcePort: Int) {
        try {
            val now = System.currentTimeMillis()
            val tsSec = (now / 1000L).toInt()
            val tsUsec = ((now % 1000L) * 1000L).toInt()

            // Ethernet + IP + UDP header erzeugen
            val eth = createEthernetHeader()
            val ip = createIpHeader(data.size, sourceIp)
            val udp = createUdpHeader(data.size, sourcePort)

            // Packet zusammenbauen
            val packetData = ByteArray(eth.size + ip.size + udp.size + data.size)
            var off = 0
            System.arraycopy(eth, 0, packetData, off, eth.size); off += eth.size
            System.arraycopy(ip, 0, packetData, off, ip.size); off += ip.size
            System.arraycopy(udp, 0, packetData, off, udp.size); off += udp.size
            System.arraycopy(data, 0, packetData, off, data.size)

            val inclLen = packetData.size
            val origLen = inclLen

            // PCAP packet header (16 bytes, little-endian)
            val packetHeader = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            packetHeader.putInt(tsSec)
            packetHeader.putInt(tsUsec)
            packetHeader.putInt(inclLen)
            packetHeader.putInt(origLen)

            pcapFile?.write(packetHeader.array())
            pcapFile?.write(packetData)

            pcapPacketCount++
            if (pcapPacketCount % 100L == 0L) {
                android.util.Log.d("PCAP", "📦 PCAP: $pcapPacketCount packets dumped")
            }
        } catch (e: Exception) {
            android.util.Log.e("PCAP", "PCAP dump error: ${e.message}", e)
        }
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

    private fun decodeAndDisplayFrame(frameData: ByteArray, options: BitmapFactory.Options) {
        try {
            // Schnellere Bitmap Decodierung
            val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.size, options)
            if (bitmap != null) {
                if (DEBUG) {
                    decodeAndLogExif(frameData)
                }
                drawToSurfaceOptimized(bitmap)
                listener?.onFrameDecoded(bitmap.width, bitmap.height)
                bitmap.recycle() // Memory sofort freigeben
            } else {
                Log.w(TAG, "Failed to decode bitmap from frame")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame decode error: ${e.message}")
        }
    }

    fun decodeAndLogExif(frameData: ByteArray) {
        val options = BitmapFactory.Options()
        val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.size, options)

        if (bitmap != null) {
            // --- EXIF AUSLESEN ---
            try {
                val exif = ExifInterface(ByteArrayInputStream(frameData))

                val tags = ExifInterface::class.java.fields
                    .filter { it.name.startsWith("TAG_") }
                    .mapNotNull { it.get(null) as? String }

                for (tag in tags) {
                    val value = exif.getAttribute(tag)
                    if (value != null) {
                        Log.d("EXIF", "$tag = $value")
                    }
                }

            } catch (e: Exception) {
                Log.e("EXIF", "EXIF reading failed", e)
            }

            // --- DEIN BESTEHENDER CODE ---
            drawToSurfaceOptimized(bitmap)
            listener?.onFrameDecoded(bitmap.width, bitmap.height)
            bitmap.recycle()
        }
    }

    private fun drawToSurfaceOptimized(bitmap: Bitmap) {
        var canvas: Canvas? = null
        try {
            canvas = surfaceHolder?.lockCanvas()
            canvas?.let {
                it.drawColor(Color.BLACK)

                // Skalierung nur wenn nötig
                val displayBitmap = if (needsScaling(bitmap, it.width, it.height)) {
                    scaleToSurface(bitmap, it.width, it.height)
                } else {
                    bitmap
                }

                val x = (it.width - displayBitmap.width) / 2f
                val y = (it.height - displayBitmap.height) / 2f
                it.drawBitmap(displayBitmap, x, y, paint)

                // Nur recyceln wenn wir skaliert haben
                if (displayBitmap != bitmap) {
                    displayBitmap.recycle()
                }
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

    private fun needsScaling(bitmap: Bitmap, surfaceWidth: Int, surfaceHeight: Int): Boolean {
        return bitmap.width > surfaceWidth || bitmap.height > surfaceHeight
    }

    private fun scaleToSurface(bitmap: Bitmap, surfaceWidth: Int, surfaceHeight: Int): Bitmap {
        val scale = min(surfaceWidth.toFloat() / bitmap.width, surfaceHeight.toFloat() / bitmap.height)
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true
        )
    }

    private fun isValidHeader(data: ByteArray): Boolean {
        return data.size >= 4 &&
                data[0] == 0x02.toByte() &&
                data[1] == 0x00.toByte() &&
                data[2] == 0xAC.toByte() &&
                data[3] == 0x05.toByte()
    }

    private fun logPerformanceStats() {
        val queueSize = packetQueue.size
        val dropRate = if (framesProcessed + framesSkipped > 0) {
            (framesSkipped.toDouble() / (framesProcessed + framesSkipped) * 100)
        } else 0.0

        val stats = "FPS: ${1000/(System.currentTimeMillis() - lastFrameTime + 1)} | " +
                "Frames: $framesProcessed | " +
                "Skipped: $framesSkipped (${"%.1f".format(dropRate)}%) | " +
                "Queue: $queueSize/100"

        Log.d(TAG, stats)
        listener?.onStreamInfo(stats)
    }

    fun stopStream() {
        isReceiving.set(false)
        udpSocket?.close()
        packetQueue.clear()
        listener?.onVideoStopped()
        stopPcapDump()
        Log.d(TAG, "Stream stopped - Processed: $framesProcessed, Skipped: $framesSkipped")
    }

    fun release() {
        stopStream()
        surfaceHolder = null
        Log.d(TAG, "Receiver released")
    }

    // ------------------- Optimized Frame Assembler -------------------
    class RTPFrameAssembler(private val context: Context) {

        private val activeFrames = mutableMapOf<FrameKey, FrameAssembly>()
        private val frameTimeout = 1000L
        private var framesAssembled = 0

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

            // Clean up old frames
            cleanupOldFrames()

            // Add fragment to frame
            val frameAssembly = activeFrames.getOrPut(frameKey) { FrameAssembly() }
            frameAssembly.fragments[fragmentOffset] = payload
            frameAssembly.lastUpdate = System.currentTimeMillis()

            // Check if frame is complete
            if (isFrameComplete(frameAssembly)) {
                val frameData = assembleJpegFrame(frameAssembly.fragments)
                activeFrames.remove(frameKey)
                framesAssembled++
                return frameData
            }

            return null
        }

        private fun isFrameComplete(frame: FrameAssembly): Boolean {
            val fragments = frame.fragments

            return fragments.values.any { hasSOIMarker(it) } &&
                    fragments.values.any { hasEOFMarker(it) } &&
                    isValidJpegFrame(assembleJpegFrame(fragments))
        }

        private fun isValidJpegFrame(data: ByteArray): Boolean {
            if (data.size < 100) return false
            return hasSOIMarker(data) && hasEOFMarker(data) &&
                    data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() &&
                    data[data.size - 2] == 0xFF.toByte() && data[data.size - 1] == 0xD9.toByte()
        }

        private fun assembleJpegFrame(fragments: Map<Int, ByteArray>): ByteArray {
            if (fragments.isEmpty()) return ByteArray(0)

            val sorted = fragments.toSortedMap()
            val lastOffset = sorted.keys.last()
            val lastSize = sorted[lastOffset]!!.size
            val buffer = ByteArray(lastOffset + lastSize)

            for ((offset, fragment) in sorted) {
                System.arraycopy(fragment, 0, buffer, offset, fragment.size)
            }

            for ((offset, fragment) in fragments.toSortedMap()) {
                val hexString = fragment.joinToString(" ") { String.format("%02X", it) }
                Log.d(TAG, "Fragment at offset $offset (${fragment.size} bytes): $hexString")
            }

            return trimToEOF(buffer)
        }

        private fun trimToEOF(data: ByteArray): ByteArray {
            for (i in 0 until data.size - 1) {
                if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD9.toByte()) {
                    return data.copyOfRange(0, i + 2)
                }
            }
            return data
        }

        private fun hasSOIMarker(data: ByteArray): Boolean {
            for (i in 0 until data.size - 1) {
                if (data[i].toInt() and 0xFF == 0xFF && data[i + 1].toInt() and 0xFF == 0xD8) {
                    return true
                }
            }
            return false
        }

        private fun hasEOFMarker(data: ByteArray): Boolean {
            for (i in 0 until data.size - 1) {
                if (data[i].toInt() and 0xFF == 0xFF && data[i + 1].toInt() and 0xFF == 0xD9) {
                    return true
                }
            }
            return false
        }

        private fun cleanupOldFrames() {
            val now = System.currentTimeMillis()
            val toRemove = activeFrames.filter { (_, frame) ->
                now - frame.lastUpdate > frameTimeout
            }.keys
            toRemove.forEach { activeFrames.remove(it) }
        }

        private fun parseRTPHeader(data: ByteArray): RTPInfo? {
            if (data.size < 20) return null
            return try {
                // 4-Byte Sequence (Little Endian)
                val frameId =  (data[4].toInt() and 0xFF)        or
                        ((data[5].toInt() and 0xFF) shl 8) or
                        ((data[6].toInt() and 0xFF) shl 16) or
                        ((data[7].toInt() and 0xFF) shl 24)

                // 4-Byte Timestamp (Little Endian)
                val timestamp =  (data[8].toInt() and 0xFF)        or
                        ((data[9].toInt() and 0xFF) shl 8) or
                        ((data[10].toInt() and 0xFF) shl 16) or
                        ((data[11].toInt() and 0xFF) shl 24)

                // 2-Byte Fragment Offset (Little Endian)
                val fragmentOffset = (data[12].toInt() and 0xFF) or
                        ((data[13].toInt() and 0xFF) shl 8)

                RTPInfo(frameId, timestamp, fragmentOffset)

            } catch (e: Exception) {
                Log.e(TAG, "Error while parsing custom RTP Header: ${e.message}")
                null
            }
        }
    }

    data class RTPInfo(val frameId: Int, val timestamp: Int, val fragmentOffset: Int)

}