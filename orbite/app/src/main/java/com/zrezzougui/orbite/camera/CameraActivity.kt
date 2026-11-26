package com.zrezzougui.orbite.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Rational
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.zrezzougui.orbite.Palette
import com.zrezzougui.orbite.chip
import com.zrezzougui.orbite.dp
import com.zrezzougui.orbite.dpf
import com.zrezzougui.orbite.edgeToEdge
import com.zrezzougui.orbite.horizontal
import com.zrezzougui.orbite.label
import com.zrezzougui.orbite.margins
import com.zrezzougui.orbite.matchWrap
import com.zrezzougui.orbite.padForSystemBars
import com.zrezzougui.orbite.pill
import com.zrezzougui.orbite.rounded
import com.zrezzougui.orbite.vertical
import com.zrezzougui.orbite.weighted
import com.zrezzougui.orbite.wrapWrap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Caméra à réglages manuels et calibration, inspirée d'Open Camera (libre, GPL) :
 * exposition, ISO, vitesse, mise au point, balance des blancs, calibration du blanc
 * sur une feuille blanche/grise, grille des tiers et niveau à bulle calibrable.
 * Les réglages calibrés sont enregistrés et réappliqués à chaque ouverture.
 */
class CameraActivity : Activity(), SensorEventListener {

    // ---------------------------------------------------------------- état caméra
    private lateinit var manager: CameraManager
    private var cameraId: String = ""
    private var chars: CameraCharacteristics? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSize = Size(1440, 1080)
    private var bgThread: HandlerThread? = null
    private var bg: Handler? = null
    @Volatile private var lastResult: TotalCaptureResult? = null

    private var canManualSensor = false
    private var canPostProcessing = false
    private var isoRange = android.util.Range(100, 1600)
    private var expRange = android.util.Range(1_000_000L, 100_000_000L)
    private var evRange = android.util.Range(-6, 6)
    private var evStep = Rational(1, 3)
    private var minFocus = 0f
    private var sensorOrientation = 90
    private var awbModes = intArrayOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)

    private var s = CamSettings.defaults(100, 10_000_000L)

    // ---------------------------------------------------------------- vues
    private lateinit var texture: TextureView
    private lateinit var overlay: GuideOverlay
    private lateinit var info: TextView
    private lateinit var manualBox: LinearLayout
    private lateinit var evRow: View
    private lateinit var evBar: SeekBar
    private lateinit var isoBar: SeekBar
    private lateinit var expBar: SeekBar
    private lateinit var focusBar: SeekBar
    private lateinit var evLabel: TextView
    private lateinit var isoLabel: TextView
    private lateinit var expLabel: TextView
    private lateinit var focusLabel: TextView
    private lateinit var modeAuto: TextView
    private lateinit var modeManual: TextView
    private lateinit var wbRow: LinearLayout
    private lateinit var toggles: LinearLayout
    private var updatingUi = false

    // ---------------------------------------------------------------- niveau
    private var sensors: SensorManager? = null
    private var rawRoll = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        manager = getSystemService(CameraManager::class.java)!!
        sensors = getSystemService(SensorManager::class.java)
        buildUi()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        bgThread = HandlerThread("camera").also { it.start(); bg = Handler(it.looper) }
        sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (texture.isAvailable) openCamera() else texture.surfaceTextureListener = textureListener
    }

    override fun onPause() {
        sensors?.unregisterListener(this)
        closeCamera()
        bgThread?.quitSafely()
        bgThread = null; bg = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (texture.isAvailable) openCamera()
        } else {
            Toast.makeText(this, "La caméra a besoin de l'autorisation Appareil photo.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ================================================================= interface

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val column = vertical()
        column.padForSystemBars()

        val previewBox = FrameLayout(this)
        texture = TextureView(this)
        overlay = GuideOverlay(this)
        previewBox.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        previewBox.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        info = label("", 12f, Palette.TEXT).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(0x99000000.toInt(), dpf(12f))
        }
        previewBox.addView(info, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(10), dp(10), 0, 0)
        })
        previewBox.addView(label("‹", 30f, Palette.TEXT).apply {
            setPadding(dp(14), dp(2), dp(14), dp(6))
            background = rounded(0x99000000.toInt(), dpf(20f))
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(10), dp(10), 0)
        })
        column.addView(previewBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Panneau de réglages
        val panel = vertical().apply {
            setBackgroundColor(0xFF0B0D18.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val modes = horizontal()
        modeAuto = pill("Auto") { setManual(false) }
        modeManual = pill("Manuel") { setManual(true) }
        modes.addView(modeAuto, wrapWrap().margins(r = dp(8)))
        modes.addView(modeManual, wrapWrap().margins(r = dp(8)))
        modes.addView(View(this), weighted())
        modes.addView(pill("Réinitialiser") { resetSettings() }, wrapWrap())
        panel.addView(modes, matchWrap().margins(b = dp(6)))

        val sliders = vertical()
        val ev = slider("Exposition") { p, user -> if (user) { s.ev = evFromBar(p); apply() } }
        evRow = ev.first; evBar = ev.second; evLabel = ev.third
        sliders.addView(evRow, matchWrap())

        manualBox = vertical()
        val iso = slider("ISO") { p, user -> if (user) { s.iso = isoFromBar(p); apply() } }
        isoBar = iso.second; isoLabel = iso.third
        manualBox.addView(iso.first, matchWrap())
        val ex = slider("Vitesse") { p, user -> if (user) { s.exposureNs = expFromBar(p); apply() } }
        expBar = ex.second; expLabel = ex.third
        manualBox.addView(ex.first, matchWrap())
        sliders.addView(manualBox, matchWrap())

        val fo = slider("Mise au point") { p, user ->
            if (user) { s.focusAuto = p == 0; s.focusDiopters = minFocus * p / 1000f; apply() }
        }
        focusBar = fo.second; focusLabel = fo.third
        sliders.addView(fo.first, matchWrap())
        panel.addView(sliders, matchWrap())

        wbRow = horizontal()
        panel.addView(label("Balance des blancs", 12f, Palette.MUTED), matchWrap().margins(t = dp(4)))
        panel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(wbRow) }, matchWrap().margins(t = dp(4), b = dp(6)))

        toggles = horizontal()
        panel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(toggles) }, matchWrap().margins(b = dp(8)))

        val shutterRow = horizontal().apply { gravity = Gravity.CENTER }
        shutterRow.addView(pill("🔄 Changer") { switchCamera() }, wrapWrap())
        shutterRow.addView(View(this), weighted())
        shutterRow.addView(label("", 1f).apply {
            background = rounded(0xFFFFFFFF.toInt(), dpf(36f), dp(5), Palette.PRIMARY)
            setOnClickListener { takePicture() }
            contentDescription = "Prendre la photo"
        }, LinearLayout.LayoutParams(dp(72), dp(72)))
        shutterRow.addView(View(this), weighted())
        shutterRow.addView(pill("🖼️ Photos") {
            runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)) }
        }, wrapWrap())
        panel.addView(shutterRow, matchWrap())

        column.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(column)
        setContentView(root)
    }

    private fun slider(name: String, onChange: (Int, Boolean) -> Unit): Triple<View, SeekBar, TextView> {
        val row = horizontal().apply { setPadding(0, dp(2), 0, dp(2)) }
        row.addView(label(name, 13f, Palette.MUTED), LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))
        val bar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!updatingUi) onChange(p, fromUser)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { saveSettings() }
            })
        }
        row.addView(bar, weighted())
        val value = label("", 13f).apply { gravity = Gravity.END }
        row.addView(value, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
        return Triple(row, bar, value)
    }

    private fun buildDynamicRows() {
        wbRow.removeAllViews()
        val names = linkedMapOf(
            CameraMetadata.CONTROL_AWB_MODE_AUTO to "Auto",
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT to "☀️ Jour",
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "☁️ Nuageux",
            CameraMetadata.CONTROL_AWB_MODE_SHADE to "Ombre",
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT to "💡 Incandescent",
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT to "Fluo",
            CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT to "Fluo chaud",
            CameraMetadata.CONTROL_AWB_MODE_TWILIGHT to "Crépuscule",
        )
        for ((mode, label) in names) {
            if (mode !in awbModes) continue
            val selected = !s.wbCalibrated && s.awbMode == mode
            wbRow.addView(chip(label, selected) {
                s.awbMode = mode; s.wbCalibrated = false; apply(); saveSettings(); buildDynamicRows()
            }, wrapWrap().margins(r = dp(6)))
        }
        if (canPostProcessing) {
            wbRow.addView(chip(if (s.wbCalibrated) "✔ Blanc calibré" else "🎯 Calibrer le blanc", s.wbCalibrated) {
                calibrateWhite()
            }, wrapWrap())
        }

        toggles.removeAllViews()
        toggles.addView(chip(if (s.grid) "▦ Grille ✔" else "▦ Grille", s.grid) {
            s.grid = !s.grid; overlay.showGrid = s.grid; overlay.invalidate(); saveSettings(); buildDynamicRows()
        }, wrapWrap().margins(r = dp(6)))
        toggles.addView(chip(if (s.level) "⚖ Niveau ✔" else "⚖ Niveau", s.level) {
            s.level = !s.level; overlay.showLevel = s.level; overlay.invalidate(); saveSettings(); buildDynamicRows()
        }, wrapWrap().margins(r = dp(6)))
        toggles.addView(pill("📐 Calibrer le niveau") { calibrateLevel() }, wrapWrap())
    }

    private fun refreshUi() {
        updatingUi = true
        val manual = s.manual && canManualSensor
        modeAuto.background = rounded(if (!manual) Palette.PRIMARY else Palette.SURFACE_2, dpf(20f))
        modeAuto.setTextColor(if (!manual) Palette.ON_PRIMARY else Palette.TEXT)
        modeManual.background = rounded(if (manual) Palette.PRIMARY else Palette.SURFACE_2, dpf(20f))
        modeManual.setTextColor(if (manual) Palette.ON_PRIMARY else Palette.TEXT)
        modeManual.alpha = if (canManualSensor) 1f else 0.4f
        manualBox.visibility = if (manual) View.VISIBLE else View.GONE
        evRow.visibility = if (manual) View.GONE else View.VISIBLE

        val evSpan = (evRange.upper - evRange.lower).coerceAtLeast(1)
        evBar.progress = ((s.ev - evRange.lower) * 1000f / evSpan).roundToInt()
        evLabel.text = "%+.1f EV".format(Locale.FRENCH, s.ev * evStep.toFloat())
        isoBar.progress = isoToBar(s.iso)
        isoLabel.text = "${s.iso}"
        expBar.progress = expToBar(s.exposureNs)
        expLabel.text = shutterText(s.exposureNs)
        focusBar.isEnabled = minFocus > 0f
        focusBar.progress = if (s.focusAuto || minFocus <= 0f) 0 else (s.focusDiopters / minFocus * 1000f).roundToInt()
        focusLabel.text = when {
            s.focusAuto || minFocus <= 0f -> "Auto"
            s.focusDiopters <= 0.01f -> "∞"
            else -> "${(100f / s.focusDiopters).roundToInt()} cm"
        }
        updatingUi = false
        overlay.showGrid = s.grid
        overlay.showLevel = s.level
        overlay.invalidate()
    }

    private fun setManual(on: Boolean) {
        if (on && !canManualSensor) {
            Toast.makeText(this, "Ce capteur n'autorise pas l'ISO et la vitesse manuels.", Toast.LENGTH_LONG).show()
            return
        }
        if (on && !s.manual) {
            // On part de l'exposition automatique actuelle pour ne pas « sauter ».
            lastResult?.let { r ->
                r.get(CaptureResult.SENSOR_SENSITIVITY)?.let { s.iso = it.coerceIn(isoRange.lower, isoRange.upper) }
                r.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { s.exposureNs = it.coerceIn(expMin(), expMax()) }
            }
        }
        s.manual = on
        apply(); saveSettings()
    }

    // ================================================================= conversions

    private fun evFromBar(p: Int) = evRange.lower + ((evRange.upper - evRange.lower) * p / 1000f).roundToInt()
    private fun isoFromBar(p: Int) = (isoRange.lower + (isoRange.upper - isoRange.lower) * (p / 1000f)).roundToInt()
    private fun isoToBar(iso: Int) = ((iso - isoRange.lower) * 1000f / (isoRange.upper - isoRange.lower).coerceAtLeast(1)).roundToInt()
    private fun expMin() = maxOf(expRange.lower, 125_000L) // 1/8000 s
    private fun expMax() = minOf(expRange.upper, 500_000_000L) // 1/2 s (au-delà, l'aperçu devient inutilisable)
    private fun expFromBar(p: Int): Long {
        val a = ln(expMin().toDouble()); val b = ln(expMax().toDouble())
        return exp(a + (b - a) * p / 1000.0).roundToLong()
    }
    private fun expToBar(ns: Long): Int {
        val a = ln(expMin().toDouble()); val b = ln(expMax().toDouble())
        return ((ln(ns.coerceIn(expMin(), expMax()).toDouble()) - a) / (b - a) * 1000).roundToInt()
    }
    private fun shutterText(ns: Long): String {
        val sec = ns / 1e9
        return if (sec >= 0.3) "%.1f s".format(Locale.FRENCH, sec) else "1/${(1 / sec).roundToInt()} s"
    }

    // ================================================================= caméra

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (device != null) return
        try {
            val ids = manager.cameraIdList
            if (ids.isEmpty()) { Toast.makeText(this, "Aucune caméra.", Toast.LENGTH_LONG).show(); return }
            if (cameraId.isEmpty() || cameraId !in ids) {
                cameraId = ids.firstOrNull {
                    manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: ids[0]
            }
            val c = manager.getCameraCharacteristics(cameraId)
            chars = c
            readCapabilities(c)
            s = CamSettings.load(this, cameraId, isoRange.lower, expMin())
            buildDynamicRows()
            refreshUi()

            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val jpeg = map.getOutputSizes(ImageFormat.JPEG).filter { isFourThree(it) }.maxByOrNull { it.width.toLong() * it.height }
                ?: map.getOutputSizes(ImageFormat.JPEG).maxBy { it.width.toLong() * it.height }
            val ratio = jpeg.width.toFloat() / jpeg.height
            previewSize = map.getOutputSizes(SurfaceTexture::class.java)
                .filter { it.width <= 1920 && abs(it.width.toFloat() / it.height - ratio) < 0.02f }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: map.getOutputSizes(SurfaceTexture::class.java).filter { it.width <= 1920 }.maxBy { it.width.toLong() * it.height }
            fitTexture()

            reader = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ r -> saveJpeg(r) }, bg)
            }
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; startSession() }
                override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close(); device = null
                    runOnUiThread { Toast.makeText(this@CameraActivity, "Erreur caméra ($error).", Toast.LENGTH_LONG).show() }
                }
            }, bg)
        } catch (e: Exception) {
            Toast.makeText(this, "Caméra indisponible : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun isFourThree(sz: Size) = abs(sz.width.toFloat() / sz.height - 4f / 3f) < 0.01f

    private fun readCapabilities(c: CameraCharacteristics) {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        canManualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        canPostProcessing = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps
        c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { isoRange = it }
        c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { expRange = it }
        c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { evRange = it }
        c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.let { evStep = it }
        minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        awbModes = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)
    }

    /** L'aperçu garde les proportions du capteur (affichage portrait). */
    private fun fitTexture() {
        val parent = texture.parent as View
        parent.post {
            val w = parent.width
            val h = parent.height
            if (w == 0 || h == 0) return@post
            val ratio = previewSize.width.toFloat() / previewSize.height // > 1
            var tw = w
            var th = (w * ratio).roundToInt()
            if (th > h) { th = h; tw = (h / ratio).roundToInt() }
            texture.layoutParams = FrameLayout.LayoutParams(tw, th, Gravity.CENTER)
            overlay.layoutParams = FrameLayout.LayoutParams(tw, th, Gravity.CENTER)
        }
    }

    private fun startSession() {
        val d = device ?: return
        val st = texture.surfaceTexture ?: return
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(st)
        val r = reader ?: return
        try {
            @Suppress("DEPRECATION")
            d.createCaptureSession(listOf(surface, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cs: CameraCaptureSession) {
                    session = cs
                    previewTarget = surface
                    repeat()
                }
                override fun onConfigureFailed(cs: CameraCaptureSession) {
                    runOnUiThread { Toast.makeText(this@CameraActivity, "Configuration de la caméra impossible.", Toast.LENGTH_LONG).show() }
                }
            }, bg)
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "Caméra : ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private var previewTarget: Surface? = null

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        private var frames = 0
        override fun onCaptureCompleted(cs: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            lastResult = result
            if (++frames % 10 == 0) runOnUiThread { updateInfo(result) }
        }
    }

    private fun repeat() {
        val d = device ?: return
        val cs = session ?: return
        val target = previewTarget ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(target)
            applySettings(b)
            cs.setRepeatingRequest(b.build(), resultCallback, bg)
        } catch (_: Exception) {
        }
    }

    /** Applique les réglages (et la calibration) à une requête. */
    private fun applySettings(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        if (s.manual && canManualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, s.iso.coerceIn(isoRange.lower, isoRange.upper))
            val e = s.exposureNs.coerceIn(expRange.lower, expRange.upper)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(e, 33_333_333L))
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s.ev.coerceIn(evRange.lower, evRange.upper))
        }
        val afModes = chars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        if (s.focusAuto || minFocus <= 0f) {
            if (CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopters)
        }
        val gains = s.gains
        val transform = s.transform
        if (s.wbCalibrated && canPostProcessing && gains != null && transform != null) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(gains[0], gains[1], gains[2], gains[3]))
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, ColorSpaceTransform(transform))
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, s.awbMode)
        }
    }

    private fun apply() {
        refreshUi()
        bg?.post { repeat() }
    }

    private fun updateInfo(r: TotalCaptureResult) {
        val iso = r.get(CaptureResult.SENSOR_SENSITIVITY)
        val e = r.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val f = r.get(CaptureResult.LENS_FOCUS_DISTANCE)
        val parts = mutableListOf(if (s.manual && canManualSensor) "MANUEL" else "AUTO")
        if (iso != null) parts += "ISO $iso"
        if (e != null) parts += shutterText(e)
        if (f != null) parts += if (f <= 0.01f) "MAP ∞" else "MAP ${(100f / f).roundToInt()} cm"
        if (s.wbCalibrated) parts += "Blanc calibré"
        if (s.level) parts += "%.1f°".format(Locale.FRENCH, overlay.roll)
        info.text = parts.joinToString(" · ")
    }

    private fun closeCamera() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null; device = null; reader = null; previewTarget = null
    }

    private fun switchCamera() {
        val ids = manager.cameraIdList
        if (ids.size < 2) return
        val facing = chars?.get(CameraCharacteristics.LENS_FACING)
        cameraId = ids.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) != facing
        } ?: ids[(ids.indexOf(cameraId) + 1) % ids.size]
        closeCamera()
        openCamera()
    }

    // ================================================================= photo

    private fun takePicture() {
        val d = device ?: return
        val cs = session ?: return
        val r = reader ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(r.surface)
            previewTarget?.let { b.addTarget(it) }
            applySettings(b)
            b.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            cs.capture(b.build(), null, bg)
            overlay.flash()
        } catch (e: Exception) {
            Toast.makeText(this, "Photo impossible : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveJpeg(r: ImageReader) {
        val image = r.acquireLatestImage() ?: return
        try {
            val buf = image.planes[0].buffer
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            val name = "ORBITE_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Orbite")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            runOnUiThread { Toast.makeText(this, "Photo enregistrée dans Images/Orbite", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "Enregistrement impossible : ${e.message}", Toast.LENGTH_LONG).show() }
        } finally {
            image.close()
        }
    }

    // ================================================================= calibration

    /** Visez une feuille blanche ou grise : on mémorise les gains de couleur trouvés par la caméra. */
    private fun calibrateWhite() {
        if (s.wbCalibrated) {
            s.wbCalibrated = false; apply(); saveSettings(); buildDynamicRows()
            Toast.makeText(this, "Calibration du blanc désactivée.", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Remplissez le cadre avec une feuille blanche ou grise… ne bougez plus.", Toast.LENGTH_LONG).show()
        s.awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
        apply()
        texture.postDelayed({
            val r = lastResult
            val g = r?.get(CaptureResult.COLOR_CORRECTION_GAINS)
            val t = r?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            if (g == null || t == null) {
                Toast.makeText(this, "Calibration impossible sur cette caméra.", Toast.LENGTH_LONG).show()
                return@postDelayed
            }
            val els = arrayOfNulls<Rational>(9)
            t.copyElements(els, 0)
            s.gains = floatArrayOf(g.red, g.greenEven, g.greenOdd, g.blue)
            s.transform = Array(9) { els[it] ?: Rational(if (it % 4 == 0) 1 else 0, 1) }
            s.wbCalibrated = true
            apply(); saveSettings(); buildDynamicRows()
            Toast.makeText(this, "Blanc calibré et enregistré ✔", Toast.LENGTH_SHORT).show()
        }, 2500)
    }

    /** Posez le téléphone droit (contre un mur ou une porte) : l'angle actuel devient le zéro. */
    private fun calibrateLevel() {
        s.levelOffset = rawRoll
        s.level = true
        saveSettings(); buildDynamicRows(); refreshUi()
        Toast.makeText(this, "Niveau calibré : %.1f° deviennent 0°.".format(Locale.FRENCH, rawRoll), Toast.LENGTH_LONG).show()
    }

    private fun resetSettings() {
        s = CamSettings.defaults(isoRange.lower, expMin())
        saveSettings(); buildDynamicRows(); apply()
    }

    private fun saveSettings() {
        if (cameraId.isNotEmpty()) s.save(this, cameraId)
    }

    // ================================================================= capteur de niveau

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]; val y = e.values[1]
        val roll = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
        rawRoll = rawRoll * 0.8f + roll * 0.2f
        overlay.roll = rawRoll - s.levelOffset
        if (s.level) overlay.invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/** Réglages de la caméra et calibration, enregistrés par objectif. */
class CamSettings(
    var manual: Boolean,
    var ev: Int,
    var iso: Int,
    var exposureNs: Long,
    var focusAuto: Boolean,
    var focusDiopters: Float,
    var awbMode: Int,
    var wbCalibrated: Boolean,
    var gains: FloatArray?,
    var transform: Array<Rational>?,
    var grid: Boolean,
    var level: Boolean,
    var levelOffset: Float,
) {
    fun save(ctx: Context, id: String) {
        val e = ctx.getSharedPreferences("camera_$id", Context.MODE_PRIVATE).edit()
        e.putBoolean("manual", manual).putInt("ev", ev).putInt("iso", iso).putLong("exp", exposureNs)
            .putBoolean("focusAuto", focusAuto).putFloat("focus", focusDiopters).putInt("awb", awbMode)
            .putBoolean("wbCal", wbCalibrated).putBoolean("grid", grid).putBoolean("level", level)
            .putFloat("levelOffset", levelOffset)
        e.putString("gains", gains?.joinToString(";"))
        e.putString("transform", transform?.joinToString(";") { "${it.numerator}/${it.denominator}" })
        e.apply()
    }

    companion object {
        fun defaults(iso: Int, exp: Long) = CamSettings(
            manual = false, ev = 0, iso = maxOf(iso, 100), exposureNs = maxOf(exp, 10_000_000L),
            focusAuto = true, focusDiopters = 0f, awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO,
            wbCalibrated = false, gains = null, transform = null, grid = true, level = true, levelOffset = 0f,
        )

        fun load(ctx: Context, id: String, iso: Int, exp: Long): CamSettings {
            val p = ctx.getSharedPreferences("camera_$id", Context.MODE_PRIVATE)
            val d = defaults(iso, exp)
            if (!p.contains("manual")) return d
            val gains = p.getString("gains", null)?.split(";")?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.size == 4 }?.toFloatArray()
            val transform = p.getString("transform", null)?.split(";")?.mapNotNull {
                val (a, b) = it.split("/").let { x -> (x.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null) to (x.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null) }
                Rational(a, b)
            }?.takeIf { it.size == 9 }?.toTypedArray()
            return CamSettings(
                manual = p.getBoolean("manual", d.manual), ev = p.getInt("ev", 0), iso = p.getInt("iso", d.iso),
                exposureNs = p.getLong("exp", d.exposureNs), focusAuto = p.getBoolean("focusAuto", true),
                focusDiopters = p.getFloat("focus", 0f), awbMode = p.getInt("awb", d.awbMode),
                wbCalibrated = p.getBoolean("wbCal", false) && gains != null && transform != null,
                gains = gains, transform = transform, grid = p.getBoolean("grid", true),
                level = p.getBoolean("level", true), levelOffset = p.getFloat("levelOffset", 0f),
            )
        }
    }
}

/** Grille des tiers + niveau à bulle dessinés par-dessus l'aperçu. */
class GuideOverlay(ctx: Context) : View(ctx) {
    var showGrid = true
    var showLevel = true
    var roll = 0f
    private var flashUntil = 0L

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF; strokeWidth = ctx.resources.displayMetrics.density
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3 * ctx.resources.displayMetrics.density; strokeCap = Paint.Cap.ROUND
    }
    private val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt(); strokeWidth = 2 * ctx.resources.displayMetrics.density
    }

    fun flash() {
        flashUntil = System.currentTimeMillis() + 120
        invalidate()
        postDelayed({ invalidate() }, 150)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        if (System.currentTimeMillis() < flashUntil) c.drawColor(0x88FFFFFF.toInt())
        if (showGrid) {
            for (i in 1..2) {
                c.drawLine(w * i / 3, 0f, w * i / 3, h, gridPaint)
                c.drawLine(0f, h * i / 3, w, h * i / 3, gridPaint)
            }
        }
        if (showLevel) {
            val cx = w / 2; val cy = h / 2
            val len = w * 0.28f
            val ok = abs(roll) < 1f
            levelPaint.color = if (ok) Palette.SUCCESS else Palette.SECONDARY
            c.drawLine(cx - len * 1.15f, cy, cx - len * 0.95f, cy, refPaint)
            c.drawLine(cx + len * 0.95f, cy, cx + len * 1.15f, cy, refPaint)
            c.save()
            c.rotate(-roll, cx, cy)
            c.drawLine(cx - len * 0.9f, cy, cx + len * 0.9f, cy, levelPaint)
            c.restore()
        }
    }
}
