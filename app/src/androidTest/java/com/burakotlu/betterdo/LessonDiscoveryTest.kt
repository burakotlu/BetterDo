package com.burakotlu.betterdo

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LessonDiscoveryTest {
    @get:Rule val rule = createComposeRule()
    private val server = MockWebServer()
    @After fun close() { server.shutdown() }

    @Test fun chooseTopicAndOpenValidatedGeneratedLesson() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        context.getSharedPreferences("lesson_requests", 0).edit().clear().commit()
        val lesson = JSONObject(instrument.context.assets.open("lessons.json").bufferedReader().use { it.readText() })
            .getJSONArray("lessons").getJSONObject(0).put("id", "en-generated-" + "c".repeat(32))
        val posts = AtomicInteger()
        val token = "t".repeat(40)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer $token") return MockResponse().setResponseCode(401)
                val body = when (request.path) {
                    "/api/categories" -> """{"generationEnabled":true,"categories":[{"id":"travel","title":"Travel"}]}"""
                    "/api/lesson-jobs" -> {
                        posts.incrementAndGet()
                        val data = JSONObject(request.body.readUtf8())
                        assertEquals("travel", data.getString("category"))
                        assertEquals("en", data.getString("language"))
                        JSONObject().put("job", JSONObject().put("id", data.getString("id")).put("status", "completed").put("lesson", lesson)).toString()
                    }
                    else -> "{}"
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
        val settings = VideoSettings(context.applicationContext as Application)
        settings.configure(server.url("/").toString(), token)
        var opened: String? = null
        rule.setContent { MaterialTheme { LessonDiscovery("en", settings, {}, {}, { opened = it }) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Travel").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Travel").performClick()
        rule.onNodeWithText("Create lesson").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Open lesson").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Open lesson").performClick()
        rule.runOnIdle { assertTrue(JsonCodec.lessons(opened!!).single().id.startsWith("en-generated-")); assertEquals(1, posts.get()) }
        context.getSharedPreferences("video_settings", 0).edit().clear().commit()
    }
}
