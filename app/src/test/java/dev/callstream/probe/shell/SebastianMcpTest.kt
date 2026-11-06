package dev.callstream.probe.shell

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SebastianMcpTest {
    private val requests = mutableListOf<Pair<JSONObject, Map<String, String?>>>()
    private var useEventStream = false
    /** A fake MCP server, answering at the HTTP level. */
    private val client = OkHttpClient.Builder().addInterceptor { handle(it) }.build()

    private fun handle(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val message = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        requests += message to listOf("Authorization", "Mcp-Session-Id", "MCP-Protocol-Version", "Accept").associateWith { request.header(it) }
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("")
        if (!message.has("id")) return builder.code(202).body("".toResponseBody()).build()
        val result = when (message.getString("method")) {
            "initialize" -> JSONObject().put("protocolVersion", "2025-03-26")
            "tools/list" -> JSONObject().put("tools", JSONArray().put(JSONObject().put("name", "create.ticket")
                .put("description", "Open a ticket")
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject().put("title", JSONObject().put("type", "string"))))))
            "tools/call" -> {
                val title = message.getJSONObject("params").getJSONObject("arguments").getString("title")
                JSONObject().put("isError", title == "bad")
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "opened $title")))
            }
            else -> error("unexpected ${message.getString("method")}")
        }
        val reply = JSONObject().put("jsonrpc", "2.0").put("id", message.get("id")).put("result", result).toString()
        val type = if (useEventStream) "text/event-stream" else "application/json"
        if (message.getString("method") == "initialize") builder.header("Mcp-Session-Id", "session-1")
        return builder.code(200).header("Content-Type", type)
            .body((if (useEventStream) "event: message\ndata: $reply\n\n" else reply).toResponseBody(type.toMediaType())).build()
    }

    private fun connected(): SebastianMcp {
        val entry = JSONObject().put("name", "desk").put("url", "https://mcp.test/mcp").put("token", "secret")
        return SebastianMcp(JSONObject().put("mcp_servers", JSONArray().put(entry)), client).also { it.connect {} }
    }

    @Test fun offersAServersToolsToTheLlmUnderAPrefixedName() {
        val fn = connected().definitions().getJSONObject(0).getJSONObject("function")
        assertEquals("mcp_desk__create_ticket", fn.getString("name"))
        assertEquals("Open a ticket", fn.getString("description"))
        assertTrue(fn.getJSONObject("parameters").getJSONObject("properties").has("title"))
    }

    @Test fun callsTheToolByItsRealNameAndReturnsItsText() {
        val result = connected().call("mcp_desk__create_ticket", JSONObject().put("title", "printer"))
        assertEquals("opened printer", result.getString("result"))
        assertEquals("create.ticket", requests.last().first.getJSONObject("params").getString("name"))
    }

    @Test fun sendsTheHandshakeThenTheSessionOnEveryLaterRequest() {
        connected()
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list"), requests.map { it.first.getString("method") })
        requests.forEach { assertEquals("Bearer secret", it.second["Authorization"]) }
        requests.forEach { assertEquals("application/json, text/event-stream", it.second["Accept"]) }
        assertEquals("session-1", requests.last().second["Mcp-Session-Id"])
        assertEquals("2025-03-26", requests.last().second["MCP-Protocol-Version"])
    }

    @Test fun readsARepliesSentAsAnEventStream() {
        useEventStream = true
        assertEquals("opened x", connected().call("mcp_desk__create_ticket", JSONObject().put("title", "x")).getString("result"))
    }

    @Test fun reportsAFailedToolAsAnError() {
        assertEquals("opened bad", connected().call("mcp_desk__create_ticket", JSONObject().put("title", "bad")).getString("error"))
    }

    @Test fun aServerThatIsDownIsSkipped() {
        val entry = JSONObject().put("name", "down").put("url", "https://mcp.test/mcp")
        val down = OkHttpClient.Builder().addInterceptor { throw IOException("refused") }.build()
        val logs = mutableListOf<String>()
        val mcp = SebastianMcp(JSONObject().put("mcp_servers", JSONArray().put(entry)), down)
        mcp.connect { logs += it }
        assertEquals(0, mcp.definitions().length())
        assertTrue(logs.single().startsWith("mcp down failed"))
    }

    @Test fun noServersMeansNoTools() {
        val mcp = SebastianMcp(JSONObject(), OkHttpClient())
        mcp.connect {}
        assertEquals(0, mcp.definitions().length())
        assertFalse(mcp.handles("web_search"))
    }

    @Test fun tokensNeverGoOverPlainHttpToAnotherMachine() {
        assertFalse(SebastianMcp.isAllowedUrl("http://example.com/mcp"))
        assertFalse(SebastianMcp.isAllowedUrl("http://127.0.0.1.example.com/mcp"))
        assertTrue(SebastianMcp.isAllowedUrl("https://example.com/mcp"))
    }

    @Test fun aBadUrlInTheConfigOnlySkipsThatServer() {
        val entry = JSONObject().put("name", "typo").put("url", "not a url")
        val logs = mutableListOf<String>()
        SebastianMcp(JSONObject().put("mcp_servers", JSONArray().put(entry)), OkHttpClient()).connect { logs += it }
        assertTrue(logs.single().startsWith("mcp typo failed"))
    }

    @Test fun findsOurReplyAmongOtherEvents() {
        val raw = "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\ndata: {\"id\":7,\"result\":{}}\n\n"
        assertEquals(7, SebastianMcp.parseReply("text/event-stream; charset=utf-8", raw, 7).getInt("id"))
    }
}
