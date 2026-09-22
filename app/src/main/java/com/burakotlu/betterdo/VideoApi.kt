package com.burakotlu.betterdo

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

enum class VideoStatus { PENDING, PROCESSING, COMPLETED, FAILED }

data class LessonVideo(
    val id: String,
    val status: VideoStatus,
    val videoPath: String?,
    val script: String,
    val mock: Boolean,
    val error: String?
)

/** Only the private backend access token reaches this client, never a provider API key. */
class VideoApi(val baseUrl: String, private val token: String, allowLocalHttp: Boolean = BuildConfig.DEBUG) {
    init {
        validateBaseUrl(baseUrl, allowLocalHttp)
        require(token.length >= 32 && token.all { it.code in 33..126 }) { "Enter a valid video service access token." }
    }

    fun get(lessonId: String): LessonVideo? = request(lessonId, false, false)
    fun generate(lessonId: String, retry: Boolean): LessonVideo? = request(lessonId, true, retry)
    fun mediaUrl(video: LessonVideo): String {
        val path = video.videoPath ?: throw IOException("The video is not ready.")
        require(path.matches(Regex("/media/[a-f0-9]{32}\\.mp4")))
        return baseUrl.trimEnd('/') + path
    }
    fun mediaHeaders(): Map<String, String> = mapOf("Authorization" to "Bearer $token")

    fun lessonsRequest(path: String, body: JSONObject? = null): JSONObject {
        require(path == "/api/categories" || path == "/api/catalog" || path == "/api/lesson-jobs" || path.matches(Regex("/api/lesson-jobs/[a-f0-9]{32}")))
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code !in listOf(200, 202, 400)) throw IOException("Could not reach the lesson service (HTTP $code). Check Service settings.")
            val stream = if (code == 400) connection.errorStream else connection.inputStream
            val output = java.io.ByteArrayOutputStream()
            stream.use {
                val bytes = ByteArray(8192)
                while (true) {
                    val count = it.read(bytes)
                    if (count < 0) break
                    if (output.size() + count > 2_000_000) throw IOException("Lesson response is too large.")
                    output.write(bytes, 0, count)
                }
            }
            val result = JSONObject(output.toString("UTF-8"))
            if (code == 400) throw IOException(result.optString("error", "Lesson request failed.").take(300))
            return result
        } finally { connection.disconnect() }
    }

    private fun request(lessonId: String, create: Boolean, retry: Boolean): LessonVideo? {
        require(lessonId.matches(Regex("(en|de)-[a-z0-9]+(?:-[a-z0-9]+)*")))
        val connection = URL(baseUrl.trimEnd('/') + "/api/lessons/$lessonId/video").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (create) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(JSONObject().put("retry", retry).toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code == 401) throw IOException("Check your video service access token in Service settings.")
            if (code !in listOf(200, 202, 400)) throw IOException("Video service unavailable (HTTP $code). Try checking again.")
            val input = if (code == 400) connection.errorStream else connection.inputStream
            val raw = input.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 65_536) throw IOException("Invalid video service response.")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            val root = JSONObject(raw)
            if (code == 400) throw IOException(root.optString("error", "This video cannot be generated.").take(300))
            return if (root.isNull("video")) null else decode(root.getJSONObject("video"), lessonId)
        } finally { connection.disconnect() }
    }

    companion object {
        fun validateBaseUrl(value: String, allowLocalHttp: Boolean) {
            val url = URL(value)
            require(url.userInfo == null && url.query == null && url.ref == null && url.path in listOf("", "/")) { "Enter the server origin, without a path or credentials." }
            require(url.protocol == "https" || (allowLocalHttp && url.protocol == "http" && url.host in setOf("localhost", "127.0.0.1", "10.0.2.2"))) { "HTTPS is required. Debug builds also allow the local development server." }
        }

        fun decode(item: JSONObject, lessonId: String): LessonVideo {
            require(item.getString("lessonId") == lessonId)
            val status = VideoStatus.valueOf(item.getString("status").uppercase(java.util.Locale.ROOT))
            val path = if (item.isNull("videoUrl")) null else item.getString("videoUrl")
            if (path != null) require(path.matches(Regex("/media/[a-f0-9]{32}\\.mp4")))
            require(status != VideoStatus.COMPLETED || path != null)
            require(item.getString("script").length <= 1200)
            return LessonVideo(item.getString("id"), status, path, item.getString("script"), item.getString("provider") == "mock",
                if (item.isNull("errorMessage")) null else item.getString("errorMessage").take(300))
        }
    }
}
