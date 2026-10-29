package lwsdriver

// Unsupported answers unsupported to every operation but configure. Embed it in your adapter, and
// write the methods of the operations your client has: yours take the place of its.
//
//	type MyAdapter struct {
//		lwsdriver.Unsupported
//		client *myclient.Client
//	}
type Unsupported struct{}

func (Unsupported) DiscoverStorage(Args) (Result, error) {
	return nil, UnsupportedError("discover_storage")
}

func (Unsupported) Head(Args) (Result, error) {
	return nil, UnsupportedError("head")
}

func (Unsupported) Read(Args) (Result, error) {
	return nil, UnsupportedError("read")
}

func (Unsupported) ListContainer(Args) (Result, error) {
	return nil, UnsupportedError("list_container")
}

func (Unsupported) Create(Args) (Result, error) {
	return nil, UnsupportedError("create")
}

func (Unsupported) CreateContainer(Args) (Result, error) {
	return nil, UnsupportedError("create_container")
}

func (Unsupported) Update(Args) (Result, error) {
	return nil, UnsupportedError("update")
}

func (Unsupported) Patch(Args) (Result, error) {
	return nil, UnsupportedError("patch")
}

func (Unsupported) Delete(Args) (Result, error) {
	return nil, UnsupportedError("delete")
}

func (Unsupported) ReadLinkset(Args) (Result, error) {
	return nil, UnsupportedError("read_linkset")
}

func (Unsupported) UpdateLinkset(Args) (Result, error) {
	return nil, UnsupportedError("update_linkset")
}

func (Unsupported) PatchLinkset(Args) (Result, error) {
	return nil, UnsupportedError("patch_linkset")
}

func (Unsupported) Move(Args) (Result, error) {
	return nil, UnsupportedError("move")
}

func (Unsupported) RequestAccess(Args) (Result, error) {
	return nil, UnsupportedError("request_access")
}

func (Unsupported) GetAccessRequest(Args) (Result, error) {
	return nil, UnsupportedError("get_access_request")
}

func (Unsupported) ListAccessRequests(Args) (Result, error) {
	return nil, UnsupportedError("list_access_requests")
}

func (Unsupported) CancelAccessRequest(Args) (Result, error) {
	return nil, UnsupportedError("cancel_access_request")
}

func (Unsupported) GrantAccess(Args) (Result, error) {
	return nil, UnsupportedError("grant_access")
}

func (Unsupported) GetAccessGrant(Args) (Result, error) {
	return nil, UnsupportedError("get_access_grant")
}

func (Unsupported) ListAccessGrants(Args) (Result, error) {
	return nil, UnsupportedError("list_access_grants")
}

func (Unsupported) RevokeAccessGrant(Args) (Result, error) {
	return nil, UnsupportedError("revoke_access_grant")
}

func (Unsupported) Subscribe(Args) (Result, error) {
	return nil, UnsupportedError("subscribe")
}

func (Unsupported) ListSubscriptions(Args) (Result, error) {
	return nil, UnsupportedError("list_subscriptions")
}

func (Unsupported) GetSubscription(Args) (Result, error) {
	return nil, UnsupportedError("get_subscription")
}

func (Unsupported) Unsubscribe(Args) (Result, error) {
	return nil, UnsupportedError("unsubscribe")
}

func (Unsupported) VerifyNotification(Args) (Result, error) {
	return nil, UnsupportedError("verify_notification")
}

func (Unsupported) ListTypes(Args) (Result, error) {
	return nil, UnsupportedError("list_types")
}

func (Unsupported) Search(Args) (Result, error) {
	return nil, UnsupportedError("search")
}
