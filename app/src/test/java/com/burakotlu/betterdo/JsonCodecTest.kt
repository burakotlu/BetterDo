package com.burakotlu.betterdo

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

class JsonCodecTest {
    @Test fun allLanguageLevelsAreAcceptedAndUnknownLevelsRejected() {
        val root = org.json.JSONObject(javaClass.classLoader!!.getResource("lessons.json")!!.readText())
        for (level in LanguageLevels.labels.keys) {
            val lessons = root.getJSONArray("lessons")
            for (index in 0 until lessons.length()) lessons.getJSONObject(index).put("level", level)
            assertTrue(JsonCodec.lessons(root.toString()).all { it.level == level })
        }
        root.getJSONArray("lessons").getJSONObject(0).put("level", "C3")
        assertThrows(IllegalArgumentException::class.java) { JsonCodec.lessons(root.toString()) }
    }
    @Test fun savedProgressRoundTripsWithIndependentLanguages() {
        val course = Scheduler.practice(CourseProgress(daily = mapOf(LocalDate.of(2026, 9, 20) to "en-sneeze")), "en-sneeze", true, LocalDate.of(2026, 9, 20))
        val state = LearningProgress("de", mapOf("en" to course, "de" to CourseProgress()), mapOf("en" to "A2", "de" to "B1"))
        assertEquals(state, JsonCodec.decodeProgress(JsonCodec.encodeProgress(state)))
    }

    @Test fun oldSavedProgressCanChooseLevelsWithoutLosingCards() {
        val old = LearningProgress(courses = mapOf("en" to Scheduler.practice(CourseProgress(), "en-sneeze", true, LocalDate.of(2026, 9, 20)), "de" to CourseProgress()))
        val legacy = org.json.JSONObject(JsonCodec.encodeProgress(old)).apply { remove("levels") }.toString()
        val loaded = JsonCodec.decodeProgress(legacy)
        assertTrue(loaded.levels.isEmpty())
        assertTrue(loaded.courses.getValue("en").cards.getValue("en-sneeze").learned)
    }

    @Test fun damagedSavedLevelIsRejectedInsteadOfSilentlyReset() {
        val raw = org.json.JSONObject(JsonCodec.encodeProgress(LearningProgress()))
            .put("levels", org.json.JSONObject().put("en", "C3")).toString()
        assertThrows(IllegalArgumentException::class.java) { JsonCodec.decodeProgress(raw) }
    }

    @Test fun dailySelectionUsesChosenLevelWhileReviewsKeepOldWords() {
        val lessons = JsonCodec.lessons(javaClass.classLoader!!.getResource("lessons.json")!!.readText())
        val oldCard = Scheduler.practice(CourseProgress(), "en-sneeze", true, LocalDate.of(2026, 9, 20))
        val state = LearningState(loading = false, lessons = lessons, activeIds = lessons.map { it.id }.toSet(),
            progress = LearningProgress(courses = mapOf("en" to oldCard, "de" to CourseProgress()), levels = mapOf("en" to "B1")),
            today = LocalDate.of(2026, 9, 21))
        assertEquals("B1", state.daily?.level)
        assertEquals("en-errand", state.daily?.id)
        assertTrue(state.due.any { it.id == "en-sneeze" })
    }

    @Test(expected = IllegalArgumentException::class) fun futureVersionIsNotSilentlyReset() {
        JsonCodec.decodeProgress("""{"version":2,"language":"en","courses":{}}""")
    }

    @Test fun catalogFixtureLoadsInAndroidParser() {
        val lessons = JsonCodec.lessons(javaClass.classLoader!!.getResource("lessons.json")!!.readText())
        assertTrue(lessons.size >= 14)
        assertEquals("sneeze", lessons.first().word)
        assertEquals("niesen", lessons.first { it.language == "de" }.word)
        assertTrue(lessons.all { lesson -> lesson.quiz.all { it.answer in it.options.indices } })
    }
}
