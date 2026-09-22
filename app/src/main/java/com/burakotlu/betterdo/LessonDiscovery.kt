package com.burakotlu.betterdo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@Composable
fun LessonDiscovery(language: String, settings: VideoSettings, configure: () -> Unit, dismiss: () -> Unit, open: (String) -> Unit) {
    val api = remember(settings.serverUrl, settings.accessToken) {
        runCatching { VideoApi(settings.serverUrl, settings.accessToken) }.getOrNull()
    }
    val preferences = LocalContext.current.getSharedPreferences("lesson_requests", 0)
    val requestKey = settings.serverUrl + ":" + language
    var categories by remember(api) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var enabled by remember(api) { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf("") }
    var level by rememberSaveable { mutableStateOf("A1") }
    var request by remember(api, language) { mutableStateOf(preferences.getString(requestKey, null)) }
    var retry by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lesson by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(api) {
        if (api != null) try {
            val data = withContext(Dispatchers.IO) { api.lessonsRequest("/api/categories") }
            enabled = data.getBoolean("generationEnabled")
            val items = data.getJSONArray("categories")
            categories = (0 until items.length().coerceAtMost(20)).map {
                items.getJSONObject(it).let { item -> item.getString("id") to item.getString("title") }
            }
            if (category.isEmpty()) category = categories.firstOrNull()?.first.orEmpty()
        } catch (exception: CancellationException) { throw exception }
        catch (exception: Exception) { error = "Could not load topics. Check Service settings and your connection." }
    }
    LaunchedEffect(api, request, retry) {
        val saved = request
        if (api != null && saved != null) {
            busy = true
            failed = false
            error = null
            try {
                val payload = JSONObject(saved)
                var job = withContext(Dispatchers.IO) { api.lessonsRequest("/api/lesson-jobs", payload).getJSONObject("job") }
                var checks = 0
                while (job.getString("status") in listOf("pending", "processing") && checks++ < 200) {
                    delay(3_000)
                    job = withContext(Dispatchers.IO) { api.lessonsRequest("/api/lesson-jobs/" + payload.getString("id")).getJSONObject("job") }
                }
                when (job.getString("status")) {
                    "completed" -> {
                        val body = JSONObject().put("version", 1).put("lessons", JSONArray().put(job.getJSONObject("lesson"))).toString()
                        val parsed = JsonCodec.lessons(body).single()
                        require(parsed.language == language)
                        lesson = body
                    }
                    "failed" -> { failed = true; error = job.optString("error", "Lesson generation failed.") }
                    else -> error = "Your lesson is still being generated. Check again shortly."
                }
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { error = exception.message?.take(300) ?: "Could not retrieve this lesson. Check again." }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Explore topics") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose a topic for a new ${if (language == "en") "English" else "German"} lesson. AI creates one word, examples, a dialogue, and a quiz.")
            if (api == null) {
                Text("Connect a learning service to generate lessons online.")
                OutlinedButton(onClick = configure) { Text("Service settings") }
            } else {
                if (!enabled && categories.isNotEmpty()) Text("The server needs an installed Ollama model before it can generate lessons.")
                if (request == null) {
                    Text("Suggested topics", style = MaterialTheme.typography.titleMedium)
                    categories.forEach { (id, title) ->
                        FilterChip(selected = category == id, onClick = { category = id }, label = { Text(title) }, modifier = Modifier.fillMaxWidth())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("A1", "A2", "B1").forEach { item -> FilterChip(selected = level == item, onClick = { level = item }, label = { Text(item) }) }
                    }
                }
                if (busy) { CircularProgressIndicator(Modifier.size(28.dp)); Text("Creating your lesson... You can close this screen and return later.") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                lesson?.let { Text("Your lesson is ready. AI-generated content has been checked for structure, but may contain language mistakes.") }
                if (error != null) TextButton(onClick = configure) { Text("Service settings") }
            }
        }
    }, confirmButton = {
        when {
            lesson != null -> TextButton(onClick = { open(lesson!!); preferences.edit().remove(requestKey).apply() }) { Text("Open lesson") }
            failed -> TextButton(onClick = { request = null; preferences.edit().remove(requestKey).apply(); error = null; failed = false }) { Text("Choose another lesson") }
            request != null -> TextButton(onClick = { retry++ }, enabled = !busy) { Text("Check again") }
            else -> TextButton(onClick = {
                val body = JSONObject().put("id", UUID.randomUUID().toString().replace("-", ""))
                    .put("language", language).put("category", category).put("level", level).toString()
                preferences.edit().putString(requestKey, body).commit()
                request = body
            }, enabled = api != null && enabled && category.isNotBlank()) { Text("Create lesson") }
        }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("Close") } })
}
