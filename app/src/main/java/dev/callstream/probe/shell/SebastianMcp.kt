package dev.callstream.probe.shell

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A small MCP client (Streamable HTTP transport): every server in the config's "mcp_servers" list hands Sebastian
 * its tools for the call. Tools are listed once, in the background, so a slow server never delays an answer.
 *
 * Covers initialize, tools/list and tools/call with no auth or a bearer token. Not covered: OAuth, stdio servers.
 */
internal class SebastianMcp(cfg: JSONObject, client: OkHttpClient) {
    private class Tool(val server: Server, val name: String)

    private val http = client.newBuilder().callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS).build()
    private val servers = cfg.optJSONArray("mcp_servers").objects()
        .map { Server(it.optString("name", "mcp"), it.optString("url"), it.optString("token"), http) }

    @Volatile private var tools = emptyMap<String, Tool>()
    @Volatile private var definitions = JSONArray()

    /** Connects to every server and lists its tools. One that fails is skipped; the call carries on without it. */
    fun connect(log: (String) -> Unit) {
        val found = linkedMapOf<String, Tool>()
        val defs = JSONArray()
        for (server in servers) {
            runCatching {
                server.initialize()
                val listed = server.listTools()
                for (tool in listed) {
                    val exposed = exposedName(server.name, tool.getString("name"))
                    found[exposed] = Tool(server, tool.getString("name"))
                    defs.put(definition(exposed, tool))
                }
                log("mcp ${server.name}: ${listed.size} tools")
            }.onFailure { log("mcp ${server.name} failed: ${it.javaClass.simpleName}: ${it.message}") }
        }
        tools = found
        definitions = defs
    }

    /** The tool definitions to offer the LLM, in the same shape as the built-in ones. */
    fun definitions(): JSONArray = definitions

    fun handles(exposedName: String) = exposedName in tools

    /** Runs a tool. The LLM reads the result, so it's capped: a large document must not flood the conversation. */
    fun call(exposedName: String, args: JSONObject): JSONObject {
        val tool = tools[exposedName] ?: return JSONObject().put("error", "unknown tool $exposedName")
        val result = tool.server.callTool(tool.name, args)
        val text = text(result).take(MAX_RESULT_CHARS)
        return if (result.optBoolean("isError")) JSONObject().put("error", text) else JSONObject().put("result", text)
    }

    private class Server(val name: String, val url: String, val token: String, val http: OkHttpClient) {
        private val json = "application/json".toMediaType()
        private var sessionId: String? = null
        private var protocol: String? = null
        private var nextId = 1

        fun initialize() {
            require(isAllowedUrl(url)) { "needs an https URL" }
            val result = rpc("initialize", JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "sebastian").put("version", "1.0")))
            protocol = result.optString("protocolVersion", PROTOCOL_VERSION)
            post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")).close()
        }

        fun listTools(): List<JSONObject> {
            val all = mutableListOf<JSONObject>()
            var cursor = ""
            repeat(MAX_TOOL_PAGES) {
                val page = rpc("tools/list", if (cursor.isEmpty()) JSONObject() else JSONObject().put("cursor", cursor))
                all += page.optJSONArray("tools").objects()
                cursor = page.optString("nextCursor")
                if (cursor.isEmpty()) return all
            }
            return all
        }

        fun callTool(tool: String, args: JSONObject) =
            rpc("tools/call", JSONObject().put("name", tool).put("arguments", args))

        private fun rpc(method: String, params: JSONObject): JSONObject {
            val id = nextId++
            post(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)).use { r ->
                val raw = r.body?.string().orEmpty()
                check(r.isSuccessful) { "HTTP ${r.code}: ${raw.take(200)}" }
                r.header("Mcp-Session-Id")?.let { sessionId = it }
                val reply = parseReply(r.header("Content-Type").orEmpty(), raw, id)
                reply.optJSONObject("error")?.let { error(it.optString("message", "MCP error")) }
                return reply.getJSONObject("result")
            }
        }

        private fun post(message: JSONObject) = http.newCall(Request.Builder().url(url)
            .header("Accept", "application/json, text/event-stream")
            .apply {
                if (token.isNotEmpty()) header("Authorization", "Bearer $token")
                sessionId?.let { header("Mcp-Session-Id", it) }
                protocol?.let { header("MCP-Protocol-Version", it) }
            }
            .post(message.toString().toRequestBody(json)).build()).execute()
    }

    companion object {
        private const val PROTOCOL_VERSION = "2025-06-18"
        private const val CALL_TIMEOUT_S = 20L
        private const val MAX_RESULT_CHARS = 4000
        private const val MAX_TOOL_PAGES = 5
        private const val MAX_TOOL_NAME = 64

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        /** Tokens travel to this URL, so only a machine's own loopback may use plain http. */
        fun isAllowedUrl(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            return parsed.isHttps || parsed.host == "127.0.0.1" || parsed.host == "localhost"
        }

        /** The LLM Gateway only accepts [a-zA-Z0-9_-] in tool names, and two servers may share a tool name. */
        fun exposedName(server: String, tool: String) =
            "mcp_${server}__$tool".replace(Regex("[^a-zA-Z0-9_-]"), "_").take(MAX_TOOL_NAME)

        /** A reply arrives as plain JSON or as server-sent events; in the second case ours is the event with our id. */
        fun parseReply(contentType: String, raw: String, id: Int): JSONObject {
            if (!contentType.startsWith("text/event-stream")) return JSONObject(raw)
            return raw.split(Regex("\\r?\\n\\r?\\n"))
                .map { event -> event.lines().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").trim() } }
                .filter { it.isNotEmpty() }
                .map { JSONObject(it) }
                .firstOrNull { it.optInt("id", -1) == id }
                ?: error("no reply in the event stream")
        }

        /** The readable part of a tools/call result: its text blocks, or its structured output if it has only that. */
        fun text(result: JSONObject): String {
            val blocks = result.optJSONArray("content").objects().filter { it.optString("type") == "text" }
            return blocks.joinToString("\n") { it.optString("text") }.ifEmpty { result.optJSONObject("structuredContent")?.toString().orEmpty() }
        }

        private fun definition(exposed: String, tool: JSONObject) = JSONObject()
            .put("type", "function")
            .put("function", JSONObject().put("name", exposed)
                .put("description", tool.optString("description").ifEmpty { tool.getString("name") })
                .put("parameters", tool.optJSONObject("inputSchema") ?: JSONObject().put("type", "object").put("properties", JSONObject())))
    }
}
