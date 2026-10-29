package com.example.lwsdriver

/**
 * The client under test, as the hello names it (driver/PROTOCOL.md section 3): it becomes the
 * subject of the session's EARL report.
 *
 * @property name the client's name
 * @property version its version, or null
 * @property homepage an absolute http(s) URL, or null
 */
data class ClientInfo(val name: String, val version: String? = null, val homepage: String? = null)
