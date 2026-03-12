package com.naveen.ba_v2

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TtsManager(private val context: Context, initialLanguageCode: String? = null) {

	private var textToSpeech: TextToSpeech? = null
	private var language: Locale = Locale.ENGLISH
	private var ready = false
	private var preferredLanguageCode: String? = initialLanguageCode
	private val mainHandler = Handler(Looper.getMainLooper())
	private val pendingMessages = ArrayDeque<String>()

	init {
		setupTextToSpeech()
	}

	private fun setupTextToSpeech() {
		textToSpeech = TextToSpeech(context.applicationContext) { status ->
			if (status == TextToSpeech.SUCCESS) {
				// Use preferred language if set, otherwise system default, fallback to English
				language = getLocaleForCode(preferredLanguageCode)
					?: getLocaleForCode(Locale.getDefault().language)
							?: Locale.ENGLISH

				var result = textToSpeech?.setLanguage(language)
				if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
					Log.w(TAG, "Language $language not supported, falling back to English")
					language = Locale.ENGLISH
					result = textToSpeech?.setLanguage(Locale.ENGLISH)
				}

				ready = (result == TextToSpeech.LANG_AVAILABLE || result == TextToSpeech.LANG_COUNTRY_AVAILABLE)

				if (ready) {
					val attributes = AudioAttributes.Builder()
						.setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
						.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
						.build()
					textToSpeech?.setAudioAttributes(attributes)
					textToSpeech?.setSpeechRate(0.95f)
					textToSpeech?.setPitch(1.0f)

					Log.d(TAG, "TTS initialized successfully. Language: $language, Ready: $ready")
					drainPendingMessages()
				} else {
					Log.e(TAG, "TTS initialized but language $language is not available")
				}
			} else {
				ready = false
				Log.e(TAG, "TTS initialization failed with status: $status")
			}
		}
	}

	fun speak(text: String) {
		if (text.isBlank()) {
			Log.w(TAG, "Attempted to speak blank text")
			return
		}
		enqueueMessage(text)
	}

	fun speakImmediate(message: String) {
		enqueueMessage(message)
	}

	private fun enqueueMessage(message: String) {
		Log.d(TAG, "Queueing speech message: '$message' (ready=$ready)")
		synchronized(pendingMessages) {
			pendingMessages.addLast(message)
		}
		if (ready) drainPendingMessages()
	}

	private fun drainPendingMessages() {
		synchronized(pendingMessages) {
			while (ready && pendingMessages.isNotEmpty()) {
				val next = pendingMessages.removeFirst()
				mainHandler.post {
					try {
						val result = textToSpeech?.speak(next, TextToSpeech.QUEUE_FLUSH, null, null)
						if (result == TextToSpeech.ERROR) {
							Log.e(TAG, "TTS speak returned ERROR for text: $next")
						}
					} catch (e: Exception) {
						Log.e(TAG, "TTS speak exception", e)
					}
				}
			}
		}
	}

	fun updatePreferredLanguage(languageCode: String?) {
		preferredLanguageCode = languageCode
		val newLocale = getLocaleForCode(languageCode)

		if (newLocale == null) {
			Log.w(TAG, "Invalid language code: $languageCode")
			return
		}

		textToSpeech?.stop()

		if (textToSpeech == null) {
			Log.d(TAG, "TTS not initialized yet, language preference stored: $newLocale")
			return
		}

		// ✅ FIXED: Explicitly declare lambda type returning Unit
		val updateLanguage: () -> Unit = {
			try {
				language = newLocale
				var result = textToSpeech?.setLanguage(language)

				if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
					Log.w(TAG, "Language $language not supported, falling back to English")
					language = Locale.ENGLISH
					result = textToSpeech?.setLanguage(Locale.ENGLISH)
				}

				ready = (result == TextToSpeech.LANG_AVAILABLE || result == TextToSpeech.LANG_COUNTRY_AVAILABLE)

				if (ready) {
					Log.d(TAG, "TTS language updated successfully to $language")
					val attributes = AudioAttributes.Builder()
						.setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
						.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
						.build()
					textToSpeech?.setAudioAttributes(attributes)
					textToSpeech?.setSpeechRate(0.95f)
					textToSpeech?.setPitch(1.0f)
				} else {
					Log.e(TAG, "Failed to set TTS language to $language, result: $result")
				}
			} catch (t: Throwable) {
				Log.e(TAG, "Exception while updating TTS language", t)
				ready = false
			}
		}

		// Run immediately or post to main thread
		if (Looper.myLooper() == Looper.getMainLooper()) {
			updateLanguage()
		} else {
			mainHandler.post(updateLanguage)
		}
	}

	private fun getLocaleForCode(code: String?): Locale? {
		return when (code?.lowercase(Locale.US)) {
			"en" -> Locale.ENGLISH
			"te" -> Locale("te", "IN")
			"hi" -> Locale("hi", "IN")
			"ta" -> Locale("ta", "IN")
			"kn" -> Locale("kn", "IN")
			else -> null
		}
	}

	fun stop() {
		textToSpeech?.stop()
		synchronized(pendingMessages) {
			pendingMessages.clear()
		}
	}

	fun cleanup() {
		textToSpeech?.stop()
		textToSpeech?.shutdown()
		textToSpeech = null
		ready = false
		pendingMessages.clear()
	}

	companion object {
		private const val TAG = "TtsManager"
	}
}
