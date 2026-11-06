package dev.callstream.probe.leads

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The lead sheet, in SQLite, owned by the shell helper (it dials on its own, with the app closed). The app
 * reads and edits it through the helper's binder. One table, one row per call; see [Lead].
 * All access is serialised on this object. The client's own columns come from [specOf] (the phone's client setup), read
 * on each use: a new agent file can add columns at any time, and they're added to the table then. Columns are never
 * dropped, so a sheet keeps what earlier setups recorded.
 */
class LeadStore(path: String, private val specOf: () -> ClientSpec) {
    private val db: SQLiteDatabase = open(File(path))
    /** Bumped on every change; the app re-reads the sheet when it moves. Starts at the clock so a helper restart moves it too. */
    @Volatile var version = System.currentTimeMillis()
        private set
    /** Columns the table has; declared before init, which fills it. */
    private var tableColumns: Set<String> = emptySet()

    init {
        synchronized(this) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS leads (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                number TEXT NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                call_no INTEGER NOT NULL DEFAULT 1,
                status TEXT NOT NULL DEFAULT 'pending',
                detail TEXT NOT NULL DEFAULT '',
                attempts INTEGER NOT NULL DEFAULT 0,
                called_at TEXT,
                follow_up_at TEXT,
                talk_time INTEGER NOT NULL DEFAULT 0,
                extraction TEXT NOT NULL DEFAULT '',
                transcript TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL DEFAULT '')""")
            db.execSQL("CREATE INDEX IF NOT EXISTS leads_number ON leads(number)")
            tableColumns = db.rawQuery("PRAGMA table_info(leads)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
            // Build 64 added the call transcript.
            if ("transcript" !in tableColumns) { db.execSQL("ALTER TABLE leads ADD COLUMN transcript TEXT NOT NULL DEFAULT ''"); tableColumns += "transcript" }
            // A helper that died mid-call left rows dialing: count them as failed tries.
            db.execSQL("UPDATE leads SET status = ?, detail = ? WHERE status IN (?, ?, ?)",
                arrayOf(Lead.FAILED, "Call Stream restarted during the call", Lead.DIALING, Lead.RINGING, Lead.IN_CALL))
        }
    }

    /** The client setup now, with its columns in the table. */
    private val spec: ClientSpec
        @Synchronized get() = specOf().also { s ->
            for (f in s.fields) if (f.key !in tableColumns) {
                require(f.key.matches(Regex("[a-z_]+"))) { "bad field key ${f.key}" }
                db.execSQL("ALTER TABLE leads ADD COLUMN ${f.key} TEXT NOT NULL DEFAULT ''")
                tableColumns += f.key
            }
        }

    private fun changed() { version = maxOf(version + 1, System.currentTimeMillis()) }

    private fun Cursor.lead(spec: ClientSpec): Lead {
        fun s(col: String) = getString(getColumnIndexOrThrow(col))
        fun i(col: String) = getInt(getColumnIndexOrThrow(col))
        return Lead(id = getLong(getColumnIndexOrThrow("id")), number = s("number"), name = s("name"), callNo = i("call_no"),
            status = s("status"), detail = s("detail"), attempts = i("attempts"), calledAt = s("called_at"),
            followUpAt = s("follow_up_at")?.ifEmpty { null }, talkTime = i("talk_time"), extraction = s("extraction"),
            createdAt = s("created_at"), fields = spec.fields.associate { it.key to (s(it.key) ?: "") }, hasTranscript = i("has_transcript") == 1)
    }

    /** Every column but the transcript (long; read on its own with [transcript]). */
    private fun columns(spec: ClientSpec) = (listOf("id", "number", "name", "call_no", "status", "detail", "attempts", "called_at", "follow_up_at",
        "talk_time", "extraction", "created_at") + spec.fields.map { it.key } + "transcript <> '' AS has_transcript").joinToString(", ")

    @Synchronized fun all(): List<Lead> = spec.let { s -> db.rawQuery("SELECT ${columns(s)} FROM leads", null).use { c -> buildList { while (c.moveToNext()) add(c.lead(s)) } } }

    @Synchronized fun get(id: Long): Lead? = spec.let { s -> db.rawQuery("SELECT ${columns(s)} FROM leads WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToNext()) c.lead(s) else null } }

    private fun forNumber(number: String) = spec.let { s -> db.rawQuery("SELECT ${columns(s)} FROM leads WHERE number = ?", arrayOf(number)).use { c -> buildList { while (c.moveToNext()) add(c.lead(s)) } } }

    private fun values(lead: Lead) = ContentValues().apply {
        put("number", lead.number); put("name", lead.name); put("call_no", lead.callNo); put("status", lead.status)
        put("detail", lead.detail); put("attempts", lead.attempts); put("called_at", lead.calledAt); put("follow_up_at", lead.followUpAt)
        put("talk_time", lead.talkTime); put("extraction", lead.extraction)
        spec.fields.forEach { put(it.key, lead.field(it.key)) }
    }

    private fun insert(lead: Lead): Long =
        db.insertOrThrow("leads", null, values(lead).apply { put("created_at", LeadTime.SECOND.format(LeadTime.now())) })

    /** An upload (`[{"name", "number"}, …]`): new numbers become rows to call; see LeadRules.planImport. */
    @Synchronized fun import(contactsJson: String): String {
        val array = JSONArray(contactsJson)
        val contacts = (0 until array.length()).map { array.getJSONObject(it).let { o -> o.optString("name") to o.getString("number") } }
        val existing = HashMap<String, Boolean>()
        db.rawQuery("SELECT number, MAX(name <> '') FROM leads GROUP BY number", null).use { c -> while (c.moveToNext()) existing[c.getString(0)] = c.getInt(1) == 1 }
        val plan = LeadRules.planImport(existing, contacts)
        db.beginTransaction()
        try {
            plan.inserts.forEach { (name, number) -> insert(Lead(number = number, name = name)) }
            plan.nameFills.forEach { (number, name) -> db.execSQL("UPDATE leads SET name = ? WHERE number = ? AND name = ''", arrayOf(name, number)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (plan.inserts.isNotEmpty() || plan.nameFills.isNotEmpty()) changed()
        return JSONObject().put("added", plan.inserts.size).put("duplicates", plan.duplicates).put("names_filled", plan.nameFills.size).toString()
    }

    /**
     * A page of the sheet for the app: `{"filter": "all|interested|follow_ups|not_called", "search", "offset", "limit"}`.
     * Interested is one row per person (their latest qualifying call); see LeadRules.interested.
     */
    @Synchronized fun query(queryJson: String): String {
        val q = JSONObject(queryJson)
        val filter = runCatching { LeadRules.Filter.valueOf(q.optString("filter", "all").uppercase()) }.getOrDefault(LeadRules.Filter.ALL)
        val search = q.optString("search")
        val all = all()
        val rule = spec.interested
        val interested = rule?.let { LeadRules.interested(all, it) }.orEmpty()
        val rows = matching(all, interested, filter, search)
        val offset = q.optInt("offset").coerceAtLeast(0)
        val limit = q.optInt("limit", 100).coerceIn(1, 1000)
        val interestedIds = all.filter { rule?.invoke(it) == true }.map { it.id }.toSet()
        val page = JSONArray()
        rows.drop(offset).take(limit).forEach { (lead, calls) ->
            page.put(lead.toJson().put("interested", lead.id in interestedIds).apply { if (calls > 0) put("calls", calls) })
        }
        val counts = JSONObject().put("all", all.size).put("interested", interested.size)
            .put("follow_ups", all.count { LeadRules.matches(it, LeadRules.Filter.FOLLOW_UPS) })
            .put("not_called", all.count { LeadRules.matches(it, LeadRules.Filter.NOT_CALLED) })
        return JSONObject().put("version", version).put("total", rows.size).put("offset", offset).put("counts", counts).put("rows", page).toString()
    }

    /**
     * The app's add or edit. A row in a call can't be edited. Setting a callback time on a finished call
     * schedules it (see [scheduleFollowUp]). Returns the saved row.
     */
    @Synchronized fun save(leadJson: String): String {
        val edit = Lead.fromJson(JSONObject(leadJson))
        val number = LeadRules.canonicalNumber(edit.number)
        require(number.isNotEmpty() && number.all { it.isDigit() || it == '+' } && number.count { it.isDigit() } in 3..15) { "Enter a valid phone number" }
        val followUp = edit.followUpAt?.let { LeadTime.clean(it) ?: throw IllegalArgumentException("Callback time isn't a date") }
        val fields = spec.fields.associate { f ->
            val v = edit.field(f.key).trim()
            f.key to if (f.type == FieldType.DATETIME && v.isNotEmpty()) LeadTime.clean(v) ?: throw IllegalArgumentException("${f.label} isn't a date") else v
        }
        val id: Long
        if (edit.id == 0L) {
            require(forNumber(number).isEmpty()) { "$number is already in the sheet" }
            id = insert(Lead(number = number, name = edit.name.trim(), followUpAt = followUp, talkTime = edit.talkTime.coerceAtLeast(0), fields = fields))
        } else {
            val old = get(edit.id) ?: throw IllegalArgumentException("That lead was deleted")
            check(!old.active) { "This lead is in a call right now; edit it after the call" }
            val updated = old.copy(number = number, name = edit.name.trim(), followUpAt = followUp, talkTime = edit.talkTime.coerceAtLeast(0), fields = fields)
            db.update("leads", values(updated), "id = ?", arrayOf(old.id.toString()))
            id = old.id
            // A callback set on a call that already happened: the next call is its own row.
            if (old.status != Lead.PENDING && followUp != null && followUp != old.followUpAt) scheduleFollowUp(number, updated.name, followUp)
        }
        changed()
        return get(id)!!.toJson().toString()
    }

    /** Deletes one row, or every row for its number. A row in a call can't be deleted. */
    @Synchronized fun delete(id: Long, allForNumber: Boolean): String {
        val lead = get(id) ?: return JSONObject().put("deleted", 0).toString()
        val rows = if (allForNumber) forNumber(lead.number) else listOf(lead)
        check(rows.none { it.active }) { "This lead is in a call right now" }
        rows.forEach { db.delete("leads", "id = ?", arrayOf(it.id.toString())) }
        changed()
        return JSONObject().put("deleted", rows.size).toString()
    }

    /**
     * Many leads at once: every call to each chosen number, as the app's selection says. `{"ids": [...]}` picks the rows
     * ticked; `{"filter", "search"}` everything that filter and search show (Select all, beyond the loaded page).
     * Numbers with a row in a call are left alone and counted.
     */
    @Synchronized fun deleteMany(requestJson: String): String {
        val r = JSONObject(requestJson)
        val all = all()
        val chosen: List<Lead> = r.optJSONArray("ids")?.let { ids -> (0 until ids.length()).map { ids.getLong(it) }.toSet().let { set -> all.filter { it.id in set } } }
            ?: run {
                val filter = runCatching { LeadRules.Filter.valueOf(r.optString("filter", "all").uppercase()) }.getOrDefault(LeadRules.Filter.ALL)
                val interested = spec.interested?.let { LeadRules.interested(all, it) }.orEmpty()
                matching(all, interested, filter, r.optString("search")).map { it.first }
            }
        val plan = LeadRules.planDelete(all, chosen.map { it.number }.toSet())
        db.beginTransaction()
        try {
            plan.ids.forEach { db.delete("leads", "id = ?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (plan.ids.isNotEmpty()) changed()
        return JSONObject().put("deleted", plan.ids.size).put("people", plan.people).put("in_call", plan.inCall).toString()
    }

    /** The rows a filter and search show, in sheet order; Interested is one row per person, with their call count. */
    private fun matching(all: List<Lead>, interested: List<Pair<Lead, Int>>, filter: LeadRules.Filter, search: String): List<Pair<Lead, Int>> =
        if (filter == LeadRules.Filter.INTERESTED) interested.filter { LeadRules.search(it.first, search) }
        else all.filter { LeadRules.matches(it, filter) && LeadRules.search(it, search) }.sortedWith(LeadRules.SHEET_ORDER).map { it to 0 }

    /**
     * Back to not called: the row is dialed again as a new lead. Its call outcome (status, tries, last dialed,
     * talk time, callback time) is cleared; name, number and the client's fields stay. A row in a call can't be reset.
     */
    @Synchronized fun reset(id: Long): String {
        val lead = get(id) ?: throw IllegalArgumentException("That lead was deleted")
        check(!lead.active) { "This lead is in a call right now" }
        db.execSQL("UPDATE leads SET status = ?, detail = '', attempts = 0, called_at = NULL, follow_up_at = NULL, talk_time = 0, extraction = '', transcript = '' WHERE id = ?",
            arrayOf(Lead.PENDING, id))
        changed()
        return get(id)!!.toJson().toString()
    }

    /** The callback row for [number]: the waiting one is moved to [at], else a new row with the next call number. */
    private fun scheduleFollowUp(number: String, name: String, at: String) {
        val rows = forNumber(number)
        val waiting = rows.firstOrNull { it.status == Lead.PENDING && it.followUpAt != null }
        if (waiting != null) db.execSQL("UPDATE leads SET follow_up_at = ? WHERE id = ?", arrayOf(at, waiting.id))
        else insert(Lead(number = number, name = name, callNo = (rows.maxOfOrNull { it.callNo } ?: 0) + 1, followUpAt = at))
    }

    // --- The outbound run (helper only) ---

    @Synchronized fun dialing(id: Long) {
        db.execSQL("UPDATE leads SET status = ?, detail = '', attempts = attempts + 1, called_at = ? WHERE id = ?",
            arrayOf(Lead.DIALING, LeadTime.SECOND.format(LeadTime.now()), id))
        changed()
    }

    @Synchronized fun status(id: Long, status: String, detail: String = "", talkSeconds: Int? = null) {
        if (talkSeconds != null) db.execSQL("UPDATE leads SET status = ?, detail = ?, talk_time = ? WHERE id = ?", arrayOf(status, detail, talkSeconds, id))
        else db.execSQL("UPDATE leads SET status = ?, detail = ? WHERE id = ?", arrayOf(status, detail, id))
        changed()
    }

    @Synchronized fun extracted(id: Long, result: CallExtractor.Result) {
        val lead = get(id) ?: return
        val values = ContentValues().apply {
            put("extraction", "done")
            result.followUpAt?.let { put("follow_up_at", it) }
            result.fields.forEach { (k, v) -> put(k, v) }
        }
        db.update("leads", values, "id = ?", arrayOf(id.toString()))
        val name = lead.name.ifBlank { result.name.orEmpty() }
        if (lead.name.isBlank() && name.isNotBlank()) db.execSQL("UPDATE leads SET name = ? WHERE number = ? AND name = ''", arrayOf(name, lead.number))
        result.followUpAt?.let { scheduleFollowUp(lead.number, name, it) }
        changed()
    }

    /** The call's transcript, `[{"who": "Agent"|"Lead", "text"}, …]`, saved after the call. */
    @Synchronized fun saveTranscript(id: Long, transcript: List<Pair<String, String>>) {
        val json = org.json.JSONArray().apply { transcript.forEach { (who, text) -> put(JSONObject().put("who", who).put("text", text.trim())) } }
        db.execSQL("UPDATE leads SET transcript = ? WHERE id = ?", arrayOf(json.toString(), id))
        changed()
    }

    /** The saved transcript as JSON ("[]" if none). */
    @Synchronized fun transcript(id: Long): String =
        db.rawQuery("SELECT transcript FROM leads WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToNext()) c.getString(0).ifEmpty { "[]" } else "[]" }

    @Synchronized fun extractionFailed(id: Long, why: String) {
        db.execSQL("UPDATE leads SET extraction = ? WHERE id = ?", arrayOf(why.take(200), id))
        changed()
    }

    /** Earlier calls to this number, for the agent's opening: when, and what's already known. */
    @Synchronized fun history(lead: Lead): List<Lead> = forNumber(lead.number).filter { it.id != lead.id && it.status == Lead.COMPLETED }.sortedBy { it.callNo }

    private companion object {
        /**
         * Opening a database reads SQLite's WAL flags from global Settings once per process. HyperOS refuses the shell
         * helper that provider ("Given calling package android does not match caller's uid 2000", Redmi, 2026-10-24),
         * so there the flags are set to their defaults instead (a hidden, test-only setter) and the open is retried.
         */
        fun open(file: File): SQLiteDatabase = try {
            SQLiteDatabase.openOrCreateDatabase(file, null)
        } catch (e: SecurityException) {
            runCatching {
                Class.forName("android.database.sqlite.SQLiteCompatibilityWalFlags").getMethod("init", String::class.java).invoke(null, null)
            }.onFailure { e.addSuppressed(it); throw e }
            SQLiteDatabase.openOrCreateDatabase(file, null)
        }
    }
}
