package ev.buan.plugins.agent.web

import ev.buan.plugins.agent.api.AgentService
import ev.buan.plugins.agent.api.MemoryService
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.ToolRegistry
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.cordis.api.PluginStorage
import org.cordis.api.ServiceDirectory
import org.json.JSONObject

/**
 * A tiny HTTP server bridging the LAN web console to the agent: static assets from this plugin's
 * storage plus a JSON API that proxies [AgentService]. Hand-rolled on [ServerSocket] because a
 * plugin container carries no web framework and adding one would bloat the jar for four routes.
 *
 * v1 honesty notes:
 *  - whole-turn JSON only; streaming (SSE) is a follow-up once the console needs live text;
 *  - token auth over plain HTTP — fine on a trusted LAN, not for exposure beyond it;
 *  - /api/approve returns 501 until agent-shell lands its approval bus.
 */
internal class WebConsoleServer(
    private val port: Int,
    private val token: String,
    private val storage: PluginStorage,
    private val directory: ServiceDirectory,
) {
    @Volatile
    private var running = false
    private var socket: ServerSocket? = null
    private var thread: Thread? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        if (running) return
        running = true
        val server = ServerSocket(port)
        socket = server
        thread = Thread {
            while (running) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute { serve(client) }
            }
        }.apply {
            isDaemon = true
            name = "agent-web"
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        pool.shutdownNow()
        runCatching { thread?.join(500) }
    }

    private fun serve(client: java.net.Socket) {
        client.use { socket ->
            val response = try {
                val request = HttpExchange.readRequest(socket.getInputStream()) ?: return
                route(request)
            } catch (error: HttpRequestException) {
                HttpResponse.text(error.status, error.message ?: "bad request")
            } catch (error: Throwable) {
                HttpResponse.text(500, "内部错误：${error.message ?: error::class.simpleName}")
            }
            socket.getOutputStream().write(response.bytes())
            socket.getOutputStream().flush()
        }
    }

    private fun route(request: HttpRequest): HttpResponse {
        if (!authorized(request)) return HttpResponse.json(401, """{"error":"需要访问令牌"}""")
        return when {
            request.path == "/api/capabilities" && request.method == "GET" -> capabilities()
            request.path == "/api/chat" && request.method == "POST" -> chat(request)
            request.path == "/api/approve" && request.method == "POST" ->
                HttpResponse.json(501, """{"error":"审批通道要等 agent-shell 落地"}""")
            request.path.startsWith("/api/") ->
                HttpResponse.json(404, """{"error":"未知接口"}""")
            request.method != "GET" -> HttpResponse.text(405, "仅支持 GET")
            else -> staticFile(request.path)
        }
    }

    private fun authorized(request: HttpRequest): Boolean =
        request.header("x-ev-token") == token || request.query["token"] == token

    private fun capabilities(): HttpResponse {
        val agent = directory.lookup(AgentService.Key)
        val json = JSONObject()
            .put("agentConnected", agent != null)
            .put("agentReady", agent?.ready ?: false)
            .put("model", directory.lookup(ModelService.Key) != null)
            .put("tools", directory.lookup(ToolRegistry.Key) != null)
            .put("memory", directory.lookup(MemoryService.Key) != null)
            .put("apiVersion", 1)
        return HttpResponse.json(200, json.toString())
    }

    private fun chat(request: HttpRequest): HttpResponse {
        val agent = directory.lookup(AgentService.Key)
            ?: return HttpResponse.json(200, JSONObject()
                .put("content", "agent 未连接：请在插件管理页启用 agent-brain 及其能力插件。")
                .put("events", org.json.JSONArray())
                .toString())
        val body = runCatching { JSONObject(request.body.decodeToString()) }.getOrNull()
            ?: return HttpResponse.json(400, """{"error":"请求体不是合法 JSON"}""")
        val sessionId = body.optString("sessionId", "web")
        val text = body.optString("text", "")
        if (text.isBlank()) return HttpResponse.json(400, """{"error":"text 不能为空"}""")
        val reply = runBlocking { agent.send(sessionId, text) }
        val events = org.json.JSONArray()
        reply.events.forEach { event ->
            events.put(JSONObject()
                .put("kind", event.kind.name)
                .put("text", event.text)
                .put("toolName", event.toolName)
                .put("toolCallId", event.toolCallId)
                .put("argumentsJson", event.argumentsJson))
        }
        val json = JSONObject()
            .put("content", reply.message.content)
            .put("events", events)
            .put("ready", agent.ready)
        return HttpResponse.json(200, json.toString())
    }

    /** Static console assets live in this plugin's storage under web/ (adb push the React dist). */
    private fun staticFile(path: String): HttpResponse {
        val relative = if (path == "/") "web/index.html" else "web${path}"
        val bytes = storage.read(relative)
            ?: return HttpResponse.text(404, "未找到 $relative（把 React 构建产物推到本插件存储区的 web/ 目录）")
        return HttpResponse(200, HttpExchange.mimeOf(relative), bytes)
    }

    companion object {
        const val DEFAULT_PORT = 8765
    }
}
