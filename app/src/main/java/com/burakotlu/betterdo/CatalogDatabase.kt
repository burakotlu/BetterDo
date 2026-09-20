package com.burakotlu.betterdo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Downloaded catalog snapshots only; progress-v1.json is preserved independently. */
class CatalogDatabase(context: Context) : SQLiteOpenHelper(context, "catalog.db", null, 1), CatalogCache {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE catalogs (source TEXT PRIMARY KEY NOT NULL, body TEXT NOT NULL, checked_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Missing catalog database migration: $oldVersion to $newVersion")
    }
    override fun read(source: String): StoredCatalog? = readableDatabase.query(
        "catalogs", arrayOf("body", "checked_at"), "source = ?", arrayOf(source), null, null, null
    ).use { cursor -> if (cursor.moveToFirst()) StoredCatalog(source, cursor.getString(0), cursor.getLong(1)) else null }

    override fun write(catalog: StoredCatalog) {
        val values = ContentValues().apply { put("source", catalog.source); put("body", catalog.body); put("checked_at", catalog.checkedAt) }
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(db.insertWithOnConflict("catalogs", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
