package com.offline.stellarium

import android.content.SharedPreferences
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.GestureDetector
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: SkyRenderer
    private lateinit var overlay: LabelsOverlay
    private lateinit var sensorFusion: SensorFusion
    private lateinit var prefs: SharedPreferences

    private lateinit var timeText: TextView
    private lateinit var speedText: TextView
    private lateinit var modeBtn: Button
    private lateinit var infoCard: LinearLayout
    private lateinit var infoTitle: TextView
    private lateinit var infoBody: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var lastTick = System.currentTimeMillis()
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US)

    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val dt = now - lastTick
            lastTick = now
            if (SkyState.timeSpeed != 0L) SkyState.timeMillis += dt * SkyState.timeSpeed
            timeText.text = dateFmt.format(Date(SkyState.timeMillis))
            speedText.text = speedLabel()
            overlay.invalidate()
            handler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("planetarium", MODE_PRIVATE)
        loadPrefs()

        renderer = SkyRenderer(this)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            preserveEGLContextOnPause = true
        }
        overlay = LabelsOverlay(this, renderer)
        buildUi()

        sensorFusion = SensorFusion(this) { m -> renderer.setSensorRotation(m) }
        SkyState.sensorPresent = sensorFusion.hasSensor

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (infoCard.visibility == View.VISIBLE) hideInfo()
                else finish()
            }
        })
    }

    // ================= UI construction =================

    private fun buildUi() {
        val root = FrameLayout(this)

        glView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        overlay.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(glView)
        root.addView(overlay)

        // ---- top time bar ----
        timeText = TextView(this).apply { textSize = 15f; setTextColor(Color.WHITE) }
        speedText = TextView(this).apply { textSize = 13f; setTextColor(0xFF53D9A9.toInt()) }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(timeText)
            val sp = TextView(context).apply { text = "   "; textSize = 13f }
            addView(sp)
            addView(speedText)
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        btnRow.addView(mkBtn("-1d") { setSpeed(-86400) })
        btnRow.addView(mkBtn("-1h") { setSpeed(-3600) })
        btnRow.addView(mkBtn("-60s") { setSpeed(-60) })
        btnRow.addView(mkBtn("⏯") { togglePause() })
        btnRow.addView(mkBtn("+60s") { setSpeed(60) })
        btnRow.addView(mkBtn("+1h") { setSpeed(3600) })
        btnRow.addView(mkBtn("+1d") { setSpeed(86400) })
        btnRow.addView(mkBtn("NOW") {
            SkyState.timeMillis = System.currentTimeMillis()
            SkyState.timeSpeed = 1
        })
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 12)
            setBackgroundResource(R.drawable.toolbar_bg)
            addView(titleRow)
            addView(btnRow)
        }
        val topLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = 20 }
        root.addView(topBar, topLp)

        // ---- right toolbar ----
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        tools.addView(mkBtn("🔍") { showSearch() })
        tools.addView(mkBtn("📍") { showLocationDialog() })
        tools.addView(mkBtn("⚙") { showSettings() })
        modeBtn = mkBtn("🧭") { toggleMode() }
        tools.addView(modeBtn)
        val toolsLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            rightMargin = 18
        }
        root.addView(tools, toolsLp)

        // ---- bottom info card ----
        infoTitle = TextView(this).apply {
            textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD)
        }
        infoBody = TextView(this).apply {
            textSize = 14f; setTextColor(0xFFB9C4D6.toInt())
        }
        val closeBtn = mkBtn("✕") { hideInfo() }
        val cardHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(infoTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(closeBtn)
        }
        infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 28)
            setBackgroundResource(R.drawable.card_bg)
            addView(cardHeader)
            addView(infoBody)
            visibility = View.GONE
        }
        val cardLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            leftMargin = 24; rightMargin = 24; bottomMargin = 24
        }
        root.addView(infoCard, cardLp)

        attachTouch(root)
        setContentView(root)
        updateModeBtn()
    }

    private fun mkBtn(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.btn_bg)
            setPadding(14, 8, 14, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(4, 2, 4, 2) }
            setOnClickListener { onClick() }
        }

    private fun attachTouch(root: View) {
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    renderer.zoom(d.scaleFactor)
                    return true
                }
            })
        val gesture = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    renderer.zoom(0.5f)
                    return true
                }
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    onTap(e.x, e.y)
                    return true
                }
            })
        var lastX = 0f
        var lastY = 0f
        overlay.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            gesture.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = ev.x; lastY = ev.y }
                MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress) {
                    if (SkyState.sensorMode) {
                        SkyState.sensorMode = false
                        renderer.adoptCurrentCenter()
                        updateModeBtn()
                    }
                    renderer.drag(ev.x - lastX, ev.y - lastY)
                    lastX = ev.x; lastY = ev.y
                }
            }
            true
        }
    }

    // ================= interactions =================

    private fun onTap(x: Float, y: Float) {
        val obj = renderer.findObjectAt(x, y) ?: return
        SkyState.selected = obj
        showInfo(obj)
        overlay.invalidate()
    }

    private fun showInfo(obj: SkyObject) {
        infoTitle.text = obj.name
        val lst = AstroMath.lstRadians(SkyState.timeMillis, SkyState.longitudeDeg)
        val azalt = AstroMath.altAz(obj.raRad, obj.decRad, SkyState.latitudeDeg, lst)
        val azDeg = Math.toDegrees(azalt[0])
        val altDeg = Math.toDegrees(azalt[1])
        val sb = StringBuilder()
        sb.append("Type: ").append(obj.type)
        if (obj.type == "Star" || obj.type == "Planet") sb.append("   Mag: %.1f".format(obj.mag))
        sb.append('\n')
        if (obj.type != "Constellation") {
            sb.append("RA:  ").append(AstroMath.fmtHMS(obj.raRad)).append('\n')
            sb.append("Dec: ").append(AstroMath.fmtDMS(obj.decRad)).append('\n')
        }
        sb.append("Az:  %.1f°   Alt: %.1f°".format((azDeg + 360) % 360, altDeg))
        if (obj.distanceText.isNotEmpty()) sb.append('\n').append(obj.distanceText)
        if (obj.extraText.isNotEmpty()) sb.append('\n').append(obj.extraText)
        infoBody.text = sb.toString()
        infoCard.visibility = View.VISIBLE
    }

    private fun hideInfo() {
        infoCard.visibility = View.GONE
        SkyState.selected = null
        overlay.invalidate()
    }

    private fun setSpeed(s: Long) {
        SkyState.timeSpeed = s
    }

    private fun togglePause() {
        SkyState.timeSpeed = if (SkyState.timeSpeed == 0L) 1L else 0L
    }

    private fun speedLabel(): String = when (SkyState.timeSpeed) {
        0L -> "paused"
        1L -> "real time"
        else -> {
            val s = SkyState.timeSpeed
            val sign = if (s < 0) "-" else "+"
            val a = kotlin.math.abs(s)
            when {
                a % 86400 == 0L -> "$sign${a / 86400} day/s"
                a % 3600 == 0L -> "$sign${a / 3600} hour/s"
                a % 60 == 0L -> "$sign${a / 60} min/s"
                else -> "${s}x"
            }
        }
    }

    private fun toggleMode() {
        if (SkyState.sensorMode) {
            SkyState.sensorMode = false
            renderer.adoptCurrentCenter()
        } else {
            SkyState.sensorMode = SkyState.sensorPresent
        }
        updateModeBtn()
    }

    private fun updateModeBtn() {
        modeBtn.text = if (SkyState.sensorMode) "🧭" else "✋"
        modeBtn.alpha = if (SkyState.sensorPresent) 1f else 0.45f
    }

    // ================= dialogs =================

    private fun showSearch() {
        val entries = ArrayList<Pair<String, SkyObject>>()
        for (s in NamedStars.all)
            entries.add(s.name to SkyObject(s.name, "Star", s.raRad, s.decRad, s.mag))
        val t = SkyState.timeMillis
        val sun = AstroMath.sunPosition(t)
        entries.add("Sun" to SkyObject("Sun", "Sun", sun[0], sun[1], -26.7))
        val moon = AstroMath.moonPosition(t)
        entries.add("Moon" to SkyObject("Moon", "Moon", moon[0], moon[1], -12.0))
        for (idx in intArrayOf(0, 1, 3, 4, 5, 6, 7)) {
            val eq = AstroMath.planetPosition(idx, t)
            entries.add(AstroMath.PLANET_NAMES[idx] to
                SkyObject(AstroMath.PLANET_NAMES[idx], "Planet", eq[0], eq[1], AstroMath.PLANET_MAGS[idx]))
        }
        for (c in Constellations.all) {
            val cen = centroidOf(c)
            entries.add(c.name to SkyObject(c.name, "Constellation", cen[0], cen[1], 0.0))
        }
        entries.sortBy { it.first.lowercase() }

        val listView = ListView(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            entries.map { it.first }.toMutableList())
        listView.adapter = adapter
        val edit = EditText(this).apply { hint = "Search the sky…" }
        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().lowercase()
                adapter.clear()
                adapter.addAll(entries.filter { it.first.lowercase().contains(q) }.map { it.first })
                adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 10, 30, 0)
            addView(edit)
            addView(listView)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Find object")
            .setView(box)
            .setNegativeButton("Close", null)
            .create()
        listView.setOnItemClickListener { _, _, pos, _ ->
            val name = adapter.getItem(pos) ?: return@setOnItemClickListener
            val obj = entries.first { it.first == name }.second
            dialog.dismiss()
            centerOnObject(obj)
        }
        dialog.show()
    }

    private fun centroidOf(c: Constellations.Con): DoubleArray {
        var x = 0.0; var y = 0.0; var z = 0.0
        val n = c.starRaDec.size / 2
        for (i in 0 until n) {
            val ra = c.starRaDec[i * 2].toDouble()
            val dec = c.starRaDec[i * 2 + 1].toDouble()
            x += cos(dec) * cos(ra); y += cos(dec) * sin(ra); z += sin(dec)
        }
        return doubleArrayOf(atan2(y, x), asin(z / sqrt(x * x + y * y + z * z)))
    }

    private fun centerOnObject(obj: SkyObject) {
        renderer.centerOn(obj.raRad, obj.decRad, SkyState.timeMillis)
        SkyState.sensorMode = false
        updateModeBtn()
        SkyState.selected = obj
        showInfo(obj)
        overlay.invalidate()
    }

    private fun showLocationDialog() {
        val lat = EditText(this).apply {
            setText("%.4f".format(SkyState.latitudeDeg))
            hint = "Latitude, e.g. 39.9042"
        }
        val lon = EditText(this).apply {
            setText("%.4f".format(SkyState.longitudeDeg))
            hint = "Longitude, e.g. 116.4074"
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 0)
            addView(lat); addView(lon)
        }
        AlertDialog.Builder(this)
            .setTitle("Observer location (WGS84)")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                SkyState.latitudeDeg = lat.text.toString().toDoubleOrNull() ?: SkyState.latitudeDeg
                SkyState.longitudeDeg = lon.text.toString().toDoubleOrNull() ?: SkyState.longitudeDeg
                savePrefs()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettings() {
        val cbCon = CheckBox(this).apply {
            text = "Constellation lines"; isChecked = SkyState.showConstellations
            setOnCheckedChangeListener { _, b -> SkyState.showConstellations = b }
        }
        val cbLab = CheckBox(this).apply {
            text = "Labels"; isChecked = SkyState.showLabels
            setOnCheckedChangeListener { _, b -> SkyState.showLabels = b }
        }
        val cbAtm = CheckBox(this).apply {
            text = "Atmosphere / daylight"; isChecked = SkyState.showAtmosphere
            setOnCheckedChangeListener { _, b -> SkyState.showAtmosphere = b }
        }
        val magVal = TextView(this).apply { text = "Star limit: mag %.1f".format(SkyState.magLimit) }
        val seek = SeekBar(this).apply {
            max = 60
            progress = ((SkyState.magLimit - 2f) * 10).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    SkyState.magLimit = 2f + p / 10f
                    magVal.text = "Star limit: mag %.1f".format(SkyState.magLimit)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 0)
            addView(cbCon); addView(cbLab); addView(cbAtm)
            addView(magVal); addView(seek)
        }
        AlertDialog.Builder(this)
            .setTitle("Display settings")
            .setView(box)
            .setPositiveButton("Done") { _, _ -> savePrefs() }
            .show()
    }

    // ================= prefs / lifecycle =================

    private fun loadPrefs() {
        SkyState.latitudeDeg = prefs.getFloat("lat", 39.9042f).toDouble()
        SkyState.longitudeDeg = prefs.getFloat("lon", 116.4074f).toDouble()
        SkyState.showConstellations = prefs.getBoolean("con", true)
        SkyState.showLabels = prefs.getBoolean("lab", true)
        SkyState.showAtmosphere = prefs.getBoolean("atm", true)
        SkyState.magLimit = prefs.getFloat("mag", 6.0f)
    }

    private fun savePrefs() {
        prefs.edit()
            .putFloat("lat", SkyState.latitudeDeg.toFloat())
            .putFloat("lon", SkyState.longitudeDeg.toFloat())
            .putBoolean("con", SkyState.showConstellations)
            .putBoolean("lab", SkyState.showLabels)
            .putBoolean("atm", SkyState.showAtmosphere)
            .putFloat("mag", SkyState.magLimit)
            .apply()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        if (SkyState.sensorPresent) sensorFusion.register()
        lastTick = System.currentTimeMillis()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        sensorFusion.unregister()
        glView.onPause()
        savePrefs()
    }
}



