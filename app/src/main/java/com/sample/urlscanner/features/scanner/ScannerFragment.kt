package com.sample.urlscanner.features.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context.DISPLAY_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.util.Patterns
import android.util.Size
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButtonToggleGroup
import com.sample.urlscanner.R
import com.sample.urlscanner.core.extension.empty
import com.sample.urlscanner.core.platform.*
import kotlinx.android.synthetic.main.fragment_scanner.*
import kotlinx.coroutines.*
import java.lang.Runnable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


class ScannerFragment : BaseFragment(), View.OnClickListener, MaterialButtonToggleGroup.OnButtonCheckedListener {

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 101
        private val TAG = ScannerFragment::class.simpleName
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
    }

    private var displayId: Int = -1

    private var camera: Camera? = null
    private var cameraExecutor: ExecutorService? = null
    private var dispatcher: CoroutineDispatcher? = null

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null

    private var screenAspectRatio: Int = 0
    private var rotation: Int = 0
    private var timeStart = 0L

    private var cameraLens = CameraSelector.LENS_FACING_BACK

    private val displayManager by lazy {
        requireContext().getSystemService(DISPLAY_SERVICE) as DisplayManager
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@ScannerFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                imageAnalyzer?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }

    private val blockListener: BlockClickListener = {
        Toast.makeText(
            requireContext(),
            "chose the block with text= ${it.text}",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun layoutId(): Int = R.layout.fragment_scanner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appComponent.inject(this)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        dispatcher = cameraExecutor!!.asCoroutineDispatcher()

        scannerCamera.post {
            displayId = scannerCamera.display.displayId
        }
        displayManager.registerDisplayListener(displayListener, null)

    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermissions()) {
            scannerCamera.post {
                bindCameraUseCases()
            }
            clear.setOnClickListener(this)
            flashGroup.addOnButtonCheckedListener(this)
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        displayManager.unregisterDisplayListener(displayListener)
        dispatcher?.cancel()
        cameraExecutor?.shutdown()
        cameraExecutor = null
        imageAnalyzer?.clearAnalyzer()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                scannerCamera.post {
                    bindCameraUseCases()
                }
            }
        }
    }

    private fun hasCameraPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun bindCameraUseCases() {
        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { scannerCamera.display.getRealMetrics(it) }
        Log.d(TAG, "Screen metrics: ${metrics.widthPixels} x ${metrics.heightPixels}")

        screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        rotation = scannerCamera.display.rotation

        // Bind the CameraProvider to the LifeCycleOwner
        val cameraSelector = buildCameraSelector()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(Runnable {

            // CameraProvider
            val cameraProvider = cameraProviderFuture.get()

            // Preview
            preview = buildPreview()

            // ImageAnalysis
            imageAnalyzer = buildImageAnalysis()

            // Must unbind the use-cases before rebinding them
            cameraProvider.unbindAll()

            try {
                // A variable number of use-cases can be passed here -
                // camera provides access to CameraControl & CameraInfo
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )

                // Attach the viewfinder's surface provider to preview use case
                preview?.setSurfaceProvider(scannerCamera.createSurfaceProvider(camera?.cameraInfo))

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    private fun buildCameraSelector() =
        CameraSelector.Builder().requireLensFacing(cameraLens).build()

    private fun buildPreview(): Preview {
        return Preview.Builder()
            // We request aspect ratio but no resolution
            .setTargetAspectRatio(screenAspectRatio)
            // Set initial target rotation
            .setTargetRotation(rotation)
            .build()
    }

    private fun buildImageAnalysis() =
        ImageAnalysis.Builder()
            // We request aspect ratio but no resolution
            .setTargetAspectRatio(screenAspectRatio)
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            .setTargetRotation(rotation)
            .build()
            // The analyzer can then be assigned to the instance
            .also {
                it.setAnalyzer(cameraExecutor!!, ImageAnalysis.Analyzer { image ->
                    timeStart = System.currentTimeMillis()
                    extractText(image)
                })
            }

    @SuppressLint("UnsafeExperimentalUsageError")
    private fun extractText(image: ImageProxy) {
        if (image.image == null) {
            return
        }
        runBlocking {
            try {
                val textImage = image.createVisionImageFromMedia()
                val visionText = TextDetector.processImage(textImage)
                val blocks = visionText.textBlocks.toMutableList()

                withContext(Dispatchers.Main) {
                    BlocksRenderer.drawBlocks(
                        blocksContainer,
                        Size(image.width, image.height),
                        image.imageInfo.rotationDegrees,
                        blocks,
                        blockListener
                    )
                    if (visionText.text.isNotEmpty()) {
                        Log.d("image recognizer", "visionText= ${visionText.text}")
                        var visionUrl = pullLinks(visionText.text)
                        if (Patterns.WEB_URL.matcher(visionUrl).matches()) {
                            Log.d("image recognizer", "timeStart= ${timeStart}")
                            url.text = visionUrl
                            url.paintFlags = url.paintFlags or Paint.UNDERLINE_TEXT_FLAG
                            url.isEnabled = true
                            url.setOnClickListener {
                                val i = Intent(Intent.ACTION_VIEW)
                                if (!visionUrl!!.startsWith("http://") && !visionUrl!!.startsWith("https://")) visionUrl = "http://$visionUrl"
                                i.data = Uri.parse(visionUrl)
                                requireActivity().startActivity(i)
                                Log.d("image recognizer", "visionUrl= $visionUrl")
                            }
                            clear.setTextColor(ContextCompat.getColor(requireContext(), R.color.red))
                            clear.isEnabled = true

                            processTime.visibility = VISIBLE
                            val time = (((System.currentTimeMillis() - timeStart)/1000) % 60)
                            Log.d("image recognizer", "time= ${(System.currentTimeMillis() - timeStart)}")
                            processTime.text = "Process time: $time s"
                            image.close()

                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            image.close()
        }
    }

    private fun pullLinks(text: String?): String? {
        val links = ArrayList<String>()
        //String regex = "\\(?\\b(http://|www[.])[-A-Za-z0-9+&@#/%?=~_()|!:,.;]*[-A-Za-z0-9+&@#/%=~_()|]";
        val regex = "\\(?\\b(https?://|www[.]|ftp://)[-A-Za-z0-9+&@#/%?=~_()|!:,.;]*[-A-Za-z0-9+&@#/%=~_()|]"
        val p: Pattern = Pattern.compile(regex)
        val m: Matcher = p.matcher(text)
        while (m.find()) {
            var urlStr: String = m.group()
            if (urlStr.startsWith("(") && urlStr.endsWith(")")) {
                urlStr = urlStr.substring(1, urlStr.length - 1)
            }
            links.add(urlStr)
        }
        return links.first()
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.clear -> {
                url.text = String.empty()
                url.hint = requireActivity().getString(R.string.url_scan_hint)
                url.isEnabled = false
                url.paintFlags = url.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
                clear.isEnabled = false
                clear.setTextColor(ContextCompat.getColor(requireContext(), R.color.gray))
                flashGroup.check(R.id.off)
                processTime.visibility = GONE
                displayManager.registerDisplayListener(displayListener, null)
                scannerCamera.post { bindCameraUseCases() }
            }
        }
    }

    override fun onButtonChecked(
        group: MaterialButtonToggleGroup?,
        checkedId: Int,
        isChecked: Boolean
    ) {
        if (isChecked) {
            when (checkedId) {
                R.id.off -> camera?.cameraControl?.enableTorch(false)
                R.id.on -> camera?.cameraControl?.enableTorch(true)
            }
        }
    }
}
