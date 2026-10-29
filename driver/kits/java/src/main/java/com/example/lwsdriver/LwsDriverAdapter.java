package com.example.lwsdriver;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Your client, as Touchstone's driver sees it (touchstone-driver/1, driver/PROTOCOL.md section 5):
 * one method per operation. Each takes the operation's arguments and returns its result, both as the
 * JSON objects the protocol describes, and throws an {@link AdapterError} for an outcome that is not a
 * result, such as a 404.
 *
 * <p>Implement the operations your client has, by calling your client's own operation with its own
 * options. Leave the others: they answer {@code unsupported}, and the rules that need them stay
 * untested, never failed. {@link AdapterRunner} lists the ones you implement in the hello.
 *
 * <p>Be a thin, faithful wrapper: never retry, fall back or fix anything up for your client. The
 * point is to see what the client sends.
 */
public interface LwsDriverAdapter {

    /** The client under test, as the session's report names it. */
    ClientInfo client();

    /** The kinds of {@code configure} authentication your client supports: none, bearer, cid, openid, saml. */
    Set<String> auth();

    /** Section 5.1: build the client every later operation uses, with {@code auth}. Always implemented. */
    JsonNode configure(JsonNode args) throws AdapterError;

    // ---- core ----

    /** {@code url} → Storage: find the storage a URL belongs to and read its description. */
    default JsonNode discoverStorage(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("discover_storage");
    }

    /** {@code url} → Metadata. */
    default JsonNode head(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("head");
    }

    /** {@code url, accept?, ifNoneMatch?, rangeStart?, rangeEnd?} → {@code {metadata, notModified, body?}}. */
    default JsonNode read(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("read");
    }

    /** {@code url, limit?} → {@code {items, truncated}}, following every page. */
    default JsonNode listContainer(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("list_container");
    }

    /** {@code container, body?, contentType?, slug?, types?, links?} → Created. */
    default JsonNode create(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("create");
    }

    /** {@code parent, slug?} → Created. */
    default JsonNode createContainer(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("create_container");
    }

    /** {@code url, body, contentType?, ifMatch?, ifNoneMatch?, links?} → Update. */
    default JsonNode update(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("update");
    }

    /** {@code url, patch, ifMatch?} → Update. */
    default JsonNode patch(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("patch");
    }

    /** {@code url, recursive?, ifMatch?} → {@code {}}. */
    default JsonNode delete(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("delete");
    }

    /** {@code url} (the resource) → {@code {url, etag?, linkset, allow, acceptPatch}}. */
    default JsonNode readLinkset(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("read_linkset");
    }

    /** {@code linksetUrl, linkset, ifMatch?} → Update. */
    default JsonNode updateLinkset(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("update_linkset");
    }

    /** {@code linksetUrl, patch, ifMatch?} → Update. */
    default JsonNode patchLinkset(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("patch_linkset");
    }

    /** {@code url, destination, ifMatch?} → Moved. */
    default JsonNode move(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("move");
    }

    /** {@code serviceUrl, request} → {@code {location}}. */
    default JsonNode requestAccess(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("request_access");
    }

    /** {@code url} → {@code {document}}. */
    default JsonNode getAccessRequest(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("get_access_request");
    }

    /** {@code serviceUrl, limit?} → {@code {items, truncated}}. */
    default JsonNode listAccessRequests(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("list_access_requests");
    }

    /** {@code url} → {@code {}}. */
    default JsonNode cancelAccessRequest(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("cancel_access_request");
    }

    /** {@code serviceUrl, grant} → {@code {location}}. */
    default JsonNode grantAccess(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("grant_access");
    }

    /** {@code url} → {@code {document}}. */
    default JsonNode getAccessGrant(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("get_access_grant");
    }

    /** {@code serviceUrl, limit?} → {@code {items, truncated}}. */
    default JsonNode listAccessGrants(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("list_access_grants");
    }

    /** {@code url} → {@code {}}. */
    default JsonNode revokeAccessGrant(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("revoke_access_grant");
    }

    // ---- notifications ----

    /** {@code serviceUrl, topics, inbox, expires?} → Subscription. */
    default JsonNode subscribe(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("subscribe");
    }

    /** {@code serviceUrl, limit?} → {@code {items, truncated}}. */
    default JsonNode listSubscriptions(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("list_subscriptions");
    }

    /** {@code url} → Subscription. */
    default JsonNode getSubscription(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("get_subscription");
    }

    /** {@code url} → {@code {}}. */
    default JsonNode unsubscribe(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("unsubscribe");
    }

    /**
     * Section 5.2: {@code method, url, headers, bodyBase64, trustedStorages?} → {@code {storage, keyid,
     * activities}}, or a {@code verification} error. Verify the delivery the way your client does.
     */
    default JsonNode verifyNotification(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("verify_notification");
    }

    // ---- index ----

    /** {@code serviceUrl, limit?} → {@code {types, truncated}}. */
    default JsonNode listTypes(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("list_types");
    }

    /** {@code serviceUrl, query, limit?} → {@code {items, truncated}}, following every page. */
    default JsonNode search(JsonNode args) throws AdapterError {
        throw AdapterError.unsupported("search");
    }
}
