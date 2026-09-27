package com.burakotlu.betterdo

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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class VideoFlowTest {
    @get:Rule val rule = createComposeRule()
    private val server = MockWebServer()

    @After fun close() { server.shutdown() }

    @Test fun completedVideoLoadsWithoutTokenOrSettings() {
        val requests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != null || request.method != "GET") return MockResponse().setResponseCode(400)
                requests.incrementAndGet()
                if (request.path?.startsWith("/api/public/media/") == true) {
                    val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("mock-video.mp4").use { it.readBytes() }
                    return MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(bytes))
                }
                val video = JSONObject().put("id", "a".repeat(32)).put("lessonId", "en-sneeze")
                    .put("status", "completed").put("provider", "mock").put("script", "Today we practice sneeze.")
                    .put("videoUrl", "/api/public/media/${"a".repeat(32)}.mp4")
                    .put("errorMessage", JSONObject.NULL)
                return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(JSONObject().put("video", video).toString())
            }
        }
        server.start()
        val api = PublishedVideoApi(server.url("/").toString(), true)
        val lesson = JsonCodec.lessons(InstrumentationRegistry.getInstrumentation().context.assets.open("lessons.json").bufferedReader().use { it.readText() }).first()
        rule.setContent {
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AiVideoSection(lesson, {}, api) } }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Show transcript").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Show transcript").performScrollTo().performClick()
        rule.onNodeWithText("Today we practice sneeze.").assertExists()
        rule.onNodeWithText("Service settings").assertDoesNotExist()
        rule.onNodeWithText("Service access token").assertDoesNotExist()
        assertTrue(requests.get() >= 1)
    }
}
