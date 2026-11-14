package ro.coroli.monitor

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
import android.widget.HorizontalScrollView
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
import java.net.URL

// ---------------------------------------------------------------- API camera

class CameraApi(private val action: String) {
    fun call(method: String, params: String = "[]", version: String = "1.0"): JSONObject {
        val c = URL("$action/camera").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 3000
        c.readTimeout = 8000
        c.setRequestProperty("Content-Type", "application/json")
        val body = """{"method":"$method","params":$params,"id":1,"version":"$version"}"""
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = c.inputStream.bufferedReader().use { it.readText() }
        val j = JSONObject(text)
        if (j.has("error")) throw Exception("$method: ${j.getJSONArray("error")}")
        return j
    }
}

// ---------------------------------------------------------------- Wi-Fi live view

class WifiSource(
    private val onFrame: (Bitmap) -> Unit,
    private val onApi: (CameraApi) -> Unit,
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

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 8000
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun loop(hosts: List<String>) {
        while (running) {
            try {
                session(hosts)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                if (running) onStatus("Wi-Fi: ${e.message} - reincerc...")
            }
            if (running) {
                try { Thread.sleep(2000) } catch (e: InterruptedException) { return }
            }
        }
    }

    private fun session(hosts: List<String>) {
        var xml: String? = null
        var host: String? = null
        for (h in hosts) {
            if (!running) return
            onStatus("Wi-Fi: caut camera la $h ...")
            try {
                xml = get("http://$h:64321/dd.xml")
                host = h
                break
            } catch (_: Exception) { }
        }
        val dd = xml
        val host0 = host
        if (dd == null || host0 == null) {
            onStatus("Wi-Fi: camera nu raspunde - reincerc...")
            return
        }
        val action = Regex(
            "<av:X_ScalarWebAPI_ActionList_URL>(.*?)</av:X_ScalarWebAPI_ActionList_URL>"
        ).find(dd)?.groupValues?.get(1)?.trim() ?: "http://$host0:10000/sony"
        val xmlLive = Regex(
            "<av:X_ScalarWebAPI_LiveView_URL>(.*?)</av:X_ScalarWebAPI_LiveView_URL>"
        ).find(dd)?.groupValues?.get(1)?.trim()

        val api = CameraApi(action)
        onApi(api)
        onStatus("Wi-Fi: camera gasita, pornesc live view")
        try { api.call("startRecMode") } catch (_: Exception) { }

        var liveUrl: String? = null
        try {
            liveUrl = api.call("startLiveview").getJSONArray("result").getString(0)
        } catch (e: Exception) {
            onStatus("startLiveview esuat (${e.message}), incerc adresa din dd.xml")
        }
        if (liveUrl == null) liveUrl = xmlLive
        if (liveUrl == null) {
            onStatus("Wi-Fi: nu am gasit adresa live view")
            return
        }

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
    }
}

// ---------------------------------------------------------------- USB (pas 1)

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

// ---------------------------------------------------------------- Procesare imagine

object Processor {
    @Volatile var zebra = false
    @Volatile var peaking = false
    @Volatile var falseColor = false

    private fun fc(l: Int): Int = when {
        l < 13 -> Color.rgb(120, 0, 160)
        l < 38 -> Color.rgb(0, 60, 220)
        l in 108..124 -> Color.rgb(0, 200, 60)
        l in 160..185 -> Color.rgb(255, 150, 190)
        l >= 250 -> Color.rgb(255, 0, 0)
        l >= 235 -> Color.rgb(255, 230, 0)
        else -> Color.rgb(l, l, l)
    }

    fun process(src: Bitmap): Pair<Bitmap, IntArray> {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h)
        val hist = IntArray(64)
        for (i in px.indices) {
            val c = px[i]
            val l = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
            lum[i] = l
            hist[l shr 2]++
        }
        if (!zebra && !peaking && !falseColor) return Pair(src, hist)

        val out = px.copyOf()
        if (falseColor) {
            for (i in out.indices) out[i] = fc(lum[i])
        }
        if (zebra) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (lum[i] >= 235 && ((x + y) / 6) % 2 == 0) out[i] = Color.BLACK
                }
            }
        }
        if (peaking) {
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    val g = Math.abs(lum[i + 1] - lum[i - 1]) + Math.abs(lum[i + w] - lum[i - w])
                    if (g > 60) out[i] = Color.RED
                }
            }
        }
        val bmp = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        return Pair(bmp, hist)
    }
}

// ---------------------------------------------------------------- Overlay

class OverlayView(ctx: Context) : View(ctx) {
    var grid = true
    var center = true
    var frameRatio = 0f
    var showHist = true
    var hist: IntArray? = null

    private val p = Paint().apply {
        color = Color.argb(160, 255, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val hp = Paint().apply {
        color = Color.argb(210, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val bg = Paint().apply {
        color = Color.argb(110, 0, 0, 0)
        style = Paint.Style.FILL
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
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
        val hh = hist
        if (showHist && hh != null) {
            val bw = w * 0.22f
            val bh = h * 0.18f
            val x0 = 24f
            val y0 = 110f
            c.drawRect(x0, y0, x0 + bw, y0 + bh, bg)
            val mx = hh.maxOrNull() ?: 1
            val m = if (mx < 1) 1 else mx
            for (i in hh.indices) {
                val v = Math.sqrt(hh[i].toDouble() / m).toFloat()
                val bx = x0 + bw * i / hh.size
                c.drawRect(bx, y0 + bh - bh * v, bx + bw / hh.size, y0 + bh, hp)
            }
        }
    }
}

// ---------------------------------------------------------------- Parametri expunere

class Param(
    val label: String,
    val getM: String,
    val setM: String,
    val isInt: Boolean,
    val fmt: (String) -> String
) {
    var values: List<String> = emptyList()
    var idx = -1
    var view: TextView? = null
    fun text(): String = if (idx in values.indices) fmt(values[idx]) else "--"
}

// ---------------------------------------------------------------- Activity

class MainActivity : Activity() {
    private lateinit var image: ImageView
    private lateinit var overlay: OverlayView
    private lateinit var status: TextView
    private lateinit var recDot: TextView
    private lateinit var recBtn: Button
    private var wifi: WifiSource? = null
    private var api: CameraApi? = null
    private var ratioIdx = 0
    private val ratios = floatArrayOf(0f, 1.78f, 2.0f, 2.39f)
    @Volatile private var firstFrame = true
    @Volatile private var recording = false
    @Volatile private var evStep = 1

    private val ev = Param("EV", "getAvailableExposureCompensation", "setExposureCompensation", true) { v ->
        val n = v.toInt()
        val e = n * (if (evStep == 2) 0.5 else 1.0 / 3.0)
        String.format("%+.1f", e)
    }
    private val iso = Param("ISO", "getAvailableIsoSpeedRate", "setIsoSpeedRate", false) { it }
    private val fnum = Param("F", "getAvailableFNumber", "setFNumber", false) { it }
    private val shutter = Param("S", "getAvailableShutterSpeed", "setShutterSpeed", false) { it }
    private val params = listOf(ev, iso, fnum, shutter)

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

        recDot = TextView(this).apply {
            text = "● REC"
            setTextColor(Color.RED); textSize = 16f
            setPadding(24, 16, 24, 16)
            visibility = View.GONE
        }
        root.addView(recDot, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        // ---- bara de expunere
        val expRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        params.forEach { paramGroup(expRow, it) }
        val refresh = Button(this)
        styleBtn(refresh, "↻")
        refresh.setOnClickListener { Thread { params.forEach { fetch(it) } }.start() }
        expRow.addView(refresh)

        // ---- bara de unelte
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addBtn(toolRow, "USB") { startUsb() }
        addBtn(toolRow, "Wi-Fi") { startWifi() }
        recBtn = Button(this)
        styleBtn(recBtn, "REC")
        recBtn.setOnClickListener { toggleRec() }
        toolRow.addView(recBtn)
        addBtn(toolRow, "Foto") { apiAction("Foto", "actTakePicture") }
        toggle(toolRow, "Grid", true) { overlay.grid = it; overlay.invalidate() }
        addBtn(toolRow, "Cadru") {
            ratioIdx = (ratioIdx + 1) % ratios.size
            overlay.frameRatio = ratios[ratioIdx]; overlay.invalidate()
        }
        toggle(toolRow, "Zebra", false) { Processor.zebra = it }
        toggle(toolRow, "Peaking", false) { Processor.peaking = it }
        toggle(toolRow, "False color", false) { Processor.falseColor = it }
        toggle(toolRow, "Histograma", true) { overlay.showHist = it; overlay.invalidate() }
        toggle(toolRow, "Umplere", false) {
            image.scaleType = if (it) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        }
        toggle(toolRow, "Oglinda", false) { image.scaleX = if (it) -1f else 1f }
        toggle(toolRow, "Rosu", false) { setRed(it) }
        addBtn(toolRow, "Info") { showApiList() }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(140, 0, 0, 0))
        }
        bottom.addView(HorizontalScrollView(this).apply { addView(expRow) })
        bottom.addView(HorizontalScrollView(this).apply { addView(toolRow) })
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
    }

    // ---------- helpers UI

    private fun styleBtn(b: Button, t: String) {
        b.text = t
        b.textSize = 12f
        b.isAllCaps = false
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(28, 14, 28, 14)
    }

    private fun addBtn(row: LinearLayout, t: String, a: () -> Unit) {
        val b = Button(this)
        styleBtn(b, t)
        b.setOnClickListener { a() }
        row.addView(b)
    }

    private fun toggle(row: LinearLayout, t: String, init: Boolean, on: (Boolean) -> Unit) {
        var s = init
        val b = Button(this)
        styleBtn(b, t)
        b.alpha = if (s) 1f else 0.5f
        b.setOnClickListener {
            s = !s
            b.alpha = if (s) 1f else 0.5f
            on(s)
        }
        row.addView(b)
    }

    private fun paramGroup(row: LinearLayout, p: Param) {
        val lbl = TextView(this).apply {
            text = p.label
            setTextColor(Color.LTGRAY); textSize = 12f
            setPadding(20, 0, 8, 0)
        }
        val minus = Button(this)
        styleBtn(minus, "−")
        minus.setOnClickListener { step(p, -1) }
        val v = TextView(this).apply {
            text = "--"
            setTextColor(Color.WHITE); textSize = 14f
            minWidth = 110
            gravity = Gravity.CENTER
        }
        p.view = v
        val plus = Button(this)
        styleBtn(plus, "+")
        plus.setOnClickListener { step(p, 1) }
        row.addView(lbl)
        row.addView(minus)
        row.addView(v)
        row.addView(plus)
    }

    private fun setRed(on: Boolean) {
        if (on) {
            val m = ColorMatrix(
                floatArrayOf(
                    0.3f, 0.59f, 0.11f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            image.colorFilter = ColorMatrixColorFilter(m)
        } else {
            image.colorFilter = null
        }
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    // ---------- Wi-Fi

    private fun onFrame(bmp: Bitmap) {
        val (out, hist) = Processor.process(bmp)
        val first = firstFrame
        if (first) {
            firstFrame = false
            Thread { params.forEach { fetch(it) } }.start()
        }
        runOnUiThread {
            image.setImageBitmap(out)
            overlay.hist = hist
            overlay.invalidate()
            if (first) status.text = "Wi-Fi: live view OK"
        }
    }

    private fun startWifi() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val hosts = LinkedHashSet<String>()
        hosts.add("192.168.122.1")
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
            break
        }
        wifi?.stop()
        firstFrame = true
        wifi = WifiSource(
            { bmp -> onFrame(bmp) },
            { a -> api = a },
            { s -> setStatus(s) }
        )
        wifi?.start(hosts.toList())
    }

    // ---------- comenzi camera

    private fun fetch(p: Param) {
        val a = api ?: return
        try {
            val r = a.call(p.getM).getJSONArray("result")
            if (p.isInt) {
                val cur = r.getInt(0)
                val max = r.getInt(1)
                val min = r.getInt(2)
                if (r.length() > 3) evStep = r.getInt(3)
                p.values = (min..max).map { it.toString() }
                p.idx = p.values.indexOf(cur.toString())
            } else {
                val cur = r.getString(0)
                val arr = r.getJSONArray(1)
                p.values = (0 until arr.length()).map { arr.getString(it) }
                p.idx = p.values.indexOf(cur)
            }
        } catch (e: Exception) {
            p.values = emptyList()
            p.idx = -1
            setStatus("${p.label}: ${e.message}")
        }
        runOnUiThread { p.view?.text = p.text() }
    }

    private fun step(p: Param, d: Int) {
        val a = api
        if (a == null) {
            setStatus("Conecteaza-te intai prin Wi-Fi")
            return
        }
        Thread {
            try {
                if (p.values.isEmpty()) fetch(p)
                if (p.values.isEmpty()) {
                    setStatus("${p.label}: nu pot citi valorile (schimba modul camerei)")
                    return@Thread
                }
                val n = (p.idx + d).coerceIn(0, p.values.size - 1)
                val v = p.values[n]
                a.call(p.setM, if (p.isInt) "[$v]" else "[\"$v\"]")
                p.idx = n
            } catch (e: Exception) {
                setStatus("${p.label}: ${e.message}")
            }
            runOnUiThread { p.view?.text = p.text() }
        }.start()
    }

    private fun toggleRec() {
        val a = api
        if (a == null) {
            setStatus("Conecteaza-te intai prin Wi-Fi")
            return
        }
        Thread {
            try {
                if (!recording) {
                    a.call("startMovieRec")
                    recording = true
                } else {
                    a.call("stopMovieRec")
                    recording = false
                }
            } catch (e: Exception) {
                setStatus("REC: ${e.message} (camera pe modul Video?)")
            }
            runOnUiThread {
                recDot.visibility = if (recording) View.VISIBLE else View.GONE
                recBtn.text = if (recording) "STOP" else "REC"
            }
        }.start()
    }

    private fun apiAction(label: String, method: String) {
        val a = api
        if (a == null) {
            setStatus("Conecteaza-te intai prin Wi-Fi")
            return
        }
        Thread {
            try {
                a.call(method)
                setStatus("$label: ok")
            } catch (e: Exception) {
                setStatus("$label: ${e.message}")
            }
        }.start()
    }

    private fun showApiList() {
        val a = api
        if (a == null) {
            setStatus("Conecteaza-te intai prin Wi-Fi")
            return
        }
        Thread {
            val txt = try {
                val arr = a.call("getAvailableApiList").getJSONArray("result").getJSONArray(0)
                (0 until arr.length()).joinToString(", ") { arr.getString(it) }
            } catch (e: Exception) {
                "Eroare: ${e.message}"
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Functii disponibile pe camera")
                    .setMessage(txt)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
    }

    // ---------- USB

    private fun startUsb() {
        wifi?.stop()
        val d = UsbSource.findVideoDevices(this)
        setStatus(
            if (d.isEmpty()) "USB: niciun dispozitiv video gasit"
            else "USB: gasit ${d[0].productName ?: d[0].deviceName} (previzualizare in pasul urmator)"
        )
    }

    override fun onPause() {
        super.onPause()
        wifi?.stop()
    }
}
