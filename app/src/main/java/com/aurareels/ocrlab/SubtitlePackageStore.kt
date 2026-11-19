package com.aurareels.ocrlab

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.security.MessageDigest

/** Durable, non-evictable transcript packages. All calls belong on the file worker, never the UI. */
class SubtitlePackageStore(context: Context) : SQLiteOpenHelper(context,"subtitle_packages.db",null,2) {
    data class Package(val id: String,val language: String,val cues: List<SpeechCue>,val coverage: List<AudioInterval>,val consumed: Boolean,val transcriptDone: Boolean,val asrVersion: String="")
    data class Edit(val cueId: String,val text: String,val deleted: Boolean)
    /** Human timing for one cue, in media time. The cue id (and so its text edit) never changes. */
    data class Timing(val cueId: String,val startMs: Long,val endMs: Long)
    private data class CueState(val start: Long,val end: Long,val source: String,val output: String?,val reason: String?,val provider: String?,val names: List<String>,val acoustic: Boolean)
    private data class Meta(val language: String,val asr: String,val intervals: List<AudioInterval>,val consumed: Boolean,val done: Boolean)
    private val written=LinkedHashMap<String,Map<String,CueState>>()
    private val metadata=HashMap<String,Meta>()
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE packages(id TEXT PRIMARY KEY, language TEXT NOT NULL, target TEXT NOT NULL, asr TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0, consumed INTEGER NOT NULL DEFAULT 0, transcript_done INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE cues(package_id TEXT NOT NULL REFERENCES packages(id), id TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, source TEXT NOT NULL, output TEXT, reason TEXT, provider TEXT, names TEXT NOT NULL, acoustic INTEGER NOT NULL, PRIMARY KEY(package_id,id))")
        db.execSQL("CREATE INDEX cues_time ON cues(package_id,start_ms)")
        db.execSQL("CREATE TABLE coverage(package_id TEXT NOT NULL REFERENCES packages(id), start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, PRIMARY KEY(package_id,start_ms))")
        db.execSQL("CREATE TABLE human_edits(package_id TEXT NOT NULL REFERENCES packages(id), cue_id TEXT NOT NULL, text TEXT NOT NULL, deleted INTEGER NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(package_id,cue_id))")
        createTiming(db)
    }
    private fun createTiming(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS human_timing(package_id TEXT NOT NULL REFERENCES packages(id), cue_id TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(package_id,cue_id))")
    }
    override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) { if(oldVersion<2) createTiming(db) }
    @Synchronized fun load(id: String): Package? {
        val db=readableDatabase
        var asrVersion=""
        val meta=db.rawQuery("SELECT language,consumed,transcript_done,asr FROM packages WHERE id=?",arrayOf(id)).use {
            if(!it.moveToFirst()) return null
            asrVersion=it.getString(3)
            Triple(it.getString(0),it.getInt(1)!=0,it.getInt(2)!=0)
        }
        val cues=ArrayList<SpeechCue>()
        db.rawQuery("SELECT c.start_ms,c.end_ms,c.source,c.output,c.reason,c.provider,c.names,c.acoustic,h.text,h.deleted,c.id,t.start_ms,t.end_ms FROM cues c LEFT JOIN human_edits h ON h.package_id=c.package_id AND h.cue_id=c.id LEFT JOIN human_timing t ON t.package_id=c.package_id AND t.cue_id=c.id WHERE c.package_id=? ORDER BY c.start_ms,c.id",arrayOf(id)).use { c ->
            // The stored id is authoritative: cues.start_ms/end_ms hold recognition bounds, human_timing overlays the user window.
            while(c.moveToNext()) cues.add(SpeechCue(c.getLong(0),c.getLong(1),c.getString(2),c.getString(10)).apply {
                output=if(c.isNull(3)) null else c.getString(3); reason=if(c.isNull(4)) null else c.getString(4)
                provider=if(c.isNull(5)) null else c.getString(5); acousticSpeech=c.getInt(7)!=0
                val names=JSONArray(c.getString(6)); preservedNames=(0 until names.length()).map { names.getString(it) }
                if(!c.isNull(8)) applyHuman(c.getString(8),c.getInt(9)!=0)
                if(!c.isNull(11) && !c.isNull(12)) applyTiming(c.getLong(11),c.getLong(12))
            })
        }
        cues.sortBy { it.startMs }
        val coverage=ArrayList<AudioInterval>()
        db.rawQuery("SELECT start_ms,end_ms FROM coverage WHERE package_id=? ORDER BY start_ms",arrayOf(id)).use { while(it.moveToNext()) coverage.add(AudioInterval(it.getLong(0),it.getLong(1))) }
        return Package(id,meta.first,cues,coverage,meta.second,meta.third,asrVersion)
    }
    @Synchronized fun checkpoint(id: String,language: String,asr: String,cues: List<SpeechCue>,intervals: List<AudioInterval>,consumed: Boolean,done: Boolean) {
        val states=cues.associate { c -> c.id to CueState(c.originStartMs,c.originEndMs,c.source,c.output,c.reason,c.provider,c.preservedNames.toList(),c.acousticSpeech) }
        val meta=Meta(language,asr,AudioCoverage.merge(intervals),consumed,done)
        val oldStates=written[id].orEmpty()
        val changed=cues.filter { oldStates[it.id]!=states[it.id] }
        if(changed.isEmpty() && metadata[id]==meta) return
        val db=writableDatabase; db.beginTransaction()
        try {
            db.insertWithOnConflict("packages",null,ContentValues().apply { put("id",id); put("language",language); put("target","pt"); put("asr",asr) },SQLiteDatabase.CONFLICT_IGNORE)
            db.execSQL("UPDATE packages SET language=?,asr=?,revision=revision+1,consumed=MAX(consumed,?),transcript_done=MAX(transcript_done,?) WHERE id=?",arrayOf<Any>(language,asr,if(consumed) 1 else 0,if(done) 1 else 0,id))
            changed.forEach { c -> db.insertWithOnConflict("cues",null,ContentValues().apply {
                put("package_id",id); put("id",c.id); put("start_ms",c.originStartMs); put("end_ms",c.originEndMs); put("source",c.source)
                put("output",c.output); put("reason",c.reason); put("provider",c.provider); put("names",JSONArray(c.preservedNames).toString()); put("acoustic",if(c.acousticSpeech) 1 else 0)
            },SQLiteDatabase.CONFLICT_REPLACE) }
            val old=ArrayList<AudioInterval>()
            db.rawQuery("SELECT start_ms,end_ms FROM coverage WHERE package_id=?",arrayOf(id)).use { while(it.moveToNext()) old.add(AudioInterval(it.getLong(0),it.getLong(1))) }
            db.delete("coverage","package_id=?",arrayOf(id))
            AudioCoverage.merge(old+intervals).forEach { r -> db.insertOrThrow("coverage",null,ContentValues().apply { put("package_id",id); put("start_ms",r.startMs); put("end_ms",r.endMs) }) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        written[id]=states; metadata[id]=meta
        while(written.size>4) { val oldest=written.keys.first(); written.remove(oldest); metadata.remove(oldest) }
    }
    /** Upserts only the edited IDs; cues/checkpoints arriving after the editor opened survive. */
    @Synchronized fun saveEdits(id: String,edits: List<Edit>,timings: List<Timing> = emptyList()) {
        val db=writableDatabase; db.beginTransaction()
        try {
            db.execSQL("UPDATE packages SET revision=revision+1 WHERE id=?",arrayOf(id))
            val rev=db.rawQuery("SELECT revision FROM packages WHERE id=?",arrayOf(id)).use { check(it.moveToFirst()); it.getLong(0) }
            edits.forEach { e -> db.insertWithOnConflict("human_edits",null,ContentValues().apply {
                put("package_id",id); put("cue_id",e.cueId); put("text",e.text); put("deleted",if(e.deleted) 1 else 0); put("revision",rev)
            },SQLiteDatabase.CONFLICT_REPLACE) }
            timings.forEach { t -> db.insertWithOnConflict("human_timing",null,ContentValues().apply {
                put("package_id",id); put("cue_id",t.cueId); put("start_ms",t.startMs); put("end_ms",t.endMs); put("revision",rev)
            },SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun availableIds(): Set<String> = readableDatabase.rawQuery("SELECT p.id FROM packages p WHERE consumed=1 AND EXISTS(SELECT 1 FROM cues c LEFT JOIN human_edits h ON h.package_id=c.package_id AND h.cue_id=c.id WHERE c.package_id=p.id AND COALESCE(h.deleted,0)=0)",null).use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }
    companion object {
        fun local(item: LocalVideo)=key("local|${item.uri}|${item.size}|${item.modified}")
        fun online(identity: String,url: String)=key("vod|$identity|$url") // Signed query parameters remain part of the revision.
        fun key(raw: String)=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
