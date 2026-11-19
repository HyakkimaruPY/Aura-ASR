package com.aurareels.ocrlab

import org.json.JSONObject
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.min
import java.util.concurrent.Executors
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/** Real on-demand ASR/OCR bundles. No speech/vision model is packaged in the APK. */
data class LabModel(val id: String, val title: String, val language: String?, val sizeHint: String, val type: Type) {
    enum class Type { VOSK, AUDIO_LID, OCR, VAD, SENSEVOICE, WHISPER }
}

object LabModels {
    val all = listOf(
        LabModel("lid", "Detector de idioma do áudio · Whisper Tiny", null, "obrigatório para ASR automático", LabModel.Type.AUDIO_LID),
        LabModel("en", "Inglês", "en", "Vosk Small · ~40 MB", LabModel.Type.VOSK),
        LabModel("fr", "Francês", "fr", "Vosk Small · ~41 MB", LabModel.Type.VOSK),
        LabModel("zh", "Chinês (mandarim)", "zh", "Vosk Small · ~42 MB", LabModel.Type.VOSK),
        LabModel("ja", "Japonês", "ja", "Vosk Small · ~48 MB", LabModel.Type.VOSK),
        LabModel("it", "Italiano", "it", "Vosk Small · ~48 MB", LabModel.Type.VOSK),
        LabModel("tr", "Turco", "tr", "Vosk Small · ~35 MB", LabModel.Type.VOSK),
        LabModel("ko", "Coreano", "ko", "Vosk Small · ~82 MB", LabModel.Type.VOSK),
        LabModel("vad", "Detector de voz (VAD) · Silero", null, "~2 MB · filtra ruído/latidos e acelera o ASR", LabModel.Type.VAD),
        LabModel("sensevoice", "Japonês · Chinês · Coreano · Inglês · SenseVoice", null, "download grande (~150 MB) · MUITO rápido, ideal para idiomas velozes", LabModel.Type.SENSEVOICE),
        LabModel("whisper", "Árabe · Turco · Francês · Italiano e +50 idiomas · Whisper Base", null, "download grande (centenas de MB) · multilíngue", LabModel.Type.WHISPER),
        LabModel("whisper-small", "Whisper Small int8 · opcional", null, "~375 MB · mais RAM/CPU; desempenho depende do aparelho", LabModel.Type.WHISPER),
        LabModel("ocr", "OCR chinês · PP-OCRv6 Small", null, "ONNX + dicionário · ~20 MB", LabModel.Type.OCR)
    )
    private val voskBundles = mapOf(
        "en" to "vosk-model-small-en-us-0.15", "fr" to "vosk-model-small-fr-0.22",
        "zh" to "vosk-model-small-cn-0.22", "ja" to "vosk-model-small-ja-0.22",
        "it" to "vosk-model-small-it-0.22", "tr" to "vosk-model-small-tr-0.3", "ko" to "vosk-model-small-ko-0.22"
    )
    /** Two-letter codes Whisper Base can transcribe in this app (Javanese etc. are deliberately absent). */
    val whisperLanguages = LanguageCatalog.whisperLanguages
    val senseVoiceLanguages = LanguageCatalog.senseVoiceLanguages
    fun voskBundle(id: String): String = requireNotNull(voskBundles[id])
    fun byId(id: String): LabModel = all.first { it.id == id }
}

/** Recognition engines. SENSEVOICE/WHISPER run on VAD segments; VOSK is the legacy streaming fallback. */
enum class AsrBackend { SENSEVOICE, WHISPER, VOSK }

class ModelRepository(context: Context) {
    private val appContext = context.applicationContext
    private val base = File(context.filesDir, "lab-models").apply { mkdirs() }
    // Several models may install at the same time; only the SAME model is locked while it installs.
    private val io = Executors.newFixedThreadPool(3) { Thread(it, "aura-model-installer") }
    private val main = Handler(Looper.getMainLooper())
    private val mirrorPrefs = appContext.getSharedPreferences("model-mirrors", Context.MODE_PRIVATE)
    private val downloader = MirrorDownloader()
    private fun manifest(): JSONObject = JSONObject(
        File(base,"models_manifest.json").takeIf { it.isFile }?.readText()
            ?: appContext.assets.open("models_manifest.json").bufferedReader().use { it.readText() })
    fun importManifest(raw: String) {
        val json=JSONObject(raw); require(json.getInt("version")==1)
        val defaults=JSONObject(appContext.assets.open("models_manifest.json").bufferedReader().use { it.readText() }).getJSONObject("models")
        val incoming=json.getJSONObject("models")
        require(incoming.keys().asSequence().toSet()==defaults.keys().asSequence().toSet()) { "Manifesto deve manter todos os modelos" }
        for(id in incoming.keys()) {
            val resources=incoming.getJSONArray(id); val original=defaults.getJSONArray(id)
            require(resources.length()==original.length())
            for(i in 0 until resources.length()) {
                val r=resources.getJSONObject(i); val o=original.getJSONObject(i)
                for(key in listOf("filename","archive","root","keep")) require(r.get(key).toString()==o.get(key).toString()) { "Estrutura não pode mudar: $id/$key" }
                require(r.getLong("sizeBytes") in 0..1_199_999_999)
                require(r.getString("sha256").matches(Regex("[a-fA-F0-9]{64}|")))
                val mirrors=r.getJSONArray("mirrors"); require(mirrors.length() in 0..8)
                val ids=HashSet<String>(); val urls=HashSet<String>()
                for(j in 0 until mirrors.length()) {
                    val m=mirrors.getJSONObject(j); val u=java.net.URI(m.getString("url"))
                    require(u.scheme=="https" && !u.host.isNullOrEmpty() && u.userInfo==null)
                    require(ids.add(m.getString("id")) && urls.add(u.toString()))
                }
            }
        }
        val temp=File(base,"manifest-new.tmp"); temp.writeText(raw)
        check(temp.renameTo(File(base,"models_manifest.json"))) { "Falha ao salvar manifesto" }
    }
    private fun resources(id: String) = manifest().getJSONObject("models").getJSONArray(id)
    private fun extractResource(r: JSONObject, archive: File, staging: File, op: Op) {
        when(r.getString("archive")) {
            "zip" -> unzipSafe(archive,staging,r.getString("root"),op)
            "tar.bz2" -> {
                val keep=r.getJSONArray("keep")
                untarBzipSafe(archive,staging,r.getString("root"),(0 until keep.length()).map { keep.getString(it) }.toSet(),op)
            }
            else -> {
                require(archive.length()>128) { "Arquivo vazio" }
                archive.copyTo(File(staging,r.getString("filename")),overwrite=true)
            }
        }
    }

    /** Live state of one install/remove. Shared (companion) so every ModelRepository instance sees it. */
    class Op(val title: String) {
        @Volatile var status = "Preparando…"
        @Volatile var cancelled = false
    }
    private companion object {
        val ops = ConcurrentHashMap<String, Op>()
        val errors = ConcurrentHashMap<String, String>()
        val validated = ConcurrentHashMap<String,Pair<Long,Boolean>>()
        const val SEGMENTS = 4                      // parallel connections for one file
        const val MIN_SEGMENTED = 6_000_000L        // smaller files use a single connection
    }
    /** Status text of a running operation for [id], or null when idle (UI polls this; no stuck dialogs). */
    fun operationStatus(id: String): String? = ops[id]?.status
    fun isBusy(id: String): Boolean = ops.containsKey(id)
    fun lastError(id: String): String? = errors[id]
    fun cancel(id: String) { ops[id]?.let { it.cancelled = true; it.status = "Cancelando…" } }

    init {
        // Leftovers of a killed/crashed install must never block or confuse a new one.
        io.execute {
            base.listFiles()?.forEach { f ->
                if (f.name.startsWith(".") && (f.name.endsWith("-install") || f.name.endsWith(".download") || f.name.endsWith("-old"))
                    && !ops.containsKey(f.name.removePrefix(".").substringBefore("-").substringBefore(".")))
                    if(f.name.endsWith("-old")) {
                        val id=f.name.removePrefix(".").removeSuffix("-old")
                        if(!directory(id).exists()) f.renameTo(directory(id)) else f.deleteRecursively()
                    } else f.deleteRecursively()
            }
        }
    }
    fun directory(id: String): File = File(base, id)
    fun installed(id: String): Boolean {
        val marker=File(directory(id),".ready")
        if(!marker.isFile) { validated.remove(id); return false }
        val stamp=marker.lastModified()
        validated[id]?.takeIf { it.first==stamp }?.let { return it.second }
        return valid(id,directory(id)).also { validated[id]=stamp to it }
    }
    fun installedAsr(): List<LabModel> = LabModels.all.filter { it.type == LabModel.Type.VOSK && installed(it.id) }
    fun file(id: String, name: String): File = File(directory(id), name)

    /** Best installed engine for [code], or null. SenseVoice > Whisper (both need the VAD) > Vosk. */
    fun asrBackendFor(code: String, music: Boolean=false): AsrBackend? {
        val c = LanguageMap.canonical(code)
        if (c.isEmpty()) return null
        if (installed("vad")) {
            if(music && c in LabModels.whisperLanguages && (installed("whisper-small") || installed("whisper"))) return AsrBackend.WHISPER
            if (c in LabModels.senseVoiceLanguages && installed("sensevoice")) return AsrBackend.SENSEVOICE
            if (c in LabModels.whisperLanguages && (installed("whisper") || installed("whisper-small"))) return AsrBackend.WHISPER
        }
        if (LabModels.all.any { it.type == LabModel.Type.VOSK && it.id == c } && installed(c)) return AsrBackend.VOSK
        return null
    }

    /** Canonical codes of every language that currently has a usable engine. */
    fun asrLanguages(): List<String> {
        val codes = LinkedHashSet<String>()
        if (installed("vad")) {
            if (installed("sensevoice")) codes.addAll(LabModels.senseVoiceLanguages)
            if (installed("whisper") || installed("whisper-small")) codes.addAll(LabModels.whisperLanguages)
        }
        for (m in installedAsr()) codes.add(m.id)
        return codes.toList()
    }
    fun whisperPack(music: Boolean=false): String = if(installed("whisper-small") && (music || AppPrefs(appContext).preferWhisperSmall || !installed("whisper"))) "whisper-small" else "whisper"
    fun anyAsrInstalled(): Boolean = asrLanguages().isNotEmpty()

    private val lidFiles = listOf("tiny-encoder.int8.onnx", "tiny-decoder.int8.onnx")

    /** True when the APK ships the spoken-language detector in assets/lid (see scripts/fetch_lid_model.py). */
    fun bundledLidAvailable(): Boolean = try {
        val present = appContext.assets.list("lid")?.toSet().orEmpty()
        lidFiles.all { it in present }
    } catch (_: Exception) { false }

    /** Copies the bundled detector into private storage (sherpa needs real file paths). Idempotent. */
    fun ensureBundledLid(onDone: (Boolean, String) -> Unit) {
        if (installed("lid")) { main.post { onDone(true, "Detector de idioma pronto") }; return }
        if (!bundledLidAvailable()) { main.post { onDone(false, "Detector não embutido neste APK; baixe em Modelos") }; return }
        val op = Op("Detector de idioma")
        if (ops.putIfAbsent("lid", op) != null) { main.post { onDone(false, "Detector já está sendo preparado") }; return }
        io.execute {
            val staging = File(base, ".lid-install")
            try {
                deleteTree(staging); staging.mkdirs()
                op.status = "Copiando detector embutido…"
                for (name in lidFiles) appContext.assets.open("lid/$name").use { input ->
                    FileOutputStream(File(staging, name)).use { input.copyTo(it, 256 * 1024) }
                }
                require(valid("lid", staging)) { "Detector embutido incompleto" }
                File(staging, ".ready").writeText("lid\n")
                val destination = directory("lid")
                deleteTree(destination)
                check(staging.renameTo(destination)) { "Falha ao instalar detector embutido" }
                main.post { onDone(true, "Detector de idioma embutido preparado") }
            } catch (e: Throwable) {
                deleteTree(staging)
                main.post { onDone(false, "Detector embutido: ${e.message}") }
            } finally { ops.remove("lid") }
        }
    }

    private val githubAsr = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"

    fun install(model: LabModel, onStatus: (String) -> Unit, onDone: (Boolean, String) -> Unit) {
        val op = Op(model.title)
        if (ops.putIfAbsent(model.id, op) != null) { main.post { onDone(false, "${model.title} já está sendo baixado") }; return }
        errors.remove(model.id)
        io.execute {
            val staging = File(base, ".${model.id}-install")
            val archive = File(base, ".${model.id}.download")
            try {
                deleteTree(staging); staging.mkdirs(); archive.delete()
                val resources=resources(model.id)
                for(i in 0 until resources.length()) {
                    val r=resources.getJSONObject(i); val mirrors=r.getJSONArray("mirrors")
                    require(mirrors.length()>0) { "No verified download mirror for ${model.id}; import a licensed model manually" }
                    val endpoints=(0 until mirrors.length()).map { j -> val m=mirrors.getJSONObject(j)
                        ModelMirror(m.getString("id"),m.getString("url"),m.optInt("priority",10)) }
                    val key="${model.id}:$i"
                    val winner=downloader.download(MirrorDownloader.Resource(endpoints,r.optLong("sizeBytes"),r.optString("sha256")),archive,
                        mirrorPrefs.getString(key,null),{ op.cancelled },{ op.status=it }) { downloaded ->
                        extractResource(r,downloaded,staging,op)
                        if(resources.length()==1) require(valid(model.id,staging)) { "Arquivos obrigatórios ausentes ou inválidos" }
                        else if(model.id=="whisper-small") require(File(staging,r.getString("filename")).length()>if(r.getString("filename").endsWith(".onnx")) 10_000_000 else 1_000)
                        else if(r.getString("filename")=="inference.onnx") require(File(staging,"inference.onnx").length()>15_000_000)
                        else require(File(staging,"rec_chars.txt").useLines { lines -> lines.count() }>=6000)
                    }
                    mirrorPrefs.edit().putString(key,winner).apply(); archive.delete()
                }
                archive.delete()                                   // free the space before validating/moving
                op.status = "Validando e aplicando…"
                publish(onStatus, op.status)
                require(valid(model.id, staging)) { "O modelo baixado está incompleto ou não é compatível" }
                File(staging, ".ready").writeText("${model.id}\n")
                val destination = directory(model.id)
                // Keep the previous install until the new one is in place.
                val backup = File(base, ".${model.id}-old")
                deleteTree(backup)
                if (destination.exists()) check(destination.renameTo(backup)) { "Falha ao substituir modelo anterior" }
                if (!staging.renameTo(destination)) {
                    if (backup.exists()) backup.renameTo(destination)
                    error("Falha ao confirmar modelo no armazenamento")
                }
                deleteTree(backup); validated.remove(model.id)
                // Release the lock BEFORE notifying: whoever reacts to "done" must already see the model as idle+installed.
                ops.remove(model.id)
                publish(onStatus, "${model.title}: instalado e validado")
                main.post { onDone(true, "${model.title} instalado") }
            } catch (e: Throwable) {                               // Throwable: an OutOfMemoryError must not leave the lock held
                deleteTree(staging)
                val reason = if (op.cancelled) "cancelado" else (e.message ?: e.javaClass.simpleName)
                if (!op.cancelled) errors[model.id] = reason
                ops.remove(model.id)
                main.post { onDone(false, "Falha em ${model.title}: $reason") }
            } finally { archive.delete(); ops.remove(model.id) }
        }
    }

    fun remove(model: LabModel, isUsed: (String) -> Boolean, onDone: (Boolean, String) -> Unit) {
        if (isUsed(model.id)) { onDone(false, "Pare a reprodução antes de excluir este modelo"); return }
        val op = Op(model.title).apply { status = "Excluindo…" }
        if (ops.putIfAbsent(model.id, op) != null) { onDone(false, "${model.title} está em operação"); return }
        io.execute {
            val ok = try { deleteTree(directory(model.id)); !directory(model.id).exists() } catch (_: Throwable) { false }
            ops.remove(model.id)
            errors.remove(model.id); validated.remove(model.id)
            main.post { onDone(ok, if (ok) "${model.title} excluído" else "Erro ao excluir ${model.title}") }
        }
    }

    private fun valid(id: String, dir: File): Boolean = when (id) {
        "ocr" -> File(dir, "inference.onnx").length() > 15_000_000 &&
            (File(dir, "rec_chars.txt").takeIf { it.exists() }?.useLines { lines -> lines.count() } ?: 0) >= 6000
        "lid" -> File(dir, "tiny-encoder.int8.onnx").length() > 1_000_000 &&
            File(dir, "tiny-decoder.int8.onnx").length() > 1_000_000
        "vad" -> File(dir, "silero_vad.onnx").length() > 300_000
        "sensevoice" -> File(dir, "model.int8.onnx").length() > 20_000_000 && File(dir, "tokens.txt").length() > 1_000
        "whisper-small" -> File(dir, "small-encoder.int8.onnx").length() > 100_000_000 &&
            File(dir, "small-decoder.int8.onnx").length() > 250_000_000 && File(dir,"small-tokens.txt").length() > 1_000
        "whisper" -> File(dir, "base-encoder.int8.onnx").length() > 10_000_000 &&
            File(dir, "base-decoder.int8.onnx").length() > 10_000_000 && File(dir, "base-tokens.txt").length() > 1_000
        else -> ArchivePaths.voskLooksComplete(
            exists = { File(dir, it).exists() },
            sizeOf = { File(dir, it).length() }
        )
    }

    /** User-selected local archive; same validation and atomic publication as the downloader. OFF UI. */
    fun importArchive(id: String, input: InputStream) {
        val op=Op("Importar $id")
        check(ops.putIfAbsent(id,op)==null) { "Modelo ocupado" }
        val staging=File(base,".$id-install"); val archive=File(base,".$id.download")
        try {
            deleteTree(staging); staging.mkdirs()
            FileOutputStream(archive).use { out ->
                val buf=ByteArray(128*1024); var size=0L
                while(true) { val n=input.read(buf); if(n<0) break; size+=n; require(size<1_200_000_000); if(op.cancelled) error("Cancelado"); out.write(buf,0,n) }
            }
            if(id in setOf("ocr","whisper-small")) unzipSafe(archive,staging,"",op) else extractResource(resources(id).getJSONObject(0),archive,staging,op)
            require(valid(id,staging)) { "Estrutura do pacote não reconhecida" }
            File(staging,".ready").writeText("$id\n")
            val dest=directory(id); val backup=File(base,".$id-old"); deleteTree(backup)
            if(dest.exists()) check(dest.renameTo(backup))
            if(!staging.renameTo(dest)) { backup.renameTo(dest); error("Falha ao aplicar") }
            deleteTree(backup); errors.remove(id); validated.remove(id)
        } finally { archive.delete(); deleteTree(staging); ops.remove(id) }
    }

    /** Counts compressed bytes consumed so extraction can show a real percentage (was silent = "stuck at 99%"). */
    private class ProgressStream(source: InputStream, private val total: Long, private val op: Op, private val label: String) : FilterInputStream(source) {
        private var read = 0L
        private var last = 0L
        private fun tick(n: Int) {
            if (op.cancelled) throw java.io.IOException("Cancelado")
            if (n <= 0) return
            read += n
            val now = System.currentTimeMillis()
            if (now - last > 400 && total > 0) { last = now; op.status = "$label: ${min(99L, 100 * read / total)}%" }
        }
        override fun read(): Int { val b = super.read(); if (b >= 0) tick(1); return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); tick(n); return n }
    }

    private fun unzipSafe(archive: File, destination: File, expectedRoot: String, op: Op) {
        // The root folder name can differ from the catalog; accept the archive's single root.
        val root = java.util.zip.ZipFile(archive).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            if (names.any { it.replace('\\', '/').removePrefix("./").startsWith("$expectedRoot/") }) expectedRoot
            else ArchivePaths.commonRoot(names) ?: ""
        }
        op.status = "Extraindo: 0%"
        ZipInputStream(ProgressStream(FileInputStream(archive).buffered(256 * 1024), archive.length(), op, "Extraindo")).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val relative = ArchivePaths.stripRoot(entry.name, root) ?: continue
                copyEntry(zip, destination, relative, entry.isDirectory)
                zip.closeEntry()
            }
        }
    }
    private fun untarBzipSafe(archive: File, destination: File, root: String, keep: Set<String>, op: Op) {
        op.status = "Extraindo: 0% (descompactar leva alguns minutos em modelos grandes)"
        val input = ProgressStream(FileInputStream(archive).buffered(256 * 1024), archive.length(), op, "Extraindo (pode levar minutos)")
        TarArchiveInputStream(BZip2CompressorInputStream(input)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (entry.isSymbolicLink || entry.isLink) continue
                // Root folder name may differ from the catalog: files are matched by their own name.
                val relative = ArchivePaths.stripRoot(entry.name, root) ?: entry.name.replace('\\', '/').substringAfterLast('/')
                // Keep only the files the app uses (skips unquantized duplicates and demo audio).
                if (relative !in keep) continue
                copyEntry(tar, destination, relative, entry.isDirectory)
            }
        }
    }
    private fun copyEntry(input: java.io.InputStream, base: File, relative: String, dir: Boolean) {
        val target = File(base, relative)
        require(ArchivePaths.isInside(base, relative)) { "Entrada perigosa em arquivo de modelo" }
        if (dir) { target.mkdirs(); return }
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(256 * 1024)
            var bytes = 0L
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                bytes += n
                require(bytes < 600_000_000) { "Arquivo extraído acima do limite" }
                output.write(buffer, 0, n)
            }
        }
    }
    private fun publish(callback: (String) -> Unit, msg: String) = main.post { callback(msg) }
    private fun deleteTree(file: File) { if (file.exists()) file.deleteRecursively() }
    fun close() { /* installs keep running if the Activity goes away; they are independent of the UI */ }
}
