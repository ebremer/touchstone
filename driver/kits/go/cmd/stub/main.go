// Command stub is the starting point: an adapter that implements only configure, so every other
// operation answers unsupported. It passes touchstone driver check as it is. Replace the client
// name, keep your client in a field that Configure builds, and implement the operations your
// client has, one by one, naming each in Operations.
package main

import (
	"log"
	"os"
	"slices"

	"example.com/lwsdriver"
)

// StubAdapter embeds Unsupported, so every operation it does not implement answers unsupported.
type StubAdapter struct {
	lwsdriver.Unsupported
	// Your client goes here, built by Configure.
}

func (a *StubAdapter) Client() lwsdriver.ClientInfo {
	return lwsdriver.ClientInfo{Name: "My LWS client (Go)", Version: "0.0.0"}
}

func (a *StubAdapter) Auth() []string {
	return []string{"none"}
}

// Operations names the operations this adapter implements, such as "read": the hello lists them,
// and only they reach their methods.
func (a *StubAdapter) Operations() []string {
	return []string{}
}

func (a *StubAdapter) Configure(args lwsdriver.Args) (lwsdriver.Result, error) {
	auth, _ := args["auth"].(map[string]any)
	kind, _ := auth["type"].(string)
	if !slices.Contains(a.Auth(), kind) {
		return nil, lwsdriver.UnsupportedError("authentication " + kind)
	}
	// Build your client here, with the authentication args["auth"] describes.
	return lwsdriver.Result{}, nil
}

func main() {
	// stdout carries protocol lines only: whatever else the program prints goes to stderr.
	protocol := os.Stdout
	os.Stdout = os.Stderr
	if err := lwsdriver.Serve(&StubAdapter{}, os.Stdin, protocol); err != nil {
		log.Fatal(err)
	}
}
