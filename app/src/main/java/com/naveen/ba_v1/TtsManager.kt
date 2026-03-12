package com.naveen.ba_v2

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TtsManager(private val context: Context, initialLanguageCode: String? = null) {

    private var tts: TextToSpeech? = null
    private var language: Locale = Locale.ENGLISH
    @Volatile private var ready = false
    private var preferredLanguageCode: String? = initialLanguageCode
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<Pair<String, Boolean>>()

    init { initTts() }

    private fun initTts() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                language = resolveLocale(preferredLanguageCode)
                    ?: resolveLocale(Locale.getDefault().language)
                    ?: Locale.ENGLISH

                var result = tts?.setLanguage(language)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Language $language not supported, falling back to English")
                    language = Locale.ENGLISH
                    result = tts?.setLanguage(Locale.ENGLISH)
                }

                ready = result == TextToSpeech.LANG_AVAILABLE || result == TextToSpeech.LANG_COUNTRY_AVAILABLE

                if (ready) {
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    tts?.setSpeechRate(0.92f)
                    tts?.setPitch(1.0f)
                    Log.d(TAG, "TTS ready. Language: $language")
                    drainPending()
                } else {
                    Log.e(TAG, "TTS language $language not available")
                }
            } else {
                ready = false
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    fun speakFlush(text: String) {
        if (text.isBlank()) return
        synchronized(pending) {
            pending.clear()
            pending.addLast(Pair(text, true))
        }
        if (ready) drainPending()
    }

    fun speakAdd(text: String) {
        if (text.isBlank()) return
        synchronized(pending) { pending.addLast(Pair(text, false)) }
        if (ready) drainPending()
    }

    private fun drainPending() {
        synchronized(pending) {
            while (ready && pending.isNotEmpty()) {
                val (text, flush) = pending.removeFirst()
                val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                mainHandler.post {
                    try {
                        val r = tts?.speak(text, mode, null, "utt_${System.currentTimeMillis()}")
                        if (r == TextToSpeech.ERROR) Log.e(TAG, "TTS speak error: $text")
                    } catch (e: Exception) {
                        Log.e(TAG, "TTS speak exception", e)
                    }
                }
            }
        }
    }

    fun updatePreferredLanguage(languageCode: String?) {
        preferredLanguageCode = languageCode
        val newLocale = resolveLocale(languageCode) ?: return
        tts?.stop()

        val update: () -> Unit = {
            try {
                language = newLocale
                var result = tts?.setLanguage(language)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    language = Locale.ENGLISH
                    result = tts?.setLanguage(Locale.ENGLISH)
                }
                ready = result == TextToSpeech.LANG_AVAILABLE || result == TextToSpeech.LANG_COUNTRY_AVAILABLE
                if (ready) {
                    tts?.setSpeechRate(0.92f)
                    tts?.setPitch(1.0f)
                    Log.d(TAG, "TTS language updated to $language")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "TTS language update error", t)
                ready = false
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) update() else mainHandler.post(update)
    }

    fun stop() {
        tts?.stop()
        synchronized(pending) { pending.clear() }
    }

    fun cleanup() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        synchronized(pending) { pending.clear() }
    }

    private fun resolveLocale(code: String?): Locale? = when (code?.lowercase(Locale.US)) {
        "en" -> Locale.ENGLISH
        "te" -> Locale("te", "IN")
        "hi" -> Locale("hi", "IN")
        "ta" -> Locale("ta", "IN")
        "kn" -> Locale("kn", "IN")
        else -> null
    }

    companion object { private const val TAG = "TtsManager" }
}
