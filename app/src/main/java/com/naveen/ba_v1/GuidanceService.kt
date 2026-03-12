package com.naveen.ba_v2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.util.Log
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleService
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import android.os.VibrationEffect
import android.os.Vibrator
import com.naveen.ba_v2.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GuidanceService : LifecycleService(), Detector.DetectorListener {

	private var cameraProvider: ProcessCameraProvider? = null
	private var imageAnalyzer: ImageAnalysis? = null
	private lateinit var detector: Detector
	private lateinit var cameraExecutor: ExecutorService

	private var bitmapBuffer: Bitmap? = null
	private val transformMatrix = Matrix()

	private lateinit var speechCoordinator: SpeechCoordinator
	private lateinit var languageManager: LanguageManager
	private lateinit var appStateManager: AppStateManager
	private lateinit var hazardAnalyzer: HazardAnalyzer
	private var localeContext: Context = this

	private var frameCounter = 0

	override fun onCreate() {
		super.onCreate()
		
		// Initialize coordinators and managers
		speechCoordinator = SpeechCoordinator.getInstance(applicationContext)
		languageManager = LanguageManager.getInstance(applicationContext)
		appStateManager = AppStateManager.getInstance()
		hazardAnalyzer = HazardAnalyzer(Constants.HARMFUL_CLASSES)
		
		// Setup locale context from language manager
		localeContext = languageManager.createLocaleContext(this)
		languageManager.addLanguageListener { languageCode ->
			localeContext = languageManager.createLocaleContext(this)
			speechCoordinator.updateLanguage(languageCode)
		}

		detector = Detector(applicationContext, Constants.MODEL_PATH, Constants.LABELS_PATH, this)
		detector.setup()
		cameraExecutor = Executors.newSingleThreadExecutor()

		startInForeground()
		
		// Set state to GUIDANCE_RUNNING
		appStateManager.setState(AppState.GUIDANCE_RUNNING)
		
		startCamera()
		
		// Announce startup after a delay to ensure TTS is ready
		android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
			if (appStateManager.getState() == AppState.GUIDANCE_RUNNING) {
				val startupMessage = localeContext.getString(R.string.msg_guidance_started)
				speechCoordinator.speakInfo(startupMessage)
				Log.d(TAG, "Guidance service startup message sent: $startupMessage")
			}
		}, 2000)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		// Language is managed by LanguageManager, no need to read from intent
		// Service will use current language from LanguageManager
		Log.d(TAG, "GuidanceService onStartCommand - current language: ${languageManager.getCurrentLanguage()}")
		return START_STICKY
	}

	private fun startInForeground() {
		val channelId = ensureNotificationChannel()
		val intent = Intent(this, MainActivity::class.java)
		val pendingIntent = PendingIntent.getActivity(
			this, 0, intent,
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
		)
		val notification: Notification = NotificationCompat.Builder(this, channelId)
			.setContentTitle(getString(R.string.app_name))
			.setContentText(getString(R.string.notif_running))
			.setSmallIcon(R.mipmap.ic_launcher)
			.setContentIntent(pendingIntent)
			.setOngoing(true)
			.build()
		startForeground(NOTIF_ID, notification)
	}

	private fun ensureNotificationChannel(): String {
		val channelId = NOTIF_CHANNEL_ID
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(channelId, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
			channel.description = getString(R.string.notif_channel_desc)
			val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
			nm.createNotificationChannel(channel)
		}
		return channelId
	}

	private fun startCamera() {
		val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
		cameraProviderFuture.addListener({
			try {
				cameraProvider = cameraProviderFuture.get()
				bindAnalyzer()
			} catch (e: Exception) {
				Log.e(TAG, "Camera provider initialization failed", e)
			}
		}, ContextCompat.getMainExecutor(this))
	}

	private fun bindAnalyzer() {
		val provider = cameraProvider ?: return
		
		// Check state before binding
		if (appStateManager.getState() != AppState.GUIDANCE_RUNNING) {
			Log.w(TAG, "State changed during camera binding, aborting")
			return
		}

		imageAnalyzer = ImageAnalysis.Builder()
			.setTargetAspectRatio(AspectRatio.RATIO_4_3)
			.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
			.setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
			.setTargetResolution(android.util.Size(Constants.SERVICE_CAMERA_WIDTH, Constants.SERVICE_CAMERA_HEIGHT))
			.build()

		imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
			// Check state before processing frame
			if (appStateManager.getState() != AppState.GUIDANCE_RUNNING) {
				imageProxy.close()
				return@setAnalyzer
			}

			// Frame skipping: only process every Nth frame
			frameCounter++
			if (frameCounter % Constants.SERVICE_FRAME_SKIP != 0) {
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

			val rotation = imageProxy.imageInfo.rotationDegrees
			val bitmapToDetect = if (rotation != 0) {
				transformMatrix.reset()
				transformMatrix.postRotate(rotation.toFloat())
				Bitmap.createBitmap(localBuffer, 0, 0, localBuffer.width, localBuffer.height, transformMatrix, true)
			} else {
				localBuffer
			}

			imageProxy.close()
			detector.detect(bitmapToDetect)

			// Clean up rotated bitmap if we created one
			if (rotation != 0 && bitmapToDetect != localBuffer) {
				bitmapToDetect.recycle()
			}
		}

		try {
			provider.unbindAll()
			provider.bindToLifecycle(
				this,
				CameraSelector.DEFAULT_BACK_CAMERA,
				imageAnalyzer
			)
		} catch (e: Exception) {
			Log.e(TAG, "Service analyzer bind failed", e)
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		try {
			cameraProvider?.unbindAll()
		} catch (e: Throwable) {
			Log.e(TAG, "Error unbinding camera", e)
		}
		cameraExecutor.shutdown()
		detector.clear()
		
		// Reset state to CAMERA_PREVIEW so Activity can resume camera
		appStateManager.setState(AppState.CAMERA_PREVIEW)
		
		// Don't cleanup speechCoordinator - it's a singleton, Activity may still need it
		Log.d(TAG, "GuidanceService destroyed")
	}

	override fun onEmptyDetect() {
		// Don't speak on empty detect - only speak when guidance is needed
	}

	override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
		// Only process if in GUIDANCE_RUNNING state
		if (appStateManager.getState() != AppState.GUIDANCE_RUNNING) {
			return
		}

		processDetections(boundingBoxes)
	}

	private fun processDetections(boundingBoxes: List<BoundingBox>) {
		// Generate hazard ID for STOP deduplication
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

	companion object {
		private const val TAG = "GuidanceService"
		private const val NOTIF_ID = 101
		private const val NOTIF_CHANNEL_ID = "guidance_channel"
		const val EXTRA_LANGUAGE_CODE = "extra_language_code" // Kept for backwards compatibility, but not used
	}
}

enum class GuidanceCommand {
	MoveLeft, MoveRight, Continue, Stop, SlightLeft, SlightRight
}

object GuidanceLogic {
	fun decideCommand(boundingBoxes: List<BoundingBox>): GuidanceCommand {
		if (boundingBoxes.isEmpty()) return GuidanceCommand.Continue
		// Compute density per third using area as proximity weight
		var left = 0f
		var center = 0f
		var right = 0f
		for (b in boundingBoxes) {
			val cx = b.cx
			val area = b.w * b.h
			when {
				cx < 1f / 3f -> left += area
				cx < 2f / 3f -> center += area
				else -> right += area
			}
		}
		val maxSide = maxOf(left, center, right)
		val blocked = center > 0.18f || maxSide > 0.35f
		if (blocked && center > left && center > right) return GuidanceCommand.Stop
		return when {
			left > right * 1.25f -> GuidanceCommand.MoveRight
			right > left * 1.25f -> GuidanceCommand.MoveLeft
			center > 0.15f && left < right -> GuidanceCommand.SlightLeft
			center > 0.15f && right < left -> GuidanceCommand.SlightRight
			else -> GuidanceCommand.Continue
		}
	}
}
