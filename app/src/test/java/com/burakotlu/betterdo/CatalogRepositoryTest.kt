package com.burakotlu.betterdo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CatalogRepositoryTest {
    private val source = "https://example.test/catalog.json"
    private val fixture get() = javaClass.classLoader!!.getResource("lessons.json")!!.readText()
    private class MemoryCache : CatalogCache {
        var stored: StoredCatalog? = null
        override fun read(source: String) = stored?.takeIf { it.source == source }
        override fun write(catalog: StoredCatalog) { stored = catalog }
    }

    @Test fun noSeedExistsBeforeFirstDownload() {
        assertNull(CatalogRepository(source, MemoryCache(), CatalogClient { error("No request expected") }).cached())
    }

    @Test fun downloadAndReopenWorksOffline() {
        val cache = MemoryCache()
        val first = CatalogRepository(source, cache, CatalogClient { fixture }).refresh(1000)
        val offline = CatalogRepository(source, cache, CatalogClient { throw IOException("offline") })
        assertEquals(first, offline.cached())
        assertEquals(14, first.activeIds.size)
    }

    @Test fun updatedServerAddsLessonWithoutChangingApplication() {
        val cache = MemoryCache()
        var body = fixture
        val repository = CatalogRepository(source, cache, CatalogClient { body })
        repository.refresh(1000)
        val root = JSONObject(fixture)
        val newLesson = JSONObject(root.getJSONArray("lessons").getJSONObject(0).toString()).put("id", "en-brand-new").put("word", "brand new")
        root.getJSONArray("lessons").put(newLesson)
        body = root.toString()
        val updated = repository.refresh(2000)
        assertTrue("en-brand-new" in updated.activeIds)
        assertEquals(15, updated.lessons.size)
        assertEquals(2000L, updated.checkedAt)
    }

    @Test fun failedOrInvalidRefreshDoesNotReplaceCache() {
        val cache = MemoryCache()
        CatalogRepository(source, cache, CatalogClient { fixture }).refresh(1000)
        val old = cache.stored
        for (body in listOf("<html>error</html>", "{}", fixture.replace("\"answer\": 1", "\"answer\": 9"))) {
            assertTrue(runCatching { CatalogRepository(source, cache, CatalogClient { body }).refresh(2000) }.isFailure)
            assertEquals(old, cache.stored)
        }
        assertTrue(runCatching { CatalogRepository(source, cache, CatalogClient { throw IOException() }).refresh() }.isFailure)
        assertEquals(old, cache.stored)
    }

    @Test fun retiredLessonsRemainAvailableForProgressButAreNotNewLessons() {
        val cache = MemoryCache()
        CatalogRepository(source, cache, CatalogClient { fixture }).refresh()
        val root = JSONObject(fixture)
        val lessons = root.getJSONArray("lessons")
        val removed = lessons.getJSONObject(0).getString("id")
        lessons.remove(0)
        val result = CatalogRepository(source, cache, CatalogClient { root.toString() }).refresh()
        assertTrue(result.lessons.any { it.id == removed })
        assertFalse(removed in result.activeIds)
    }

    @Test fun emptyCatalogIsValidAndDoesNotEraseHistory() {
        val cache = MemoryCache()
        CatalogRepository(source, cache, CatalogClient { fixture }).refresh()
        val result = CatalogRepository(source, cache, CatalogClient { """{"version":1,"lessons":[]}""" }).refresh()
        assertTrue(result.activeIds.isEmpty())
        assertEquals(14, result.lessons.size)
    }

    @Test fun singleLanguageCatalogIsSupported() {
        val root = JSONObject(fixture)
        val lessons = root.getJSONArray("lessons")
        root.put("lessons", JSONArray((0 until lessons.length()).map { lessons.getJSONObject(it) }.filter { it.getString("language") == "en" }))
        val result = CatalogRepository(source, MemoryCache(), CatalogClient { root.toString() }).refresh()
        assertEquals(7, result.activeIds.size)
    }

    @Test fun cacheIsIsolatedBySource() {
        val cache = MemoryCache()
        CatalogRepository(source, cache, CatalogClient { fixture }).refresh()
        assertNull(CatalogRepository("https://other.test/catalog.json", cache, CatalogClient { fixture }).cached())
    }

    @Test fun stringQuizIndexIsRejected() {
        val root = JSONObject(fixture)
        root.getJSONArray("lessons").getJSONObject(0).getJSONArray("quiz").getJSONObject(0).put("answer", "1")
        assertTrue(runCatching { JsonCodec.lessons(root.toString()) }.isFailure)
    }
}
