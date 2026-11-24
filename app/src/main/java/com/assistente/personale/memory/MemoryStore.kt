package com.assistente.personale.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Un messaggio della conversazione: role = "user", "assistant" oppure "system" */
data class ChatMsg(val role: String, val content: String)

/**
 * Memoria locale dell'assistente: conserva conversazioni e cose da ricordare.
 * Tutto resta sul telefono, nel database interno dell'app.
 */
class MemoryStore(context: Context) :
    SQLiteOpenHelper(context, "assistente.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE chat(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, content TEXT NOT NULL, ts INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE facts(id INTEGER PRIMARY KEY AUTOINCREMENT, fact TEXT NOT NULL UNIQUE, ts INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // versioni future: migrazioni qui
    }

    fun add(role: String, content: String) {
        val cv = ContentValues().apply {
            put("role", role)
            put("content", content)
            put("ts", System.currentTimeMillis())
        }
        writableDatabase.insert("chat", null, cv)
    }

    fun lastN(n: Int): List<ChatMsg> {
        val out = mutableListOf<ChatMsg>()
        readableDatabase.rawQuery(
            "SELECT role, content FROM chat ORDER BY id DESC LIMIT ?",
            arrayOf(n.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(ChatMsg(c.getString(0), c.getString(1)))
        }
        out.reverse()
        return out
    }

    fun addFact(fact: String): Boolean {
        val clean = fact.trim()
        if (clean.isBlank()) return false
        val cv = ContentValues().apply {
            put("fact", clean)
            put("ts", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict(
            "facts", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    fun facts(): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT fact FROM facts ORDER BY id ASC", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun clearAll() {
        writableDatabase.execSQL("DELETE FROM chat")
        writableDatabase.execSQL("DELETE FROM facts")
    }
}
