package com.runway.assistant.data

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.security.MessageDigest

/**
 * 素材读写。
 *
 * 目录结构：
 * ```
 * <root>/<sessionId>/
 *   session.json
 *   recording.jsonl
 *   checksum.sha256
 * ```
 */
object RecordingStore {

    private const val FILE_MANIFEST = "session.json"
    private const val FILE_EVENTS = "recording.jsonl"
    private const val FILE_CHECKSUM = "checksum.sha256"

    fun rootDir(context: Context): File =
        File(context.filesDir, "sessions").apply { mkdirs() }

    fun sessionDir(context: Context, sessionId: String): File =
        File(rootDir(context), sessionId)

    fun listSessions(context: Context): List<File> =
        rootDir(context).listFiles { f -> f.isDirectory && File(f, FILE_MANIFEST).exists() }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    fun readManifest(dir: File): SessionManifest? {
        val file = File(dir, FILE_MANIFEST)
        if (!file.exists()) return null
        return runCatching { SessionManifest.fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    fun readEvents(dir: File): List<JSONObject> {
        val file = File(dir, FILE_EVENTS)
        if (!file.exists()) return emptyList()
        val out = ArrayList<JSONObject>(4096)
        BufferedReader(file.reader()).useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank()) {
                    runCatching { out.add(JSONObject(line)) }
                }
            }
        }
        return out
    }

    fun checksum(dir: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(FILE_MANIFEST, FILE_EVENTS).forEach { name ->
            val file = File(dir, name)
            if (file.exists()) digest.update(file.readBytes())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun writeChecksum(dir: File) {
        File(dir, FILE_CHECKSUM).writeText(checksum(dir) + "  session\n")
    }

    /** 一次录制的写入器，负责 JSONL 落盘与清单生成。 */
    class Writer(
        private val dir: File,
        private val manifestProvider: (counts: Map<String, Int>, durationMs: Long) -> SessionManifest,
    ) : AutoCloseable {

        private val eventsFile = File(dir, FILE_EVENTS)
        private val writer: BufferedWriter = BufferedWriter(FileWriter(eventsFile, true), 1 shl 16)
        private val counts = HashMap<String, Int>()
        private val startedAtMs = System.currentTimeMillis()
        private var lastFlushMs = 0L
        private var closed = false

        init {
            dir.mkdirs()
        }

        fun write(event: JSONObject) {
            if (closed) return
            writer.write(event.toString())
            writer.newLine()
            val type = event.optString("type", "unknown")
            counts[type] = (counts[type] ?: 0) + 1
            val now = System.currentTimeMillis()
            if (now - lastFlushMs > 2_000) {
                writer.flush()
                lastFlushMs = now
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { writer.flush() }
            runCatching { writer.close() }
            val duration = System.currentTimeMillis() - startedAtMs
            val manifest = manifestProvider(counts.toMap(), duration)
            File(dir, FILE_MANIFEST).writeText(manifest.toJson().toString(2))
            writeChecksum(dir)
        }
    }
}
