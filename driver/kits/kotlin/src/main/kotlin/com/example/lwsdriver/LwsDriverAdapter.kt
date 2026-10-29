package com.example.lwsdriver

import kotlinx.serialization.json.JsonObject

/**
 * Your client, as Touchstone's driver sees it (touchstone-driver/1, driver/PROTOCOL.md section 5):
 * one method per operation. Each takes the operation's arguments and returns its result, both as the
 * JSON objects the protocol describes, and throws an [AdapterError] for an outcome that is not a
 * result, such as a 404.
 *
 * Implement the operations your client has, by calling your client's own operation with its own
 * options. Leave the others: they answer `unsupported`, and the rules that need them stay
 * untested, never failed. [AdapterRunner] lists the ones you implement in the hello: it finds, by
 * reflection, the methods your class overrides.
 *
 * Be a thin, faithful wrapper: never retry, fall back or fix anything up for your client. The
 * point is to see what the client sends.
 */
interface LwsDriverAdapter {

    /** The client under test, as the session's report names it. */
    val client: ClientInfo

    /** The kinds of `configure` authentication your client supports: none, bearer, cid, openid, saml. */
    val auth: Set<String>

    /** Section 5.1: build the client every later operation uses, with `auth`. Always implemented. */
    fun configure(args: JsonObject): JsonObject

    // ---- core ----

    /** `url` → Storage: find the storage a URL belongs to and read its description. */
    fun discoverStorage(args: JsonObject): JsonObject = throw AdapterError.unsupported("discover_storage")

    /** `url` → Metadata. */
    fun head(args: JsonObject): JsonObject = throw AdapterError.unsupported("head")

    /** `url, accept?, ifNoneMatch?, rangeStart?, rangeEnd?` → `{metadata, notModified, body?}`. */
    fun read(args: JsonObject): JsonObject = throw AdapterError.unsupported("read")

    /** `url, limit?` → `{items, truncated}`, following every page. */
    fun listContainer(args: JsonObject): JsonObject = throw AdapterError.unsupported("list_container")

    /** `container, body?, contentType?, slug?, types?, links?` → Created. */
    fun create(args: JsonObject): JsonObject = throw AdapterError.unsupported("create")

    /** `parent, slug?` → Created. */
    fun createContainer(args: JsonObject): JsonObject = throw AdapterError.unsupported("create_container")

    /** `url, body, contentType?, ifMatch?, ifNoneMatch?, links?` → Update. */
    fun update(args: JsonObject): JsonObject = throw AdapterError.unsupported("update")

    /** `url, patch, ifMatch?` → Update. */
    fun patch(args: JsonObject): JsonObject = throw AdapterError.unsupported("patch")

    /** `url, recursive?, ifMatch?` → `{}`. */
    fun delete(args: JsonObject): JsonObject = throw AdapterError.unsupported("delete")

    /** `url` (the resource) → `{url, etag?, linkset, allow, acceptPatch}`. */
    fun readLinkset(args: JsonObject): JsonObject = throw AdapterError.unsupported("read_linkset")

    /** `linksetUrl, linkset, ifMatch?` → Update. */
    fun updateLinkset(args: JsonObject): JsonObject = throw AdapterError.unsupported("update_linkset")

    /** `linksetUrl, patch, ifMatch?` → Update. */
    fun patchLinkset(args: JsonObject): JsonObject = throw AdapterError.unsupported("patch_linkset")

    /** `url, destination, ifMatch?` → Moved. */
    fun move(args: JsonObject): JsonObject = throw AdapterError.unsupported("move")

    /** `serviceUrl, request` → `{location}`. */
    fun requestAccess(args: JsonObject): JsonObject = throw AdapterError.unsupported("request_access")

    /** `url` → `{document}`. */
    fun getAccessRequest(args: JsonObject): JsonObject = throw AdapterError.unsupported("get_access_request")

    /** `serviceUrl, limit?` → `{items, truncated}`. */
    fun listAccessRequests(args: JsonObject): JsonObject = throw AdapterError.unsupported("list_access_requests")

    /** `url` → `{}`. */
    fun cancelAccessRequest(args: JsonObject): JsonObject = throw AdapterError.unsupported("cancel_access_request")

    /** `serviceUrl, grant` → `{location}`. */
    fun grantAccess(args: JsonObject): JsonObject = throw AdapterError.unsupported("grant_access")

    /** `url` → `{document}`. */
    fun getAccessGrant(args: JsonObject): JsonObject = throw AdapterError.unsupported("get_access_grant")

    /** `serviceUrl, limit?` → `{items, truncated}`. */
    fun listAccessGrants(args: JsonObject): JsonObject = throw AdapterError.unsupported("list_access_grants")

    /** `url` → `{}`. */
    fun revokeAccessGrant(args: JsonObject): JsonObject = throw AdapterError.unsupported("revoke_access_grant")

    // ---- notifications ----

    /** `serviceUrl, topics, inbox, expires?` → Subscription. */
    fun subscribe(args: JsonObject): JsonObject = throw AdapterError.unsupported("subscribe")

    /** `serviceUrl, limit?` → `{items, truncated}`. */
    fun listSubscriptions(args: JsonObject): JsonObject = throw AdapterError.unsupported("list_subscriptions")

    /** `url` → Subscription. */
    fun getSubscription(args: JsonObject): JsonObject = throw AdapterError.unsupported("get_subscription")

    /** `url` → `{}`. */
    fun unsubscribe(args: JsonObject): JsonObject = throw AdapterError.unsupported("unsubscribe")

    /**
     * Section 5.2: `method, url, headers, bodyBase64, trustedStorages?` → `{storage, keyid,
     * activities}`, or a `verification` error. Verify the delivery the way your client does.
     */
    fun verifyNotification(args: JsonObject): JsonObject = throw AdapterError.unsupported("verify_notification")

    // ---- index ----

    /** `serviceUrl, limit?` → `{types, truncated}`. */
    fun listTypes(args: JsonObject): JsonObject = throw AdapterError.unsupported("list_types")

    /** `serviceUrl, query, limit?` → `{items, truncated}`, following every page. */
    fun search(args: JsonObject): JsonObject = throw AdapterError.unsupported("search")
}
