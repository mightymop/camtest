package local.test.camtest.protocol

import dalvik.annotation.optimization.CriticalNative
import java.io.InputStream

class NativeConnection {

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }

    public external fun start(ip: String, width: Int, height: Int,): Boolean

    public external fun stop()

}