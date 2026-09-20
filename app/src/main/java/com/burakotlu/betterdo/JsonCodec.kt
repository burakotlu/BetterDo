package com.burakotlu.betterdo

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

object JsonCodec {
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    fun lessons(raw: String): List<Lesson> {
        val root = JSONObject(raw)
        require(root.getInt("version") == 1)
        return root.getJSONArray("lessons").objects().map { item ->
            Lesson(
                item.getString("id"), item.getString("language"), item.getString("word"), item.getString("pronunciation"),
                item.getString("meaning"), item.getString("partOfSpeech"), item.getString("level"), item.getString("category"),
                item.getString("context"), item.getJSONArray("examples").objects().map { Example(it.getString("text"), it.getString("translation")) },
                item.getString("dialogueContext"), item.getJSONArray("dialogue").objects().map { DialogueLine(it.getString("speaker"), it.getString("text"), it.getString("translation")) },
                item.getJSONArray("quiz").objects().map { quiz ->
                    val options = quiz.getJSONArray("options")
                    Quiz(quiz.getString("question"), (0 until options.length()).map { options.getString(it) }, quiz.getInt("answer"), quiz.getString("explanation"))
                }
            )
        }.also { lessons ->
            require(lessons.map { it.id }.distinct().size == lessons.size)
            require(listOf("en", "de").all { lang -> lessons.any { it.language == lang } })
            require(lessons.all { it.quiz.size == 2 && it.examples.size == 3 && it.quiz.all { quiz -> quiz.answer in quiz.options.indices } })
        }
    }

    fun decodeProgress(raw: String): LearningProgress {
        val root = JSONObject(raw)
        require(root.getInt("version") == 1)
        val language = root.getString("language")
        require(language in listOf("en", "de"))
        val all = root.getJSONObject("courses")
        val courses = listOf("en", "de").associateWith { lang ->
            val course = all.getJSONObject(lang)
            val cards = course.getJSONObject("cards")
            val parsedCards = cards.keys().asSequence().associateWith { id ->
                val card = cards.getJSONObject(id)
                val stage = card.getInt("stage")
                require(stage in 0..5)
                CardProgress(stage, LocalDate.parse(card.getString("due")), LocalDate.parse(card.getString("lastPracticed")), card.getBoolean("learned"))
            }
            val activity = course.getJSONArray("activity")
            val daily = course.getJSONObject("daily")
            CourseProgress(parsedCards, (0 until activity.length()).map { LocalDate.parse(activity.getString(it)) }.toSet(),
                daily.keys().asSequence().associate { day -> LocalDate.parse(day) to daily.getString(day) })
        }
        return LearningProgress(language, courses)
    }

    fun encodeProgress(progress: LearningProgress): String {
        val courses = JSONObject()
        progress.courses.forEach { (language, course) ->
            val cards = JSONObject()
            course.cards.forEach { (id, card) ->
                cards.put(id, JSONObject().put("stage", card.stage).put("due", card.due.toString())
                    .put("lastPracticed", card.lastPracticed.toString()).put("learned", card.learned))
            }
            val daily = JSONObject()
            course.daily.forEach { (day, id) -> daily.put(day.toString(), id) }
            courses.put(language, JSONObject().put("cards", cards).put("activity", JSONArray(course.activity.map { it.toString() })).put("daily", daily))
        }
        return JSONObject().put("version", 1).put("language", progress.language).put("courses", courses).toString()
    }
}
