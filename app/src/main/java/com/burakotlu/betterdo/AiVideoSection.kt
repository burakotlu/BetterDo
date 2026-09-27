package com.burakotlu.betterdo

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AiVideoSection(lesson: Lesson, stopSpeech: () -> Unit, api: PublishedVideoApi? = remember(BuildConfig.VIDEO_PLAYBACK_URL, BuildConfig.BACKEND_URL) {
    (BuildConfig.VIDEO_PLAYBACK_URL.ifBlank { BuildConfig.BACKEND_URL }).takeIf { it.isNotBlank() }?.let { PublishedVideoApi(it) }
}) {
    var video by remember(lesson.id, api) { mutableStateOf<LessonVideo?>(null) }
    var loading by remember(lesson.id, api) { mutableStateOf(true) }
    var error by remember(lesson.id, api) { mutableStateOf<String?>(null) }
    var refresh by remember(lesson.id, api) { mutableIntStateOf(0) }
    var showScript by remember(lesson.id) { mutableStateOf(false) }

    LaunchedEffect(lesson.id, api, refresh) {
        if (api == null) { loading = false; return@LaunchedEffect }
        loading = true
        try {
            video = withContext(Dispatchers.IO) { api.get(lesson.id) }
            error = null
        } catch (exception: CancellationException) { throw exception }
        catch (exception: Exception) { error = "The video could not be loaded. Check your connection." }
        finally { loading = false }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            Text("AI Video", style = MaterialTheme.typography.titleLarge)
            when {
                api == null -> Text("A video is not available for this lesson yet.")
                loading -> { CircularProgressIndicator(Modifier.size(28.dp)); Text("Loading lesson video...") }
                error != null -> {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { refresh++ }) { Text("Try again") }
                }
                video == null -> {
                    Text("A video for this word has not been published yet.")
                    OutlinedButton(onClick = { refresh++ }) { Text("Check again") }
                }
                else -> {
                    if (video!!.mock) Text("Mock preview: sample video only.")
                    Text(lesson.word, style = MaterialTheme.typography.headlineSmall)
                    Text(lesson.pronunciation)
                    key(video!!.id) { LessonVideoPlayer(api, video!!, stopSpeech) }
                    TextButton(onClick = { showScript = !showScript }) { Text(if (showScript) "Hide transcript" else "Show transcript") }
                    if (showScript) Text(video!!.script)
                }
            }
        }
    }
}

@Composable
private fun LessonVideoPlayer(api: PublishedVideoApi, video: LessonVideo, stopSpeech: () -> Unit) {
    var player by remember { mutableStateOf<VideoView?>(null) }
    var playbackError by remember(video.id) { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, player) {
        val activePlayer = player
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) activePlayer?.pause()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); activePlayer?.stopPlayback() }
    }
    key(video.id, reload) {
        AndroidView(factory = { context ->
            object : VideoView(context) {
                override fun start() { stopSpeech(); super.start() }
            }.apply {
                contentDescription = "Lesson video. Use playback controls to pause or seek."
                setMediaController(MediaController(context).also { it.setAnchorView(this) })
                setOnPreparedListener { start() }
                setOnErrorListener { _, _, _ -> playbackError = true; true }
                setVideoURI(Uri.parse(api.mediaUrl(video)))
                player = this
            }
        }, modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f))
    }
    if (playbackError) {
        Text("The video could not be played. Check your connection.")
        TextButton(onClick = { playbackError = false; reload++ }) { Text("Reload video") }
    }
}
