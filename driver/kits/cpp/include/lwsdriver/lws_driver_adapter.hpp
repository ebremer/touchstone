#pragma once

#include <optional>
#include <set>
#include <string>

#include <nlohmann/json.hpp>

#include "lwsdriver/adapter_error.hpp"
#include "lwsdriver/client_info.hpp"

namespace lwsdriver {

/// Arguments and results: the JSON objects the protocol describes.
using Json = nlohmann::json;

/// Your client, as Touchstone's driver sees it (touchstone-driver/1, driver/PROTOCOL.md section 5):
/// one virtual method per operation. Each takes the operation's arguments and returns its result,
/// both JSON objects, and throws an AdapterError for an outcome that is not a result, such as a 404.
///
/// Implement the operations your client has, by calling your client's own operation with its own
/// options. Leave the others: they answer `unsupported`, and the rules that need them stay untested,
/// never failed. The runner lists the ones you override in the hello (adapter_runner.hpp): keep your
/// overrides public and mark them `override`.
///
/// Be a thin, faithful wrapper: never retry, fall back or fix anything up for your client. The point
/// is to see what the client sends.
class LwsDriverAdapter {
public:
    virtual ~LwsDriverAdapter() = default;

    /// The client under test, as the session's report names it.
    virtual ClientInfo client() const = 0;

    /// The kinds of `configure` authentication your client supports: none, bearer, cid, openid, saml.
    virtual std::set<std::string> auth() const = 0;

    /// Section 5.1: `auth, userAgent?, timeoutSeconds?` -> `{agent?}`. Build the client every later
    /// operation uses, with `auth`. Always implemented.
    virtual Json configure(const Json& args) = 0;

    // ---- core ----

    /// `url` -> Storage: find the storage a URL belongs to and read its description.
    virtual Json discover_storage(const Json& /*args*/) { throw AdapterError::unsupported("discover_storage"); }

    /// `url` -> Metadata.
    virtual Json head(const Json& /*args*/) { throw AdapterError::unsupported("head"); }

    /// `url, accept?, ifNoneMatch?, rangeStart?, rangeEnd?` -> `{metadata, notModified, body?}`.
    virtual Json read(const Json& /*args*/) { throw AdapterError::unsupported("read"); }

    /// `url, limit?` -> `{items, truncated}`, following every page.
    virtual Json list_container(const Json& /*args*/) { throw AdapterError::unsupported("list_container"); }

    /// `container, body?, contentType?, slug?, types?, links?` -> Created.
    virtual Json create(const Json& /*args*/) { throw AdapterError::unsupported("create"); }

    /// `parent, slug?` -> Created.
    virtual Json create_container(const Json& /*args*/) { throw AdapterError::unsupported("create_container"); }

    /// `url, body, contentType?, ifMatch?, ifNoneMatch?, links?` -> Update.
    virtual Json update(const Json& /*args*/) { throw AdapterError::unsupported("update"); }

    /// `url, patch, ifMatch?` -> Update.
    virtual Json patch(const Json& /*args*/) { throw AdapterError::unsupported("patch"); }

    /// The operation `delete` (`delete` is a C++ keyword): `url, recursive?, ifMatch?` -> `{}`.
    virtual Json delete_(const Json& /*args*/) { throw AdapterError::unsupported("delete"); }

    /// `url` (the resource) -> `{url, etag?, linkset, allow, acceptPatch}`.
    virtual Json read_linkset(const Json& /*args*/) { throw AdapterError::unsupported("read_linkset"); }

    /// `linksetUrl, linkset, ifMatch?` -> Update.
    virtual Json update_linkset(const Json& /*args*/) { throw AdapterError::unsupported("update_linkset"); }

    /// `linksetUrl, patch, ifMatch?` -> Update.
    virtual Json patch_linkset(const Json& /*args*/) { throw AdapterError::unsupported("patch_linkset"); }

    /// `url, destination, ifMatch?` -> Moved.
    virtual Json move(const Json& /*args*/) { throw AdapterError::unsupported("move"); }

    /// `serviceUrl, request` -> `{location}`.
    virtual Json request_access(const Json& /*args*/) { throw AdapterError::unsupported("request_access"); }

    /// `url` -> `{document}`.
    virtual Json get_access_request(const Json& /*args*/) { throw AdapterError::unsupported("get_access_request"); }

    /// `serviceUrl, limit?` -> `{items, truncated}`.
    virtual Json list_access_requests(const Json& /*args*/) { throw AdapterError::unsupported("list_access_requests"); }

    /// `url` -> `{}`.
    virtual Json cancel_access_request(const Json& /*args*/) { throw AdapterError::unsupported("cancel_access_request"); }

    /// `serviceUrl, grant` -> `{location}`.
    virtual Json grant_access(const Json& /*args*/) { throw AdapterError::unsupported("grant_access"); }

    /// `url` -> `{document}`.
    virtual Json get_access_grant(const Json& /*args*/) { throw AdapterError::unsupported("get_access_grant"); }

    /// `serviceUrl, limit?` -> `{items, truncated}`.
    virtual Json list_access_grants(const Json& /*args*/) { throw AdapterError::unsupported("list_access_grants"); }

    /// `url` -> `{}`.
    virtual Json revoke_access_grant(const Json& /*args*/) { throw AdapterError::unsupported("revoke_access_grant"); }

    // ---- notifications ----

    /// `serviceUrl, topics, inbox, expires?` -> Subscription.
    virtual Json subscribe(const Json& /*args*/) { throw AdapterError::unsupported("subscribe"); }

    /// `serviceUrl, limit?` -> `{items, truncated}`.
    virtual Json list_subscriptions(const Json& /*args*/) { throw AdapterError::unsupported("list_subscriptions"); }

    /// `url` -> Subscription.
    virtual Json get_subscription(const Json& /*args*/) { throw AdapterError::unsupported("get_subscription"); }

    /// `url` -> `{}`.
    virtual Json unsubscribe(const Json& /*args*/) { throw AdapterError::unsupported("unsubscribe"); }

    /// Section 5.2: `method, url, headers, bodyBase64, trustedStorages?` -> `{storage, keyid,
    /// activities}`, or a `verification` error. Verify the delivery the way your client does.
    virtual Json verify_notification(const Json& /*args*/) { throw AdapterError::unsupported("verify_notification"); }

    // ---- index ----

    /// `serviceUrl, limit?` -> `{types, truncated}`.
    virtual Json list_types(const Json& /*args*/) { throw AdapterError::unsupported("list_types"); }

    /// `serviceUrl, query, limit?` -> `{items, truncated}`, following every page.
    virtual Json search(const Json& /*args*/) { throw AdapterError::unsupported("search"); }
};

/// The member `name` of an arguments object, or nullptr when it is absent or null: both mean absent
/// (section 4).
inline const Json* member(const Json& args, const char* name) {
    if (!args.is_object()) return nullptr;
    const auto it = args.find(name);
    return it == args.end() || it->is_null() ? nullptr : &*it;
}

/// The string member `name`, or nullopt when it is absent or null. Anything but a string is
/// invalidArguments.
inline std::optional<std::string> string_member(const Json& args, const char* name) {
    const Json* value = member(args, name);
    if (value == nullptr) return std::nullopt;
    if (!value->is_string()) throw AdapterError::invalid_arguments(std::string(name) + " must be a string");
    return value->get<std::string>();
}

}  // namespace lwsdriver
