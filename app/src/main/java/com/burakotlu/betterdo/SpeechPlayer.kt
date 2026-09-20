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
    private var closed = false
    private val engine = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (!ready && !closed) report("Ses motoru başlatılamadı. Android konuşma ayarlarını kontrol et.")
    }

    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit
            @Deprecated("Android compatibility callback")
            override fun onError(utteranceId: String?) { report("Ses oynatılamadı. Dil ses paketini ve medya sesini kontrol et.") }
        })
    }

    private fun report(text: String) { handler.post { if (!closed) message(text) } }

    fun speak(text: String, language: String, slow: Boolean = false) {
        if (!ready || closed) { report("Ses motoru henüz hazır değil. Birkaç saniye sonra tekrar dene."); return }
        val locale = if (language == "de") Locale.GERMANY else Locale.UK
        val support = engine.setLanguage(locale)
        if (support == TextToSpeech.LANG_MISSING_DATA || support == TextToSpeech.LANG_NOT_SUPPORTED) {
            report("Bu dilin ses paketi eksik. Android Ayarlar → Metin okuma bölümünden İngilizce veya Almanca sesini yükle.")
            return
        }
        engine.setSpeechRate(if (slow) .65f else .9f)
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString()) == TextToSpeech.ERROR) report("Ses başlatılamadı. Android konuşma ayarlarını kontrol et.")
    }

    fun stop() { engine.stop() }
    fun close() { closed = true; ready = false; engine.stop(); engine.shutdown(); handler.removeCallbacksAndMessages(null) }
}
