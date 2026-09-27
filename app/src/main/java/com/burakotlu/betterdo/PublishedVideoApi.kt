package com.burakotlu.betterdo

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Read-only client for completed, published lesson videos. No shared service credential is shipped. */
class PublishedVideoApi(val baseUrl: String, allowLocalHttp: Boolean = BuildConfig.DEBUG) {
    init { VideoApi.validateBaseUrl(baseUrl, allowLocalHttp) }

    fun get(lessonId: String): LessonVideo? {
        require(lessonId.matches(Regex("(en|de)-[a-z0-9]+(?:-[a-z0-9]+)*")))
        val connection = URL(baseUrl.trimEnd('/') + "/api/public/lessons/$lessonId/video").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            val code = connection.responseCode
            if (code != 200) throw IOException("Video service unavailable (HTTP $code).")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 65_536) throw IOException("Invalid video response.")
                    output.write(buffer, 0, count)
                }
            }
            val root = JSONObject(output.toString("UTF-8"))
            return if (root.isNull("video")) null else VideoApi.decode(root.getJSONObject("video"), lessonId).also {
                require(it.status == VideoStatus.COMPLETED && it.videoPath?.startsWith("/api/public/media/") == true)
            }
        } finally { connection.disconnect() }
    }

    fun mediaUrl(video: LessonVideo): String {
        val path = video.videoPath ?: throw IOException("The video is not ready.")
        require(path.matches(Regex("/api/public/media/[a-f0-9]{32}\\.mp4")))
        return baseUrl.trimEnd('/') + path
    }
}
