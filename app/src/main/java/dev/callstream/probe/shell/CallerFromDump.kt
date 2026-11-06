package dev.callstream.probe.shell

import java.io.File

/**
 * Who a WhatsApp call is with, read by the shell helper from `dumpsys notification --noredact`, so it works without the
 * app's notification-access switch (which Android/HyperOS keeps turning off; with it off Sebastian called everyone "Them"
 * and had no number to WhatsApp a meeting link to, 2026-11-05). Reads WhatsApp's call notification: the name WhatsApp
 * shows (a saved contact's name, or the number for someone unsaved) and a number when the notification carries one.
 *
 * The dump's exact text for a call notification isn't known beforehand, so the parsing is forgiving and the raw call
 * record (or the list of WhatsApp records when there is none) is saved to [DEBUG_DIR] for the next fix.
 */
internal object CallerFromDump {
    private const val DEBUG_DIR = "/data/local/tmp/sebastian-rec"
    private val CALL_MARKERS = listOf("callstyle", "android.callperson", "android.hangupintent", "category=call", "android.answerintent")

    @Volatile private var lastRaw: String = ""

    /** "name<TAB>number" like the notification listener's answer, or null when no WhatsApp call notification is showing. */
    fun read(): String? {
        val dump = runCatching {
            val p = ProcessBuilder("dumpsys", "notification", "--noredact").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().also { p.waitFor() }
        }.getOrDefault("")
        val block = callBlock(dump)
        lastRaw = block ?: whatsAppHeaders(dump)
        return block?.let(::parse)
    }

    /** Saves what the last [read] saw, for working out the format. */
    fun saveDebug(tag: String) = runCatching {
        File(DEBUG_DIR).mkdirs()
        File(DEBUG_DIR, "ident_${System.currentTimeMillis()}_$tag.txt").writeText(lastRaw.take(20_000))
    }

    private fun records(dump: String): List<String> {
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (line in dump.lineSequence()) {
            if (line.trimStart().startsWith("NotificationRecord(")) {
                if (cur.isNotEmpty()) out += cur.toString()
                cur = StringBuilder()
            }
            if (cur.isNotEmpty() || line.trimStart().startsWith("NotificationRecord(")) cur.appendLine(line)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun isWhatsApp(rec: String) = rec.lineSequence().firstOrNull()?.let { "pkg=com.whatsapp" in it || "pkg=com.whatsapp.w4b" in it } == true

    private fun callBlock(dump: String): String? = records(dump).filter(::isWhatsApp)
        .firstOrNull { rec -> rec.lowercase().let { l -> CALL_MARKERS.any { it in l } } }

    private fun whatsAppHeaders(dump: String): String = "no WhatsApp call notification. WhatsApp records:\n" +
        records(dump).filter(::isWhatsApp).joinToString("\n") { it.lineSequence().first().trim() }

    internal fun parse(block: String): String? {
        val title = Regex("""android\.title=(?:[A-Za-z]+ \()?([^\r\n]*?)\)?\s*$""", RegexOption.MULTILINE).find(block)?.groupValues?.get(1)?.trim().orEmpty()
        val person = Regex("""android\.callPerson=.*?name=([^,}\r\n]+)""").find(block)?.groupValues?.get(1)?.trim().orEmpty()
        val name = person.ifBlank { title }.takeIf { it.isNotBlank() && !it.equals("null", true) } ?: return null
        val number = Regex("""tel:(\+?[0-9]{10,15})""").find(block)?.groupValues?.get(1)
            ?: Regex("""([0-9]{10,15})@s\.whatsapp\.net""").find(block)?.groupValues?.get(1)?.let { "+$it" }
            ?: ""
        return "${name.replace('\t', ' ')}\t$number"
    }
}
