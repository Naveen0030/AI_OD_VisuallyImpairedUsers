package com.naveen.ba_v2

import android.content.Context
import android.util.Log

class SpeechCoordinator private constructor(context: Context) {

    private val tts: TtsManager = TtsManager(context.applicationContext, null)

    private val commandLastSpokenMs = mutableMapOf<String, Long>()

    @Volatile private var lastInfoTimeMs: Long = 0L
    @Volatile private var lastNavigationTimeMs: Long = 0L
    @Volatile private var lastStopHazardId: String? = null
    @Volatile private var lastStopTimeMs: Long = 0L

    fun speakCritical(text: String, hazardId: String? = null) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()

        if (hazardId != null && hazardId == lastStopHazardId
            && now - lastStopTimeMs < Constants.STOP_DEDUP_WINDOW_MS) {
            Log.d(TAG, "CRITICAL suppressed (duplicate hazard): $hazardId")
            return
        }

        lastStopHazardId = hazardId
        lastStopTimeMs = now
        lastNavigationTimeMs = now

        Log.d(TAG, "CRITICAL speak: $text")
        tts.speakFlush(text)
    }

    fun speakNavigation(text: String, isContinue: Boolean = false) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()

        val cooldown = if (isContinue) Constants.CONTINUE_ANNOUNCE_COOLDOWN_MS
                       else Constants.NAVIGATION_REPEAT_COOLDOWN_MS

        val lastSpoken = commandLastSpokenMs[text] ?: 0L
        if (now - lastSpoken < cooldown) {
            Log.d(TAG, "NAVIGATION suppressed (cooldown): $text")
            return
        }

        commandLastSpokenMs[text] = now
        lastNavigationTimeMs = now

        Log.d(TAG, "NAVIGATION speak: $text")
        tts.speakAdd(text)
    }

    fun speakInfo(text: String) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()

        if (now - lastNavigationTimeMs < Constants.NAVIGATION_SUPPRESS_INFO_MS) {
            Log.d(TAG, "INFO suppressed (nav active): $text")
            return
        }

        if (now - lastInfoTimeMs < Constants.INFO_SPEECH_COOLDOWN_MS) {
            Log.d(TAG, "INFO suppressed (cooldown): $text")
            return
        }

        lastInfoTimeMs = now
        Log.d(TAG, "INFO speak: $text")
        tts.speakAdd(text)
    }

    fun updateLanguage(languageCode: String) {
        tts.updatePreferredLanguage(languageCode)
    }

    fun cleanup() {
        tts.cleanup()
        commandLastSpokenMs.clear()
        lastInfoTimeMs = 0L
        lastNavigationTimeMs = 0L
        lastStopHazardId = null
        lastStopTimeMs = 0L
    }

    companion object {
        private const val TAG = "SpeechCoordinator"

        @Volatile private var INSTANCE: SpeechCoordinator? = null

        fun getInstance(context: Context): SpeechCoordinator =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpeechCoordinator(context.applicationContext).also { INSTANCE = it }
            }
    }
}
