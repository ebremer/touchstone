package com.example.lwsdriver

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import kotlin.reflect.KFunction2
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The protocol, so that your adapter is only your client (driver/PROTOCOL.md sections 2 and 3): it
 * writes the hello, reads one request a line from stdin, calls the method of [LwsDriverAdapter]
 * for its operation, and writes one response a line to stdout. stdout carries protocol lines only:
 * log to stderr. While it serves, `System.out` goes to stderr too, so a stray `println` cannot
 * break the protocol.
 *
 * ```
 * fun main() {
 *     AdapterRunner.serve(MyAdapter())
 *     exitProcess(0)
 * }
 * ```
 *
 * Copy it as it is: it follows touchstone-driver/1, and nothing in it is particular to a client.
 */
object AdapterRunner {

    const val PROTOCOL = "touchstone-driver/1"

    /** Every operation of section 5 but shutdown, with the interface method each calls. */
    private val OPERATIONS: Map<String, KFunction2<LwsDriverAdapter, JsonObject, JsonObject>> = linkedMapOf(
        "configure" to LwsDriverAdapter::configure,
        "discover_storage" to LwsDriverAdapter::discoverStorage,
        "head" to LwsDriverAdapter::head,
        "read" to LwsDriverAdapter::read,
        "list_container" to LwsDriverAdapter::listContainer,
        "create" to LwsDriverAdapter::create,
        "create_container" to LwsDriverAdapter::createContainer,
        "update" to LwsDriverAdapter::update,
        "patch" to LwsDriverAdapter::patch,
        "delete" to LwsDriverAdapter::delete,
        "read_linkset" to LwsDriverAdapter::readLinkset,
        "update_linkset" to LwsDriverAdapter::updateLinkset,
        "patch_linkset" to LwsDriverAdapter::patchLinkset,
        "move" to LwsDriverAdapter::move,
        "request_access" to LwsDriverAdapter::requestAccess,
        "get_access_request" to LwsDriverAdapter::getAccessRequest,
        "list_access_requests" to LwsDriverAdapter::listAccessRequests,
        "cancel_access_request" to LwsDriverAdapter::cancelAccessRequest,
        "grant_access" to LwsDriverAdapter::grantAccess,
        "get_access_grant" to LwsDriverAdapter::getAccessGrant,
        "list_access_grants" to LwsDriverAdapter::listAccessGrants,
        "revoke_access_grant" to LwsDriverAdapter::revokeAccessGrant,
        "subscribe" to LwsDriverAdapter::subscribe,
        "list_subscriptions" to LwsDriverAdapter::listSubscriptions,
        "get_subscription" to LwsDriverAdapter::getSubscription,
        "unsubscribe" to LwsDriverAdapter::unsubscribe,
        "verify_notification" to LwsDriverAdapter::verifyNotification,
        "list_types" to LwsDriverAdapter::listTypes,
        "search" to LwsDriverAdapter::search,
    )

    private val EMPTY = JsonObject(emptyMap())

    /** Serves the driver on stdin and stdout until end of input or `shutdown`. */
    fun serve(adapter: LwsDriverAdapter) {
        val protocol = FileOutputStream(FileDescriptor.out)
        System.setOut(System.err)
        serve(adapter, System.`in`, protocol)
    }

    /** Serves the driver on these streams until end of input or `shutdown`. */
    fun serve(adapter: LwsDriverAdapter, input: InputStream, output: OutputStream) {
        val protocol = PrintStream(output, false, Charsets.UTF_8)
        input.bufferedReader(Charsets.UTF_8).use { reader ->
            write(protocol, hello(adapter))
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isBlank()) {
                    continue
                }
                val request = try {
                    Json.parseToJsonElement(line) as? JsonObject
                } catch (e: Exception) {
                    null
                }
                if (request == null) {
                    write(protocol, error(JsonNull, AdapterError.invalidArguments("a request is a JSON object")))
                    continue
                }
                val id = request["id"] ?: JsonNull
                val op = (request["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
                if (op == "shutdown") {
                    write(protocol, ok(id, EMPTY))
                    return
                }
                val response = try {
                    val call = OPERATIONS[op] ?: throw AdapterError.unsupported(op)
                    ok(id, call(adapter, request["args"] as? JsonObject ?: EMPTY))
                } catch (e: AdapterError) {
                    error(id, e)
                } catch (e: IllegalArgumentException) {
                    error(id, AdapterError.invalidArguments(e.message ?: e.toString()))
                } catch (e: Throwable) {
                    // A bug in the adapter or the client, TODO() included: report it, and keep running.
                    e.printStackTrace(System.err)
                    error(id, AdapterError("internal", null, e.toString()))
                }
                write(protocol, response)
            }
        }
    }

    /** The hello: the client, its authentication, and every operation the adapter implements. */
    internal fun hello(adapter: LwsDriverAdapter): JsonObject = buildJsonObject {
        putJsonObject("hello") {
            put("protocol", PROTOCOL)
            val info = adapter.client
            putJsonObject("client") {
                put("name", info.name)
                info.version?.let { put("version", it) }
                info.homepage?.let { put("homepage", it) }
            }
            put("language", "kotlin")
            putJsonArray("auth") { adapter.auth.forEach { add(it) } }
            putJsonArray("operations") {
                OPERATIONS.forEach { (op, method) -> if (implemented(adapter, method.name)) add(op) }
            }
        }
    }

    /**
     * Whether the adapter's class implements a method itself, rather than leaving the interface's default.
     * Kotlin compiles a default it inherits into a bridge method of the class (with `-jvm-default=enable`,
     * the default), so a bridge does not count. Do not compile with `-jvm-default=disable`.
     */
    private fun implemented(adapter: LwsDriverAdapter, method: String): Boolean = try {
        val m = adapter.javaClass.getMethod(method, JsonObject::class.java)
        m.declaringClass != LwsDriverAdapter::class.java && !m.isBridge
    } catch (e: NoSuchMethodException) {
        false
    }

    private fun ok(id: JsonElement, result: JsonObject) = buildJsonObject {
        put("id", id)
        put("ok", true)
        put("result", result)
    }

    private fun error(id: JsonElement, e: AdapterError) = buildJsonObject {
        put("id", id)
        put("ok", false)
        putJsonObject("error") {
            put("kind", e.kind)
            e.status?.let { put("status", it) }
            put("message", e.message ?: e.kind)
        }
    }

    private fun write(out: PrintStream, line: JsonObject) {
        out.print(line.toString())
        out.print('\n')
        out.flush()
    }
}
