//! The adapter trait: your client, as Touchstone's driver sees it.

use serde_json::Value;

use crate::error::AdapterError;

/// The client under test, as the hello names it (driver/PROTOCOL.md section 3): it becomes the
/// subject of the session's EARL report.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClientInfo {
    /// The client's name.
    pub name: String,
    /// Its version, if it has one.
    pub version: Option<String>,
    /// An absolute http(s) URL, if it has one.
    pub homepage: Option<String>,
}

impl ClientInfo {
    /// A client with this name, and no version or homepage.
    #[must_use]
    pub fn new(name: impl Into<String>) -> Self {
        ClientInfo {
            name: name.into(),
            version: None,
            homepage: None,
        }
    }

    /// The same client, with a version.
    #[must_use]
    pub fn with_version(mut self, version: impl Into<String>) -> Self {
        self.version = Some(version.into());
        self
    }

    /// The same client, with a homepage.
    #[must_use]
    pub fn with_homepage(mut self, homepage: impl Into<String>) -> Self {
        self.homepage = Some(homepage.into());
        self
    }
}

/// Your client, as Touchstone's driver sees it (touchstone-driver/1, driver/PROTOCOL.md section
/// 5): one method per operation. Each takes the operation's arguments and returns its result, both
/// as the JSON objects the protocol describes, or an [`AdapterError`] for an outcome that is not a
/// result, such as a 404. `args` is always a JSON object: `args["url"]` is `Value::Null` when the
/// argument is absent.
///
/// Implement the operations your client has, by calling your client's own operation with its own
/// options, and name each one in [`operations`](Self::operations). Leave the others: they answer
/// `unsupported`, and the rules that need them stay untested, never failed.
///
/// Be a thin, faithful wrapper: never retry, fall back or fix anything up for your client. The
/// point is to see what the client sends.
///
/// The methods block. If your client is async, keep a runtime in your adapter and block on each
/// call, for example with tokio's `Runtime::block_on`.
#[allow(unused_variables)]
pub trait LwsDriverAdapter {
    /// The client under test, as the session's report names it.
    fn client(&self) -> ClientInfo;

    /// The kinds of `configure` authentication your client supports: none, bearer, cid, openid,
    /// saml.
    fn auth(&self) -> &[&'static str];

    /// The operations you implement, by their protocol names, such as `"read"`. The hello lists
    /// exactly these, plus `configure`, and the runner answers every other operation `unsupported`
    /// without calling its method. Rust cannot see which methods you implemented, so name each one
    /// here when you implement it.
    fn operations(&self) -> &[&'static str];

    /// Section 5.1: `auth, userAgent?, timeoutSeconds?` → `{agent?}`. Build the client every later
    /// operation uses, with `auth`, replacing any client and token you hold. Always implemented.
    fn configure(&mut self, args: &Value) -> Result<Value, AdapterError>;

    // ---- core ----

    /// `url` → Storage: find the storage a URL belongs to and read its description.
    fn discover_storage(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("discover_storage"))
    }

    /// `url` → Metadata.
    fn head(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("head"))
    }

    /// `url, accept?, ifNoneMatch?, rangeStart?, rangeEnd?` → `{metadata, notModified, body?}`.
    fn read(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("read"))
    }

    /// `url, limit?` → `{items, truncated}`, following every page.
    fn list_container(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("list_container"))
    }

    /// `container, body?, contentType?, slug?, types?, links?` → Created.
    fn create(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("create"))
    }

    /// `parent, slug?` → Created.
    fn create_container(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("create_container"))
    }

    /// `url, body, contentType?, ifMatch?, ifNoneMatch?, links?` → Update.
    fn update(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("update"))
    }

    /// `url, patch, ifMatch?` → Update.
    fn patch(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("patch"))
    }

    /// `url, recursive?, ifMatch?` → `{}`.
    fn delete(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("delete"))
    }

    /// `url` (the resource) → `{url, etag?, linkset, allow, acceptPatch}`.
    fn read_linkset(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("read_linkset"))
    }

    /// `linksetUrl, linkset, ifMatch?` → Update.
    fn update_linkset(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("update_linkset"))
    }

    /// `linksetUrl, patch, ifMatch?` → Update.
    fn patch_linkset(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("patch_linkset"))
    }

    /// `move`: `url, destination, ifMatch?` → Moved. It is `move_resource` here, since `move` is a
    /// keyword in Rust.
    fn move_resource(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("move"))
    }

    /// `serviceUrl, request` → `{location}`.
    fn request_access(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("request_access"))
    }

    /// `url` → `{document}`.
    fn get_access_request(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("get_access_request"))
    }

    /// `serviceUrl, limit?` → `{items, truncated}`.
    fn list_access_requests(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("list_access_requests"))
    }

    /// `url` → `{}`.
    fn cancel_access_request(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("cancel_access_request"))
    }

    /// `serviceUrl, grant` → `{location}`.
    fn grant_access(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("grant_access"))
    }

    /// `url` → `{document}`.
    fn get_access_grant(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("get_access_grant"))
    }

    /// `serviceUrl, limit?` → `{items, truncated}`.
    fn list_access_grants(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("list_access_grants"))
    }

    /// `url` → `{}`.
    fn revoke_access_grant(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("revoke_access_grant"))
    }

    // ---- notifications ----

    /// `serviceUrl, topics, inbox, expires?` → Subscription.
    fn subscribe(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("subscribe"))
    }

    /// `serviceUrl, limit?` → `{items, truncated}`.
    fn list_subscriptions(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("list_subscriptions"))
    }

    /// `url` → Subscription.
    fn get_subscription(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("get_subscription"))
    }

    /// `url` → `{}`.
    fn unsubscribe(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("unsubscribe"))
    }

    /// Section 5.2: `method, url, headers, bodyBase64, trustedStorages?` → `{storage, keyid,
    /// activities}`, or a `verification` error. Verify the delivery the way your client does.
    fn verify_notification(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("verify_notification"))
    }

    // ---- index ----

    /// `serviceUrl, limit?` → `{types, truncated}`.
    fn list_types(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("list_types"))
    }

    /// `serviceUrl, query, limit?` → `{items, truncated}`, following every page.
    fn search(&mut self, args: &Value) -> Result<Value, AdapterError> {
        Err(AdapterError::unsupported("search"))
    }
}
