package dev.callstream.probe.shell

import android.os.SystemClock
import android.util.Log
import dev.callstream.probe.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Bounded asynchronous metadata logging. Never retain PCM, transcripts, tokens or URLs. */
internal class CallLatencyTrace(val originNs: Long = SystemClock.elapsedRealtimeNanos()) {
    val id: String = UUID.randomUUID().toString()
    @Volatile var callId: String? = null
    // Concurrent so audio threads can skip an already-recorded mark without the lock or building its fields.
    private val once: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val stages = LinkedHashMap<String, Double>()
    private val windows = HashMap<String, Window>()
    val turns = TurnLatencyTracker { name, fields -> event(name, fields) }
    @Volatile var lastSummary = "latencyTrace=$id — waiting for timing data"

    init {
        event("trace_started", mapOf("wall_time_ms" to System.currentTimeMillis(),
            "app_version" to BuildConfig.VERSION_NAME, "build" to BuildConfig.VERSION_CODE,
            "caller_rms_threshold" to TurnLatencyTracker.CALLER_RMS,
            "agent_rms_threshold" to TurnLatencyTracker.AGENT_RMS,
            "activity_silence_ms" to TurnLatencyTracker.SILENCE_MS))
    }

    fun nowMs(): Double = (SystemClock.elapsedRealtimeNanos() - originNs) / 1_000_000.0

    /** Lock-free: true once [mark] recorded [name]. Per-frame call sites check this before building fields. */
    fun marked(name: String): Boolean = name in once

    @Synchronized fun mark(name: String, fields: Map<String, Any> = emptyMap()) {
        if (!once.add(name)) return
        val time = nowMs(); stages[name] = time
        event(name, fields, time)
        lastSummary = "latencyTrace=$id callId=${callId ?: "pending"} ${stages.entries.toList().takeLast(4).joinToString { "${it.key}=%.0fms".format(it.value) }}"
    }

    /** Queues the event; JSON is built on the writer thread, never on the calling (audio) thread. */
    fun event(name: String, fields: Map<String, Any> = emptyMap(), atMs: Double = nowMs()) {
        Writer.offer(Writer.Line(id, name, atMs, callId, fields))
    }

    @Synchronized fun sample(name: String, value: Double) {
        if (!value.isFinite()) return
        windows.getOrPut(name) { Window() }.add(value)
    }

    fun flush(fields: Map<String, Any> = emptyMap()) {
        val detached = synchronized(this) { HashMap(windows).also { windows.clear() } }
        val result = fields.toMutableMap()
        detached.forEach { (key, window) -> result[key] = JSONObject(window.snapshot()) }
        result["logger_dropped_total"] = Writer.dropped.get()
        event("audio_window", result)
    }

    fun finish(reason: String) {
        turns.finish(); flush()
        mark("session_finished", mapOf("reason" to reason))
    }

    private class Window {
        private var n = 0L; private var sum = 0.0; private var max = Double.NEGATIVE_INFINITY // windows exist only once sampled
        // Fixed-size ring: bounded even if a long-running path stops flushing.
        private val values = DoubleArray(512)
        fun add(value: Double) { values[(n % values.size).toInt()] = value; n++; sum += value; max = maxOf(max, value) }
        fun snapshot(): Map<String, Any> {
            val sorted = values.copyOf(minOf(n, values.size.toLong()).toInt()).sorted()
            fun percentile(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
            return mapOf("n" to n, "mean" to sum / n, "max" to max,
                "recent_p50" to percentile(.5), "recent_p95" to percentile(.95))
        }
    }

    private object Writer {
        class Line(val trace: String, val name: String, val atMs: Double, val callId: String?, val fields: Map<String, Any>)
        private val queue = ArrayBlockingQueue<Line>(1024)
        val dropped = AtomicLong()
        private val directory = File("/data/local/tmp/call-stream-metrics")
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024
        /** One open file per trace (a few traces can interleave: the call, then post-call metrics). */
        private class Open(val stream: FileOutputStream, var bytes: Long)
        private val open = LinkedHashMap<String, Open>()
        private var directoryReady = false

        init {
            Thread({
                while (true) {
                    val line = queue.take()
                    val json = JSONObject().put("schema", 1).put("trace", line.trace).put("event", line.name).put("t_ms", line.atMs)
                    line.callId?.let { json.put("call_id", it) }
                    line.fields.forEach { (key, value) -> json.put(key, value) }
                    val text = json.toString()
                    Log.i("CallStreamMetrics", text)
                    runCatching { append(line.trace, text) }
                        .onFailure { Log.w("CallStreamMetrics", "Metrics file unavailable: ${it.javaClass.simpleName}"); close(line.trace); directoryReady = false }
                }
            }, "callstream-metrics").apply { isDaemon = true; start() }
        }

        private fun append(trace: String, text: String) {
            val file = open[trace] ?: run {
                if (!directoryReady) {
                    directory.mkdirs(); directory.setReadable(false, false); directory.setWritable(false, false); directory.setExecutable(false, false)
                    directory.setReadable(true, true); directory.setWritable(true, true); directory.setExecutable(true, true)
                    directoryReady = true
                }
                val f = File(directory, "$trace.jsonl")
                if (!f.exists()) {
                    directory.listFiles()?.filter { it.extension == "jsonl" }?.sortedByDescending { it.lastModified() }
                        ?.drop(11)?.forEach { it.delete() }
                    f.createNewFile(); f.setReadable(false, false); f.setWritable(false, false)
                    f.setReadable(true, true); f.setWritable(true, true)
                }
                if (open.size >= 3) open.keys.first().let(::close)
                Open(FileOutputStream(f, true), f.length()).also { open[trace] = it }
            }
            val bytes = (text + "\n").toByteArray()
            if (file.bytes + bytes.size > MAX_FILE_BYTES) return
            file.stream.write(bytes); file.bytes += bytes.size
        }

        private fun close(trace: String) { open.remove(trace)?.let { runCatching { it.stream.close() } } }

        fun offer(line: Line) { if (!queue.offer(line)) dropped.incrementAndGet() }
    }
}
