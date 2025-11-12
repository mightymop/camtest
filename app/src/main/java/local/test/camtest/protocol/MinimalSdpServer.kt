import android.content.Context
import local.test.camtest.R
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

class MinimalSdpServer(private val context: Context) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var port = 12345

    fun startServer(): Int {
        return try {
            serverSocket = ServerSocket(port) // 0 = auto-assign port
            isRunning = true

            Thread {
                while (isRunning) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        handleClient(clientSocket)
                    } catch (e: IOException) {
                        if (isRunning) {
                            e.printStackTrace()
                        }
                    }
                }
            }.start()

            port
        } catch (e: IOException) {
            e.printStackTrace()
            -1
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val outputStream = client.getOutputStream()
            val sdpContent = createSdpContent()
            val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/sdp\r\n" +
                    "Content-Length: ${sdpContent.toByteArray().size}\r\n" +
                    "\r\n" +
                    sdpContent
            outputStream.write(response.toByteArray(Charsets.UTF_8))
            outputStream.flush()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            try {
                client.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun createSdpContent(): String {

        val ip = getLocalWifiLikeIp()

        val inputStream = context.resources.openRawResource(R.raw.rts_jpeg)
        var content = inputStream.bufferedReader().use { it.readText() }
        inputStream.close()

        content=content.replace("127.0.0.1",ip)+"\n"
        return content;
    }

    fun getLocalWifiLikeIp(): String {
        val en = NetworkInterface.getNetworkInterfaces()
        while (en.hasMoreElements()) {
            val intf = en.nextElement()
            if (!intf.isUp || intf.isLoopback) continue
            val addrs = intf.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address) {
                    val ip = addr.hostAddress
                    // Prüfe, ob IP wie typische private WLAN-IP aussieht
                    if (ip.startsWith("192.168.")) {
                        return ip
                    }
                }
            }
        }
        return "127.0.0.1"
    }

    fun getSdpUrl(ip: String): String {
        return "http://$ip:$port/stream.sdp"
    }

    fun stopServer() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}