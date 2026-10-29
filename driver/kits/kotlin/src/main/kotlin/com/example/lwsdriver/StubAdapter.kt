package com.example.lwsdriver

import kotlin.system.exitProcess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The starting point: an adapter that implements only `configure`, so every other operation
 * answers `unsupported`. It passes `touchstone driver check` as it is. Replace the client
 * name, keep your client in a property that [configure] builds, and implement the operations your
 * client has, one by one.
 */
class StubAdapter : LwsDriverAdapter {

    override val client = ClientInfo("My LWS client (Kotlin)", "0.0.0")

    override val auth = setOf("none")

    override fun configure(args: JsonObject): JsonObject {
        val type = ((args["auth"] as? JsonObject)?.get("type") as? JsonPrimitive)?.content ?: ""
        if (type !in auth) {
            throw AdapterError.unsupported("authentication $type")
        }
        // Build your client here, with the authentication args["auth"] describes.
        return JsonObject(emptyMap())
    }
}

fun main() {
    AdapterRunner.serve(StubAdapter())
    exitProcess(0)
}
