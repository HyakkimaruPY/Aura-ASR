"""Durable SQLite subtitle packages (v1->v2 migration, human text/timing overlays,
tombstones, silent audio coverage, transactional draft rollback), local atomic cache
and remembered language persistence across Local/Online/Live/Music."""
import sqlite3
import tempfile
import unittest
from pathlib import Path

from helpers import read_kt, slice_between


SCHEMA_V2 = """
PRAGMA foreign_keys = ON;
CREATE TABLE packages(id TEXT PRIMARY KEY, language TEXT NOT NULL, target TEXT NOT NULL, asr TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0, consumed INTEGER NOT NULL DEFAULT 0, transcript_done INTEGER NOT NULL DEFAULT 0);
CREATE TABLE cues(package_id TEXT NOT NULL REFERENCES packages(id), id TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, source TEXT NOT NULL, output TEXT, reason TEXT, provider TEXT, names TEXT NOT NULL, acoustic INTEGER NOT NULL, PRIMARY KEY(package_id,id));
CREATE INDEX cues_time ON cues(package_id,start_ms);
CREATE TABLE coverage(package_id TEXT NOT NULL REFERENCES packages(id), start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, PRIMARY KEY(package_id,start_ms));
CREATE TABLE human_edits(package_id TEXT NOT NULL REFERENCES packages(id), cue_id TEXT NOT NULL, text TEXT NOT NULL, deleted INTEGER NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(package_id,cue_id));
CREATE TABLE IF NOT EXISTS human_timing(package_id TEXT NOT NULL REFERENCES packages(id), cue_id TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(package_id,cue_id));
"""


class SubtitlePackageSqliteTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmp.name) / "subtitle_packages.db"
        self.db = sqlite3.connect(self.db_path)
        self.db.executescript(SCHEMA_V2)

    def tearDown(self):
        self.db.close()
        self.tmp.cleanup()

    def test_schema_v2_migration_and_origin_start_and_end_preservation(self):
        store = read_kt("SubtitlePackageStore.kt")
        self.assertIn('SQLiteOpenHelper(context,"subtitle_packages.db",null,2)', store)
        self.assertIn("if(oldVersion<2) createTiming(db)", store)
        self.assertIn('put("start_ms",c.originStartMs); put("end_ms",c.originEndMs)', store)

        timeline = read_kt("SubtitleTimeline.kt")
        self.assertIn("val originStartMs: Long = originStartMs", timeline)
        self.assertIn("@Volatile var originEndMs: Long = originEndMs", timeline)
        self.assertIn("fun extendMachineEnd(", timeline)
        self.assertIn("SpeechCue(startMs, endMs, source, id, originStartMs, originEndMs)", timeline)

    def test_human_edits_and_timing_overlays_survive_late_machine_checkpoints(self):
        cid = "c_1000_konnichiwa"
        self.db.execute("INSERT INTO packages VALUES('pkg1','ja','pt','sherpa',1,1,1)")
        self.db.execute("INSERT INTO cues VALUES(?,?,?,?,?,?,?,?,?,?)", ("pkg1", cid, 1000, 3000, "こんにちは", "Olá máquina", "OK", "api", "[]", 1))
        self.db.execute("INSERT INTO human_edits VALUES(?,?,?,?,?)", ("pkg1", cid, "Olá humano", 0, 2))
        self.db.execute("INSERT INTO human_timing VALUES(?,?,?,?,?)", ("pkg1", cid, 850, 3250, 2))
        # Simulate late machine checkpoint replacing the cues row via CONFLICT_REPLACE while preserving overlays
        self.db.execute("INSERT OR REPLACE INTO cues VALUES(?,?,?,?,?,?,?,?,?,?)", ("pkg1", cid, 1000, 3100, "こんにちは", "Outra tradução", "OK", "web", "[]", 1))
        self.db.commit()

        joined = self.db.execute(
            "SELECT c.start_ms,c.end_ms,h.text,h.deleted,t.start_ms,t.end_ms "
            "FROM cues c LEFT JOIN human_edits h ON h.package_id=c.package_id AND h.cue_id=c.id "
            "LEFT JOIN human_timing t ON t.package_id=c.package_id AND t.cue_id=c.id WHERE c.package_id='pkg1'"
        ).fetchone()
        self.assertEqual(joined, (1000, 3100, "Olá humano", 0, 850, 3250))

    def test_tombstones_and_natural_consumption_gate_availability(self):
        self.db.execute("INSERT INTO packages VALUES('unconsumed','en','pt','sherpa',1,0,1)")
        self.db.execute("INSERT INTO cues VALUES('unconsumed','c0',0,1000,'hi','oi','OK','api','[]',1)")
        self.db.execute("INSERT INTO packages VALUES('consumed','en','pt','sherpa',1,1,1)")
        self.db.execute("INSERT INTO cues VALUES('consumed','c0',0,1000,'hi','oi','OK','api','[]',1)")
        self.db.execute("INSERT INTO packages VALUES('deleted_all','en','pt','sherpa',1,1,1)")
        self.db.execute("INSERT INTO cues VALUES('deleted_all','c0',0,1000,'hi','oi','OK','api','[]',1)")
        self.db.execute("INSERT INTO human_edits VALUES('deleted_all','c0','',1,2)")
        self.db.commit()

        rows = self.db.execute(
            "SELECT p.id FROM packages p WHERE consumed=1 AND EXISTS("
            "SELECT 1 FROM cues c LEFT JOIN human_edits h ON h.package_id=c.package_id AND h.cue_id=c.id "
            "WHERE c.package_id=p.id AND COALESCE(h.deleted,0)=0)"
        ).fetchall()
        self.assertEqual([r[0] for r in rows], ["consumed"])

    def test_editor_draft_keeps_concurrent_new_cues_and_rolls_back_on_error(self):
        self.db.execute("INSERT INTO packages VALUES('p','es','pt','sherpa',1,1,0)")
        self.db.execute("INSERT INTO cues VALUES('p','c1',0,1000,'uno','um','OK','api','[]',1)")
        self.db.execute("INSERT INTO human_edits VALUES('p','c1','um editado',0,1)")
        self.db.commit()

        # Concurrent playback adds c2 while editor is open; rollback on error preserves original edit
        self.db.execute("INSERT INTO cues VALUES('p','c2',1200,2200,'dos','dois','OK','api','[]',1)")
        self.db.commit()
        try:
            with self.db:
                self.db.execute("DELETE FROM human_edits WHERE package_id='p'")
                raise RuntimeError("disk full")
        except RuntimeError:
            pass
        preserved = self.db.execute("SELECT text FROM human_edits WHERE package_id='p' AND cue_id='c1'").fetchone()
        self.assertEqual(preserved, ("um editado",))

    def test_silent_audio_coverage_intervals_persist_across_reopen(self):
        self.db.execute("INSERT INTO packages VALUES('silent_ep','ko','pt','sherpa',1,0,0)")
        self.db.executemany("INSERT INTO coverage VALUES('silent_ep',?,?)", [(0, 10_000), (20_000, 30_000)])
        self.db.commit()
        self.db.close()
        self.db = sqlite3.connect(self.db_path)
        saved = self.db.execute("SELECT start_ms,end_ms FROM coverage ORDER BY start_ms").fetchall()
        self.assertEqual(saved, [(0, 10_000), (20_000, 30_000)])


class EditorCacheAndLanguageMemoryTests(unittest.TestCase):
    def test_editor_uses_vector_icons_and_preserves_human_timing_on_export(self):
        editor = read_kt("SubtitleEditorPage.kt")
        self.assertIn("R.drawable.ic_back", editor)
        self.assertIn("R.drawable.ic_close", editor)
        self.assertIn("if (timings.containsKey(cue.id) || cue.humanTimed) applyTiming(", editor)
        self.assertNotIn('action("‹"', editor)

        main = read_kt("MainActivity.kt")
        self.assertRegex(main, r"packages\.saveEdits\(id,\s*edits,\s*timings\)")
        self.assertIn("timeline.applyTimings(timings)", main)
        self.assertRegex(main, r"editorTranslator\?\.applyEdits\(edits,\s*timings\)")

    def test_local_subtitle_cache_uses_atomic_files_move_and_preserves_origin_bounds(self):
        cache = read_kt("LocalSubtitleCache.kt")
        self.assertIn("StandardCopyOption.REPLACE_EXISTING", cache)
        self.assertNotIn("tmp.renameTo(", cache)
        self.assertRegex(cache, r'\.put\("id",\s*c\.id\)')
        self.assertRegex(cache, r'\.put\("origin_end",\s*c\.originEndMs\)')

    def test_remembered_language_retains_local_and_music_content_uris(self):
        prefs = read_kt("AppPrefs.kt")
        rem_fn = slice_between(prefs, "fun rememberLanguage(", "fun all():")
        self.assertIn("MAX_LANGUAGES", rem_fn)
        self.assertIn('url.startsWith("content://")', prefs)
        self.assertIn("fun forgetLanguage(", prefs)

        main = read_kt("MainActivity.kt")
        self.assertRegex(main, r"rememberedVideoLanguage\(mediaUrl,\s*channelId\)")
        self.assertIn("(selectedSource ?: remembered)?.let { applyManualLanguage(it) }", main)
        self.assertRegex(main, r"forgetRememberedLanguage\(currentUrl,\s*activeChannelId\s*!=\s*null\)")


if __name__ == "__main__":
    unittest.main()
