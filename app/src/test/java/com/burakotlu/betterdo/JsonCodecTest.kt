package com.burakotlu.betterdo

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

class JsonCodecTest {
    @Test fun savedProgressRoundTripsWithIndependentLanguages() {
        val course = Scheduler.practice(CourseProgress(daily = mapOf(LocalDate.of(2026, 9, 20) to "en-sneeze")), "en-sneeze", true, LocalDate.of(2026, 9, 20))
        val state = LearningProgress("de", mapOf("en" to course, "de" to CourseProgress()))
        assertEquals(state, JsonCodec.decodeProgress(JsonCodec.encodeProgress(state)))
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
