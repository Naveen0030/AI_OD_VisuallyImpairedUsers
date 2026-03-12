package com.naveen.ba_v2

object Constants {
    // Model paths
    const val MODEL_PATH = "best_float16.tflite"
    const val LABELS_PATH = "labels.txt"
    
    // Speech cooldowns (milliseconds)
    const val OBJECT_SPEECH_COOLDOWN_MS = 2500L
    const val NAVIGATION_COMMAND_COOLDOWN_MS = 2000L
    const val STOP_COMMAND_COOLDOWN_MS = 800L
    const val CONTINUE_COMMAND_COOLDOWN_MS = 5000L
    const val STOP_DEDUP_WINDOW_MS = 3000L
    
    // Frame processing
    const val SERVICE_FRAME_SKIP = 2
    
    // Hazard tracking
    const val HAZARD_TRACK_STALE_MS = 1500L
    
    // Camera resolution (service)
    const val SERVICE_CAMERA_WIDTH = 640
    const val SERVICE_CAMERA_HEIGHT = 480
    
    // Detection thresholds
    const val CONFIDENCE_THRESHOLD = 0.3f
    const val IOU_THRESHOLD = 0.5f
    
    // Harmful classes for hazard detection
    val HARMFUL_CLASSES = setOf(
        "auto", "barricade", "bicycle", "bus", "car", "cow", "dog", 
        "horse", "motorcycle", "person", "truck"
    )
}
