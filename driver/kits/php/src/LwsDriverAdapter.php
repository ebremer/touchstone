<?php

declare(strict_types=1);

namespace Example\LwsDriver;

/**
 * Your client, as Touchstone's driver sees it (touchstone-driver/1, driver/PROTOCOL.md section 5):
 * one method per operation. Each takes the operation's arguments and returns its result, both as the
 * JSON objects the protocol describes, and throws an {@see AdapterError} for an outcome that is not a
 * result, such as a 404.
 *
 * Arguments arrive as json_decode makes them, objects as \stdClass, so that an empty object stays one
 * when your client sends it on. Return an array or a \stdClass. Inside a result, write an empty object
 * as new \stdClass(), since [] is an empty list; the result itself may be [].
 *
 * Extend {@see AbstractAdapter} and implement the operations your client has, by calling your client's
 * own operation with its own options. Leave the others: they answer unsupported, and the rules that
 * need them stay untested, never failed. {@see AdapterRunner} lists the ones you implement in the hello.
 *
 * Be a thin, faithful wrapper: never retry, fall back or fix anything up for your client. The
 * point is to see what the client sends.
 */
interface LwsDriverAdapter
{
    /** The client under test, as the session's report names it. */
    public function client(): ClientInfo;

    /**
     * The kinds of configure authentication your client supports: none, bearer, cid, openid, saml.
     *
     * @return list<string>
     */
    public function auth(): array;

    /** Section 5.1: build the client every later operation uses, with auth. Always implemented. */
    public function configure(\stdClass $args): array|\stdClass;

    // ---- core ----

    /** url → Storage: find the storage a URL belongs to and read its description. */
    public function discoverStorage(\stdClass $args): array|\stdClass;

    /** url → Metadata. */
    public function head(\stdClass $args): array|\stdClass;

    /** url, accept?, ifNoneMatch?, rangeStart?, rangeEnd? → {metadata, notModified, body?}. */
    public function read(\stdClass $args): array|\stdClass;

    /** url, limit? → {items, truncated}, following every page. */
    public function listContainer(\stdClass $args): array|\stdClass;

    /** container, body?, contentType?, slug?, types?, links? → Created. */
    public function create(\stdClass $args): array|\stdClass;

    /** parent, slug? → Created. */
    public function createContainer(\stdClass $args): array|\stdClass;

    /** url, body, contentType?, ifMatch?, ifNoneMatch?, links? → Update. */
    public function update(\stdClass $args): array|\stdClass;

    /** url, patch, ifMatch? → Update. */
    public function patch(\stdClass $args): array|\stdClass;

    /** url, recursive?, ifMatch? → {}. */
    public function delete(\stdClass $args): array|\stdClass;

    /** url (the resource) → {url, etag?, linkset, allow, acceptPatch}. */
    public function readLinkset(\stdClass $args): array|\stdClass;

    /** linksetUrl, linkset, ifMatch? → Update. */
    public function updateLinkset(\stdClass $args): array|\stdClass;

    /** linksetUrl, patch, ifMatch? → Update. */
    public function patchLinkset(\stdClass $args): array|\stdClass;

    /** url, destination, ifMatch? → Moved. */
    public function move(\stdClass $args): array|\stdClass;

    /** serviceUrl, request → {location}. */
    public function requestAccess(\stdClass $args): array|\stdClass;

    /** url → {document}. */
    public function getAccessRequest(\stdClass $args): array|\stdClass;

    /** serviceUrl, limit? → {items, truncated}. */
    public function listAccessRequests(\stdClass $args): array|\stdClass;

    /** url → {}. */
    public function cancelAccessRequest(\stdClass $args): array|\stdClass;

    /** serviceUrl, grant → {location}. */
    public function grantAccess(\stdClass $args): array|\stdClass;

    /** url → {document}. */
    public function getAccessGrant(\stdClass $args): array|\stdClass;

    /** serviceUrl, limit? → {items, truncated}. */
    public function listAccessGrants(\stdClass $args): array|\stdClass;

    /** url → {}. */
    public function revokeAccessGrant(\stdClass $args): array|\stdClass;

    // ---- notifications ----

    /** serviceUrl, topics, inbox, expires? → Subscription. */
    public function subscribe(\stdClass $args): array|\stdClass;

    /** serviceUrl, limit? → {items, truncated}. */
    public function listSubscriptions(\stdClass $args): array|\stdClass;

    /** url → Subscription. */
    public function getSubscription(\stdClass $args): array|\stdClass;

    /** url → {}. */
    public function unsubscribe(\stdClass $args): array|\stdClass;

    /**
     * Section 5.2: method, url, headers, bodyBase64, trustedStorages? → {storage, keyid, activities},
     * or a verification error. Verify the delivery the way your client does.
     */
    public function verifyNotification(\stdClass $args): array|\stdClass;

    // ---- index ----

    /** serviceUrl, limit? → {types, truncated}. */
    public function listTypes(\stdClass $args): array|\stdClass;

    /** serviceUrl, query, limit? → {items, truncated}, following every page. */
    public function search(\stdClass $args): array|\stdClass;
}
