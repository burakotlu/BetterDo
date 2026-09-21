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
        rule.onNodeWithText("German").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("niesen").fetchSemanticsNodes().isNotEmpty() }
        screenshot("german-daily")
        rule.onNodeWithText("DE · German").performClick()
        rule.onNodeWithText("English").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("sneeze").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("Mark as learned").performScrollTo().assertIsNotEnabled()
        val sneezeOptions = rule.onAllNodes(hasText("sneeze", substring = false) and hasClickAction())
        sneezeOptions[0].performScrollTo().performClick()
        sneezeOptions[1].performScrollTo().performClick()
        rule.onNodeWithText("Mark as learned").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("My words").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Learned", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("sneeze").assertIsDisplayed()
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Learned", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("sneeze").assertIsDisplayed()
        screenshot("word-library")
        rule.onNodeWithText("Review", substring = false).performClick()
        rule.onNodeWithText("You are all caught up.").assertIsDisplayed()
    }

    @Test fun extraWordsAreAvailableTheSameDayAndPersist() {
        rule.waitUntil(10_000) { rule.onAllNodesWithText("sneeze").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Review tomorrow").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Learn another word").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Learn another word").performScrollTo().performClick()
        rule.onNodeWithText("errand", substring = false).assertExists()
        rule.onNodeWithText("Review tomorrow").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("2 words practiced today", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("My words").performClick()
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("errand", substring = false).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("sneeze", substring = false).assertExists()
        rule.onNodeWithText("errand", substring = false).assertExists()
    }

    @Test fun sameApkDownloadsChangedLessonAndKeepsItOnNetworkFailure() {
        rule.waitUntil(15_000) { rule.onAllNodesWithText("sneeze").fetchSemanticsNodes().isNotEmpty() }
        val updated = JSONObject(responseBody)
        updated.getJSONArray("lessons").getJSONObject(0).put("context", "An updated usage note from the server.")
        val added = JSONObject(updated.getJSONArray("lessons").getJSONObject(0).toString()).put("id", "en-server-added").put("word", "server-added word")
        updated.getJSONArray("lessons").put(added)
        responseBody = updated.toString()
        rule.onNodeWithContentDescription("Refresh lessons").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText("An updated usage note from the server.").fetchSemanticsNodes().isNotEmpty() }
        CatalogDatabase(InstrumentationRegistry.getInstrumentation().targetContext).use { database ->
            val stored = database.read(server.url("/lessons.json").toString())!!
            org.junit.Assert.assertTrue(JsonCodec.lessons(stored.body).any { it.id == "en-server-added" })
        }
        statusCode = 503
        rule.onNodeWithContentDescription("Refresh lessons").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Lessons could not be updated.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("An updated usage note from the server.").fetchSemanticsNodes().isNotEmpty() }
    }
}
