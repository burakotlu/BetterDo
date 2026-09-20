package com.burakotlu.betterdo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

class SpeechPlayer(context: Context, private val message: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var ready = false
    private var failed = false
    private var closed = false
    private val engine = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        failed = !ready
    }

    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit
            @Deprecated("Android compatibility callback")
            override fun onError(utteranceId: String?) { report("Audio could not be played. Check the language voice pack and media volume.") }
        })
    }

    private fun report(text: String) { handler.post { if (!closed) message(text) } }

    fun speak(text: String, language: String, slow: Boolean = false) {
        if (!ready || closed) {
            report(if (failed) "The speech engine could not start. Check your Android text-to-speech settings." else "The speech engine is not ready yet. Try again in a few seconds.")
            return
        }
        val locale = if (language == "de") Locale.GERMANY else Locale.UK
        val support = engine.setLanguage(locale)
        if (support == TextToSpeech.LANG_MISSING_DATA || support == TextToSpeech.LANG_NOT_SUPPORTED) {
            report("The voice pack for this language is missing. Install an English or German voice in Android Settings → Text-to-speech.")
            return
        }
        engine.setSpeechRate(if (slow) .65f else .9f)
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString()) == TextToSpeech.ERROR) report("Audio could not start. Check your Android text-to-speech settings.")
    }

    fun stop() { engine.stop() }
    fun close() { closed = true; ready = false; engine.stop(); engine.shutdown(); handler.removeCallbacksAndMessages(null) }
}
