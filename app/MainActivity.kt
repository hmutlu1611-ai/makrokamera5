package com.example.makrokamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var focusRing: View
    private lateinit var infoText: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var zoomSeek: SeekBar
    private lateinit var focusSeek: SeekBar
    private lateinit var manualSwitch: Switch

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    private var backCameraIds: List<String> = emptyList()
    private var currentIndex = 0
    private var torchOn = false

    private var minFocusDiopter = 0f

    private val maxQualityZoom = 5f

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera() else {
                Toast.makeText(this, "Kamera izni gerekli", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        focusRing = findViewById(R.id.focusRing)
        infoText = findViewById(R.id.infoText)
        zoomLabel = findViewById(R.id.zoomLabel)
        zoomSeek = findViewById(R.id.zoomSeek)
        focusSeek = findViewById(R.id.focusSeek)
        manualSwitch = findViewById(R.id.manualSwitch)

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        setupGestures()
        setupControls()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            backCameraIds = rankBackCameras(provider!!)
            if (backCameraIds.isEmpty()) {
                Toast.makeText(this, "Arka kamera bulunamadı", Toast.LENGTH_LONG).show()
                return@addListener
            }
            currentIndex = 0
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rankBackCameras(p: ProcessCameraProvider): List<String> {
        return p.availableCameraInfos
            .filter {
                Camera2CameraInfo.from(it).getCameraCharacteristic(
                    CameraCharacteristics.LENS_FACING
                ) == CameraCharacteristics.LENS_FACING_BACK
            }
            .map {
                val c2 = Camera2CameraInfo.from(it)
                val diopter = c2.getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
                ) ?: 0f
                c2.cameraId to diopter
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun bindCamera() {
        val p = provider ?: return
        val id = backCameraIds[currentIndex]

        val selector = CameraSelector.Builder()
            .addCameraFilter { infos: List<CameraInfo> ->
                infos.filter { Camera2CameraInfo.from(it).cameraId == id }
            }.build()

        val previewBuilder = Preview.Builder()
        Camera2Interop.Extender(previewBuilder).apply {
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
            setCaptureRequestOption(
                CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY
            )
            setCaptureRequestOption(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            )
        }
        val preview = previewBuilder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(98)
        Camera2Interop.Extender(captureBuilder).apply {
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            setCaptureRequestOption(
                CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY
            )
            setCaptureRequestOption(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            )
        }
        imageCapture = captureBuilder.build()

        p.unbindAll()
        camera = p.bindToLifecycle(this, selector, preview, imageCapture)

        val cam = camera!!
        minFocusDiopter = Camera2CameraInfo.from(cam.cameraInfo).getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
        ) ?: 0f

        cam.cameraInfo.zoomState.observe(this) { z ->
            val limit = minOf(z.maxZoomRatio, maxQualityZoom)
            val range = (limit - z.minZoomRatio).coerceAtLeast(0.01f)
            zoomSeek.progress = (((z.zoomRatio - z.minZoomRatio) / range) * 100).toInt()
            zoomLabel.text = "Yakınlaştırma: %.1fx (maks %.1fx)".format(Locale.US, z.zoomRatio, limit)
        }

        manualSwitch.isChecked = false
        focusSeek.isEnabled = false
        torchOn = false

        val closest = if (minFocusDiopter > 0f) (100f / minFocusDiopter) else Float.NaN
        infoText.text = "Kamera ${currentIndex + 1}/${backCameraIds.size}" +
            if (closest.isNaN()) " • sabit odak" else " • en yakın odak ≈ %.1f cm".format(Locale.US, closest)
    }

    private fun setupControls() {
        zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val z = camera?.cameraInfo?.zoomState?.value ?: return
                val limit = minOf(z.maxZoomRatio, maxQualityZoom)
                camera?.cameraControl?.setZoomRatio(z.minZoomRatio + (limit - z.minZoomRatio) * p / 100f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        manualSwitch.setOnCheckedChangeListener { _, checked ->
            focusSeek.isEnabled = checked && minFocusDiopter > 0f
            if (checked) applyManualFocus(focusSeek.progress) else restoreAutoFocus()
        }

        focusSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser && manualSwitch.isChecked) applyManualFocus(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        findViewById<Button>(R.id.switchCameraBtn).setOnClickListener {
            if (backCameraIds.size > 1) {
                currentIndex = (currentIndex + 1) % backCameraIds.size
                bindCamera()
            } else Toast.makeText(this, "Tek arka kamera var", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.torchBtn).setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            if (!cam.cameraInfo.hasFlashUnit()) return@setOnClickListener
            torchOn = !torchOn
            cam.cameraControl.enableTorch(torchOn)
        }

        findViewById<Button>(R.id.captureBtn).setOnClickListener { takePhoto() }
    }

    private fun applyManualFocus(progress: Int) {
        val cam = camera ?: return
        if (minFocusDiopter <= 0f) return
        val diopter = minFocusDiopter * progress / 100f
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF
                )
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopter)
                .build()
        )
    }

    private fun restoreAutoFocus() {
        val cam = camera ?: return
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                .build()
        )
    }

    private fun setupGestures() {
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    val z = camera?.cameraInfo?.zoomState?.value ?: return true
                    val limit = minOf(z.maxZoomRatio, maxQualityZoom)
                    val target = (z.zoomRatio * d.scaleFactor).coerceIn(z.minZoomRatio, limit)
                    camera?.cameraControl?.setZoomRatio(target)
                    return true
                }
            })

        previewView.setOnTouchListener { v, e ->
            scaleDetector.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP && e.pointerCount == 1 &&
                !scaleDetector.isInProgress && e.eventTime - e.downTime < 300
            ) {
                tapToFocus(e.x, e.y)
                v.performClick()
            }
            true
        }
    }

    private fun tapToFocus(x: Float, y: Float) {
        val cam = camera ?: return
        if (manualSwitch.isChecked) manualSwitch.isChecked = false

        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)

        focusRing.x = x - focusRing.width / 2f
        focusRing.y = y - focusRing.height / 2f
        focusRing.visibility = View.VISIBLE
        focusRing.postDelayed({ focusRing.visibility = View.GONE }, 900)
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "MAKRO_$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MakroKamera")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()

        capture.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    Toast.makeText(this@MainActivity, "Kaydedildi: Pictures/MakroKamera", Toast.LENGTH_SHORT).show()
                }
                override fun onError(e: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Hata: ${e.message}", Toast.LENGTH_LONG).show()
                }
            })
    }
}
