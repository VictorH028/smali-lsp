package xyz.surendrajat.smalilsp.shared

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A minimal LSP client that drives the real server binary over stdio with JSON-RPC
 * Content-Length framing, exactly as an editor would. The server runs in a separate JVM
 * on the test runtime classpath (`MainKt lsp`), so `System.exit` and stdout hygiene are
 * observable.
 *
 * Server → client requests are answered automatically: `window/workDoneProgress/create`
 * gets `null` (or an error when [rejectProgress] is set) and `client/registerCapability`
 * is recorded in [registrations] and acknowledged.
 */
class LspStdioClient(private val rejectProgress: Boolean = false) : AutoCloseable {

    private val gson = Gson()
    private val process: Process
    private val stdin: OutputStream
    private val inbox = LinkedBlockingQueue<JsonObject>()
    private val nextId = AtomicInteger(1)

    /** Every notification received, in order. */
    val notifications = mutableListOf<JsonObject>()
    /** Params of every `client/registerCapability` request the server sent. */
    val registrations = mutableListOf<JsonObject>()
    /** Methods of every server → client request. */
    val serverRequests = mutableListOf<String>()
    val stderr = StringBuilder()

    init {
        val javaBinary = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")
        require(classpath.isNotBlank()) { "test runtime classpath must not be blank" }
        process = ProcessBuilder(javaBinary, "-cp", classpath, "xyz.surendrajat.smalilsp.MainKt", "lsp")
            .directory(File(System.getProperty("user.dir")))
            .start()
        stdin = process.outputStream
        thread(isDaemon = true, name = "lsp-stdio-reader") { readLoop() }
        thread(isDaemon = true, name = "lsp-stderr-reader") {
            process.errorStream.bufferedReader().forEachLine { synchronized(stderr) { stderr.appendLine(it) } }
        }
    }

    private fun readLoop() {
        val input = BufferedInputStream(process.inputStream)
        while (true) {
            var contentLength = -1
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val (k, v) = line.split(":", limit = 2).let { it[0].trim().lowercase() to it.getOrElse(1) { "" }.trim() }
                if (k == "content-length") contentLength = v.toInt()
            }
            if (contentLength < 0) return
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n < 0) return
                read += n
            }
            inbox.put(JsonParser.parseString(String(body, Charsets.UTF_8)).asJsonObject)
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun send(message: Any) {
        val json = gson.toJson(message).toByteArray(Charsets.UTF_8)
        synchronized(stdin) {
            stdin.write("Content-Length: ${json.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
            stdin.write(json)
            stdin.flush()
        }
    }

    fun notify(method: String, params: Any? = null) {
        send(mapOf("jsonrpc" to "2.0", "method" to method, "params" to (params ?: emptyMap<String, Any>())))
    }

    /** Send a request and wait for its response, dispatching whatever else arrives meanwhile. */
    fun request(method: String, params: Any? = null, timeoutSeconds: Long = 30): JsonObject {
        val id = nextId.getAndIncrement()
        send(mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to (params ?: emptyMap<String, Any>())))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw TimeoutException("no response to $method within ${timeoutSeconds}s; stderr:\n$stderr")
            val msg = inbox.poll(remaining, TimeUnit.NANOSECONDS) ?: continue
            if (msg.has("id") && !msg.has("method") && msg["id"].asInt == id) return msg
            dispatch(msg)
        }
    }

    /** Result of a request, failing on a JSON-RPC error. */
    fun result(method: String, params: Any? = null, timeoutSeconds: Long = 30): JsonElement {
        val response = request(method, params, timeoutSeconds)
        check(!response.has("error")) { "$method failed: ${response["error"]}" }
        return response["result"] ?: JsonNull.INSTANCE
    }

    /** Pump messages until a notification matching [predicate] arrives. */
    fun awaitNotification(method: String, timeoutSeconds: Long = 60, predicate: (JsonObject) -> Boolean = { true }): JsonObject {
        notifications.firstOrNull { it["method"]?.asString == method && predicate(it) }?.let { return it }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw TimeoutException("no $method notification within ${timeoutSeconds}s; stderr:\n$stderr")
            val msg = inbox.poll(remaining, TimeUnit.NANOSECONDS) ?: continue
            dispatch(msg)
            if (msg.has("method") && !msg.has("id") && msg["method"].asString == method && predicate(msg)) return msg
        }
    }

    /** Pump whatever has arrived without blocking. */
    fun drain() {
        while (true) dispatch(inbox.poll() ?: return)
    }

    private fun dispatch(msg: JsonObject) {
        if (msg.has("method") && msg.has("id")) {
            val method = msg["method"].asString
            serverRequests.add(method)
            when (method) {
                "window/workDoneProgress/create" ->
                    if (rejectProgress) send(mapOf("jsonrpc" to "2.0", "id" to msg["id"].asInt, "error" to mapOf("code" to -32601, "message" to "Method not found")))
                    else send(mapOf("jsonrpc" to "2.0", "id" to msg["id"].asInt, "result" to null))
                "client/registerCapability" -> {
                    registrations.add(msg.getAsJsonObject("params"))
                    send(mapOf("jsonrpc" to "2.0", "id" to msg["id"].asInt, "result" to null))
                }
                else -> send(mapOf("jsonrpc" to "2.0", "id" to msg["id"].asInt, "result" to null))
            }
        } else if (msg.has("method")) {
            notifications.add(msg)
        }
    }

    /** The server's exit code, or null if it is still running after [timeoutSeconds]. */
    fun waitForExit(timeoutSeconds: Long = 10): Int? =
        if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) process.exitValue() else null

    fun isAlive(): Boolean = process.isAlive

    override fun close() {
        if (process.isAlive) process.destroyForcibly()
    }

    // ----- small LSP param helpers -----

    fun textDocument(uri: String) = mapOf("uri" to uri)
    fun position(line: Int, character: Int) = mapOf("line" to line, "character" to character)
    fun at(uri: String, line: Int, character: Int) = mapOf("textDocument" to textDocument(uri), "position" to position(line, character))
}
