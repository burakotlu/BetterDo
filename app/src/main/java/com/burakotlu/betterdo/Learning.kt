package com.burakotlu.betterdo

import java.time.LocalDate

object LanguageLevels {
    val labels = linkedMapOf(
        "A1" to "Beginner", "A2" to "Elementary", "B1" to "Intermediate",
        "B2" to "Upper intermediate", "C1" to "Advanced", "C2" to "Proficient"
    )
}

data class Example(val text: String, val translation: String)
data class DialogueLine(val speaker: String, val text: String, val translation: String)
data class Quiz(val question: String, val options: List<String>, val answer: Int, val explanation: String)
data class Lesson(
    val id: String, val language: String, val word: String, val pronunciation: String,
    val meaning: String, val partOfSpeech: String, val level: String, val category: String,
    val context: String, val examples: List<Example>, val dialogueContext: String,
    val dialogue: List<DialogueLine>, val quiz: List<Quiz>
)
data class CardProgress(val stage: Int, val due: LocalDate, val lastPracticed: LocalDate, val learned: Boolean)
data class CourseProgress(
    val cards: Map<String, CardProgress> = emptyMap(),
    val activity: Set<LocalDate> = emptySet(),
    val daily: Map<LocalDate, String> = emptyMap()
)
data class LearningProgress(
    val language: String = "en",
    val courses: Map<String, CourseProgress> = mapOf("en" to CourseProgress(), "de" to CourseProgress())
)

object Scheduler {
    private val intervals = listOf(1L, 3L, 7L, 14L, 30L, 60L)

    fun nextUnseen(lessons: List<Lesson>, progress: CourseProgress): Lesson? =
        lessons.firstOrNull { it.id !in progress.cards }

    fun practicedToday(progress: CourseProgress, today: LocalDate): Int =
        progress.cards.values.count { it.lastPracticed == today }

    fun daily(lessons: List<Lesson>, progress: CourseProgress, today: LocalDate): Lesson {
        require(lessons.isNotEmpty())
        return lessons.find { it.id == progress.daily[today] }
            ?: lessons.firstOrNull { it.id !in progress.cards }
            ?: lessons.minBy { progress.cards.getValue(it.id).due }
    }

    fun due(lessons: List<Lesson>, progress: CourseProgress, today: LocalDate): List<Lesson> =
        lessons.filter { lesson -> progress.cards[lesson.id]?.let { !it.due.isAfter(today) } == true }
            .sortedBy { progress.cards.getValue(it.id).due }

    fun practice(progress: CourseProgress, id: String, remembered: Boolean, today: LocalDate): CourseProgress {
        val old = progress.cards[id]
        val sameDay = old?.lastPracticed == today
        val stage = if (!remembered) 0 else if (sameDay) old!!.stage else ((old?.stage ?: -1) + 1).coerceAtMost(intervals.lastIndex)
        val card = CardProgress(stage, today.plusDays(if (remembered) intervals[stage] else 1L), today, remembered || old?.learned == true)
        return progress.copy(cards = progress.cards + (id to card), activity = progress.activity + today)
    }

    fun streak(activity: Set<LocalDate>, today: LocalDate): Int {
        var cursor = if (today in activity) today else today.minusDays(1)
        var count = 0
        while (cursor in activity) { count++; cursor = cursor.minusDays(1) }
        return count
    }
}
