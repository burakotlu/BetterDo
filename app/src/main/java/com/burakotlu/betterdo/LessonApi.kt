package com.burakotlu.betterdo

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The app uses its own server and the signed-in user's revocable session. */
class LessonApi(private val origin: String, private val token: () -> String?) {
    init { VideoApi.validateBaseUrl(origin, BuildConfig.DEBUG) }

    fun lessonsRequest(path: String, body: JSONObject? = null): JSONObject {
        require(path == "/api/categories" || path == "/api/catalog" || path == "/api/lesson-jobs" ||
            path.matches(Regex("/api/lesson-jobs/[a-f0-9]{32}")))
        val access = token() ?: throw IOException("Sign in to access new lessons.")
        val connection = URL(origin.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $access")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val output = ByteArrayOutputStream()
            stream?.use {
                val bytes = ByteArray(8192)
                while (true) {
                    val count = it.read(bytes)
                    if (count < 0) break
                    if (output.size() + count > 2_000_000) throw IOException("Lesson response is too large.")
                    output.write(bytes, 0, count)
                }
            }
            val result = JSONObject(output.toString("UTF-8"))
            if (status !in 200..299) throw IOException(result.optString("error", "Lesson service unavailable (HTTP $status).").take(300))
            return result
        } finally { connection.disconnect() }
    }
}
