package com.burakotlu.betterdo

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class VideoFlowTest {
    @get:Rule val rule = createComposeRule()
    private val server = MockWebServer()
    private val token = "test-service-token-" + "a".repeat(32)
    private val posts = AtomicInteger()
    @Volatile private var status: String? = null

    @After fun close() { server.shutdown() }

    @Test fun pendingFailureRetryAndCompletedTranscript() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer $token") return MockResponse().setResponseCode(401)
                if (request.method == "POST") {
                    posts.incrementAndGet()
                    status = "pending"
                }
                val video = status?.let {
                    JSONObject().put("id", "a".repeat(32)).put("lessonId", "en-sneeze")
                        .put("status", it).put("provider", "mock").put("script", "Today we practice sneeze.")
                        .put("videoUrl", if (it == "completed") "/media/${"a".repeat(32)}.mp4" else JSONObject.NULL)
                        .put("errorMessage", if (it == "failed") "Video generation failed." else JSONObject.NULL)
                }
                if (request.path?.startsWith("/media/") == true) {
                    val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("mock-video.mp4").use { it.readBytes() }
                    return MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(bytes))
                }
                return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(JSONObject().put("video", video ?: JSONObject.NULL).toString())
            }
        }
        server.start()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = VideoSettings(context.applicationContext as Application)
        settings.configure(server.url("/").toString(), token)
        val lesson = JsonCodec.lessons(InstrumentationRegistry.getInstrumentation().context.assets.open("lessons.json").bufferedReader().use { it.readText() }).first()
        rule.setContent {
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AiVideoSection(lesson, settings, {}, {}) } }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Generate AI Video").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Generate AI Video").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Generating your lesson video...", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, posts.get())
        status = "failed"
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Try again").performScrollTo().performClick()
        rule.waitUntil(10_000) { posts.get() == 2 }
        status = "completed"
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Show transcript").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Show transcript").performScrollTo().performClick()
        rule.onNodeWithText("Today we practice sneeze.").assertExists()
        rule.waitUntil(15_000) { rule.onAllNodes(hasText("Play video") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Play video").performScrollTo().performClick()
        assertEquals(2, posts.get())
        // Avoid leaving this test's local endpoint in the shared application settings.
        context.getSharedPreferences("video_settings", 0).edit().clear().commit()
    }
}
