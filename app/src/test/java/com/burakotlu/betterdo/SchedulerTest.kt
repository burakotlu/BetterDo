package com.burakotlu.betterdo

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SchedulerTest {
    private val today = LocalDate.of(2026, 9, 20)
    private fun lesson(id: String) = Lesson(id, "en", id, "ipa", "meaning", "verb", "A2", "daily", "context", emptyList(), "dialogue", emptyList(), emptyList())

    @Test fun firstSuccessIsDueTomorrow() {
        val course = Scheduler.practice(CourseProgress(), "en-sneeze", true, today)
        assertEquals(today.plusDays(1), course.cards.getValue("en-sneeze").due)
        assertEquals(0, course.cards.getValue("en-sneeze").stage)
        assertTrue(course.cards.getValue("en-sneeze").learned)
    }

    @Test fun repeatedSameDayPracticeDoesNotInflateIntervalsOrStreak() {
        val first = Scheduler.practice(CourseProgress(), "one", true, today)
        val repeated = Scheduler.practice(first, "one", true, today)
        assertEquals(first, repeated)
        assertEquals(1, Scheduler.streak(repeated.activity, today))
    }

    @Test fun nextDayReviewAdvancesToThreeDays() {
        val first = Scheduler.practice(CourseProgress(), "one", true, today)
        val reviewed = Scheduler.practice(first, "one", true, today.plusDays(1))
        assertEquals(today.plusDays(4), reviewed.cards.getValue("one").due)
    }

    @Test fun failureResetsIntervalButKeepsPreviouslyLearnedWord() {
        val first = Scheduler.practice(CourseProgress(), "one", true, today)
        val failed = Scheduler.practice(first, "one", false, today.plusDays(1))
        assertEquals(0, failed.cards.getValue("one").stage)
        assertEquals(today.plusDays(2), failed.cards.getValue("one").due)
        assertTrue(failed.cards.getValue("one").learned)
    }

    @Test fun incompletePracticeDoesNotCountAsLearned() {
        val course = Scheduler.practice(CourseProgress(), "one", false, today)
        assertFalse(course.cards.getValue("one").learned)
        assertEquals(1, Scheduler.streak(course.activity, today))
    }

    @Test fun streakSurvivesUntilEndOfNextDayAndThenExpires() {
        val activity = setOf(today.minusDays(2), today.minusDays(1))
        assertEquals(2, Scheduler.streak(activity, today))
        assertEquals(0, Scheduler.streak(activity, today.plusDays(1)))
    }

    @Test fun dailyLessonRemainsPinnedAfterCompletionAndCatalogAppend() {
        val first = lesson("one")
        val second = lesson("two")
        val progress = Scheduler.practice(CourseProgress(daily = mapOf(today to first.id)), first.id, true, today)
        assertEquals(first, Scheduler.daily(listOf(second, first), progress, today))
        assertEquals(second, Scheduler.daily(listOf(first, second), progress, today.plusDays(1)))
    }

    @Test fun dueQueueExcludesFutureAndUnseenWords() {
        val old = Scheduler.practice(CourseProgress(), "old", true, today.minusDays(2))
        val current = Scheduler.practice(old, "new", true, today)
        assertEquals(listOf("old"), Scheduler.due(listOf(lesson("new"), lesson("old"), lesson("unseen")), current, today).map { it.id })
    }

    @Test fun intervalsCapAtSixtyDays() {
        var progress = CourseProgress()
        var day = today
        repeat(12) {
            progress = Scheduler.practice(progress, "one", true, day)
            day = progress.cards.getValue("one").due
        }
        val card = progress.cards.getValue("one")
        assertEquals(5, card.stage)
        assertEquals(60L, java.time.temporal.ChronoUnit.DAYS.between(card.lastPracticed, card.due))
    }

    @Test fun calendarArithmeticHandlesYearBoundary() {
        val end = LocalDate.of(2026, 12, 31)
        val progress = Scheduler.practice(CourseProgress(), "one", true, end)
        assertEquals(LocalDate.of(2027, 1, 1), progress.cards.getValue("one").due)
    }
}
