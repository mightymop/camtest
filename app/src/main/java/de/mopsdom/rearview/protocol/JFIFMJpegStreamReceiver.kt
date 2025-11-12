package de.mopsdom.rearview.protocol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.util.Log
import android.view.SurfaceHolder
import de.mopsdom.rearview.R
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

    enum class StreamMode {
        RTP_FORWARDING,    // Aktuelles Verhalten - Weiterleitung via RTP
        DIRECT_DISPLAY     // NEU: Direkte Anzeige ohne RTP
    }

    private var udpSocket: DatagramSocket? = null
    private val isReceiving = AtomicBoolean(false)
    private var listener: StreamListener? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var paint: Paint = Paint()
    private val redPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 15f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val yellowPaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 15f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val greenPaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 15f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // Performance Optimierungen
    private val packetQueue = LinkedBlockingQueue<ByteArray>(100)
    private lateinit var frameAssembler: RTPFrameAssembler
    private lateinit var directFrameAssembler: DirectFrameAssembler // NEU
    private lateinit var context: Context

    // Frame-Rate Limiting
    private var lastFrameTime = 0L
    private val targetFrameTime = 33L // ~30 FPS
    private var framesProcessed = 0
    private var framesSkipped = 0

    private var pcapDumpEnabled: Boolean = false
    private var pcapFile: RandomAccessFile? = null
    private var pcapPacketCount = 0

    private var parkingLines: Boolean = true

    // NEUE VARIABLEN FÜR DIRECT DISPLAY
    private var streamMode: StreamMode = StreamMode.DIRECT_DISPLAY // Standard: Direkte Anzeige
    private var currentStreamMode: StreamMode = StreamMode.DIRECT_DISPLAY
    private var frameBuffer = ByteArray(200 * 1024) // 200KB Buffer für komplette Frames
    private var frameBufferUsed = 0
    private var currentFrameSize = 0
    private var currentFrameSeq = 0
    private var framesReceived = 0L
    private var fps = 0

    private val DEBUG = false

    companion object {
        private const val TAG = "JFIFMJpegStreamReceiver"
        private const val BUFFER_SIZE = 65536
        private const val PROTO_HEADER_SIZE = 20
    }

    init {
        paint.isFilterBitmap = true
        paint.isAntiAlias = false
    }

    fun initialize(holder: SurfaceHolder, listener: StreamListener, context: Context) {
        this.surfaceHolder = holder
        this.listener = listener
        this.context = context
        this.frameAssembler = RTPFrameAssembler(DEBUG)
        this.directFrameAssembler = DirectFrameAssembler() // NEU

        Log.d(TAG, "JFIF MJPEG Receiver initialized - Mode: $streamMode")
        listener.onStreamInfo("JFIF MJPEG Ready - Mode: $streamMode")
    }

    fun setStreamMode(mode: StreamMode) {
        this.streamMode = mode
        Log.d(TAG, "Stream mode set to: $mode")
        listener?.onStreamInfo("Mode changed to: $mode")
    }

    fun startStream(enablePcapDump: Boolean = false) {
        pcapDumpEnabled = enablePcapDump
        currentStreamMode = streamMode // Aktuellen Modus speichern

        if (isReceiving.get()) {
            Log.w(TAG, "Stream already running")
            return
        }

        if (pcapDumpEnabled) startPcapDump()

        isReceiving.set(true)
        packetQueue.clear()
        framesProcessed = 0
        framesSkipped = 0
        framesReceived = 0

        // Buffer zurücksetzen
        frameBufferUsed = 0
        currentFrameSize = 0

        // Starte separate Threads für Empfang und Verarbeitung
        startUdpReceiver()

        when (currentStreamMode) {
            StreamMode.RTP_FORWARDING -> startRtpFrameProcessor()
            StreamMode.DIRECT_DISPLAY -> startDirectFrameProcessor() // NEU
        }

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

    private fun startDirectFrameProcessor() {
        Thread {
            Log.d(TAG, "Direct Frame Processor started")

            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inMutable = true
                inSampleSize = 1
                inJustDecodeBounds = false
            }

            while (isReceiving.get()) {
                try {
                    val packet = packetQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (packet != null) {
                        processDirectPacket(packet, options)
                    }

                    if (framesProcessed % 60 == 0) {
                        logPerformanceStats()
                    }

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Direct frame processing error: ${e.message}")
                }
            }

            Log.d(TAG, "Direct Frame Processor stopped")
        }.start()
    }

    private fun processDirectPacket(packet: ByteArray, options: BitmapFactory.Options) {
        if (packet.size < PROTO_HEADER_SIZE) {
            Log.w(TAG, "Short packet for direct processing: ${packet.size} bytes")
            return
        }

        // NEU: Prüfe auf kombinierte Pakete
        val isCombinedPacket = packet.size > 1500
        if (isCombinedPacket && DEBUG) {
            Log.d(TAG, "Combined packet detected: ${packet.size} bytes")
        }

        var processed = 0
        var fragmentCount = 0

        // NEU: Loop für multiple Frames im Paket
        while (processed < packet.size) {
            // Prüfe ob genug Daten für Header vorhanden
            if (processed + PROTO_HEADER_SIZE > packet.size) {
                Log.w(TAG, "Incomplete header at offset $processed")
                break
            }

            // Protokoll-Header parsen (Little Endian)
            val type = packet[processed].toInt() and 0xFF
            val blockSize = (packet[processed + 2].toInt() and 0xFF) or
                    ((packet[processed + 3].toInt() and 0xFF) shl 8)
            val sequence = (packet[processed + 4].toInt() and 0xFF) or
                    ((packet[processed + 5].toInt() and 0xFF) shl 8) or
                    ((packet[processed + 6].toInt() and 0xFF) shl 16) or
                    ((packet[processed + 7].toInt() and 0xFF) shl 24)
            val frameSize = (packet[processed + 8].toInt() and 0xFF) or
                    ((packet[processed + 9].toInt() and 0xFF) shl 8) or
                    ((packet[processed + 10].toInt() and 0xFF) shl 16) or
                    ((packet[processed + 11].toInt() and 0xFF) shl 24)
            val offset = (packet[processed + 12].toInt() and 0xFF) or
                    ((packet[processed + 13].toInt() and 0xFF) shl 8) or
                    ((packet[processed + 14].toInt() and 0xFF) shl 16) or
                    ((packet[processed + 15].toInt() and 0xFF) shl 24)

            // Prüfe ob komplettes Fragment verfügbar ist
            if (processed + PROTO_HEADER_SIZE + blockSize > packet.size) {
                Log.w(TAG, "Incomplete fragment at offset $processed: need $blockSize bytes, have ${packet.size - processed - PROTO_HEADER_SIZE}")
                break
            }

            fragmentCount++

            val dataType = type and 0x7F
            val isLastFragment = (type and 0x80) != 0

            // NEU: Detailliertes Logging für kombinierte Pakete
            if (isCombinedPacket && DEBUG) {
                Log.d(TAG, "Combined packet fragment $fragmentCount: type=0x${type.toString(16)}, seq=$sequence, offset=$offset, size=$blockSize, pos=$processed")
            }

            // Nur JPEG-Video-Daten verarbeiten (type 2)
            if (dataType != 2) {
                processed += PROTO_HEADER_SIZE + blockSize
                continue
            }

            val payload = packet.copyOfRange(
                processed + PROTO_HEADER_SIZE,
                processed + PROTO_HEADER_SIZE + blockSize
            )

            val isNewFrame = (offset == 0)

            if (isNewFrame) {
                // Neuer Frame startet - Buffer zurücksetzen
                frameBufferUsed = 0
                currentFrameSeq = sequence
                currentFrameSize = frameSize
                if (DEBUG) {
                    Log.v(
                        TAG,
                        "New direct frame: seq=$sequence, expectedSize=$frameSize, combined=$isCombinedPacket"
                    )
                }
            }

            // NEU: JPEG Header Validierung
            if (offset == 0) {
                // Prüfe JPEG Start Marker
                if (payload.size >= 2) {
                    if (payload[0] == 0xFF.toByte() && payload[1] == 0xD8.toByte()) {
                        if (DEBUG) {
                            Log.v(TAG, "Valid JPEG SOI marker found in fragment $fragmentCount")
                        }
                    } else {
                        Log.w(TAG, "INVALID JPEG SOI marker in fragment $fragmentCount: ${payload[0].toInt() and 0xFF} ${payload[1].toInt() and 0xFF}")

                        // Versuche korrumpierte JPEGs zu erkennen
                        if (payload[0] == 0x00.toByte() && payload[1] == 0x00.toByte()) {
                            Log.w(TAG, "H264 start code detected in JPEG data - STREAM CORRUPTION!")
                        }
                    }
                }
            }

            // Daten zum Frame-Buffer hinzufügen
            if (frameBufferUsed + blockSize <= frameBuffer.size) {
                System.arraycopy(payload, 0, frameBuffer, frameBufferUsed, blockSize)
                frameBufferUsed += blockSize
                if (DEBUG) {
                    Log.v(
                        TAG,
                        "Added $blockSize bytes to direct frame buffer, total: $frameBufferUsed"
                    )
                }
            } else {
                Log.e(TAG, "Direct frame buffer overflow! Cannot add $blockSize bytes (already $frameBufferUsed used)")
            }

            // Prüfen ob Frame komplett ist
            val isFrameComplete = currentFrameSize > 0 && (offset + blockSize >= currentFrameSize)

            if (isFrameComplete) {
                // Frame-Rate Limiting
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastFrameTime < targetFrameTime) {
                    // Frame überspringen, aber Buffer zurücksetzen
                    frameBufferUsed = 0
                    currentFrameSize = 0
                    processed += PROTO_HEADER_SIZE + blockSize
                    continue
                }
                lastFrameTime = currentTime

                framesProcessed++
                framesReceived++

                if (DEBUG) {
                    Log.v(
                        TAG,
                        "Direct frame complete: seq=$sequence, size=$frameBufferUsed, combined=$isCombinedPacket"
                    )
                }
                decodeAndDisplayDirectFrame(frameBuffer.copyOfRange(0, frameBufferUsed), options)
                updateFps()

                // Buffer für nächsten Frame zurücksetzen
                frameBufferUsed = 0
                currentFrameSize = 0
            }

            // Zum nächsten Fragment
            processed += PROTO_HEADER_SIZE + blockSize
        }

        // NEU: Zusammenfassung für kombinierte Pakete
        if (isCombinedPacket && fragmentCount > 1) {
            if (DEBUG) {
                Log.i(
                    TAG,
                    "=== COMBINED PACKET SUMMARY: $fragmentCount fragments processed from ${packet.size} bytes ==="
                )
            }
        }
    }

    private fun decodeAndDisplayDirectFrame(frameData: ByteArray, options: BitmapFactory.Options) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.size, options)
            if (bitmap != null) {
                if (DEBUG) {
                    decodeAndLogExif(frameData)
                }
                drawToSurfaceOptimized(bitmap)
                listener?.onFrameDecoded(bitmap.width, bitmap.height)
                bitmap.recycle()
            } else {
                Log.w(TAG, "Failed to decode direct frame bitmap")
                // Debug: Erste Bytes loggen
                if (frameData.size >= 8 && DEBUG) {
                    Log.d(TAG, "Direct frame start: ${frameData.sliceArray(0..7).joinToString(" ") { "%02X".format(it) }}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Direct frame decode error: ${e.message}")
        }
    }

    private fun updateFps() {
        val currentTime = System.currentTimeMillis()
        if (lastFrameTime > 0) {
            val frameTime = currentTime - lastFrameTime
            if (frameTime > 0) {
                fps = ((1000 / frameTime).coerceAtMost(60)).toInt()
            }
        }
        lastFrameTime = currentTime
    }

    private fun drawToSurfaceOptimized(bitmap: Bitmap) {
        var canvas: Canvas? = null
        try {
            canvas = surfaceHolder?.lockCanvas()
            canvas?.let {
                it.drawColor(Color.BLACK)

                val displayBitmap = if (needsScaling(bitmap, it.width, it.height)) {
                    scaleToSurface(bitmap, it.width, it.height)
                } else {
                    bitmap
                }

                val x = (it.width - displayBitmap.width) / 2f
                val y = (it.height - displayBitmap.height) / 2f
                it.drawBitmap(displayBitmap, x, y, paint)

                // FPS und Modus anzeigen
                if (DEBUG) {
                    drawStatusInfo(it)
                }

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

    private fun drawStatusInfo(canvas: Canvas) {
        val statusPaint = Paint().apply {
            color = Color.WHITE
            textSize = 30f
            isAntiAlias = true
        }

        val queueSize = packetQueue.size
        val dropRate = if (framesProcessed + framesSkipped > 0) {
            (framesSkipped.toDouble() / (framesProcessed + framesSkipped) * 100)
        } else 0.0

        canvas.drawText("FPS: $fps", 20f, 50f, statusPaint)
        canvas.drawText("Frames: $framesReceived", 20f, 90f, statusPaint)
        canvas.drawText("Mode: $currentStreamMode", 20f, 130f, statusPaint)
        canvas.drawText("Queue: $queueSize/100", 20f, 170f, statusPaint)
        canvas.drawText("Dropped: ${"%.1f".format(dropRate)}%", 20f, 210f, statusPaint)
    }

    private fun startRtpFrameProcessor() {
        Thread {
            Log.d(TAG, "RTP Frame Processor started")

            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inMutable = true
                inSampleSize = 1
                inJustDecodeBounds = false
            }

            while (isReceiving.get()) {
                try {
                    val packet = packetQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (packet != null) {
                        processRtpPacket(packet, options)
                    }

                    if (framesProcessed % 60 == 0) {
                        logPerformanceStats()
                    }

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "RTP frame processing error: ${e.message}")
                }
            }

            Log.d(TAG, "RTP Frame Processor stopped")
        }.start()
    }

    private fun processRtpPacket(packet: ByteArray, options: BitmapFactory.Options) {
        // NEU: Prüfe auf kombinierte Pakete für RTP
        val isCombinedPacket = packet.size > 1500
        var processed = 0
        var framesInPacket = 0

        if (isCombinedPacket) {
            Log.d(TAG, "RTP Combined packet detected: ${packet.size} bytes")
        }

        // NEU: Loop für multiple RTP-Frames
        while (processed < packet.size) {
            if (processed + PROTO_HEADER_SIZE > packet.size) {
                break
            }

            // Versuche Fragment zu verarbeiten
            val remainingData = packet.copyOfRange(processed, packet.size)
            val frameData = frameAssembler.processPacket(remainingData)

            if (frameData != null) {
                framesInPacket++

                // Frame-Rate Limiting
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastFrameTime < targetFrameTime) {
                    processed += PROTO_HEADER_SIZE + getPayloadSize(remainingData)
                    continue
                }
                lastFrameTime = currentTime

                framesProcessed++
                framesReceived++
                decodeAndDisplayFrame(frameData, options)
                updateFps()

                // Zur nächsten Position springen (approximativ)
                processed += PROTO_HEADER_SIZE + getPayloadSize(remainingData)
            } else {
                // Kein kompletter Frame gefunden - break
                break
            }
        }

        if (isCombinedPacket && framesInPacket > 1) {
            Log.i(TAG, "RTP Combined: $framesInPacket frames in one packet")
        }
    }

    // NEUE HILFSFUNKTION: Payload-Größe aus Packet ermitteln
    private fun getPayloadSize(packet: ByteArray): Int {
        return if (packet.size >= 4) {
            (packet[2].toInt() and 0xFF) or ((packet[3].toInt() and 0xFF) shl 8)
        } else {
            0
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

        val stats = "FPS: $fps | " +
                "Frames: $framesReceived | " +
                "Skipped: $framesSkipped (${"%.1f".format(dropRate)}%) | " +
                "Queue: $queueSize/100 | " +
                "Mode: $currentStreamMode"

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

    private class DirectFrameAssembler {
        // Kann erweitert werden für komplexere Frame-Zusammensetzung
        fun isFrameComplete(currentSize: Int, expectedSize: Int): Boolean {
            return currentSize >= expectedSize && expectedSize > 0
        }
    }
    // ------------------- Optimized Frame Assembler -------------------
    class RTPFrameAssembler {

        private val activeFrames = mutableMapOf<FrameKey, FrameAssembly>()
        private val frameTimeout = 1000L
        private var framesAssembled = 0

        data class FrameKey(val frameId: Int, val timestamp: Int)
        data class FrameAssembly(
            val fragments: MutableMap<Int, ByteArray> = mutableMapOf(),
            var lastUpdate: Long = System.currentTimeMillis()
        )

        private var debug : Boolean = false

        constructor( debug: Boolean)
        {
            this.debug=debug
        }

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
            val frameData = isFrameComplete(frameAssembly)
            if (frameData!=null) {

                if (debug) {
                    for ((offset, fragment) in frameAssembly.fragments.toSortedMap()) {
                        val hexString = fragment.joinToString(" ") { String.format("%02X", it) }
                        Log.d(
                            TAG,
                            "Fragment at offset $offset (${fragment.size} bytes): $hexString"
                        )
                    }
                }

                activeFrames.remove(frameKey)
                framesAssembled++
                return frameData
            }

            return null
        }

        private fun isFrameComplete(frame: FrameAssembly): ByteArray? {
            val fragments = frame.fragments

            var hasMarkers =  fragments.values.any { hasSOIMarker(it) } && fragments.values.any { hasEOFMarker(it) }
            if (hasMarkers) {
                var frame = assembleJpegFrame(fragments)
                if (isValidJpegFrame(frame))
                {
                    return frame
                }
            }

            return null
        }

        private fun isValidJpegFrame(data: ByteArray): Boolean {
            if (data.size < 100) return false
            return hasSOIMarker(data) && hasEOFMarker(data) &&
                    data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() &&
                    data[data.size - 2] == 0xFF.toByte() && data[data.size - 1] == 0xD9.toByte()
        }

        private fun assembleJpegFrame(fragments: Map<Int, ByteArray>): ByteArray {
            if (fragments.isEmpty())
                return ByteArray(0)

            val sorted = fragments.toSortedMap()
            val lastOffset = sorted.keys.last()
            val lastSize = sorted[lastOffset]!!.size
            val buffer = ByteArray(lastOffset + lastSize)

            for ((offset, fragment) in sorted) {
                System.arraycopy(fragment, 0, buffer, offset, fragment.size)
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

                // 4-Byte frametick (Little Endian)
                val frametick =  (data[8].toInt() and 0xFF)        or
                        ((data[9].toInt() and 0xFF) shl 8) or
                        ((data[10].toInt() and 0xFF) shl 16) or
                        ((data[11].toInt() and 0xFF) shl 24)

                // 4-Byte Fragment Offset (Little Endian)
                val fragmentOffset = (data[12].toInt() and 0xFF)        or
                        ((data[13].toInt() and 0xFF) shl 8) or
                        ((data[14].toInt() and 0xFF) shl 16) or
                        ((data[15].toInt() and 0xFF) shl 24)

                RTPInfo(frameId, frametick, fragmentOffset)

            } catch (e: Exception) {
                Log.e(TAG, "Error while parsing custom RTP Header: ${e.message}")
                null
            }
        }
    }

    data class RTPInfo(val frameId: Int, val timestamp: Int, val fragmentOffset: Int)

}