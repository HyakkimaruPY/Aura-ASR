package com.aurareels.ocrlab

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Persistent cross-session phrase dictionary with an in-memory fast path.
 * Lookups hit RAM first (no worker stall behind SQLite writes) and batch hit-count updates asynchronously.
 */
class TranslationVault private constructor(context: Context) : SQLiteOpenHelper(context.applicationContext,
    File(context.filesDir, "translation-vault.db").absolutePath, null, 1) {
    companion object {
        private const val MEM_PAIR_CAP = 2500
        @Volatile private var instance: TranslationVault? = null
        @Synchronized fun shared(context: Context): TranslationVault = instance ?: TranslationVault(context).also { instance = it }
    }

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "aura-vault") }
    private val pending = ArrayList<(SQLiteDatabase) -> Unit>()
    private var flushScheduled = false
    private var writes = 0
    @Volatile var revision = 0L; private set
    private val memDict = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val loadedPairs = ConcurrentHashMap.newKeySet<String>()
    private val pairOverflow = ConcurrentHashMap.newKeySet<String>()

    private fun pairKey(src: String, dst: String) = "$src:$dst"

    private fun flushPending() {
        flushScheduled = false
        if (pending.isEmpty()) return
        val work = pending.toList(); pending.clear()
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            work.forEach { it(db) }
            if (writes >= 64) {
                writes = 0
                db.execSQL("DELETE FROM phrase_seen WHERE last_seen<?", arrayOf<Any>(now - 60L * 86400000))
                for (table in listOf("phrase_seen", "phrase_dict")) {
                    db.execSQL("DELETE FROM $table WHERE rowid IN (SELECT rowid FROM $table ORDER BY ${if (table == "phrase_dict") "last_used" else "last_seen"} DESC LIMIT -1 OFFSET 50000)")
                }
                fun pragma(name: String): Long = db.rawQuery("PRAGMA $name", null).use { c -> c.moveToFirst(); c.getLong(0) }
                // Cap occupied pages; free pages are reused. File/WAL size can temporarily be larger.
                repeat(10) {
                    if ((pragma("page_count") - pragma("freelist_count")) * pragma("page_size") > 20_000_000) {
                        db.execSQL("DELETE FROM phrase_seen WHERE rowid IN (SELECT rowid FROM phrase_seen ORDER BY last_seen LIMIT 1000)")
                        db.execSQL("DELETE FROM phrase_dict WHERE rowid IN (SELECT rowid FROM phrase_dict ORDER BY last_used LIMIT 1000)")
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun scheduleFlush() {
        if (pending.size >= 32) flushPending()
        else if (!flushScheduled) {
            flushScheduled = true
            worker.schedule({ flushPending() }, 2, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE phrase_dict (src_lang TEXT NOT NULL, tgt_lang TEXT NOT NULL,
            src_hash INTEGER NOT NULL, src_norm TEXT NOT NULL, kind TEXT NOT NULL, src_sample TEXT NOT NULL,
            tgt_text TEXT NOT NULL, backend TEXT NOT NULL, quality INTEGER NOT NULL, hits INTEGER NOT NULL,
            sessions INTEGER NOT NULL, created_at INTEGER NOT NULL, last_used INTEGER NOT NULL,
            PRIMARY KEY(src_lang,tgt_lang,src_hash,src_norm))""")
        db.execSQL("CREATE INDEX ix_dict_lru ON phrase_dict(last_used)")
        db.execSQL("""CREATE TABLE phrase_seen (src_lang TEXT NOT NULL, tgt_lang TEXT NOT NULL,
            src_hash INTEGER NOT NULL, src_norm TEXT NOT NULL, kind TEXT NOT NULL, hits INTEGER NOT NULL,
            sessions INTEGER NOT NULL, last_session TEXT, last_seen INTEGER NOT NULL, pending_tgt TEXT,
            quality INTEGER NOT NULL, backend TEXT NOT NULL,
            PRIMARY KEY(src_lang,tgt_lang,src_hash,src_norm))""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Migração desconhecida") }

    private fun ensurePairLoaded(src: String, dst: String): ConcurrentHashMap<String, String> {
        val pk = pairKey(src, dst)
        val map = memDict.getOrPut(pk) { ConcurrentHashMap(256) }
        if (loadedPairs.contains(pk)) return map
        synchronized(map) {
            if (loadedPairs.contains(pk)) return map
            var rows = 0
            readableDatabase.rawQuery(
                "SELECT src_norm,tgt_text FROM phrase_dict WHERE src_lang=? AND tgt_lang=? ORDER BY hits DESC, last_used DESC LIMIT ${MEM_PAIR_CAP + 1}",
                arrayOf(src, dst)
            ).use { c ->
                while (c.moveToNext()) {
                    if (rows < MEM_PAIR_CAP) map.putIfAbsent(c.getString(0), c.getString(1))
                    rows++
                }
            }
            if (rows > MEM_PAIR_CAP) pairOverflow.add(pk) else pairOverflow.remove(pk)
            loadedPairs.add(pk)
        }
        return map
    }

    private fun recordHitAsync(src: String, dst: String, hash: Long, norm: String) {
        if (worker.isShutdown) return
        worker.execute {
            pending.add { db ->
                db.execSQL(
                    "UPDATE phrase_dict SET hits=hits+1,last_used=? WHERE src_lang=? AND tgt_lang=? AND src_hash=? AND src_norm=?",
                    arrayOf<Any>(System.currentTimeMillis(), src, dst, hash, norm)
                )
            }
            scheduleFlush()
        }
    }

    fun lookup(src: String, dst: String, text: String): String? {
        val norm = DictionaryPolicy.normalize(text, src)
        if (norm.isEmpty()) return null
        val hash = DictionaryPolicy.hash(norm)
        val map = ensurePairLoaded(src, dst)
        map[norm]?.let { hit ->
            recordHitAsync(src, dst, hash, norm)
            return hit
        }
        if (!pairOverflow.contains(pairKey(src, dst))) return null
        // Overflow fallback uses WAL concurrent read instead of blocking behind write flushes.
        val args = arrayOf(src, dst, hash.toString(), norm)
        val found = readableDatabase.rawQuery(
            "SELECT tgt_text FROM phrase_dict WHERE src_lang=? AND tgt_lang=? AND src_hash=? AND src_norm=?",
            args
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (found != null) {
            if (map.size < MEM_PAIR_CAP * 2) map[norm] = found
            recordHitAsync(src, dst, hash, norm)
        }
        return found
    }

    /** Last-resort local memory fallback when every online and offline engine is unavailable: rescues high-quality seen phrases. */
    fun lookupSeenFallback(src: String, dst: String, text: String): String? {
        val norm = DictionaryPolicy.normalize(text, src)
        if (norm.isEmpty()) return null
        val args = arrayOf(src, dst, DictionaryPolicy.hash(norm).toString(), norm)
        return runCatching {
            readableDatabase.rawQuery(
                "SELECT pending_tgt FROM phrase_seen WHERE src_lang=? AND tgt_lang=? AND src_hash=? AND src_norm=? AND quality>=70",
                args
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
        }.getOrNull()
    }

    fun preload(src: String, dst: String): Map<String, String> = LinkedHashMap(ensurePairLoaded(src, dst))

    fun observe(src: String, dst: String, text: String, translated: String, backend: String, session: String) {
        if (!DictionaryPolicy.eligible(text, src) || !DictionaryPolicy.validTranslation(text, translated) || worker.isShutdown) return
        worker.execute {
            pending.add { db ->
                val now = System.currentTimeMillis()
                val norm = DictionaryPolicy.normalize(text, src); val hash = DictionaryPolicy.hash(norm)
                val args = arrayOf(src, dst, hash.toString(), norm)
                val kind = if (NoiseClassifier.seed(text, src) != null) "INTERJECTION" else "PHRASE"
                var hits = 1; var sessions = 1; var candidate = translated
                var quality = DictionaryPolicy.quality(backend); var provider = backend; var consistent = true
                db.rawQuery("SELECT hits,sessions,last_session,pending_tgt,quality,backend FROM phrase_seen WHERE src_lang=? AND tgt_lang=? AND src_hash=? AND src_norm=?", args).use { c ->
                    if (c.moveToFirst()) {
                        hits = c.getInt(0) + 1; sessions = c.getInt(1) + if(c.getString(2) == session) 0 else 1
                        consistent = DictionaryPolicy.normalize(c.getString(3), dst) == DictionaryPolicy.normalize(translated, dst)
                        if (!consistent) {
                            // Reset evidence on a conflict; a high-quality candidate still needs repeated agreement.
                            hits = 1; sessions = 1
                            if (c.getInt(4) > quality) { candidate = c.getString(3); quality = c.getInt(4); provider = c.getString(5) }
                        }
                    }
                }
                db.execSQL("INSERT OR REPLACE INTO phrase_seen VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any>(src,dst,hash,norm,kind,hits,sessions,session,now,candidate,quality,provider))
                if (DictionaryPolicy.promote(kind,hits,sessions,consistent)) {
                    db.execSQL("""INSERT OR IGNORE INTO phrase_dict VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                        arrayOf<Any>(src,dst,hash,norm,kind,text,candidate,provider,quality,hits,sessions,now,now))
                    db.execSQL("UPDATE phrase_dict SET tgt_text=?,backend=?,quality=?,last_used=? WHERE src_lang=? AND tgt_lang=? AND src_hash=? AND src_norm=? AND quality<?",
                        arrayOf<Any>(candidate,provider,quality,now,src,dst,hash,norm,quality))
                    val pk = pairKey(src, dst)
                    memDict.getOrPut(pk) { ConcurrentHashMap(256) }[norm] = candidate
                }
                writes++
            }
            scheduleFlush()
        }
    }

    fun counts(callback: (Map<String, Int>) -> Unit) { worker.execute {
        flushPending()
        val result = linkedMapOf<String, Int>()
        readableDatabase.rawQuery("SELECT src_lang,COUNT(*) FROM phrase_dict GROUP BY src_lang", null).use { c -> while(c.moveToNext()) result[c.getString(0)] = c.getInt(1) }
        callback(result)
    } }

    fun clear(language: String? = null, done: () -> Unit = {}) { worker.execute {
        flushPending()
        if (language == null) { memDict.clear(); loadedPairs.clear(); pairOverflow.clear() }
        else {
            val prefix = "$language:"
            memDict.keys.filter { it.startsWith(prefix) }.forEach { memDict.remove(it); loadedPairs.remove(it); pairOverflow.remove(it) }
        }
        revision++
        for (table in listOf("phrase_dict", "phrase_seen")) writableDatabase.delete(table, if(language == null) null else "src_lang=?", language?.let { arrayOf(it) })
        done()
    } }

    fun exportJson(): String = worker.submit(Callable {
        flushPending()
        val rows = JSONArray()
        readableDatabase.rawQuery("SELECT src_lang,tgt_lang,src_sample,tgt_text,backend FROM phrase_dict ORDER BY src_lang,src_norm", null).use { c ->
            while(c.moveToNext()) rows.put(JSONObject().put("src",c.getString(0)).put("dst",c.getString(1)).put("source",c.getString(2)).put("target",c.getString(3)).put("backend",c.getString(4)))
        }
        JSONObject().put("version",1).put("entries",rows).toString(2)
    }).get()

    fun importJson(raw: String): Int = worker.submit(Callable {
        flushPending()
        require(raw.length <= 20_000_000) { "Backup excede 20 MB" }
        val json = JSONObject(raw); require(json.getInt("version") == 1)
        val rows = json.getJSONArray("entries"); require(rows.length() <= 50000)
        val db = writableDatabase; var count = 0
        db.beginTransaction()
        try {
            for (i in 0 until rows.length()) {
                val row=rows.getJSONObject(i); val src=row.getString("src"); val dst=row.getString("dst")
                val text=row.getString("source"); val target=row.getString("target")
                if(dst != "pt" || !DictionaryPolicy.eligible(text,src) || !DictionaryPolicy.validTranslation(text,target)) continue
                val norm=DictionaryPolicy.normalize(text,src); val now=System.currentTimeMillis()
                db.execSQL("INSERT OR REPLACE INTO phrase_dict VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any>(src,dst,DictionaryPolicy.hash(norm),norm,
                    if(NoiseClassifier.seed(text,src)!=null) "INTERJECTION" else "PHRASE",text,target,"user",100,1,1,now,now))
                memDict.getOrPut(pairKey(src, dst)) { ConcurrentHashMap(256) }[norm] = target
                count++
            }; db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        loadedPairs.clear(); pairOverflow.clear()
        revision++; count
    }).get()

    override fun close() {
        synchronized(Companion) { if (instance === this) instance = null }
        worker.execute { flushPending(); super.close() }
        worker.shutdown()
    }
}
