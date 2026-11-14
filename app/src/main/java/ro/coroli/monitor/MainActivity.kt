package ro.coroli.monitor

import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.Gravity
import android.view.View    private fun startWifi() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var host = "192.168.122.1"
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                cm.bindProcessToNetwork(n)
                val gw = cm.getLinkProperties(n)?.routes
                    ?.firstOrNull { it.gateway is java.net.Inet4Address }
                    ?.gateway?.hostAddress
                if (gw != null) host = gw
                break
            }
        }
        setStatus("Wi-Fi: incerc $host")
        wifi?.stop()
        wifi = WifiSource({ bmp -> runOnUiThread { image.setImageBitmap(bmp) } }, ::setStatus)
        wifi?.start(host)
    }
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

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

    private fun bindToWifi() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                cm.bindProcessToNetwork(n); return
            }
        }
    }

    private fun startWifi() {
        bindToWifi()
        wifi?.stop()
        wifi = WifiSource({ bmp -> runOnUiThread { image.setImageBitmap(bmp) } }, ::setStatus)
        wifi?.start()
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
