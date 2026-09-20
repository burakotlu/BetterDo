package com.burakotlu.betterdo

import org.json.JSONArray
import org.json.JSONObject

data class StoredCatalog(val source: String, val body: String, val checkedAt: Long)
data class CatalogSnapshot(val lessons: List<Lesson>, val activeIds: Set<String>, val checkedAt: Long)
interface CatalogCache {
    fun read(source: String): StoredCatalog?
    fun write(catalog: StoredCatalog)
}
fun interface CatalogClient { fun fetch(source: String): String }

/** Network and storage operations run on the caller's IO dispatcher. */
class CatalogRepository(private val source: String, private val cache: CatalogCache, private val client: CatalogClient) {
    private fun snapshot(stored: StoredCatalog): CatalogSnapshot {
        val lessons = JsonCodec.lessons(stored.body)
        val root = JSONObject(stored.body)
        val active = root.getJSONArray("activeIds")
        val activeIds = (0 until active.length()).map { active.getString(it) }.toSet()
        require(activeIds.all { id -> lessons.any { it.id == id } })
        return CatalogSnapshot(lessons, activeIds, stored.checkedAt)
    }

    fun cached(): CatalogSnapshot? = cache.read(source)?.let(::snapshot)

    fun refresh(now: Long = System.currentTimeMillis()): CatalogSnapshot {
        val incoming = client.fetch(source)
        val lessons = JsonCodec.lessons(incoming) // Validate the entire response before replacing anything.
        require(lessons.map { it.language to it.word.trim().lowercase() }.distinct().size == lessons.size)
        val previous = cache.read(source)?.let { stored -> runCatching { snapshot(stored); stored }.getOrNull() }
        val records = linkedMapOf<String, JSONObject>()
        previous?.let {
            val old = JSONObject(it.body).getJSONArray("lessons")
            for (index in 0 until old.length()) {
                val record = old.getJSONObject(index)
                records[record.getString("id")] = record
            }
        }
        val fresh = JSONObject(incoming).getJSONArray("lessons")
        for (index in 0 until fresh.length()) {
            val record = fresh.getJSONObject(index)
            val id = record.getString("id")
            require(records[id]?.getString("language")?.let { it == record.getString("language") } != false) { "A lesson ID cannot move between languages" }
            records[id] = record
        }
        // Keep retired lessons for existing progress/reviews, but never choose them as new lessons.
        val merged = JSONObject().put("version", 1).put("lessons", JSONArray(records.values.toList()))
            .put("activeIds", JSONArray(lessons.map { it.id }))
        val stored = StoredCatalog(source, merged.toString(), now)
        val result = snapshot(stored)
        cache.write(stored)
        return result
    }
}
