package com.burakotlu.betterdo

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

class VideoSettings(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("video_settings", 0)
    var serverUrl by mutableStateOf(preferences.getString("server_url", "").orEmpty())
        private set
    // Session-only: never stored in preferences, saved-instance state, source code, or the APK.
    var accessToken by mutableStateOf("")
        private set

    fun configure(url: String, token: String) {
        VideoApi(url.trim(), token.trim())
        serverUrl = url.trim().trimEnd('/')
        accessToken = token.trim()
        preferences.edit().putString("server_url", serverUrl).apply()
    }
}
