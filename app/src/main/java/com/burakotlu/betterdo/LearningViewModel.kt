package com.burakotlu.betterdo

import android.app.Application
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

data class LearningState(
    val loading: Boolean = true,
    val lessons: List<Lesson> = emptyList(),
    val activeIds: Set<String> = emptySet(),
    val syncing: Boolean = false,
    val catalogReady: Boolean = false,
    val lastSynced: Long = 0,
    val syncError: String? = null,
    val progress: LearningProgress = LearningProgress(),
    val today: LocalDate = LocalDate.now(),
    val answers: Map<String, Map<Int, Int>> = emptyMap(),
    val message: String? = null,
    val fatalError: Boolean = false,
    val writable: Boolean = true
) {
    val language get() = progress.language
    val catalog get() = lessons.filter { it.language == language }
    val course get() = progress.courses.getValue(language)
    val available get() = catalog.filter { it.id in activeIds }
    val daily get() = catalog.find { it.id == course.daily[today] }
        ?: available.takeIf { it.isNotEmpty() }?.let { Scheduler.daily(it, course, today) }
    val due get() = Scheduler.due(catalog, course, today)
}

class LearningViewModel(application: Application) : AndroidViewModel(application) {
    private val file = AtomicFile(File(application.filesDir, "progress-v1.json"))
    private val mutex = Mutex()
    private val database = CatalogDatabase(application)
    private val source = if (BuildConfig.DEBUG) application.getSharedPreferences("catalog", 0).getString("test_url", null) ?: BuildConfig.CATALOG_URL else BuildConfig.CATALOG_URL
    private val repository = CatalogRepository(source, database, HttpCatalogClient(BuildConfig.DEBUG))
    private var syncJob: Job? = null
    private val mutable = MutableStateFlow(LearningState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { runCatching { repository.cached() }.getOrNull() }
            val progressResult = withContext(Dispatchers.IO) { runCatching {
                if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) JsonCodec.decodeProgress(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
                else LearningProgress()
            } }
            mutable.value = LearningState(loading = cached == null, lessons = cached?.lessons.orEmpty(), activeIds = cached?.activeIds.orEmpty(),
                catalogReady = cached != null, lastSynced = cached?.checkedAt ?: 0, progress = progressResult.getOrDefault(LearningProgress()),
                writable = progressResult.isSuccess, message = if (progressResult.isFailure) "Saved progress could not be read and has not been overwritten. Progress in this session will not be saved." else null)
            if (cached != null) updateProgress { it }
            refreshCatalog()
            while (true) {
                delay(30_000)
                refreshDate()
            }
        }
    }

    private fun pinDaily(progress: LearningProgress, lessons: List<Lesson>, today: LocalDate): LearningProgress {
        val courses = progress.courses.mapValues { (lang, course) ->
            val available = lessons.filter { it.language == lang && it.id in mutable.value.activeIds }
            if (available.isEmpty() || lessons.any { it.id == course.daily[today] }) course
            else course.copy(daily = course.daily + (today to Scheduler.daily(available, course, today).id))
        }
        return progress.copy(courses = courses)
    }

    private suspend fun updateProgress(transform: (LearningProgress) -> LearningProgress) = mutex.withLock {
        val current = mutable.value
        val today = LocalDate.now()
        val updated = pinDaily(transform(current.progress), current.lessons, today)
        val write = if (current.writable) withContext(Dispatchers.IO) { runCatching {
            val output = file.startWrite()
            try {
                output.write(JsonCodec.encodeProgress(updated).toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (error: Exception) { file.failWrite(output); throw error }
        } } else null
        mutable.value = mutable.value.copy(progress = updated, today = today,
            answers = if (current.today == today) mutable.value.answers else emptyMap(),
            message = if (write?.isFailure == true) "Your progress could not be saved. Recent changes may be lost when you close this session." else mutable.value.message)
    }

    fun refreshDate() {
        if (!mutable.value.loading && mutable.value.today != LocalDate.now()) {
            viewModelScope.launch { updateProgress { it } }
            refreshCatalog()
        }
    }

    fun onResume() {
        refreshDate()
        if (!mutable.value.loading && System.currentTimeMillis() - mutable.value.lastSynced > 6 * 60 * 60 * 1000L) refreshCatalog()
    }

    fun refreshCatalog() {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            mutable.value = mutable.value.copy(syncing = true, syncError = null)
            val result = withContext(Dispatchers.IO) { runCatching { repository.refresh() } }
            mutex.withLock {
                val current = mutable.value
                val fresh = result.getOrNull()
                mutable.value = if (fresh != null) current.copy(
                    loading = false, syncing = false, catalogReady = true, lessons = fresh.lessons, activeIds = fresh.activeIds,
                    lastSynced = fresh.checkedAt, syncError = null,
                    answers = current.answers.filterKeys { id -> current.lessons.find { it.id == id } == fresh.lessons.find { it.id == id } }
                ) else current.copy(loading = false, syncing = false,
                    syncError = if (current.catalogReady) "Lessons could not be updated. You can continue with downloaded lessons." else "An internet connection is needed to download your first lessons. Check your connection and try again.")
            }
            if (result.isSuccess) updateProgress { it }
        }
    }

    fun changeLanguage(language: String) {
        require(language in listOf("en", "de"))
        viewModelScope.launch { updateProgress { it.copy(language = language) } }
    }

    fun answer(lesson: Lesson, question: Int, answer: Int) {
        val current = mutable.value
        val answers = current.answers[lesson.id].orEmpty() + (question to answer)
        mutable.value = current.copy(answers = current.answers + (lesson.id to answers))
    }

    fun practice(lesson: Lesson, remembered: Boolean) {
        val current = mutable.value
        if (remembered && lesson.quiz.indices.any { current.answers[lesson.id]?.get(it) != lesson.quiz[it].answer }) return
        viewModelScope.launch {
            updateProgress { progress ->
                val course = progress.courses.getValue(lesson.language)
                progress.copy(courses = progress.courses + (lesson.language to Scheduler.practice(course, lesson.id, remembered, LocalDate.now())))
            }
            if (mutable.value.writable && mutable.value.message == null) {
                mutable.value = mutable.value.copy(message = if (remembered) "Nice progress! Your word has been added to your review schedule." else "Practice complete. This word will be ready to review tomorrow.")
            }
        }
    }

    fun dismissMessage() { if (mutable.value.writable) mutable.value = mutable.value.copy(message = null) }
}
