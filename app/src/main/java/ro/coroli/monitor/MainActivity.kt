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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
            val bw = w * 0.2f
            val bh = h * 0.16f
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
    val availM: String,
    val setM: String,
    val isInt: Boolean,
    val curM: String? = null,
    val supM: String? = null,
    val fmt: (String) -> String = { it }
) {
    var values: List<String> = emptyList()
    var idx = -1
    var valueView: TextView? = null
    fun text(): String = if (idx in values.indices) fmt(values[idx]) else "--"
}

// ---------------------------------------------------------------- Activity

class MainActivity : Activity() {
    private lateinit var image: ImageView
    private lateinit var overlay: OverlayView
    private lateinit var status: TextView
    private lateinit var recTime: TextView
    private lateinit var recInner: View
    private lateinit var connectBtn: TextView
    private lateinit var toolsScroll: HorizontalScrollView
    private var wifi: WifiSource? = null
    private var api: CameraApi? = null
    private var ratioIdx = 0
    private val ratios = floatArrayOf(0f, 1.78f, 2.0f, 2.39f)
    @Volatile private var firstFrame = true
    @Volatile private var recording = false
    @Volatile private var evStep = 1
    private var recStart = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val errLog = StringBuilder()

    private val tick = object : Runnable {
        override fun run() {
            val s = (System.currentTimeMillis() - recStart) / 1000
            recTime.text = String.format("●  %02d:%02d", s / 60, s % 60)
            if (recording) handler.postDelayed(this, 1000)
        }
    }

    private val ev = Param(
        "EV", "getAvailableExposureCompensation", "setExposureCompensation", true
    ) { v ->
        val n = v.toInt()
        val e = n * (if (evStep == 2) 0.5 else 1.0 / 3.0)
        String.format("%+.1f", e)
    }
    private val iso = Param(
        "ISO", "getAvailableIsoSpeedRate", "setIsoSpeedRate", false,
        "getIsoSpeedRate", "getSupportedIsoSpeedRate"
    )
    private val fnum = Param(
        "F", "getAvailableFNumber", "setFNumber", false,
        "getFNumber", "getSupportedFNumber"
    )
    private val shutter = Param(
        "S", "getAvailableShutterSpeed", "setShutterSpeed", false,
        "getShutterSpeed", "getSupportedShutterSpeed"
    )
    private val params = listOf(ev, iso, fnum, shutter)

    // ---------- helpers de stil

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, radiusDp: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun ovalBg(color: Int, stroke: Int = 0, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (stroke != 0) setStroke(dp(strokeDp), stroke)
        }

    private fun chip(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
    }

    private fun styleChip(c: TextView, on: Boolean) {
        if (on) {
            c.background = roundBg(Color.argb(235, 255, 255, 255), 18)
            c.setTextColor(Color.BLACK)
        } else {
            c.background = roundBg(Color.argb(150, 20, 20, 20), 18, Color.argb(60, 255, 255, 255))
            c.setTextColor(Color.WHITE)
        }
    }

    private fun addTo(row: LinearLayout, v: View) {
        row.addView(v, LinearLayout.LayoutParams(-2, -2).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
    }

    private fun actionChip(row: LinearLayout, t: String, a: () -> Unit) {
        val c = chip(t)
        styleChip(c, false)
        c.setOnClickListener { a() }
        addTo(row, c)
    }

    private fun toggleChip(row: LinearLayout, t: String, init: Boolean, on: (Boolean) -> Unit) {
        var s = init
        val c = chip(t)
        styleChip(c, s)
        c.setOnClickListener {
            s = !s
            styleChip(c, s)
            on(s)
        }
        addTo(row, c)
    }

    private fun circle(sizeDp: Int, label: String): TextView = TextView(this).apply {
        text = label
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = ovalBg(Color.argb(150, 20, 20, 20), Color.argb(90, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
            setMargins(0, dp(8), 0, dp(8))
        }
    }

    private fun paramGroup(row: LinearLayout, p: Param) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBg(Color.argb(150, 20, 20, 20), 22, Color.argb(60, 255, 255, 255))
        }
        val minus = TextView(this).apply {
            text = "‹"; textSize = 24f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(2), dp(14), dp(2))
        }
        minus.setOnClickListener { step(p, -1) }
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val lbl = TextView(this).apply {
            text = p.label; textSize = 10f
            setTextColor(Color.argb(180, 255, 255, 255))
            gravity = Gravity.CENTER
        }
        val v = TextView(this).apply {
            text = "--"; textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = dp(56)
        }
        p.valueView = v
        mid.addView(lbl)
        mid.addView(v)
        val plus = TextView(this).apply {
            text = "›"; textSize = 24f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(2), dp(14), dp(2))
        }
        plus.setOnClickListener { step(p, 1) }
        box.addView(minus)
        box.addView(mid)
        box.addView(plus)
        addTo(row, box)
    }

    // ---------- UI

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

        // gradiente sus si jos
        val topScrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(150, 0, 0, 0), Color.TRANSPARENT)
            )
        }
        root.addView(topScrim, FrameLayout.LayoutParams(-1, dp(90), Gravity.TOP))
        val botScrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(170, 0, 0, 0), Color.TRANSPARENT)
            )
        }
        root.addView(botScrim, FrameLayout.LayoutParams(-1, dp(180), Gravity.BOTTOM))

        // brand
        val brand = TextView(this).apply {
            text = "CORALI ALEXANDRU PHOTOGRAPHY".replace("CORALI", "COROLI")
            setTextColor(Color.WHITE)
            textSize = 11f
            letterSpacing = 0.12f
            alpha = 0.9f
        }
        root.addView(brand, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(20), dp(16), 0, 0)
        })

        // status
        status = TextView(this).apply {
            text = "Gata de conectare"
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 2
            maxWidth = dp(420)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundBg(Color.argb(130, 0, 0, 0), 14)
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(12), dp(16), 0)
        })

        // cronometru REC
        recTime = TextView(this).apply {
            text = "●  00:00"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = roundBg(Color.argb(210, 210, 30, 30), 16)
            visibility = View.GONE
        }
        root.addView(recTime, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, dp(10), 0, 0)
        })

        // buton mare de conectare
        connectBtn = TextView(this).apply {
            text = "Conectare Wi-Fi"
            textSize = 16f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(14), dp(28), dp(14))
            background = roundBg(Color.argb(240, 255, 255, 255), 28)
        }
        connectBtn.setOnClickListener {
            connectBtn.text = "Se conectează..."
            startWifi()
        }
        root.addView(connectBtn, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // coloana din dreapta: Foto, REC, Unelte
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val photo = circle(52, "📷")
        photo.setOnClickListener { apiAction("Foto", "actTakePicture") }
        right.addView(photo)

        val recWrap = FrameLayout(this).apply {
            background = ovalBg(Color.argb(90, 0, 0, 0), Color.WHITE, 4)
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(84)).apply {
                setMargins(0, dp(8), 0, dp(8))
            }
        }
        recInner = View(this)
        recWrap.addView(recInner, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER))
        setRecUi(false)
        recWrap.setOnClickListener { toggleRec() }
        right.addView(recWrap)

        val toolsBtn = circle(52, "⚙")
        toolsBtn.setOnClickListener {
            toolsScroll.visibility =
                if (toolsScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        right.addView(toolsBtn)
        root.addView(right, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            setMargins(0, 0, dp(22), 0)
        })

        // rand expunere
        val expRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        params.forEach { paramGroup(expRow, it) }
        val refresh = chip("↻")
        styleChip(refresh, false)
        refresh.setOnClickListener { Thread { params.forEach { fetch(it) } }.start() }
        addTo(expRow, refresh)

        // rand unelte
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actionChip(toolRow, "Wi-Fi") { startWifi() }
        actionChip(toolRow, "USB") { startUsb() }
        toggleChip(toolRow, "Grid", true) { overlay.grid = it; overlay.invalidate() }
        actionChip(toolRow, "Cadru") {
            ratioIdx = (ratioIdx + 1) % ratios.size
            overlay.frameRatio = ratios[ratioIdx]; overlay.invalidate()
        }
        toggleChip(toolRow, "Zebra", false) { Processor.zebra = it }
        toggleChip(toolRow, "Peaking", false) { Processor.peaking = it }
        toggleChip(toolRow, "False color", false) { Processor.falseColor = it }
        toggleChip(toolRow, "Histogramă", true) { overlay.showHist = it; overlay.invalidate() }
        toggleChip(toolRow, "Umplere", false) {
            image.scaleType = if (it) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        }
        toggleChip(toolRow, "Oglindă", false) { image.scaleX = if (it) -1f else 1f }
        toggleChip(toolRow, "Roșu", false) { setRed(it) }
        actionChip(toolRow, "Info") { showInfo() }

        toolsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(toolRow)
        }
        val expScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(expRow)
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bottom.addView(toolsScroll)
        bottom.addView(expScroll)
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(140), dp(10))
        })

        setContentView(root)
    }

    private fun setRecUi(rec: Boolean) {
        if (rec) {
            recInner.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(7).toFloat()
                setColor(Color.rgb(230, 30, 30))
            }
            recInner.layoutParams = FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)
        } else {
            recInner.background = ovalBg(Color.rgb(230, 30, 30))
            recInner.layoutParams = FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER)
        }
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

    private fun logErr(s: String) {
        synchronized(errLog) {
            errLog.append(s).append('\n')
            if (errLog.length > 3000) errLog.delete(0, 1000)
        }
        setStatus(s)
    }

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
            if (first) {
                status.text = "Wi-Fi: live view OK"
                connectBtn.visibility = View.GONE
            }
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
        var err: String? = null
        var ok = false
        try {
            val r = a.call(p.availM).getJSONArray("result")
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
            ok = p.values.isNotEmpty()
        } catch (e: Exception) {
            err = e.message
        }
        val cm = p.curM
        val sm = p.supM
        if (!ok && cm != null && sm != null) {
            try {
                val cur = a.call(cm).getJSONArray("result").get(0).toString()
                val sup = a.call(sm).getJSONArray("result").getJSONArray(0)
                p.values = (0 until sup.length()).map { sup.getString(it) }
                p.idx = p.values.indexOf(cur)
                ok = p.values.isNotEmpty()
            } catch (e: Exception) {
                err = (err ?: "") + " | " + e.message
            }
        }
        if (!ok) {
            p.values = emptyList()
            p.idx = -1
            logErr("${p.label}: $err")
        }
        runOnUiThread { p.valueView?.text = p.text() }
    }

    private fun step(p: Param, d: Int) {
        val a = api
        if (a == null) {
            setStatus("Conectează-te întâi prin Wi-Fi")
            return
        }
        Thread {
            try {
                if (p.values.isEmpty()) fetch(p)
                if (p.values.isEmpty()) {
                    logErr("${p.label}: nu pot citi valorile (verifica modul camerei)")
                    return@Thread
                }
                val n = (p.idx + d).coerceIn(0, p.values.size - 1)
                val v = p.values[n]
                a.call(p.setM, if (p.isInt) "[$v]" else "[\"$v\"]")
                p.idx = n
            } catch (e: Exception) {
                logErr("${p.label}: ${e.message}")
            }
            runOnUiThread { p.valueView?.text = p.text() }
        }.start()
    }

    private fun toggleRec() {
        val a = api
        if (a == null) {
            setStatus("Conectează-te întâi prin Wi-Fi")
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
                logErr("REC: ${e.message} (camera pe modul Video?)")
            }
            runOnUiThread {
                setRecUi(recording)
                if (recording) {
                    recStart = System.currentTimeMillis()
                    recTime.visibility = View.VISIBLE
                    handler.post(tick)
                } else {
                    recTime.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun apiAction(label: String, method: String) {
        val a = api
        if (a == null) {
            setStatus("Conectează-te întâi prin Wi-Fi")
            return
        }
        Thread {
            try {
                a.call(method)
                setStatus("$label: ok")
            } catch (e: Exception) {
                logErr("$label: ${e.message}")
            }
        }.start()
    }

    private fun showInfo() {
        val a = api
        if (a == null) {
            setStatus("Conectează-te întâi prin Wi-Fi")
            return
        }
        Thread {
            val apis = try {
                val arr = a.call("getAvailableApiList").getJSONArray("result").getJSONArray(0)
                (0 until arr.length()).joinToString(", ") { arr.getString(it) }
            } catch (e: Exception) {
                "Eroare: ${e.message}"
            }
            val errs = synchronized(errLog) { errLog.toString() }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Info cameră")
                    .setMessage("FUNCTII DISPONIBILE:\n$apis\n\nERORI:\n$errs")
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
            if (d.isEmpty()) "USB: niciun dispozitiv video găsit"
            else "USB: găsit ${d[0].productName ?: d[0].deviceName} (previzualizare în pasul următor)"
        )
    }

    override fun onPause() {
        super.onPause()
        wifi?.stop()
    }
}
