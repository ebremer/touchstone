package com.example.lwsdriver

/**
 * An operation's outcome that is not a result (driver/PROTOCOL.md section 4.2): it is reported to the
 * driver, and the adapter keeps running. A 409 from deleting a non-empty container is exactly what a
 * drive may be looking for.
 *
 * @property kind http, authentication, protocol, verification, transport, invalidArguments,
 *   unsupported or internal
 * @property status the HTTP status of the response that caused it, or null; required for http
 */
class AdapterError(val kind: String, val status: Int?, message: String) : Exception(message) {

    companion object {
        /** A response with a status the operation does not succeed with. */
        fun http(status: Int, message: String) = AdapterError("http", status, message)

        /** The operation, or an argument it was given, is not available in this client. */
        fun unsupported(what: String) = AdapterError("unsupported", null, "$what is not available in this client")

        /** The request's arguments are missing or malformed. */
        fun invalidArguments(message: String) = AdapterError("invalidArguments", null, message)

        /** No response: connection refused, TLS failure, timeout. */
        fun transport(message: String) = AdapterError("transport", null, message)
    }
}
