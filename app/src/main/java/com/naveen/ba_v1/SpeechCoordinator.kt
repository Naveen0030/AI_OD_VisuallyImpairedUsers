package com.naveen.ba_v2

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralized speech coordinator that ensures zero speech collisions.
 * All speech requests must go through this coordinator.
 * 
 * Priority system:
 * - CRITICAL: STOP commands (interrupts everything)
 * - NAVIGATION: Move left/right commands (suppresses INFO)
 * - INFO: Object detection info (never interrupts NAVIGATION)
 */
class SpeechCoordinator private constructor(context: Context) {
    
    private val ttsManager: TtsManager
    private val isNavigationActive = AtomicBoolean(false)
    
    // STOP deduplication: track last STOP hazard to prevent repetition
    private var lastStopHazardId: String? = null
    private var lastStopTimeMs: Long = 0L
    
    // Track when navigation was last called to auto-clear after timeout
    @Volatile
    private var lastNavigationTimeMs: Long = 0L
    
    init {
        ttsManager = TtsManager(context.applicationContext, null)
    }
    
    /**
     * Speak a CRITICAL message (STOP commands).
     * Interrupts all other speech immediately.
     * Deduplicates repeated STOP commands for the same hazard.
     */
    fun speakCritical(text: String, hazardId: String? = null) {
        if (text.isBlank()) return
        
        val now = System.currentTimeMillis()
        
        // Deduplicate: don't repeat STOP for same hazard within window
        if (hazardId != null && hazardId == lastStopHazardId) {
            if (now - lastStopTimeMs < Constants.STOP_DEDUP_WINDOW_MS) {
                Log.d(TAG, "Suppressing duplicate STOP for hazard: $hazardId")
                return
            }
        }
        
        lastStopHazardId = hazardId
        lastStopTimeMs = now
        
        Log.d(TAG, "Speaking CRITICAL: $text")
        ttsManager.stop()
        isNavigationActive.set(false)
        lastNavigationTimeMs = 0L
        ttsManager.speakImmediate(text)
    }
    
    /**
     * Speak a NAVIGATION command (move left/right/continue).
     * Suppresses INFO messages but can be interrupted by CRITICAL.
     */
    fun speakNavigation(text: String) {
        if (text.isBlank()) return
        
        val now = System.currentTimeMillis()
        
        // If navigation wasn't active, stop any current speech (likely INFO)
        if (!isNavigationActive.get()) {
            ttsManager.stop()
        }
        
        Log.d(TAG, "Speaking NAVIGATION: $text")
        isNavigationActive.set(true)
        lastNavigationTimeMs = now
        ttsManager.speakImmediate(text)
    }
    
    /**
     * Speak an INFO message (object detection).
     * Only speaks if no NAVIGATION or CRITICAL is active.
     */
    fun speakInfo(text: String) {
        if (text.isBlank()) return
        
        val now = System.currentTimeMillis()
        
        // Clear navigation active flag if enough time has passed since last navigation command
        if (isNavigationActive.get()) {
            if (now - lastNavigationTimeMs > Constants.NAVIGATION_COMMAND_COOLDOWN_MS) {
                isNavigationActive.set(false)
                lastNavigationTimeMs = 0L
            } else {
                // Navigation was recently active, suppress INFO
                Log.d(TAG, "Suppressing INFO (navigation recently active): $text")
                return
            }
        }
        
        Log.d(TAG, "Speaking INFO: $text")
        ttsManager.speakImmediate(text)
    }
    
    /**
     * Update the language for TTS.
     */
    fun updateLanguage(languageCode: String) {
        ttsManager.updatePreferredLanguage(languageCode)
    }
    
    /**
     * Cleanup resources.
     */
    fun cleanup() {
        ttsManager.cleanup()
        isNavigationActive.set(false)
        lastNavigationTimeMs = 0L
        lastStopHazardId = null
        lastStopTimeMs = 0L
    }
    
    companion object {
        private const val TAG = "SpeechCoordinator"
        
        @Volatile
        private var INSTANCE: SpeechCoordinator? = null
        
        fun getInstance(context: Context): SpeechCoordinator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpeechCoordinator(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

