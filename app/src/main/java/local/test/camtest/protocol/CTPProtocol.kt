package local.test.camtest.protocol

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CTPProtocol {

    companion object {
        private const val TAG = "CTPProtocol"
        private const val HEADER = "CTP:"
        private val HEADER_BYTES = HEADER.toByteArray()
        private const val MAX_PAYLOAD_SIZE = 5 * 1024 * 1024 // 5MB like Java version
    }

    suspend fun feed(input: InputStream, onMessage: (CTPMessage) -> Unit) {
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(8192)
            var receiveErrorTime = 0

            while (true) {
                try {
                    // Check for available data first
                    if (input.available() <= 0) {
                        kotlinx.coroutines.delay(10L) // Like SystemClock.sleep(10L)
                        continue
                    }

                    // Read header (4 bytes)
                    val headerBytes = ByteArray(4)
                    if (!readFully(input, headerBytes)) {
                        Log.w(TAG, "Failed to read header")
                        continue
                    }

                    // Check signature
                    if (!headerBytes.contentEquals(HEADER_BYTES)) {
                        Log.w(TAG, "CTP signature not match")
                        // Skip invalid byte and continue
                        input.skip(1)
                        continue
                    }

                    // Read topic length (2 bytes little-endian)
                    val topicLenBytes = ByteArray(2)
                    if (!readFully(input, topicLenBytes)) {
                        Log.w(TAG, "Failed to read topic length")
                        continue
                    }
                    val topicLen = ByteBuffer.wrap(topicLenBytes)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .short.toInt() and 0xFFFF

                    if (topicLen <= 0) {
                        Log.w(TAG, "Invalid topic length: $topicLen")
                        continue
                    }

                    // Read topic
                    val topicBytes = ByteArray(topicLen)
                    if (!readFully(input, topicBytes) || topicBytes.size != topicLen) {
                        Log.w(TAG, "Failed to read topic")
                        continue
                    }
                    val topic = String(topicBytes, Charsets.UTF_8)

                    if (topic.isEmpty()) {
                        Log.w(TAG, "Topic is empty")
                        continue
                    }

                    // Read content length (4 bytes little-endian)
                    val contentLenBytes = ByteArray(4)
                    if (!readFully(input, contentLenBytes)) {
                        Log.w(TAG, "Failed to read content length")
                        continue
                    }
                    val contentLen = ByteBuffer.wrap(contentLenBytes)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .int

                    // Read JSON content
                    val message = if (contentLen > 0 && contentLen < MAX_PAYLOAD_SIZE) {
                        val contentBytes = ByteArray(contentLen)
                        if (readFully(input, contentBytes) && contentBytes.size == contentLen) {
                            parseContent(topic, contentBytes)
                        } else {
                            Log.w(TAG, "Failed to read content")
                            CTPMessage(topic, 0, "{}") // Empty message
                        }
                    } else {
                        CTPMessage(topic, 0, "{}") // Empty message for zero or too large content
                    }

                    onMessage(message)
                    receiveErrorTime = 0 // Reset error counter on success

                } catch (e: Exception) {
                    Log.e(TAG, "Error reading data: ${e.message}", e)
                    receiveErrorTime++

                    if (receiveErrorTime > 5) {
                        Log.w(TAG, "Too many receive errors, stopping")
                        break
                    }

                    kotlinx.coroutines.delay(1000L) // Wait before retry
                }
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = input.read(buffer, totalRead, buffer.size - totalRead)
            if (read == -1) {
                return false
            }
            totalRead += read
        }
        return true
    }

    private fun parseContent(topic: String, contentBytes: ByteArray): CTPMessage {
        return try {
            val contentStr = String(contentBytes, Charsets.UTF_8)
            if (contentStr.isEmpty()) {
                return CTPMessage(topic, 0, "{}")
            }

            val json = JSONObject(contentStr)
            val errorType = json.optInt("err", 0)
            val operation = json.optString("op", "")
            val paramsJson = json.optJSONObject("param") ?: JSONObject()

            // Convert params to map
            val paramsMap = mutableMapOf<String, String>()
            val keys = paramsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                paramsMap[key] = paramsJson.optString(key, "")
            }

            // Build response JSON in your format
            val responseJson = buildString {
                append("""{"op":"$operation",""")
                if (errorType != 0) {
                    append(""","err":$errorType,""")
                }
                append(""","param":{""")
                paramsMap.entries.forEachIndexed { index, (key, value) ->
                    if (index > 0) append(",")
                    append("""""$key":"$value"""")
                }
                append("}}")
            }

            CTPMessage(topic, 0, responseJson)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing content: ${e.message}", e)
            CTPMessage(topic, 0, "{}")
        }
    }

    fun buildPacket(command: String, op: String, params: Map<String, Any>): ByteArray {
        val jsonStr = buildJsonString(op, params)
        Log.i(TAG, "Sending: $jsonStr")

        return ByteArrayOutputStream().use { stream ->
            // Header
            stream.write(HEADER_BYTES)

            // Command length (2 bytes little-endian)
            val cmdBytes = command.toByteArray(Charsets.UTF_8)
            stream.write(byteArrayOf(
                (cmdBytes.size and 0xFF).toByte(),
                ((cmdBytes.size shr 8) and 0xFF).toByte()
            ))

            // Command
            stream.write(cmdBytes)

            // Suffix - als ByteArray mit einem Element
            stream.write(byteArrayOf(getSuffixForCommand(command)))

            // Padding
            stream.write(ByteArray(3))

            // JSON data
            stream.write(jsonStr.toByteArray(Charsets.UTF_8))

            stream.toByteArray()
        }
    }

    private fun buildJsonString(op: String, params: Map<String, Any>): String {
        val paramStr = params.entries.joinToString(",") { """"${it.key}":"${it.value}"""" }
        return """{"op":"$op","param":{$paramStr}}"""
    }

    private fun getSuffixForCommand(command: String): Byte {
        return when (command) {
            "APP_ACCESS" -> 0x2f
            "OPEN_RT_STREAM" -> 0x42
            "CLOSE_RT_STREAM" -> 0x23
            "VIDEO_PARAM" -> 0x00
            "VIDEO_CTRL" -> 0x26
            "DATE_TIME" -> 0x2e
            "LANGUAGE" -> 0x23
            "CTP_KEEP_ALIVE" -> 0x17
            else -> 0x00
        }.toByte()
    }
}

// Helper extension for ByteArrayOutputStream
private inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        this.close()
    }
}