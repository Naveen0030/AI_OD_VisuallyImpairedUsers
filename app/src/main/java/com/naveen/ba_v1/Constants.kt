package com.naveen.ba_v2

object Constants {
    const val MODEL_PATH = "best_float16.tflite"
    const val LABELS_PATH = "labels.txt"

    const val NAVIGATION_REPEAT_COOLDOWN_MS = 4000L
    const val STOP_DEDUP_WINDOW_MS = 3000L
    const val INFO_SPEECH_COOLDOWN_MS = 5000L
    const val NAVIGATION_SUPPRESS_INFO_MS = 3000L
    const val CONTINUE_ANNOUNCE_COOLDOWN_MS = 8000L

    const val SERVICE_FRAME_SKIP = 3

    const val HAZARD_TRACK_STALE_MS = 1500L

    const val SERVICE_CAMERA_WIDTH = 640
    const val SERVICE_CAMERA_HEIGHT = 480

    const val CONFIDENCE_THRESHOLD = 0.35f
    const val IOU_THRESHOLD = 0.5f

    val HARMFUL_CLASSES = setOf(
        "auto", "barricade", "bicycle", "bus", "car", "cow", "dog",
        "horse", "motorcycle", "person", "truck"
    )
}
