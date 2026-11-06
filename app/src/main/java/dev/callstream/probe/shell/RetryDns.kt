package dev.callstream.probe.shell

import okhttp3.Dns
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * DNS for Sebastian's network calls. The helper runs as the shell user, and on the Redmi a lookup sometimes fails at the
 * moment a WhatsApp call connects ("Unable to resolve host streaming.assemblyai.com", 2026-11-04, twice): with one
 * attempt and no reconnect he then stayed deaf and mute for the whole call. So: retry a few times, and when the phone
 * still can't resolve, use the addresses that worked last time (kept in a small file).
 */
internal object RetryDns : Dns {
    private const val CACHE = "/data/local/tmp/sebastian/dns.cache"
    private const val ATTEMPTS = 4
    private const val PAUSE_MS = 500L

    override fun lookup(hostname: String): List<InetAddress> {
        var last: UnknownHostException? = null
        for (attempt in 1..ATTEMPTS) {
            try {
                val found = Dns.SYSTEM.lookup(hostname)
                if (found.isNotEmpty()) { remember(hostname, found); return found }
            } catch (e: UnknownHostException) {
                last = e
            }
            if (attempt < ATTEMPTS) Thread.sleep(PAUSE_MS)
        }
        recall(hostname)?.let { return it }
        throw last ?: UnknownHostException(hostname)
    }

    @Synchronized private fun remember(host: String, addrs: List<InetAddress>) = runCatching {
        val f = File(CACHE)
        val all = readAll(f).toMutableMap()
        val line = addrs.mapNotNull { it.hostAddress }.joinToString(",")
        if (line.isEmpty()) return@runCatching
        if (all[host] == line) return@runCatching
        all[host] = line
        f.writeText(all.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    @Synchronized private fun recall(host: String): List<InetAddress>? = runCatching {
        val line = readAll(File(CACHE))[host] ?: return null
        line.split(",").filter { it.isNotBlank() }.map { InetAddress.getByName(it) }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun readAll(f: File): Map<String, String> =
        if (!f.exists()) emptyMap() else f.readLines().mapNotNull { l -> l.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
}
