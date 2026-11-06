package dev.callstream.probe.leads

import java.time.Duration
import java.time.LocalDateTime

/** Pure rules for the lead sheet: what to call next, who is interested, uploads and exports. JVM-tested. */
object LeadRules {
    /** Fresh leads and retries are dialed only from 9:00 to 21:00 Pakistan time; callbacks go when the lead asked. */
    const val HOURS_START = 9
    const val HOURS_END = 21
    /** The first try plus two retries. */
    const val MAX_ATTEMPTS = 3
    val RETRY_GAP: Duration = Duration.ofHours(2)

    sealed class Next {
        data class Call(val lead: Lead, val why: String) : Next()
        /** Nothing to dial now. [until]: when something will be (null: nothing left at all). */
        data class Wait(val until: LocalDateTime?, val why: String) : Next()
    }

    fun inHours(t: LocalDateTime, start: Int = HOURS_START, end: Int = HOURS_END) = t.hour in start until end

    private fun nextHoursStart(t: LocalDateTime, start: Int, end: Int): LocalDateTime {
        if (inHours(t, start, end)) return t
        val today = t.toLocalDate().atTime(start, 0)
        return if (t.isBefore(today)) today else today.plusDays(1)
    }

    /**
     * The next row to dial. Order: callbacks that are due (earliest first), at any hour; then, within calling
     * hours, never-called leads in upload order; then retries of unanswered calls, oldest attempt first.
     */
    fun pickNext(leads: List<Lead>, now: LocalDateTime, start: Int = HOURS_START, end: Int = HOURS_END): Next {
        val followUps = leads.filter { it.status == Lead.PENDING && it.followUpAt != null }
            .mapNotNull { l -> LeadTime.parse(l.followUpAt)?.let { l to it } }
        followUps.filter { !it.second.isAfter(now) }.minWithOrNull(compareBy<Pair<Lead, LocalDateTime>> { it.second }.thenBy { it.first.id })
            ?.let { return Next.Call(it.first, "callback") }
        val fresh = leads.filter { it.status == Lead.PENDING && it.followUpAt == null }
        val retries = leads.filter { it.status in Lead.RETRYABLE && it.attempts < MAX_ATTEMPTS }
            .map { l -> l to (LeadTime.parse(l.calledAt)?.plus(RETRY_GAP) ?: now) }
        val open = inHours(now, start, end)
        if (open) {
            fresh.minByOrNull { it.id }?.let { return Next.Call(it, "new") }
            retries.filter { !it.second.isAfter(now) }.minByOrNull { it.second }?.let { return Next.Call(it.first, "retry") }
        }
        // Nothing now: when will there be?
        val times = ArrayList<Pair<LocalDateTime, String>>()
        followUps.minOfOrNull { it.second }?.let { times += it to "next callback" }
        if (fresh.isNotEmpty()) times += nextHoursStart(now, start, end) to "calling hours start"
        retries.minOfOrNull { it.second }?.let { times += nextHoursStart(maxOf(it, now), start, end) to "next retry" }
        val soonest = times.minByOrNull { it.first } ?: return Next.Wait(null, "nothing left to call")
        return Next.Wait(soonest.first, soonest.second)
    }

    /**
     * The interested people: one line per number, from its latest call that qualifies under [rule].
     * [calls] per number is attached so the export can say how many calls there were.
     */
    fun interested(leads: List<Lead>, rule: (Lead) -> Boolean): List<Pair<Lead, Int>> {
        val calls = leads.groupingBy { it.number }.eachCount()
        return leads.filter(rule).groupBy { it.number }.values
            .map { rows -> rows.maxWith(compareBy<Lead> { it.callNo }.thenBy { it.id }) }
            .sortedByDescending { it.id }
            .map { it to (calls[it.number] ?: 1) }
    }

    enum class Filter { ALL, INTERESTED, FOLLOW_UPS, NOT_CALLED }

    fun matches(lead: Lead, filter: Filter): Boolean = when (filter) {
        Filter.ALL, Filter.INTERESTED -> true
        Filter.FOLLOW_UPS -> lead.status == Lead.PENDING && lead.followUpAt != null
        Filter.NOT_CALLED -> lead.status == Lead.PENDING && lead.followUpAt == null && lead.attempts == 0
    }

    fun search(lead: Lead, text: String): Boolean {
        val q = text.trim()
        if (q.isEmpty()) return true
        val digits = q.filter { it.isDigit() }
        return lead.name.contains(q, ignoreCase = true) || (digits.isNotEmpty() && lead.number.filter { it.isDigit() }.contains(digits)) ||
            lead.fields.values.any { it.contains(q, ignoreCase = true) }
    }

    /** Sheet order: calls in progress, then due-soonest callbacks, then newest first. */
    val SHEET_ORDER: Comparator<Lead> = compareBy<Lead> { if (it.active) 0 else if (it.status == Lead.PENDING && it.followUpAt != null) 1 else 2 }
        .thenBy { if (it.status == Lead.PENDING) it.followUpAt.orEmpty() else "" }
        .thenByDescending { it.id }

    /**
     * Pakistani numbers in one form (0300…), so +92 300…, 0092 300… and 92300… are the same lead.
     * Other numbers are kept as given.
     */
    fun canonicalNumber(number: String): String {
        val n = number.trim()
        val digits = n.filter { it.isDigit() }
        return when {
            n.startsWith("+92") && digits.length == 12 -> "0" + digits.drop(2)
            digits.startsWith("0092") && digits.length == 14 -> "0" + digits.drop(4)
            !n.startsWith("+") && digits.startsWith("92") && digits.length == 12 -> "0" + digits.drop(2)
            else -> n
        }
    }

    data class ImportPlan(val inserts: List<Pair<String, String>>, val nameFills: Map<String, String>, val duplicates: Int)

    /**
     * An upload against the sheet. A number already there isn't added again; if the sheet has no name for it
     * and the upload does, the name is filled in. Repeats within the upload count as duplicates too.
     * [existing]: number → whether the sheet has a name for it.
     */
    fun planImport(existing: Map<String, Boolean>, contacts: List<Pair<String, String>>): ImportPlan {
        val inserts = LinkedHashMap<String, String>()
        val fills = HashMap<String, String>()
        var duplicates = 0
        for ((rawName, rawNumber) in contacts) {
            val number = canonicalNumber(rawNumber)
            val name = rawName.trim()
            when {
                number in inserts -> { duplicates++; if (inserts[number].isNullOrEmpty() && name.isNotEmpty()) inserts[number] = name }
                number in existing -> { duplicates++; if (existing[number] == false && name.isNotEmpty()) fills.putIfAbsent(number, name) }
                else -> inserts[number] = name
            }
        }
        return ImportPlan(inserts.map { it.value to it.key }, fills, duplicates)
    }

    data class DeletePlan(val ids: List<Long>, val people: Int, val inCall: Int)

    /** Every row of each number in [numbers], except numbers with a row in a call right now (those are only counted). */
    fun planDelete(all: List<Lead>, numbers: Set<String>): DeletePlan {
        val byNumber = all.filter { it.number in numbers }.groupBy { it.number }
        val (busy, free) = byNumber.values.partition { rows -> rows.any { it.active } }
        return DeletePlan(free.flatten().map { it.id }, free.size, busy.size)
    }

    fun talkTime(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

    /** The interested sheet for the client's callers: UTF-8 with a BOM so Excel shows Urdu names. */
    fun exportCsv(rows: List<Pair<Lead, Int>>, spec: ClientSpec): String {
        val header = listOf("name", "number") + spec.fields.map { it.key } + listOf("follow_up_at", "talk_time", "interested", "call_no", "calls", "called_at")
        val sb = StringBuilder("﻿")
        sb.append(header.joinToString(",") { csvField(it) }).append("\r\n")
        for ((lead, calls) in rows) {
            val values = listOf(lead.name, lead.number) + spec.fields.map { lead.field(it.key) } +
                listOf(lead.followUpAt.orEmpty(), talkTime(lead.talkTime), "yes", lead.callNo.toString(), calls.toString(), lead.calledAt.orEmpty())
            sb.append(values.joinToString(",") { csvField(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /** Quoted when needed; a leading =, +, - or @ is prefixed so a spreadsheet never runs it as a formula. */
    fun csvField(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in "=+-@" && !value.matches(Regex("[+-]?\\d[\\d ]*"))) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
