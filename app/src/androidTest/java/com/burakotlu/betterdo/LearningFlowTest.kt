package com.burakotlu.betterdo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.junit.Rule
import org.junit.Test

class LearningFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")
        directory.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
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
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Bugünkü pratik tamamlandı.", substring = true).fetchSemanticsNodes().isNotEmpty() }

        rule.activityRule.scenario.recreate()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Bugünkü pratik tamamlandı.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Kelimelerim").performClick()
        rule.onNodeWithText("sneeze").assertIsDisplayed()
        screenshot("word-library")
        rule.onNodeWithText("Tekrar", substring = false).performClick()
        rule.onNodeWithText("Şimdilik hepsi tamam.").assertIsDisplayed()
    }
}
