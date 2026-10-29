---
title: Testing a client
nav_order: 2.5
description: "Test an LWS client against a storage of its own: live results for every request it sends, a checklist of tasks, and EARL, JUnit XML and JSON reports."
---

# Testing a client
{: .no_toc }

Touchstone tests LWS servers by acting as the client. It tests LWS clients the other way round.
It plays everything a client talks to: the storage, its authorization server, an OpenID Provider,
the hosts of identity documents, and the notification service that calls the client's inbox.
You run your own client against a session of its own. Touchstone serves every request for real,
records it, judges it against the client requirements of the LWS drafts, and explains each
failure: what your client sent, which clause it breaks, and what to change.

You drive your client as you normally would, by hand or from its own test suite, and the session
page shows the results as the requests arrive. Or you write a small adapter, and Touchstone drives
your client itself (see [Let Touchstone drive your client](#let-touchstone-drive-your-client)).

{: .note }
> There is no public instance: run the service yourself, as
> [Running the service yourself](#running-the-service-yourself) describes. It is one jar.

1. TOC
{:toc}

## Start a session

Open the service's start page. You can name your client, give its version and homepage, and
choose the areas to test:

| Area | What its rules judge |
|---|---|
| Core | creating, reading, updating and deleting resources and containers; metadata and linksets; pagination; access requests and grants |
| Authentication | where the client presents its token, the credentials it exchanges for one, and how it handles a 401 |
| Notifications | subscriptions, and what the client's inbox does with the signed notifications it receives |
| Index | the type index and search |

The name, version and homepage become the subject of the reports you export. They are optional,
and you can change them, and the areas, later on the session page. The rules of an area you leave
out show as *inapplicable*.

Starting a session opens its page. **The page's address holds the session key**, after `#key=`.
Bookmark it to come back to the session, and keep it to yourself: whoever has it can read the
session's results and credentials, and end it.

## Connect your client

The session page shows the storage URL. It is the only URL your client should need: the
storage's answers lead to everything else, as they would for a real storage.

A session has two identities. **alice** owns the storage, and **bob** has no access until alice
grants it. Your client can authenticate as either in four ways:

| Way | What to do |
|---|---|
| A token from the page | Select *Get a token* in the identities table, and give your client the access token. It is the quickest start, for a client without authentication yet, and lasts an hour. |
| OpenID sign-in | Register your client's redirect URIs on the page first. A redirect URI must match exactly, except that one on `http://127.0.0.1` or `http://[::1]` may use any port. The provider does the authorization code flow with PKCE (S256) for public clients. *Show* in the identities table gives the username and password for its sign-in form. |
| Self-issued credentials (the CID suite) | *Show* also gives the private key, a JWK, of the verification method in the identity's document. Sign an ES256 JWT whose `sub`, `iss` and `client_id` are the identity's URL and whose `aud` is the authorization server, with `exp` and `iat`, and the verification method's URL as the header's `kid`. |
| A SAML 2.0 assertion (the SAML suite) | *Get an assertion* gives a signed assertion from the session's SAML identity provider, base64url-encoded and valid for five minutes. The suite leaves open how a client obtains one, so the page and the session API hand them out (`POST …/assertions/{name}`, which may name your client's identifier). The authorization server trusts the provider's key. |

With an ID Token, a self-issued credential or an assertion, your client asks the authorization
server for an access token, as the LWS authentication suites describe. The storage's `401` names
the requested resource's protected resource metadata in `resource_metadata` (RFC 9728), whose
`authorization_servers` names the authorization server, and links to the storage with
`rel="https://www.w3.org/ns/lws#storage"`. Before you ask for a token, check that the metadata's URL
and the storage's root (its description's `StorageRoot`) are on the origin you requested, and that
the metadata's `resource` is the URL you requested. The authorization server's metadata, at
`/.well-known/lws-configuration` followed by the issuer's path, gives the token endpoint. Send it an
RFC 8693 token exchange: `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`, the storage as
`resource`, the credential as `subject_token`, and its type as `subject_token_type`: `jwt` for a
self-issued credential, `id_token` for an ID Token and `saml2` for an assertion, each after
`urn:ietf:params:oauth:token-type:`.

A browser client works too: the session answers CORS itself, for any origin.

## What the storage does

The storage behaves as the LWS drafts allow, which is not always what a client expects. A client
that follows the specification never notices; one that relies on unspecified behaviour trips
over these:

- Container pages are opaque URLs: follow `first`, `next`, `prev` and `last`, and build none.
- Resource URLs do not nest under their container's. Use the `Location` of a create, a
  container's items, or a `Link` header.
- A linkset is found only through its `rel="linkset"` link.
- A data resource's linkset accepts PUT and lists it in `Allow`; a container's does not.
- Binary resources refuse PUT, and their `Allow` leaves it out.
- The root container lists a decoy first. It answers with a `401` that links to a storage whose
  root is on another origin, so a client should ask for no token for it, and send it none.
- Data resources move between containers when their linkset's up link is changed; containers do
  not, and the storage does not say whether a moved resource keeps its URL: take it from the
  response.
- The type index and search show a write only after a few seconds.
- A PUT or PATCH changes a resource's content only. The storage does no combined updates of
  content and metadata, which are optional for servers: it ignores `Prefer: set-linkset`. Update
  metadata through the linkset.

## Work through the checklist

Most rules judge whatever your client does. The checklist holds the ones that need you to do
something on purpose. Select *Start task*, then do what the task says with your client: the next
matching request is the rule's trial. The order does not matter, and you can start a task again.

Some tasks also arm a **fault**. The session then answers one request in a way a server may
legally answer, to see how your client copes:

| Fault | What the session does |
|---|---|
| `methodNotAllowed` | refuses the next PUT to a linkset that supports PUT with `405` |
| `lostCreateResponse` | performs the next create, then answers `503`, as if the answer were lost |
| `pageGone` | answers the next request for a page of search results with `410` |
| `tokenExpired` | refuses the next storage request with a valid token with `401` and `error="invalid_token"`, and that token from then on |

Four more tasks make the session forge its next notification, which your client's inbox must
refuse (see [Notifications](#notifications)).

## Read the results

Each rule's outcome is one of:

| Outcome | Meaning |
|---|---|
| passed | at least one request tried the rule, and none failed it |
| failed | a request failed it; the first is kept as evidence |
| untested | your client has not done what the rule is about yet |
| inapplicable | you left the rule's area out |

A failure shows the request that failed, with its status. Select it to open the whole exchange
in the traffic log. It also shows the term of the rule that failed, what was expected and what
your client sent, how to fix it, and links to the clause in the specification. Outcomes only get
worse during a session: once you have fixed your client, select *Reset results* to start them
over. The storage and the traffic log stay.

**The verdict.** Only MUST rules decide it. SHOULD failures are warnings, and MAY rules are
information. The verdict reads, for example, "no MUST failure in 12 MUST rules exercised, of 30
that apply". It never says plain "conformant": an untested rule is coverage the session did not
have, not evidence either way. Touchstone gives feedback, not certification.

**The traffic log** shows every request your client sent, newest first, as the storage saw it.
Credentials appear only as a fingerprint, the first twelve hexadecimal digits of their SHA-256.
The *URL came from* column says how the session gave your client the URL: `session`, `location`,
`link:next`, and so on. It shows *built by client* when your client made the URL up, which LWS
clients must not do for most URLs.

## Notifications

When your client subscribes to notifications, the session delivers a webhook notification for
every change the subscription covers, signed with an HTTP Message Signature (RFC 9421) under the
key its storage description publishes. Each delivery shows in the traffic log, marked →, with
your inbox's answer.

Your inbox must be reachable from the service: an `https` URL whose host resolves to a public
address. The service never sends to a private or loopback address, never follows a redirect, and
gives up after ten seconds. A client on a laptop needs a tunnel to a public https endpoint. A
refused delivery shows with status 0 and the reason.

The forgery tasks sign the next notification wrongly: with a key the storage does not publish,
over a body altered afterwards, under a key id without a fragment, or with the key of a document
that only claims to be the storage description. An inbox that verifies notifications as the
webhook suite's section 5.2 says refuses each, answering with anything but a `2xx`. One that
accepts a forgery fails that rule. A genuine notification should get a `2xx`.

## Export the reports

The *Export* section of the page, and the session API, give the results in three forms. All
three come from the same results, so they agree:

| Format | For | API |
|---|---|---|
| EARL, in Turtle | W3C implementation reports. One `earl:Assertion` per rule, in `earl:semiAuto` mode when you drive the client and Touchstone judges, `earl:automatic` when Touchstone drives it. The subject is your client, a `doap:Project` with its name, release and homepage. | `results?format=earl` |
| JUnit XML | CI. One test case per rule; failures of any level are JUnit failures, and untested or inapplicable rules are skipped. | `results?format=junit` |
| JSON | dashboards and scripts. Each rule's outcome, trials, evidence, guidance and specification links, the verdict, the client and when the results began. | `results?format=json` |

Nothing outlives the session, so save what you want to keep.

## Test from CI

Everything the page does is plain HTTP, authenticated with the session key as a Bearer token.
That lets your client's own test suite, in any language, run against a session:

```bash
BASE=http://localhost:18090/touchstone/clients   # your instance
curl -sf -X POST "$BASE/sessions" -H 'Content-Type: application/json' \
     -d '{"clientUnderTest": {"name": "My LWS client", "version": "'"$VERSION"'"},
          "areas": ["core", "authentication"]}' > session.json
KEY=$(jq -r .key session.json)
API=$(jq -r .api session.json)

# Your client's tests, pointed at the session's storage with alice's token.
LWS_STORAGE=$(jq -r .storage session.json) LWS_TOKEN=$(jq -r .tokens.alice session.json) ./run-my-tests

# A task between steps: the next container your tests create is judged for Link rel="type".
curl -sf -X POST -H "Authorization: Bearer $KEY" "$API/tasks/client-create-container-type-link"

curl -sf -H "Authorization: Bearer $KEY" "$API/results?format=junit" > touchstone-junit.xml
curl -sf -H "Authorization: Bearer $KEY" "$API/results" | jq -e '.verdict.mustFailed == 0'
curl -sf -X DELETE -H "Authorization: Bearer $KEY" "$API"
```

The [harness-clients README](https://github.com/ebremer/touchstone/blob/master/harness-clients/README.md)
lists the whole session API. A fault can be armed alone with `POST $API/faults/{fault}`.

## Let Touchstone drive your client

Instead of driving your client yourself, you can let Touchstone do it. You write an **adapter**: a
small program in your client's language that speaks
[`touchstone-driver/1`](https://github.com/ebremer/touchstone/blob/master/driver/PROTOCOL.md) on
its standard input and output. Touchstone sends it operations, such as `create` or
`update_linkset`, and it runs each with your client and reports what happened. Touchstone then
walks your client through every rule, starting each task and arming each fault on the way, and the
session judges your client's requests exactly as when you drive it.

1. **Start from a kit.** [`driver/kits/`](https://github.com/ebremer/touchstone/tree/master/driver/kits)
   has a starter kit for Java, Kotlin, JavaScript, Python, Go, Rust, C#, Swift, PHP and C++: an
   interface with one method per operation, a runner that handles the protocol, and a stub. Copy
   it, and implement the operations your client has. The ones it lacks leave their rules untested,
   never failed.
2. **Check it.** `touchstone driver check -- <your adapter's command>` says whether your adapter
   speaks the protocol. It needs no session.
3. **Register it** in Touchstone's `adapters.yaml`, with the client-session service to use:

   ```yaml
   services:
     local: http://localhost:18090/touchstone/clients
   clients:
     my-client:
       command: [node, adapter.mjs]
       directory: ../my-client
   ```
4. **Drive it.** `touchstone drive --client my-client` starts a session, drives your client through
   it, writes `results.json` (each rule with a note on how its drive went), `earl.ttl` and
   `junit.xml`, and exits `1` on a MUST failure, so it fits CI as it is.

The webhook rules need the session to reach the inbox Touchstone hosts for your client: a local
service started with `--allow-private-inboxes` does; a public one needs `--inbox-public-base`. The
[harness-driver README](https://github.com/ebremer/touchstone/blob/master/harness-driver/README.md)
has the details.

## Read the results from a coding agent

Touchstone's [MCP server](mcp.md) has three read-only tools over a client session, so the
coding agent working on your client can read the same feedback you see:
- `get_client_session`: the session and its verdict;
- `get_client_findings`: the failures, each with its evidence, how to fix it and the clause;
- `get_client_exchange`: one exchange of the traffic log.

Give the tools the session page's address with its key. Or set `TOUCHSTONE_CLIENTS_SESSION` to
it when you start the MCP server, so the key never passes through the agent. The tools only
read: they never drive your client, start a task or reset the results.

Started locally on stdio, the MCP server also has driving tools for a client registered in
`adapters.yaml`: `drive_rule` to drive one rule and see its outcome, `client_operation` to run one
operation, `start_task` and `arm_fault`, and `drive_client` for a whole session. An agent working on
your client can then fix it and drive the rule again, and read the findings by the session's id.
See [MCP server](mcp.md#driving-a-client).

## Proxy mode: test against a real server

The session's own storage sets traps that a client meets in the wild only now and then. To
see how your client gets on with a particular server, run the service yourself with that server
behind it as a **proxy target** (see [Running the service yourself](#running-the-service-yourself)).
Start a session with the target chosen on the start page, or with `{"proxy": "<id>"}`. Your
client then talks to the real server through the session's proxy, and the session records and
judges every request as it would its own storage's.

The server is configured with the proxy's URLs as its own, so nothing is rewritten and every way
of authenticating works. The proxy injects four faults itself: a refused linkset PUT, a lost
create, a refused page of search results, and an expired token. The rules that need what only
the session's own servers know are inapplicable: credential details, notification signatures,
a container's members, the decoy. That leaves 34 of the 53. The public service has no proxy
targets.

## Limits and privacy

- A session ends after two idle hours, or a day at most. Everything lives in memory and is gone
  when the session ends.
- A request body is at most 1 MiB, a storage holds at most 500 resources and 16 MiB, and a
  session takes a burst of 200 requests, then 20 a second.
- The traffic log keeps the latest 5,000 exchanges, with bodies cut to 64 KiB each.
- An address may start 20 sessions an hour.
- Some obligations are invisible to a server, such as not assuming that search shows a write at
  once. Touchstone has no rules for those.
- Coverage is what you exercised. The checklist and the *untested* rows show what is left.

## Running the service yourself

The service is the `harness-clients` module, a single jar:

```bash
./mvnw -pl harness-clients -am package -DskipTests
java -jar harness-clients/target/touchstone-clients.jar --port 18090 \
    --definitions definitions --catalog catalog
```

The start page is then at `http://localhost:18090/touchstone/clients/`. Add
`--allow-private-inboxes` to deliver notifications to an inbox on your own machine; a public
service never sets it. Add `--proxy-targets targets.yaml` to put real servers behind the service
for proxy mode; the
[harness-clients README](https://github.com/ebremer/touchstone/blob/master/harness-clients/README.md#proxy-mode)
shows how to register one. Behind a reverse proxy, give the public URL with `--public-base`, forward
the base path, and `/.well-known/lws-configuration` and `/.well-known/oauth-protected-resource`
followed by it, and add
`--trust-forwarded-for` when the proxy sets `X-Forwarded-For`. The proxy must leave the
`Access-Control-*` headers alone, since the service answers CORS itself. The
[harness-clients README](https://github.com/ebremer/touchstone/blob/master/harness-clients/README.md)
has the options and the bounds.

## Troubleshooting

**The storage answers `401` to everything.** That is the LWS challenge: follow `resource_metadata`
to the authorization server and exchange a credential, or use a token from the page. For a token from the
page, send it as `Authorization: Bearer <token>`, and nowhere else.

**A rule fails with "issued: expected true".** Your client built a URL instead of following one
the storage gave it. The traffic log's *URL came from* column says which handed-out URL it was
built from.

**No notification arrives.** The traffic log shows each delivery. Status 0 means the service did
not send it, or got no answer; the reason says why. The usual cause is an inbox URL that is not a
public `https` URL.

**The page says the session has ended.** It expired, or someone ended it. Start a new one;
the old results are gone unless you exported them.

**`429` when starting a session.** The address has started twenty sessions in the last hour.
