package com.burakotlu.betterdo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject

class LearningFlowTest {
    private val server = MockWebServer()
    @Volatile private var responseBody = ""
    @Volatile private var statusCode = 200
    private val rule = createAndroidComposeRule<MainActivity>()
    private val serverRule = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            responseBody = instrumentation.context.assets.open("lessons.json").bufferedReader().use { it.readText() }
            statusCode = 200
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(statusCode).setBody(responseBody)
            }
            server.start()
            val context = instrumentation.targetContext
            context.deleteDatabase("catalog.db")
            java.io.File(context.filesDir, "progress-v1.json").delete()
            java.io.File(context.filesDir, "progress-v1.json.bak").delete()
            context.getSharedPreferences("catalog", 0).edit().putString("test_url", server.url("/lessons.json").toString()).commit()
        }
        override fun after() { server.shutdown() }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(serverRule).around(rule)

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Shell-owned output survives Gradle uninstalling the test application.
        listOf("mkdir -p /sdcard/Download/betterdo-screenshots", "screencap -p /sdcard/Download/betterdo-screenshots/$name.png").forEach { command ->
            val output = instrumentation.uiAutomation.executeShellCommand(command)
            ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
        }
    }

    @After fun diagnostics() {
        screenshot("final-state")
        println(rule.onRoot().printToString())
        val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "progress-v1.json")
        println(if (file.exists()) file.readText() else "No progress file")
    }

    @Test fun languageQuizReviewAndSavedProgress() {
        rule.waitUntil(10_000) { rule.onAllNodesWithText("sneeze").fetchSemanticsNodes().isNotEmpty() }
        screenshot("english-daily")
        rule.onNodeWithText("EN · English").performClick()
        rule.onNodeWithText("Deutsch").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("niesen").fetchSemanticsNodes().isNotEmpty() }
        screenshot("german-daily")
        rule.onNodeWithText("DE · Deutsch").performClick()
        rule.onNodeWithText("English").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("sneeze").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("Öğrendim").performScrollTo().assertIsNotEnabled()
        val sneezeOptions = rule.onAllNodes(hasText("sneeze", substring = false) and hasClickAction())
        sneezeOptions[0].performScrollTo().performClick()
        sneezeOptions[1].performScrollTo().performClick()
        rule.onNodeWithText("Öğrendim").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("Kelimelerim").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Öğrenildi", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("sneeze").assertIsDisplayed()
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Öğrenildi", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("sneeze").assertIsDisplayed()
        screenshot("word-library")
        rule.onNodeWithText("Tekrar", substring = false).performClick()
        rule.onNodeWithText("Şimdilik hepsi tamam.").assertIsDisplayed()
    }

    @Test fun sameApkDownloadsChangedLessonAndKeepsItOnNetworkFailure() {
        rule.waitUntil(15_000) { rule.onAllNodesWithText("sneeze").fetchSemanticsNodes().isNotEmpty() }
        val updated = JSONObject(responseBody)
        updated.getJSONArray("lessons").getJSONObject(0).put("context", "Sunucudan güncellenen kullanım açıklaması.")
        val added = JSONObject(updated.getJSONArray("lessons").getJSONObject(0).toString()).put("id", "en-server-added").put("word", "server-added word")
        updated.getJSONArray("lessons").put(added)
        responseBody = updated.toString()
        rule.onNodeWithContentDescription("Dersleri güncelle").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Sunucudan güncellenen kullanım açıklaması.").fetchSemanticsNodes().isNotEmpty() }
        CatalogDatabase(InstrumentationRegistry.getInstrumentation().targetContext).use { database ->
            val stored = database.read(server.url("/lessons.json").toString())!!
            org.junit.Assert.assertTrue(JsonCodec.lessons(stored.body).any { it.id == "en-server-added" })
        }
        statusCode = 503
        rule.onNodeWithContentDescription("Dersleri güncelle").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Dersler güncellenemedi.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Sunucudan güncellenen kullanım açıklaması.").fetchSemanticsNodes().isNotEmpty() }
    }
}
