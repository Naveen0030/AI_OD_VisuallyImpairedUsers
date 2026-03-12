package com.naveen.ba_v2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.naveen.ba_v2.R
import com.naveen.ba_v2.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.view.View
import android.view.HapticFeedbackConstants

class MainActivity : AppCompatActivity(), Detector.DetectorListener {
    
    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private lateinit var languageNames: Array<String>
    private lateinit var languageCodes: Array<String>

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var detector: Detector

    private lateinit var cameraExecutor: ExecutorService

    // Reusable buffers to minimize per-frame allocations
    private var bitmapBuffer: Bitmap? = null
    private val transformMatrix = Matrix()

    // Coordinators and managers
    private lateinit var speechCoordinator: SpeechCoordinator
    private lateinit var appStateManager: AppStateManager
    private lateinit var languageManager: LanguageManager
    private lateinit var hazardAnalyzer: HazardAnalyzer
    private var localeContext: Context = this
    
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
        ).toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize coordinators and managers
        speechCoordinator = SpeechCoordinator.getInstance(applicationContext)
        appStateManager = AppStateManager.getInstance()
        languageManager = LanguageManager.getInstance(applicationContext)
        hazardAnalyzer = HazardAnalyzer(Constants.HARMFUL_CLASSES)

        // Setup language listener to update locale context and announce changes
        languageManager.addLanguageListener { languageCode ->
            localeContext = languageManager.createLocaleContext(this)
            speechCoordinator.updateLanguage(languageCode)
            // Announce language change confirmation
            Handler(Looper.getMainLooper()).postDelayed({
                val confirmMsg = localeContext.getString(R.string.msg_language_changed)
                speechCoordinator.speakInfo(confirmMsg)
                Log.d(TAG, "Language change confirmation: $confirmMsg")
            }, 200)
        }
        
        // Setup state listener to update UI
        appStateManager.addStateListener { state ->
            updateUIForState(state)
        }

        languageNames = resources.getStringArray(R.array.language_names)
        languageCodes = resources.getStringArray(R.array.language_codes)
        
        showLanguagePicker()

        detector = Detector(baseContext, Constants.MODEL_PATH, Constants.LABELS_PATH, this)
        detector.setup()

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Setup button listeners
        setupButtonListeners()

        // Initialize camera if permissions granted
        if (allPermissionsGranted()) {
            appStateManager.setState(AppState.CAMERA_PREVIEW)
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun setupButtonListeners() {
        binding.startButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            handleStartButtonClick()
        }

        binding.endButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            handleEndButtonClick()
        }
    }

    private fun handleStartButtonClick() {
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            return
        }

        val currentState = appStateManager.getState()
        if (currentState != AppState.CAMERA_PREVIEW) {
            Log.w(TAG, "Start button clicked but state is $currentState")
            return
        }

        // Stop camera preview
        stopCamera()
        
        // Change state first
        appStateManager.setState(AppState.GUIDANCE_RUNNING)
        
        // Start service
        val intent = android.content.Intent(this, GuidanceService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(this, intent)
    }

    private fun handleEndButtonClick() {
        val currentState = appStateManager.getState()
        if (currentState != AppState.GUIDANCE_RUNNING) {
            Log.w(TAG, "End button clicked but state is $currentState")
            return
        }

        // Stop service
        val intent = android.content.Intent(this, GuidanceService::class.java)
        stopService(intent)
        
        // Change state (service will also set state in onDestroy, but set it here for immediate UI update)
        appStateManager.setState(AppState.CAMERA_PREVIEW)
    }

    private fun updateUIForState(state: AppState) {
        runOnUiThread {
            when (state) {
                AppState.IDLE -> {
                    binding.startButton.visibility = View.GONE
                    binding.endButton.visibility = View.GONE
                }
                AppState.CAMERA_PREVIEW -> {
                    binding.startButton.visibility = View.VISIBLE
                    binding.endButton.visibility = View.GONE
                    hideVisualElements(false)
                    if (allPermissionsGranted()) {
                        startCamera()
                    }
                }
                AppState.GUIDANCE_RUNNING -> {
                    binding.startButton.visibility = View.GONE
                    binding.endButton.visibility = View.VISIBLE
                    hideVisualElements(true)
                }
            }
        }
    }

    private fun startCamera() {
        val currentState = appStateManager.getState()
        if (currentState != AppState.CAMERA_PREVIEW) {
            Log.w(TAG, "Attempted to start camera in state: $currentState")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider initialization failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera", e)
        }
        binding.overlay.clear()
        binding.inferenceTime.text = ""
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        // Check state again before binding
        if (appStateManager.getState() != AppState.CAMERA_PREVIEW) {
            Log.w(TAG, "State changed during camera binding, aborting")
            return
        }

        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            // Check state before processing frame
            if (appStateManager.getState() != AppState.CAMERA_PREVIEW) {
                imageProxy.close()
                return@setAnalyzer
            }

            val width = imageProxy.width
            val height = imageProxy.height

            val localBuffer = bitmapBuffer ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                bitmapBuffer = it
            }

            imageProxy.use {
                localBuffer.copyPixelsFromBuffer(it.planes[0].buffer)
            }

            transformMatrix.reset()
            transformMatrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            if (isFrontCamera) {
                transformMatrix.postScale(-1f, 1f, width.toFloat(), height.toFloat())
            }

            val rotated = Bitmap.createBitmap(localBuffer, 0, 0, localBuffer.width, localBuffer.height, transformMatrix, true)
            imageProxy.close()

            detector.detect(rotated)
        }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            appStateManager.setState(AppState.CAMERA_PREVIEW)
        }
    }

    override fun onPause() {
        super.onPause()
        // Only stop camera if we're in CAMERA_PREVIEW state
        if (appStateManager.getState() == AppState.CAMERA_PREVIEW) {
            stopCamera()
        }
    }

    override fun onResume() {
        super.onResume()
        val currentState = appStateManager.getState()
        
        // Only start camera if in CAMERA_PREVIEW state and permissions granted
        if (currentState == AppState.CAMERA_PREVIEW && allPermissionsGranted()) {
            startCamera()
        } else if (!allPermissionsGranted()) {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector.clear()
        cameraExecutor.shutdown()
        stopCamera()
        // Don't cleanup speechCoordinator here - it's a singleton shared with service
        // Service will handle cleanup
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.invalidate()
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        // Only process if in CAMERA_PREVIEW state
        if (appStateManager.getState() != AppState.CAMERA_PREVIEW) {
            return
        }

        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }
        }

        // Provide voice feedback about detected objects and navigation guidance
        processDetections(boundingBoxes)
    }

    private fun processDetections(boundingBoxes: List<BoundingBox>) {
        // Generate hazard ID for STOP deduplication (simple hash of relevant objects)
        val hazardId = generateHazardId(boundingBoxes)
        
        // Check for approaching hazards first (CRITICAL priority)
        val approaching = hazardAnalyzer.updateAndHasApproachingHazard(boundingBoxes)
        val baseCommand = GuidanceLogic.decideCommand(boundingBoxes)
        val command = if (approaching) GuidanceCommand.Stop else baseCommand

        // Speak navigation commands (NAVIGATION priority)
        speakNavigationCommand(command, hazardId)

        // Speak object info only if not navigation-active (INFO priority)
        if (boundingBoxes.isNotEmpty() && command != GuidanceCommand.Stop) {
            val relevantObject = findMostRelevantObject(boundingBoxes)
            if (relevantObject != null) {
                val distance = estimateDistance(relevantObject)
                val objectMessage = createObjectMessage(relevantObject, distance)
                speechCoordinator.speakInfo(objectMessage)
            }
        }
    }

    private fun generateHazardId(boundingBoxes: List<BoundingBox>): String? {
        // Generate a simple ID based on harmful objects in center path
        val harmfulObjects = boundingBoxes.filter { box ->
            Constants.HARMFUL_CLASSES.contains(box.clsName) &&
            box.cx in 0.33f..0.67f && box.cy > 0.4f
        }
        if (harmfulObjects.isEmpty()) return null
        
        // Create ID from class names and approximate positions
        return harmfulObjects.joinToString("|") { "${it.clsName}_${(it.cx * 10).toInt()}_${(it.cy * 10).toInt()}" }
    }

    private fun speakNavigationCommand(command: GuidanceCommand, hazardId: String?) {
        val phraseRes = when (command) {
            GuidanceCommand.MoveLeft -> R.string.cmd_move_left
            GuidanceCommand.MoveRight -> R.string.cmd_move_right
            GuidanceCommand.Continue -> R.string.cmd_continue
            GuidanceCommand.Stop -> R.string.cmd_stop
            GuidanceCommand.SlightLeft -> R.string.cmd_slight_left
            GuidanceCommand.SlightRight -> R.string.cmd_slight_right
        }
        val phraseText = localeContext.getString(phraseRes)

        if (command == GuidanceCommand.Stop) {
            speechCoordinator.speakCritical(phraseText, hazardId)
            vibrateAlert()
        } else {
            speechCoordinator.speakNavigation(phraseText)
        }
    }

    private fun findMostRelevantObject(boundingBoxes: List<BoundingBox>): BoundingBox? {
        if (boundingBoxes.isEmpty()) return null
        val centerObjects = boundingBoxes.filter { it.cx in 0.33f..0.67f && it.cy > 0.4f }
        if (centerObjects.isNotEmpty()) {
            return centerObjects.maxByOrNull { it.cnf * (it.w * it.h) }
        }
        return boundingBoxes.maxByOrNull { it.cnf }
    }

    private fun estimateDistance(boundingBox: BoundingBox): String {
        val boxArea = boundingBox.w * boundingBox.h
        return when {
            boxArea > 0.25f -> localeContext.getString(R.string.distance_very_close)
            boxArea > 0.12f -> localeContext.getString(R.string.distance_close)
            boxArea > 0.06f -> localeContext.getString(R.string.distance_medium)
            else -> localeContext.getString(R.string.distance_ahead)
        }
    }

    private fun createObjectMessage(boundingBox: BoundingBox, distance: String): String {
        val objectName = boundingBox.clsName
        return when (objectName) {
            "person" -> localeContext.getString(R.string.msg_person_detected, distance)
            "car", "auto", "bus", "truck", "motorcycle" -> localeContext.getString(R.string.msg_vehicle_detected, distance)
            "barricade", "pole" -> localeContext.getString(R.string.msg_obstacle_detected, distance)
            "chair", "table", "bench" -> localeContext.getString(R.string.msg_object_detected, distance)
            else -> localeContext.getString(R.string.msg_unknown_detected, objectName, distance)
        }
    }

    private fun vibrateAlert() {
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(200)
        }
    }

    private fun hideVisualElements(hide: Boolean) {
        binding.viewFinder.visibility = if (hide) View.GONE else View.VISIBLE
        binding.overlay.visibility = if (hide) View.GONE else View.VISIBLE
        binding.inferenceTime.visibility = if (hide) View.GONE else View.VISIBLE
    }

    private fun showLanguagePicker() {
        val currentLanguage = languageManager.getCurrentLanguage()
        var tempSelection = currentLanguage
        val currentIndex = languageCodes.indexOf(currentLanguage).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(this)
            .setTitle(R.string.language_picker_title)
            .setSingleChoiceItems(languageNames, currentIndex) { _, which ->
                tempSelection = languageCodes[which]
            }
            .setPositiveButton(R.string.language_picker_confirm) { dialog, _ ->
                languageManager.setLanguage(tempSelection)
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }
}
