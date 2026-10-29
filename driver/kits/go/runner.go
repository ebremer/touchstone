package lwsdriver

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"runtime/debug"
)

// Protocol is the protocol Serve speaks.
const Protocol = "touchstone-driver/1"

// logger writes to stderr: stdout carries protocol lines only.
var logger = log.New(os.Stderr, "lwsdriver: ", log.LstdFlags)

// operation is one operation of the protocol: how Serve calls it.
type operation struct {
	name string
	call func(Adapter, Args) (Result, error)
}

// operations are every operation of section 5 but shutdown, with the method each calls.
var operations = []operation{
	{"configure", Adapter.Configure},
	{"discover_storage", Adapter.DiscoverStorage},
	{"head", Adapter.Head},
	{"read", Adapter.Read},
	{"list_container", Adapter.ListContainer},
	{"create", Adapter.Create},
	{"create_container", Adapter.CreateContainer},
	{"update", Adapter.Update},
	{"patch", Adapter.Patch},
	{"delete", Adapter.Delete},
	{"read_linkset", Adapter.ReadLinkset},
	{"update_linkset", Adapter.UpdateLinkset},
	{"patch_linkset", Adapter.PatchLinkset},
	{"move", Adapter.Move},
	{"request_access", Adapter.RequestAccess},
	{"get_access_request", Adapter.GetAccessRequest},
	{"list_access_requests", Adapter.ListAccessRequests},
	{"cancel_access_request", Adapter.CancelAccessRequest},
	{"grant_access", Adapter.GrantAccess},
	{"get_access_grant", Adapter.GetAccessGrant},
	{"list_access_grants", Adapter.ListAccessGrants},
	{"revoke_access_grant", Adapter.RevokeAccessGrant},
	{"subscribe", Adapter.Subscribe},
	{"list_subscriptions", Adapter.ListSubscriptions},
	{"get_subscription", Adapter.GetSubscription},
	{"unsubscribe", Adapter.Unsubscribe},
	{"verify_notification", Adapter.VerifyNotification},
	{"list_types", Adapter.ListTypes},
	{"search", Adapter.Search},
}

type helloJSON struct {
	Hello struct {
		Protocol   string     `json:"protocol"`
		Client     ClientInfo `json:"client"`
		Language   string     `json:"language"`
		Auth       []string   `json:"auth"`
		Operations []string   `json:"operations"`
	} `json:"hello"`
}

type okJSON struct {
	ID     json.RawMessage `json:"id"`
	OK     bool            `json:"ok"`
	Result Result          `json:"result"`
}

type failedJSON struct {
	ID    json.RawMessage `json:"id"`
	OK    bool            `json:"ok"`
	Error errorJSON       `json:"error"`
}

type errorJSON struct {
	Kind    string `json:"kind"`
	Status  int    `json:"status,omitempty"`
	Message string `json:"message"`
}

// Serve is the protocol, so that your adapter is only your client (driver/PROTOCOL.md sections 2
// and 3): it writes the hello, reads one request a line from in, calls the Adapter method of its
// operation, and writes one response a line to out, until end of input or shutdown. It returns an
// error only when in or out broke.
//
//	func main() {
//		if err := lwsdriver.Serve(&MyAdapter{}, os.Stdin, os.Stdout); err != nil {
//			log.Fatal(err)
//		}
//	}
//
// out carries protocol lines only: log to stderr. Copy it as it is: it follows
// touchstone-driver/1, and nothing in it is particular to a client.
func Serve(adapter Adapter, in io.Reader, out io.Writer) error {
	w := bufio.NewWriter(out)
	implemented := implementedBy(adapter)
	if err := write(w, hello(adapter, implemented)); err != nil {
		return err
	}
	r := bufio.NewReader(in) // ReadBytes takes a line of any length
	for {
		line, readErr := r.ReadBytes('\n')
		if line = bytes.TrimSpace(line); len(line) > 0 {
			response, shutdown := respond(adapter, implemented, line)
			if err := write(w, response); err != nil {
				return err
			}
			if shutdown {
				return nil
			}
		}
		if readErr == io.EOF {
			return nil
		}
		if readErr != nil {
			return readErr
		}
	}
}

// implementedBy is what Operations names, and configure.
func implementedBy(adapter Adapter) map[string]bool {
	known := map[string]bool{"shutdown": true}
	for _, op := range operations {
		known[op.name] = true
	}
	implemented := map[string]bool{"configure": true}
	for _, name := range adapter.Operations() {
		if !known[name] {
			logger.Printf("Operations names %q, which is not an operation of %s", name, Protocol)
			continue
		}
		implemented[name] = true
	}
	return implemented
}

// hello is the client, its authentication, and every operation the adapter implements.
func hello(adapter Adapter, implemented map[string]bool) []byte {
	var h helloJSON
	h.Hello.Protocol = Protocol
	h.Hello.Client = adapter.Client()
	h.Hello.Language = "go"
	h.Hello.Auth = []string{}
	seen := map[string]bool{}
	for _, kind := range adapter.Auth() {
		if !seen[kind] {
			seen[kind] = true
			h.Hello.Auth = append(h.Hello.Auth, kind)
		}
	}
	h.Hello.Operations = []string{}
	for _, op := range operations {
		if implemented[op.name] {
			h.Hello.Operations = append(h.Hello.Operations, op.name)
		}
	}
	line, err := encode(h)
	if err != nil {
		panic(err) // strings only: it cannot fail
	}
	return line
}

// respond answers one request line, and says whether it was shutdown.
func respond(adapter Adapter, implemented map[string]bool, line []byte) ([]byte, bool) {
	var request map[string]json.RawMessage
	if err := json.Unmarshal(line, &request); err != nil || request == nil {
		return failed(nil, "", InvalidArgumentsError("a request is a JSON object")), false
	}
	id := request["id"]
	var name string
	_ = json.Unmarshal(request["op"], &name)
	if name == "shutdown" {
		return answered(id, name, Result{}), true
	}
	args := Args{}
	if raw, ok := request["args"]; ok {
		var given Args
		if json.Unmarshal(raw, &given) == nil && given != nil {
			args = given
		}
	}
	for _, op := range operations {
		if op.name == name && implemented[name] {
			result, err := call(op, adapter, args)
			if err != nil {
				return failed(id, name, err), false
			}
			return answered(id, name, result), false
		}
	}
	if name == "" {
		name = "an operation without a name"
	}
	return failed(id, name, UnsupportedError(name)), false
}

// call runs an operation, turning a panic into internal so that the adapter keeps running.
func call(op operation, adapter Adapter, args Args) (result Result, err error) {
	defer func() {
		if r := recover(); r != nil {
			logger.Printf("%s: panic: %v\n%s", op.name, r, debug.Stack())
			err = &Error{Kind: KindInternal, Message: fmt.Sprintf("panic: %v", r)}
		}
	}()
	return op.call(adapter, args)
}

func answered(id json.RawMessage, name string, result Result) []byte {
	if result == nil {
		result = Result{}
	}
	line, err := encode(okJSON{ID: id, OK: true, Result: result})
	if err != nil {
		return failed(id, name, fmt.Errorf("the result is not JSON: %w", err))
	}
	return line
}

// failed reports an *Error as it is, and any other error as internal: a bug in the adapter or the
// client.
func failed(id json.RawMessage, name string, err error) []byte {
	var e *Error
	if !errors.As(err, &e) || e == nil {
		logger.Printf("%s: %v", name, err)
		e = &Error{Kind: KindInternal, Message: err.Error()}
	}
	message := e.Message
	if message == "" {
		message = e.Kind
	}
	line, _ := encode(failedJSON{ID: id, OK: false, Error: errorJSON{Kind: e.Kind, Status: e.Status, Message: message}})
	return line
}

// encode is one line of JSON, ending in "\n" (the encoder escapes newlines inside strings).
func encode(v any) ([]byte, error) {
	var b bytes.Buffer
	enc := json.NewEncoder(&b)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return b.Bytes(), nil
}

// write writes one line and flushes it.
func write(w *bufio.Writer, line []byte) error {
	if _, err := w.Write(line); err != nil {
		return err
	}
	return w.Flush()
}
