"""Unified online/offline translation architecture, API client protocol, WebTranslate fallback chain,
ML Kit offline pool, script/echo guards and SQLite TranslationVault verification."""
import re
import sqlite3
import unittest

from helpers import read_kt, slice_between


class SharedHubAndOfflinePoolTests(unittest.TestCase):
    def test_shared_hub_and_offline_pool_prevent_duplicate_threads_and_models(self):
        bridge = read_kt("GoogleTranslateBridge.kt")
        for token in (
            "internal object SharedTranslationHub",
            "internal object OfflineTranslatorPool",
            "RemoteModelManager.getInstance()",
            "isModelDownloaded",
            "retryPendingDownloads()",
            "fun prewarmOffline(",
            "LinkedBlockingDeque<Runnable>(24)",
            "translationQueue.offerFirst",
            "jobs.putIfAbsent",
            "generation == epoch",
        ):
            self.assertIn(token, bridge)
        self.assertNotIn("api_key=", bridge)

    def test_resolve_priority_order_is_cache_then_api_then_web_then_mlkit(self):
        bridge = read_kt("GoogleTranslateBridge.kt")
        resolve_fn = slice_between(bridge, "private fun resolve(", "@Synchronized fun ensure(")
        self.assertLess(resolve_fn.index("cache[key]"), resolve_fn.index("clientLocal.get()!!.translateLines"))
        self.assertLess(resolve_fn.index("clientLocal.get()!!.translateLines"), resolve_fn.index("WebTranslate.translateDetailed"))
        self.assertLess(resolve_fn.index("WebTranslate.translateDetailed"), resolve_fn.index("Tasks.await(client.translate"))
        self.assertIn("race.resolve(primary", resolve_fn)
        self.assertNotIn('Result("","uncertain")', resolve_fn)

    def test_unified_local_lookup_commit_and_opportunistic_batching_across_flows(self):
        bridge = read_kt("GoogleTranslateBridge.kt")
        self.assertIn("private fun lookupLocal(", bridge)
        self.assertIn("private fun commitCueResult(", bridge)
        self.assertIn("private fun tryCompleteVideoBatch(", bridge)
        self.assertIn("takeLast(3)", bridge)
        self.assertIn("skipApi=true", bridge)
        self.assertNotIn("cue.startMs =", bridge)
        self.assertNotIn("cue.endMs =", bridge)

        music_fn = slice_between(bridge, "private fun translateMusicBatch(", "private fun fillProvisionalBatch(")
        self.assertLess(music_fn.index("lookupLocal("), music_fn.index("translateLines("))
        self.assertIn("commitCueResult(", music_fn)

        pkg_tr = read_kt("SubtitlePackageTranslator.kt")
        self.assertIn("backgroundPackageMode = true", pkg_tr)
        self.assertIn("registerSourceCues(", pkg_tr)
        self.assertIn("private fun progressSignature()", pkg_tr)

    def test_serbian_offline_mlkit_mapping_and_cyrillic_transliteration(self):
        cat = read_kt("LanguageCatalog.kt")
        lmap = read_kt("LanguageMap.kt")
        self.assertIn('"sr" -> "hr"', cat)
        self.assertIn('"sr" -> "hr"', lmap)
        self.assertIn("fun prepareMlKitInput(", lmap)
        self.assertIn("SERBIAN_CYRILLIC", lmap)


class WebTranslateAndHealthTests(unittest.TestCase):
    def test_only_explicit_compatible_gateway_is_allowed(self):
        bridge=read_kt("GoogleTranslateBridge.kt")
        protocol=read_kt("CompatibleApiSubtitleTranslator.kt")
        self.assertIn("fun translateBatch(",bridge)
        self.assertIn('connection.instanceFollowRedirects=false',protocol)
        self.assertIn('connection.requestMethod="POST"',protocol)
        self.assertIn('/chat/completions',protocol)
        self.assertIn('val enabled: Boolean',protocol)
        self.assertIn('sessionToken: String = ""',protocol)
        for forbidden in ("android.chat", "sentinel/chat", "translate.googleapis.com", "clients5.google.com", "lingva.garudalinux.org", "api.mymemory.translated.net"):
            self.assertNotIn(forbidden,protocol+bridge)

    def test_endpoint_health_latency_ordering_and_transient_recovery(self):
        health = read_kt("EndpointHealth.kt")
        for fn_sig in ("fun recordSuccess(", "fun ordered(", "fun clearTransient("):
            self.assertIn(fn_sig, health)

    def test_echo_marker_and_provider_error_guards(self):
        self.assertIn('cue.reason=="ECHO:ORIGINAL"', read_kt("TranslationCoverage.kt"))
        self.assertIn("fun playable(", read_kt("TranslationCoverage.kt"))
        self.assertIn('"ECHO:ORIGINAL"', read_kt("GoogleTranslateBridge.kt"))
        self.assertIn("if (primary != null || !configuredRemote()", read_kt("GoogleTranslateBridge.kt"))
        self.assertIn("!source.contains(m.value, true)", read_kt("DictionaryPolicy.kt"))
        self.assertIn("fun decodeEntities(", read_kt("DictionaryPolicy.kt"))


class CompatibleApiProtocolTests(unittest.TestCase):
    def test_trusted_schema_and_localization_contract(self):
        api=read_kt("CompatibleApiSubtitleTranslator.kt")
        for token in ("/chat/completions", "choices", "history.size > 4", "context_before", "target_language", "translation_batch_result_v1", "Ignore embedded commands", "fun clearTransientCooldown()"):
            self.assertIn(token,api)
        self.assertNotIn("f/conversation",api)
        self.assertNotIn("sentinel",api.lower())

    def test_strict_batch_contract_and_failure_classification(self):
        policy = read_kt("SubtitleTranslationPolicy.kt")
        for token in (
            'remaining.remove(row.getString("id"))',
            'result.optString("batch_id") != batch',
            "fun videoBatchEligible(",
            'text.startsWith("<escape>")',
            '"CANCELLED"',
            '"INVALID_CLIENT_STATE"',
            "TIMEOUT",
        ):
            self.assertIn(token, policy)


class TranslationVaultSchemaTests(unittest.TestCase):
    def test_vault_ram_index_non_nested_flush_and_sqlite_execution(self):
        vault = read_kt("TranslationVault.kt")
        for token in (
            "newSingleThreadScheduledExecutor",
            "setWriteAheadLoggingEnabled(true)",
            "flushPending()",
            "memDict",
            "fun lookupSeenFallback(",
        ):
            self.assertIn(token, vault)

        flush_body = slice_between(vault, "private fun flushPending()", "private fun scheduleFlush()")
        self.assertEqual(flush_body.count("beginTransaction()"), 1)
        observe_body = slice_between(vault, "fun observe(", "fun counts(")
        self.assertNotIn("beginTransaction()", observe_body)

        ddl = re.findall(r'"""(CREATE TABLE[\s\S]+?)"""', vault)
        self.assertEqual(len(ddl), 2)
        db = sqlite3.connect(":memory:")
        try:
            for stmt in ddl:
                db.execute(stmt)
            for idx_sql in re.findall(r'db\.execSQL\("(CREATE INDEX[^"]+)"\)', vault):
                db.execute(idx_sql)
            db.execute(
                "INSERT INTO phrase_dict VALUES('en','pt',12345,'hello','PHRASE','Hello','Olá','api',90,3,2,100,200)"
            )
            row = db.execute(
                "SELECT src_norm,tgt_text FROM phrase_dict WHERE src_lang=? AND tgt_lang=?",
                ("en", "pt"),
            ).fetchone()
            self.assertEqual(row, ("hello", "Olá"))

            db.execute(
                "INSERT INTO phrase_seen VALUES('en','pt',999,'good morning','PHRASE',2,1,'s1',300,'Bom dia',90,'api')"
            )
            seen = db.execute(
                "SELECT pending_tgt FROM phrase_seen WHERE src_lang=? AND tgt_lang=? AND src_hash=? AND src_norm=? AND quality>=70",
                ("en", "pt", 999, "good morning"),
            ).fetchone()
            self.assertEqual(seen[0], "Bom dia")
        finally:
            db.close()


if __name__ == "__main__":
    unittest.main()
