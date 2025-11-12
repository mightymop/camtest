package local.test.camtest.protocol

class RtpConvertProxy {

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }

    public external fun start(destIp: String, destPort: Int, juseRfc2435: Boolean, jfps : Int): Boolean

    public external fun stop()

}