using System.Text.Json.Nodes;

namespace Example.LwsDriver;

/// <summary>
/// Your client, as Touchstone's driver sees it (touchstone-driver/1, driver/PROTOCOL.md section 5):
/// one method per operation. Each takes the operation's arguments and returns its result, both as the
/// JSON objects the protocol describes, and throws an <see cref="AdapterException"/> for an outcome
/// that is not a result, such as a 404.
/// </summary>
/// <remarks>
/// <para>Implement the operations your client has, by calling your client's own operation with its
/// own options. Leave the others: they answer <c>unsupported</c>, and the rules that need them stay
/// untested, never failed. <see cref="AdapterRunner"/> lists the ones you implement in the hello: the
/// methods your class implements itself, rather than leaving the default.</para>
/// <para>Be a thin, faithful wrapper: never retry, fall back or fix anything up for your client. The
/// point is to see what the client sends.</para>
/// </remarks>
public interface ILwsDriverAdapter
{
    /// <summary>The client under test, as the session's report names it.</summary>
    ClientInfo Client { get; }

    /// <summary>The kinds of <c>configure</c> authentication your client supports: none, bearer, cid, openid, saml.</summary>
    IReadOnlyCollection<string> Auth { get; }

    /// <summary>
    /// Section 5.1: <c>auth, userAgent?, timeoutSeconds?</c> → <c>{agent?}</c>. Build the client every
    /// later operation uses, with <c>auth</c>, replacing any client and token you hold. Always implemented.
    /// </summary>
    Task<JsonObject> ConfigureAsync(JsonObject args);

    // ---- core ----

    /// <summary><c>url</c> → Storage: find the storage a URL belongs to and read its description.</summary>
    Task<JsonObject> DiscoverStorageAsync(JsonObject args) => throw AdapterException.Unsupported("discover_storage");

    /// <summary><c>url</c> → Metadata.</summary>
    Task<JsonObject> HeadAsync(JsonObject args) => throw AdapterException.Unsupported("head");

    /// <summary><c>url, accept?, ifNoneMatch?, rangeStart?, rangeEnd?</c> → <c>{metadata, notModified, body?}</c>.</summary>
    Task<JsonObject> ReadAsync(JsonObject args) => throw AdapterException.Unsupported("read");

    /// <summary><c>url, limit?</c> → <c>{items, truncated}</c>, following every page.</summary>
    Task<JsonObject> ListContainerAsync(JsonObject args) => throw AdapterException.Unsupported("list_container");

    /// <summary><c>container, body?, contentType?, slug?, types?, links?</c> → Created.</summary>
    Task<JsonObject> CreateAsync(JsonObject args) => throw AdapterException.Unsupported("create");

    /// <summary><c>parent, slug?</c> → Created.</summary>
    Task<JsonObject> CreateContainerAsync(JsonObject args) => throw AdapterException.Unsupported("create_container");

    /// <summary><c>url, body, contentType?, ifMatch?, ifNoneMatch?, links?</c> → Update.</summary>
    Task<JsonObject> UpdateAsync(JsonObject args) => throw AdapterException.Unsupported("update");

    /// <summary><c>url, patch, ifMatch?</c> → Update.</summary>
    Task<JsonObject> PatchAsync(JsonObject args) => throw AdapterException.Unsupported("patch");

    /// <summary><c>url, recursive?, ifMatch?</c> → <c>{}</c>.</summary>
    Task<JsonObject> DeleteAsync(JsonObject args) => throw AdapterException.Unsupported("delete");

    /// <summary><c>url</c> (the resource) → <c>{url, etag?, linkset, allow, acceptPatch}</c>.</summary>
    Task<JsonObject> ReadLinksetAsync(JsonObject args) => throw AdapterException.Unsupported("read_linkset");

    /// <summary><c>linksetUrl, linkset, ifMatch?</c> → Update.</summary>
    Task<JsonObject> UpdateLinksetAsync(JsonObject args) => throw AdapterException.Unsupported("update_linkset");

    /// <summary><c>linksetUrl, patch, ifMatch?</c> → Update.</summary>
    Task<JsonObject> PatchLinksetAsync(JsonObject args) => throw AdapterException.Unsupported("patch_linkset");

    /// <summary><c>url, destination, ifMatch?</c> → Moved.</summary>
    Task<JsonObject> MoveAsync(JsonObject args) => throw AdapterException.Unsupported("move");

    /// <summary><c>serviceUrl, request</c> → <c>{location}</c>.</summary>
    Task<JsonObject> RequestAccessAsync(JsonObject args) => throw AdapterException.Unsupported("request_access");

    /// <summary><c>url</c> → <c>{document}</c>.</summary>
    Task<JsonObject> GetAccessRequestAsync(JsonObject args) => throw AdapterException.Unsupported("get_access_request");

    /// <summary><c>serviceUrl, limit?</c> → <c>{items, truncated}</c>.</summary>
    Task<JsonObject> ListAccessRequestsAsync(JsonObject args) => throw AdapterException.Unsupported("list_access_requests");

    /// <summary><c>url</c> → <c>{}</c>.</summary>
    Task<JsonObject> CancelAccessRequestAsync(JsonObject args) => throw AdapterException.Unsupported("cancel_access_request");

    /// <summary><c>serviceUrl, grant</c> → <c>{location}</c>.</summary>
    Task<JsonObject> GrantAccessAsync(JsonObject args) => throw AdapterException.Unsupported("grant_access");

    /// <summary><c>url</c> → <c>{document}</c>.</summary>
    Task<JsonObject> GetAccessGrantAsync(JsonObject args) => throw AdapterException.Unsupported("get_access_grant");

    /// <summary><c>serviceUrl, limit?</c> → <c>{items, truncated}</c>.</summary>
    Task<JsonObject> ListAccessGrantsAsync(JsonObject args) => throw AdapterException.Unsupported("list_access_grants");

    /// <summary><c>url</c> → <c>{}</c>.</summary>
    Task<JsonObject> RevokeAccessGrantAsync(JsonObject args) => throw AdapterException.Unsupported("revoke_access_grant");

    // ---- notifications ----

    /// <summary><c>serviceUrl, topics, inbox, expires?</c> → Subscription.</summary>
    Task<JsonObject> SubscribeAsync(JsonObject args) => throw AdapterException.Unsupported("subscribe");

    /// <summary><c>serviceUrl, limit?</c> → <c>{items, truncated}</c>.</summary>
    Task<JsonObject> ListSubscriptionsAsync(JsonObject args) => throw AdapterException.Unsupported("list_subscriptions");

    /// <summary><c>url</c> → Subscription.</summary>
    Task<JsonObject> GetSubscriptionAsync(JsonObject args) => throw AdapterException.Unsupported("get_subscription");

    /// <summary><c>url</c> → <c>{}</c>.</summary>
    Task<JsonObject> UnsubscribeAsync(JsonObject args) => throw AdapterException.Unsupported("unsubscribe");

    /// <summary>
    /// Section 5.2: <c>method, url, headers, bodyBase64, trustedStorages?</c> → <c>{storage, keyid,
    /// activities}</c>, or a <c>verification</c> error. Verify the delivery the way your client does.
    /// </summary>
    Task<JsonObject> VerifyNotificationAsync(JsonObject args) => throw AdapterException.Unsupported("verify_notification");

    // ---- index ----

    /// <summary><c>serviceUrl, limit?</c> → <c>{types, truncated}</c>.</summary>
    Task<JsonObject> ListTypesAsync(JsonObject args) => throw AdapterException.Unsupported("list_types");

    /// <summary><c>serviceUrl, query, limit?</c> → <c>{items, truncated}</c>, following every page.</summary>
    Task<JsonObject> SearchAsync(JsonObject args) => throw AdapterException.Unsupported("search");
}
