package com.example.lwsdriver;

/**
 * An operation's outcome that is not a result (driver/PROTOCOL.md section 4.2): it is reported to the
 * driver, and the adapter keeps running. A 409 from deleting a non-empty container is exactly what a
 * drive may be looking for.
 */
public class AdapterError extends Exception {

    private final String kind;
    private final Integer status;

    /**
     * @param kind    http, authentication, protocol, verification, transport, invalidArguments,
     *                unsupported or internal
     * @param status  the HTTP status of the response that caused it, or null; required for http
     */
    public AdapterError(String kind, Integer status, String message) {
        super(message);
        this.kind = kind;
        this.status = status;
    }

    public String kind() {
        return kind;
    }

    public Integer status() {
        return status;
    }

    /** A response with a status the operation does not succeed with. */
    public static AdapterError http(int status, String message) {
        return new AdapterError("http", status, message);
    }

    /** The operation, or an argument it was given, is not available in this client. */
    public static AdapterError unsupported(String what) {
        return new AdapterError("unsupported", null, what + " is not available in this client");
    }

    /** The request's arguments are missing or malformed. */
    public static AdapterError invalidArguments(String message) {
        return new AdapterError("invalidArguments", null, message);
    }

    /** No response: connection refused, TLS failure, timeout. */
    public static AdapterError transport(String message) {
        return new AdapterError("transport", null, message);
    }
}
