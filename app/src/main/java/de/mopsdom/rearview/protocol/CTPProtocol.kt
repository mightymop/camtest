package de.mopsdom.rearview.protocol

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import de.mopsdom.rearview.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class CTPProtocol(
    private val context: Context,
    private val listener: CommandStreamListener
) {

    interface CommandStreamListener {
        fun onConnected()
        fun onDisconnected()
        fun onCameraConnected(connected: Boolean)
        fun onCommandSent(info: String)
        fun onError(error: String, e: Exception?)
    }

    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var input: InputStream? = null
    private var connected = false

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var readerThread: Thread? = null

    @Volatile private var running = false

    companion object {
        private const val TAG = "CTPProtocol"
        private const val HEADER_STR = "CTP:"
        private val HEADER_BYTES = HEADER_STR.toByteArray()
        private const val MAX_PAYLOAD_SIZE = 5 * 1024 * 1024
    }

    // ======================== CONNECT ==============================
    fun connect() {
        if (connected) return
        ioScope.launch {
            try {
                val ip = context.getString(R.string.cameraip)
                val port = context.resources.getInteger(R.integer.tcpport)
                val timeout = context.resources.getInteger(R.integer.timeout)

                socket = Socket().apply {
                    connect(InetSocketAddress(ip, port), timeout)
                }
                out = socket!!.getOutputStream()
                input = socket!!.getInputStream()
                connected = true
                running = true
                Log.i(TAG, "Connected to $ip:$port")

                startReaderThread()
                startHeartbeat()

                // Video öffnen
                openCameraStream()
                listener.onConnected()

            } catch (e: Exception) {
                connected = false
                listener.onError("connect() failed: ${e.message}", e)
                closeInternal()
            }
        }
    }

    // ======================== DISCONNECT ==============================
    fun disconnect() {
        if (!connected) {
            closeInternal()
            return
        }

        ioScope.launch {
            try {
                val closePacket = buildPacket(
                    CTPCommand.CLOSE_RT_STREAM.command, "PUT", mapOf("status" to "1")
                )
                sendCommand(closePacket)
                Log.i(TAG, "Sent CLOSE_RT_STREAM, waiting for ACK")

                withTimeoutOrNull(2000L) {
                    waitForCloseAck()
                }

            } catch (e: Exception) {
                listener.onError("disconnect() error: ${e.message}", e)
            } finally {
                closeInternal()
                listener.onDisconnected()
            }
        }
    }

    private fun waitForCloseAck() {
        val start = System.currentTimeMillis()
        while (running && System.currentTimeMillis() - start < 2000) {
            Thread.sleep(50)
        }
    }

    // ======================== READER THREAD ==============================
    private fun startReaderThread() {
        readerThread = thread(start = true, name = "CTPReader") {
            try {
                val inp = input ?: return@thread
                val buffer = ByteArray(8192)
                while (running && connected) {
                    val header = ByteArray(4)
                    if (!readFully(inp, header)) continue
                    if (!header.contentEquals(HEADER_BYTES)) {
                        inp.skip(1)
                        continue
                    }

                    val topicLenBytes = ByteArray(2)
                    if (!readFully(inp, topicLenBytes)) continue
                    val topicLen = ByteBuffer.wrap(topicLenBytes)
                        .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                    val topicBytes = ByteArray(topicLen)
                    if (!readFully(inp, topicBytes)) continue
                    val topic = String(topicBytes)

                    val contentLenBytes = ByteArray(4)
                    if (!readFully(inp, contentLenBytes)) continue
                    val contentLen = ByteBuffer.wrap(contentLenBytes)
                        .order(ByteOrder.LITTLE_ENDIAN).int
                    val content = ByteArray(contentLen.coerceAtMost(MAX_PAYLOAD_SIZE))
                    if (!readFully(inp, content)) continue

                    handleMessage(topic, content)
                }
            } catch (e: Exception) {
                if (running) listener.onError("Reader thread crashed: ${e.message}", e)
            }
        }
    }

    private fun handleMessage(topic: String, bytes: ByteArray) {
        try {
            val msgStr = String(bytes)
            val json = JSONObject(msgStr)
            val op = json.optString("op", "")
            val param = json.getJSONObject("param")

            if (topic == CTPCommand.OPEN_RT_STREAM.command && op == "NOTIFY") {
                listener.onCameraConnected(true)
            } else if (topic == CTPCommand.CLOSE_RT_STREAM.command && op == "NOTIFY") {
                listener.onCameraConnected(false)
                running = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Invalid message: ${e.message}")
        }
    }

    // ======================== HEARTBEAT ==============================
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = ioScope.launch {
            while (running) {
                try {
                    delay(30_000)
                    if (!running) break
                    val pkt = buildPacket(
                        CTPCommand.CTP_KEEP_ALIVE.command, "PUT", emptyMap()
                    )
                    sendCommand(pkt)
                    Log.v(TAG, "Heartbeat sent")
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat error: ${e.message}")
                }
            }
        }
    }

    // ======================== SEND & UTILS ==============================
    fun sendCommand(packet: ByteArray) {
        ioScope.launch {
            try {
                out?.write(packet)
                out?.flush()
                listener.onCommandSent("Command sent: ${packet.size} bytes")
            } catch (e: Exception) {
                listener.onError("sendCommand() failed", e)
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var total = 0
        while (total < buf.size) {
            val r = input.read(buf, total, buf.size - total)
            if (r == -1) return false
            total += r
        }
        return true
    }

    private fun buildPacket(command: String, op: String, params: Map<String, Any>): ByteArray {
        val jsonStr = buildJson(op, params)
        val cmdBytes = command.toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().use { stream ->
            stream.write(HEADER_BYTES)
            stream.write(byteArrayOf(
                (cmdBytes.size and 0xFF).toByte(),
                ((cmdBytes.size shr 8) and 0xFF).toByte()
            ))
            stream.write(cmdBytes)
            stream.write(byteArrayOf(getSuffix(command)))
            stream.write(ByteArray(3))
            stream.write(jsonStr.toByteArray())
            stream.toByteArray()
        }
    }

    private fun buildJson(op: String, params: Map<String, Any>): String {
        val paramStr = params.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
        return """{"op":"$op","param":{$paramStr}}"""
    }

    private fun getSuffix(cmd: String): Byte {
        return when (cmd) {
            CTPCommand.APP_ACCESS.command -> 0x2f
            CTPCommand.OPEN_RT_STREAM.command -> 0x42
            CTPCommand.CLOSE_RT_STREAM.command -> 0x23
            CTPCommand.VIDEO_PARAM.command -> 0x00
            CTPCommand.VIDEO_CTRL.command -> 0x26
            CTPCommand.DATE_TIME.command -> 0x2e
            CTPCommand.LANGUAGE.command -> 0x23
            CTPCommand.CTP_KEEP_ALIVE.command -> 0x17
            else -> 0x00
        }.toByte()
    }

    private fun openCameraStream(useHd: Boolean = true) {

        var quality: String? = PreferenceManager.getDefaultSharedPreferences(context)
                               .getString("pref_quality",context.resources
                                   .getString(R.string.defaultquality))

        var h=0
        var w=0
        when (quality) {
            "480p" -> {
                h=context.resources.getInteger(R.integer.sd_height)
                w=context.resources.getInteger(R.integer.sd_width)
            }
            "720p" -> {
                h=context.resources.getInteger(R.integer.hd_height)
                w=context.resources.getInteger(R.integer.hd_width)
            }
            "1080p" -> {
                h=context.resources.getInteger(R.integer.fhd_height)
                w=context.resources.getInteger(R.integer.fhd_width)
            }
            else -> {
                h=context.resources.getInteger(R.integer.hd_height)
                w=context.resources.getInteger(R.integer.hd_width)
            }
        }

        val fps = context.resources.getInteger(R.integer.frames)

        val setParams = buildPacket(
            CTPCommand.OPEN_RT_STREAM.command, "PUT",
            mapOf("w" to w, "h" to h, "format" to "0", "fps" to fps)
        )
        sendCommand(setParams)
    }

    // ======================== CLOSE INTERNAL ==============================
    private fun closeInternal() {
        running = false
        connected = false
        heartbeatJob?.cancel()
        try { readerThread?.interrupt() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { input?.close() } catch (_: Exception) {}
        try { out?.close() } catch (_: Exception) {}
        socket = null; out = null; input = null
        Log.i(TAG, "Sockets closed")
    }
}
