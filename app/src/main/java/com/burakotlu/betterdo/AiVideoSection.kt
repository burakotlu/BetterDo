package com.burakotlu.betterdo

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
fun VideoSettingsDialog(settings: VideoSettings, dismiss: () -> Unit) {
    var url by remember { mutableStateOf(settings.serverUrl) }
    var token by remember { mutableStateOf(settings.accessToken) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Video settings") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect to your video service. Use its access token, not an AI provider API key. The token stays in memory until the app closes.")
            OutlinedTextField(url, { url = it }, label = { Text("Video server URL") }, singleLine = true)
            OutlinedTextField(token, { token = it }, label = { Text("Service access token") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            try { settings.configure(url, token); dismiss() }
            catch (exception: Exception) { error = "Enter a valid server URL and a service access token of at least 32 characters." }
        }) { Text("Connect") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
fun AiVideoSection(lesson: Lesson, settings: VideoSettings, configure: () -> Unit, stopSpeech: () -> Unit) {
    key(lesson.id, settings.serverUrl, settings.accessToken) {
        var video by remember { mutableStateOf<LessonVideo?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var requestError by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var checked by remember { mutableStateOf(false) }
        var refresh by remember { mutableIntStateOf(0) }
        var showScript by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val lifecycle = LocalLifecycleOwner.current
        val api = remember(settings.serverUrl, settings.accessToken) {
            if (settings.serverUrl.isBlank() || settings.accessToken.isBlank()) null
            else runCatching { VideoApi(settings.serverUrl, settings.accessToken) }.getOrNull()
        }
        LaunchedEffect(api, refresh) {
            if (api != null) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                do {
                    try {
                        val result = withContext(Dispatchers.IO) { api.get(lesson.id) }
                        if (!busy) video = result
                        checked = true
                        error = null
                    } catch (exception: CancellationException) { throw exception }
                    catch (exception: Exception) { error = "Could not check your video. Check your connection and Video settings."; break }
                    if (video?.status !in listOf(VideoStatus.PENDING, VideoStatus.PROCESSING) && !busy) break
                    delay(4_000)
                } while (true)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("AI Video", style = MaterialTheme.typography.titleLarge)
                Text("Watch an AI teacher explain this word in a short video.")
                if (api == null) {
                    Text("Connect a video service to watch or generate lesson videos.")
                    OutlinedButton(onClick = configure) { Text("Video settings") }
                } else {
                    if (video?.mock == true) Text("Mock preview: sample video only. No AI generation or charges.")
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    requestError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (error != null) OutlinedButton(onClick = { refresh++ }, enabled = !busy) { Text("Check video status") }
                    when {
                        busy || video?.status in listOf(VideoStatus.PENDING, VideoStatus.PROCESSING) -> {
                            CircularProgressIndicator(Modifier.size(28.dp))
                            Text("Generating your lesson video... You can leave this lesson and return later.")
                        }
                        video?.status == VideoStatus.COMPLETED -> {
                            Text(lesson.word, style = MaterialTheme.typography.headlineSmall)
                            Text(lesson.pronunciation)
                            LessonVideoPlayer(api, video!!, stopSpeech)
                            TextButton(onClick = { showScript = !showScript }) { Text(if (showScript) "Hide transcript" else "Show transcript") }
                            if (showScript) Text(video!!.script)
                        }
                        checked && error == null -> {
                            val failed = video?.status == VideoStatus.FAILED
                            if (failed) Text(video?.error ?: "Video generation failed.", color = MaterialTheme.colorScheme.error)
                            else Text("Generation may use your video service's paid allowance.")
                            Button(onClick = {
                                busy = true
                                requestError = null
                                scope.launch {
                                    try { video = withContext(Dispatchers.IO) { api.generate(lesson.id, failed) }; error = null }
                                    catch (exception: CancellationException) { throw exception }
                                    catch (exception: Exception) { requestError = if (exception is IOException) exception.message else "Could not request this video. Check its status before trying again." }
                                    finally { busy = false; refresh++ }
                                }
                            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (failed) "Try again" else "Generate AI Video") }
                        }
                        error == null -> { CircularProgressIndicator(Modifier.size(28.dp)); Text("Checking for an existing video...") }
                    }
                    TextButton(onClick = configure) { Text("Video settings") }
                }
            }
        }
    }
}

@Composable
private fun LessonVideoPlayer(api: VideoApi, video: LessonVideo, stopSpeech: () -> Unit) {
    var player by remember { mutableStateOf<VideoView?>(null) }
    var playbackError by remember(video.id) { mutableStateOf(false) }
    var ready by remember(video.id) { mutableStateOf(false) }
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
                contentDescription = "Lesson video. Use the playback controls to play, pause, or seek."
                setMediaController(MediaController(context).also { it.setAnchorView(this) })
                setOnPreparedListener { ready = true; seekTo(1) }
                setOnErrorListener { _, _, _ -> playbackError = true; true }
                setVideoURI(Uri.parse(api.mediaUrl(video)), api.mediaHeaders())
                player = this
            }
        }, modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f))
    }
    Button(onClick = { player?.start() }, enabled = ready && !playbackError) { Text("Play video") }
    if (playbackError) {
        Text("The video could not be played. Check your connection.")
        TextButton(onClick = { playbackError = false; ready = false; reload++ }) { Text("Reload video") }
    }
}
