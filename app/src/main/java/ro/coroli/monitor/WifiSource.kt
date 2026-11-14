package ro.coroli.monitor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.URL

/** Live view over Wi-Fi using the Sony Camera Remote API (camera = access point). */
class WifiSource(
    private val onFrame: (Bitmap) -> Unit,
    private val onStatus: (String) -> Unit
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(host: String = "192.168.122.1") {
        stop()
        running = true
        thread = Thread { loop(host) }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun rpc(host: String, method: String): String {
        val c = URL("http://$host:8080/sony/camera").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 4000
        c.readTimeout = 8000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use {
            it.write("""{"method":"$method","params":[],"id":1,"version":"1.0"}""".toByteArray())
        }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun loop(host: String) {
        try {
            onStatus("Wi-Fi: conectare la $host ...")
            try { rpc(host, "startRecMode") } catch (_: Exception) { }
            val resp = rpc(host, "startLiveview")
            val liveUrl = JSONObject(resp).getJSONArray("result").getString(0)
            onStatus("Wi-Fi: live view pornit")

            val c = URL(liveUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 8000
            val ins = DataInputStream(BufferedInputStream(c.inputStream, 1 shl 16))
            val common = ByteArray(8)
            val ph = ByteArray(128)
            while (running) {
                ins.readFully(common)
                if (common[0] != 0xFF.toByte()) continue
                val type = common[1].toInt() and 0xFF
                ins.readFully(ph)
                val size = ((ph[4].toInt() and 0xFF) shl 16) or
                        ((ph[5].toInt() and 0xFF) shl 8) or
                        (ph[6].toInt() and 0xFF)
                val pad = ph[7].toInt() and 0xFF
                val data = ByteArray(size)
                ins.readFully(data)
                if (pad > 0) ins.skipBytes(pad)
                if (type == 1) {
                    BitmapFactory.decodeByteArray(data, 0, size)?.let(onFrame)
                }
            }
        } catch (e: Exception) {
            if (running) onStatus("Wi-Fi eroare: ${e.message}. Esti conectat la Wi-Fi-ul camerei?")
        }
    }
}
