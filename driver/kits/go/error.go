package lwsdriver

// The kinds of Error (section 4.2).
const (
	KindHTTP             = "http"
	KindAuthentication   = "authentication"
	KindProtocol         = "protocol"
	KindVerification     = "verification"
	KindTransport        = "transport"
	KindInvalidArguments = "invalidArguments"
	KindUnsupported      = "unsupported"
	KindInternal         = "internal"
)

// Error is an operation's outcome that is not a result (section 4.2): it is reported to the
// driver, and the adapter keeps running. A 409 from deleting a non-empty container is exactly what
// a drive may be looking for. Return it from an operation; any other error answers internal.
type Error struct {
	Kind    string // one of the Kind constants
	Status  int    // the HTTP status of the response that caused it, or 0; http requires it
	Message string
}

func (e *Error) Error() string { return e.Kind + ": " + e.Message }

// NewError is an Error of any kind; status is 0 when no response caused it.
func NewError(kind string, status int, message string) *Error {
	return &Error{Kind: kind, Status: status, Message: message}
}

// HTTPError is a response with a status the operation does not succeed with.
func HTTPError(status int, message string) *Error {
	return &Error{Kind: KindHTTP, Status: status, Message: message}
}

// UnsupportedError says the operation, or an argument it was given, is not available in this
// client.
func UnsupportedError(what string) *Error {
	return &Error{Kind: KindUnsupported, Message: what + " is not available in this client"}
}

// InvalidArgumentsError says the request's arguments are missing or malformed.
func InvalidArgumentsError(message string) *Error {
	return &Error{Kind: KindInvalidArguments, Message: message}
}

// TransportError is no response: connection refused, TLS failure, timeout.
func TransportError(message string) *Error {
	return &Error{Kind: KindTransport, Message: message}
}
