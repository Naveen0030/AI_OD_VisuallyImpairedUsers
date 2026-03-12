package com.naveen.ba_v2

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.util.Log
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Centralized language management.
 * Single source of truth for selected language across the app.
 */
class LanguageManager private constructor(context: Context) {
    
    private val prefs: SharedPreferences
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    
    init {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Get current language code.
     */
    fun getCurrentLanguage(): String {
        return prefs.getString(KEY_LANGUAGE_CODE, Locale.getDefault().language) ?: "en"
    }
    
    /**
     * Set language code and notify listeners.
     */
    fun setLanguage(languageCode: String) {
        val oldCode = getCurrentLanguage()
        if (oldCode != languageCode) {
            prefs.edit().putString(KEY_LANGUAGE_CODE, languageCode).apply()
            Log.d(TAG, "Language changed: $oldCode → $languageCode")
            notifyListeners(languageCode)
        }
    }
    
    /**
     * Get Locale object for language code.
     */
    fun getLocaleForCode(code: String?): Locale? {
        return when (code?.lowercase(Locale.US)) {
            "en" -> Locale.ENGLISH
            "te" -> Locale("te", "IN")
            "hi" -> Locale("hi", "IN")
            "ta" -> Locale("ta", "IN")
            "kn" -> Locale("kn", "IN")
            else -> null
        }
    }
    
    /**
     * Create a ConfigurationContext for the current language.
     */
    fun createLocaleContext(context: Context): Context {
        val locale = getLocaleForCode(getCurrentLanguage()) ?: Locale.getDefault()
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
    
    /**
     * Add a listener for language changes.
     */
    fun addLanguageListener(listener: (String) -> Unit) {
        listeners.add(listener)
        // Immediately notify of current language
        listener(getCurrentLanguage())
    }
    
    /**
     * Remove a language listener.
     */
    fun removeLanguageListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }
    
    private fun notifyListeners(languageCode: String) {
        listeners.forEach { listener ->
            try {
                listener(languageCode)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying language listener", e)
            }
        }
    }
    
    companion object {
        private const val TAG = "LanguageManager"
        private const val PREFS_NAME = "ba_v2_prefs"
        private const val KEY_LANGUAGE_CODE = "language_code"
        
        @Volatile
        private var INSTANCE: LanguageManager? = null
        
        fun getInstance(context: Context): LanguageManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LanguageManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

