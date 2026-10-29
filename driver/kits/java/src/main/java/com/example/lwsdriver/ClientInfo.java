package com.example.lwsdriver;

/**
 * The client under test, as the hello names it (driver/PROTOCOL.md section 3): it becomes the
 * subject of the session's EARL report.
 *
 * @param name     the client's name
 * @param version  its version, or null
 * @param homepage an absolute http(s) URL, or null
 */
public record ClientInfo(String name, String version, String homepage) {
}
