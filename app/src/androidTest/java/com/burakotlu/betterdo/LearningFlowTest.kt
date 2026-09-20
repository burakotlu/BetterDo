package com.burakotlu.betterdo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class LearningFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun languageQuizReviewAndSavedProgress() {
        rule.waitUntil(10_000) { rule.onAllNodesWithText("sneeze").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("EN · English").performClick()
        rule.onNodeWithText("Deutsch").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("niesen").fetchSemanticsNodes().isNotEmpty() }
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
        rule.onNodeWithText("Tekrar", substring = false).performClick()
        rule.onNodeWithText("Şimdilik hepsi tamam.").assertIsDisplayed()
    }
}
