package com.naveen.ba_v2

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages application state to prevent camera and service collisions.
 * 
 * State transitions:
 * - IDLE → CAMERA_PREVIEW (on permissions granted)
 * - CAMERA_PREVIEW → GUIDANCE_RUNNING (Start button pressed)
 * - GUIDANCE_RUNNING → CAMERA_PREVIEW (End button pressed or service stopped)
 */
enum class AppState {
    IDLE,
    CAMERA_PREVIEW,
    GUIDANCE_RUNNING
}

class AppStateManager private constructor() {
    
    @Volatile
    private var currentState: AppState = AppState.IDLE
    
    private val listeners = CopyOnWriteArrayList<(AppState) -> Unit>()
    
    /**
     * Get current application state.
     */
    fun getState(): AppState = currentState
    
    /**
     * Set new application state.
     * Notifies all listeners of state change.
     */
    fun setState(newState: AppState) {
        if (currentState != newState) {
            val oldState = currentState
            currentState = newState
            Log.d(TAG, "State transition: $oldState → $newState")
            notifyListeners(newState)
        }
    }
    
    /**
     * Add a listener for state changes.
     */
    fun addStateListener(listener: (AppState) -> Unit) {
        listeners.add(listener)
        // Immediately notify of current state
        listener(currentState)
    }
    
    /**
     * Remove a state listener.
     */
    fun removeStateListener(listener: (AppState) -> Unit) {
        listeners.remove(listener)
    }
    
    private fun notifyListeners(state: AppState) {
        listeners.forEach { listener ->
            try {
                listener(state)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying state listener", e)
            }
        }
    }
    
    companion object {
        private const val TAG = "AppStateManager"
        
        @Volatile
        private var INSTANCE: AppStateManager? = null
        
        fun getInstance(): AppStateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppStateManager().also { INSTANCE = it }
            }
        }
    }
}

