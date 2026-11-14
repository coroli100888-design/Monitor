package ro.coroli.monitor

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

class WifiSource(
    private val onFrame: (Bitmap) -> Unit,
    private val onStatus: (String) -> Unit
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(hosts: List<String>) {
        stop()
        running = true
        thread = Thread { loop(hosts) }.also { it.start() }
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

    private fun loop(hosts: List<String>) {
        try {
            var found: String? = null
            for (h in hosts) {
                if (!running) return
                onStatus("Wi-Fi: testez $h:8080 ...")
                try {
                    Socket().use { it.connect(InetSocketAddress(h, 8080), 1500) }
                    found = h
                    break
                } catch (_: Exception) { }
            }
            val host = found
            if (host == null) {
                onStatus("Wi-Fi: portul 8080 nu raspunde la: ${hosts.joinToString()}")
                return
            }

            onStatus("Wi-Fi: conectare la $host ...")
            try { rpc(host, "startRecMode") } catch (_: Exception) { }
            val resp = rpc(host, "startLiveview")
            val liveUrl = JSONObject(resp).getJSONArray("result").getString(0)
            onStatus("Wi-Fi: live view pornit ($host)")

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
            if (running) onStatus("Wi-Fi eroare: ${e.message}")
        }
    }
}

object UsbSource {
    fun findVideoDevices(ctx: Context): List<UsbDevice> {
        val m = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        return m.deviceList.values.filter { d ->
            (0 until d.interfaceCount).any {
                d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO
            }
        }
    }
}

class OverlayView(ctx: Context) : View(ctx) {
    var grid = true
    var center = true
    var frameRatio = 0f

    private val p = Paint().apply {
        color = Color.argb(160, 255, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (grid) {
            for (i in 1..2) {
                c.drawLine(w * i / 3, 0f, w * i / 3, h, p)
                c.drawLine(0f, h * i / 3, w, h * i / 3, p)
            }
        }
        if (center) {
            c.drawLine(w / 2 - 30, h / 2, w / 2 + 30, h / 2, p)
            c.drawLine(w / 2, h / 2 - 30, w / 2, h / 2 + 30, p)
        }
        if (frameRatio > 0f) {
            val fh = w / frameRatio
            if (fh < h) c.drawRect(0f, (h - fh) / 2, w, (h + fh) / 2, p)
        }
    }
}

class MainActivity : Activity() {
    private lateinit var image: ImageView
    private lateinit var overlay: OverlayView
    private lateinit var status: TextView
    private var wifi: WifiSource? = null
    private var ratioIdx = 0
    private val ratios = floatArrayOf(0f, 1.78f, 2.0f, 2.39f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        overlay = OverlayView(this)
        root.addView(image, -1, -1)
        root.addView(overlay, -1, -1)

        val brand = TextView(this).apply {
            text = "Coroli Alexandru Photography"
            setTextColor(Color.WHITE); textSize = 14f; alpha = 0.85f
            setPadding(24, 16, 24, 16)
        }
        root.addView(brand, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))

        status = TextView(this).apply {
            text = "Alege sursa: USB sau Wi-Fi"
            setTextColor(Color.LTGRAY); textSize = 12f
            setPadding(24, 16, 24, 16)
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, a: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { a() }
        }.also { bar.addView(it) }
        btn("USB") { startUsb() }
        btn("Wi-Fi") { startWifi() }
        btn("Grid") { overlay.grid = !overlay.grid; overlay.invalidate() }
        btn("Cadru") {
            ratioIdx = (ratioIdx + 1) % ratios.size
            overlay.frameRatio = ratios[ratioIdx]; overlay.invalidate()
        }
        btn("Oglinda") { image.scaleX = -image.scaleX }
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    private fun startWifi() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val hosts = LinkedHashSet<String>()
        var myIp = "?"
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            cm.bindProcessToNetwork(n)
            val lp = cm.getLinkProperties(n)
            lp?.routes?.forEach { r ->
                val g = r.gateway
                if (g is Inet4Address && !g.isAnyLocalAddress) {
                    g.hostAddress?.let { hosts.add(it) }
                }
            }
            lp?.linkAddresses?.forEach { la ->
                val a = la.address
                if (a is Inet4Address) {
                    myIp = a.hostAddress ?: "?"
                    val b = a.address
                    hosts.add(
                        "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.1"
                    )
                }
            }
            break
        }
        hosts.add("192.168.122.1")
        hosts.add("192.168.49.1")
        setStatus("Wi-Fi: IP telefon $myIp")
        wifi?.stop()
        wifi = WifiSource({ bmp -> runOnUiThread { image.setImageBitmap(bmp) } }, ::setStatus)
        wifi?.start(hosts.toList())
    }

    private fun startUsb() {
        wifi?.stop()
        val d = UsbSource.findVideoDevices(this)
        setStatus(
            if (d.isEmpty()) "USB: niciun dispozitiv video gasit"
            else "USB: gasit ${d[0].productName ?: d[0].deviceName} (previzualizare in pasul urmator)"
        )
    }

    override fun onPause() { super.onPause(); wifi?.stop() }
}
