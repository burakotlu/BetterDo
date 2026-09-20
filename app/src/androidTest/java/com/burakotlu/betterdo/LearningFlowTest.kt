package com.burakotlu.betterdo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import org.junit.After
import org.junit.Rule
import org.junit.Test

class LearningFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Shell-owned output survives Gradle uninstalling the test application.
        val output = instrumentation.uiAutomation.executeShellCommand("sh -c 'mkdir -p /sdcard/Download/betterdo-screenshots && screencap -p /sdcard/Download/betterdo-screenshots/$name.png'")
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
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
}
