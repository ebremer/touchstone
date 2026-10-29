// Package lwsdriver makes an LWS client drivable by Touchstone: it speaks touchstone-driver/1
// (driver/PROTOCOL.md) on stdin and stdout, so that your adapter is only your client.
package lwsdriver

// Args are an operation's arguments, and Result its result: the JSON objects the protocol
// describes, as encoding/json decodes them (numbers are float64). An absent member and a null one
// both mean absent (section 4).
type (
	Args   = map[string]any
	Result = map[string]any
)

// ClientInfo is the client under test, as the hello names it (section 3): it becomes the subject
// of the session's EARL report. Version and Homepage are optional; empty leaves them out.
type ClientInfo struct {
	Name     string `json:"name"`
	Version  string `json:"version,omitempty"`
	Homepage string `json:"homepage,omitempty"` // an absolute http(s) URL
}

// Adapter is your client, as Touchstone's driver sees it (section 5): one method per operation.
// Each takes the operation's arguments and returns its result, or an *Error for an outcome that is
// not a result, such as a 404.
//
// Embed Unsupported, and implement the operations your client has, by calling your client's own
// operation with its own options. Name them in Operations, too: Go cannot see which methods you
// wrote yourself, so Operations is what the hello lists and what Serve dispatches. The others
// answer unsupported, and the rules that need them stay untested, never failed.
//
// Be a thin, faithful wrapper: never retry, fall back or fix anything up for your client. The point
// is to see what the client sends.
type Adapter interface {
	// Client is the client under test, as the session's report names it.
	Client() ClientInfo

	// Auth lists the kinds of configure authentication your client supports: none, bearer, cid,
	// openid, saml.
	Auth() []string

	// Operations names the operations you implement, as section 5 names them, such as "read".
	// configure is always implemented, listed or not.
	Operations() []string

	// Configure, section 5.1: auth, userAgent?, timeoutSeconds? -> {agent?}. Build the client every
	// later operation uses, with auth. Always implemented.
	Configure(args Args) (Result, error)

	// ---- core ----

	// DiscoverStorage: url -> Storage. Find the storage a URL belongs to and read its description.
	DiscoverStorage(args Args) (Result, error)
	// Head: url -> Metadata.
	Head(args Args) (Result, error)
	// Read: url, accept?, ifNoneMatch?, rangeStart?, rangeEnd? -> {metadata, notModified, body?}.
	Read(args Args) (Result, error)
	// ListContainer: url, limit? -> {items, truncated}, following every page.
	ListContainer(args Args) (Result, error)
	// Create: container, body?, contentType?, slug?, types?, links? -> Created.
	Create(args Args) (Result, error)
	// CreateContainer: parent, slug? -> Created.
	CreateContainer(args Args) (Result, error)
	// Update: url, body, contentType?, ifMatch?, ifNoneMatch?, links? -> Update.
	Update(args Args) (Result, error)
	// Patch: url, patch, ifMatch? -> Update.
	Patch(args Args) (Result, error)
	// Delete: url, recursive?, ifMatch? -> {}.
	Delete(args Args) (Result, error)
	// ReadLinkset: url (the resource) -> {url, etag?, linkset, allow, acceptPatch}.
	ReadLinkset(args Args) (Result, error)
	// UpdateLinkset: linksetUrl, linkset, ifMatch? -> Update.
	UpdateLinkset(args Args) (Result, error)
	// PatchLinkset: linksetUrl, patch, ifMatch? -> Update.
	PatchLinkset(args Args) (Result, error)
	// Move: url, destination, ifMatch? -> Moved.
	Move(args Args) (Result, error)
	// RequestAccess: serviceUrl, request -> {location}.
	RequestAccess(args Args) (Result, error)
	// GetAccessRequest: url -> {document}.
	GetAccessRequest(args Args) (Result, error)
	// ListAccessRequests: serviceUrl, limit? -> {items, truncated}.
	ListAccessRequests(args Args) (Result, error)
	// CancelAccessRequest: url -> {}.
	CancelAccessRequest(args Args) (Result, error)
	// GrantAccess: serviceUrl, grant -> {location}.
	GrantAccess(args Args) (Result, error)
	// GetAccessGrant: url -> {document}.
	GetAccessGrant(args Args) (Result, error)
	// ListAccessGrants: serviceUrl, limit? -> {items, truncated}.
	ListAccessGrants(args Args) (Result, error)
	// RevokeAccessGrant: url -> {}.
	RevokeAccessGrant(args Args) (Result, error)

	// ---- notifications ----

	// Subscribe: serviceUrl, topics, inbox, expires? -> Subscription.
	Subscribe(args Args) (Result, error)
	// ListSubscriptions: serviceUrl, limit? -> {items, truncated}.
	ListSubscriptions(args Args) (Result, error)
	// GetSubscription: url -> Subscription.
	GetSubscription(args Args) (Result, error)
	// Unsubscribe: url -> {}.
	Unsubscribe(args Args) (Result, error)
	// VerifyNotification, section 5.2: method, url, headers, bodyBase64, trustedStorages? ->
	// {storage, keyid, activities}, or a verification error. Verify the delivery the way your
	// client does.
	VerifyNotification(args Args) (Result, error)

	// ---- index ----

	// ListTypes: serviceUrl, limit? -> {types, truncated}.
	ListTypes(args Args) (Result, error)
	// Search: serviceUrl, query, limit? -> {items, truncated}, following every page.
	Search(args Args) (Result, error)
}
