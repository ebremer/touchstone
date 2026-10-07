# harness-clients: client sessions

The service that tests LWS clients ([CLIENT-TESTING.md](../CLIENT-TESTING.md)). A client
developer starts a session, points their client at the session's storage, and watches every
request it sends on the session's page. Every request is judged against the client rules,
`definitions/lws10/clients/`, as it is recorded ([`OBSERVATION.md`](../definitions/OBSERVATION.md)).
Phases C1 to C7 are built: sessions, the traffic log, the rules, the tasks and faults that let a
developer try every rule on purpose, four ways for a client to authenticate, signed
notifications to the client's inbox, the guided page and the EARL, JUnit XML and JSON
exports, and proxy mode, in which a session fronts a real server. The guide for client developers is the docs site's
[Testing a client](../docs/testing-a-client.md).

## Running it

```bash
./mvnw -pl harness-clients -am package -DskipTests
java -jar harness-clients/target/touchstone-clients.jar \
    --public-base https://example.org/touchstone/clients --port 18090 --trust-forwarded-for \
    --definitions definitions --catalog catalog
```

| Option | Default | Meaning |
|---|---|---|
| `--public-base` | `http://localhost:<port>/touchstone/clients` | The service's public URL: a path, no trailing slash. Every URL a session hands out starts with it. |
| `--bind` | `127.0.0.1` | The interface to listen on. |
| `--port` | `18090` | The port. |
| `--trust-forwarded-for` | off | Take the client's address from `X-Forwarded-For`, for the per-address session limit. Only behind a proxy that sets it. |
| `--definitions` | `definitions` | The definitions directory; the client rules are under `lws10/clients/`. |
| `--catalog` | `catalog` | The requirements catalog the rules cite. |
| `--allow-private-inboxes` | off | Deliver notifications to http URLs and private addresses, such as an inbox on the same machine. For local development only: never on a public service. |
| `--proxy-targets` | none | A target registry of real servers deployed behind the service, which proxy sessions front ([Proxy mode](#proxy-mode)). |

The rules are loaded and checked at start: YAML, schema, JSON-LD and the lint of
`OBSERVATION.md` section 2. A rule that fails stops the service with exit code 2.

Behind a reverse proxy, forward two path prefixes, keeping the paths:

- the public base path, such as `/touchstone/clients/`;
- `/.well-known/lws-configuration` followed by the public base path: each session's
  authorization server publishes its RFC 8414 metadata there.

The service answers CORS itself, so a proxy must not add or replace `Access-Control-*` headers
on these paths.

## A session

`POST <base>/sessions` starts one. Its body may name the client under test, which becomes the
subject of the EARL report, and the areas in scope; both are optional, and an empty body will do:

```json
{"clientUnderTest": {"name": "My LWS client", "version": "0.3.1", "homepage": "https://example.org/my-client"},
 "areas": ["core", "authentication", "notifications", "index"]}
```

The name is at most 100 characters, the version 50, and the homepage an absolute http or https
URL. A rule whose area is left out is *inapplicable*. Settings that do not check out are refused
with `400` before the session counts against the address's limit. The answer, `201`, holds:

- `storage`: the storage URL, the one URL a client needs;
- `tokens`: access tokens for alice, who owns the storage, and bob, who has no access until
  alice grants it, for a client without authentication yet;
- `key`: the session key, shown only here, and `pageWithKey`, the session page's URL with
  the key in its fragment.

The session API takes the key as a Bearer token:

| Request | Answer |
|---|---|
| `GET <base>/sessions/{id}` | the session: URLs, identities, the client under test, the areas in scope, the export URLs, traps, limits, expiry |
| `PATCH <base>/sessions/{id}` | changes the settings, with a body like the one that starts a session; `clientUnderTest: null` forgets the name. Returns the session |
| `GET <base>/sessions/{id}/exchanges?after=N&limit=M` | the traffic log after exchange `N`, at most `M` (≤ 500) |
| `GET <base>/sessions/{id}/results` | each rule's outcome, trials, specification links and first failure with how to fix it, and the verdict |
| `GET <base>/sessions/{id}/results?format=earl` | the results as EARL, in Turtle: one `earl:Assertion` per rule, in `earl:semiAuto` mode, about the client as named, by its homepage when it has one |
| `GET <base>/sessions/{id}/results?format=junit` | the results as JUnit XML, one test case per rule, for CI |
| `GET <base>/sessions/{id}/results?format=json` | the JSON results, named for saving |
| `POST <base>/sessions/{id}/reset` | starts the results over, and their date; the storage and the log stay |
| `POST <base>/sessions/{id}/tasks/{rule}` | starts a rule's task, arming its fault if it has one; `204`, or `404` for a rule without a task |
| `POST <base>/sessions/{id}/faults/{fault}` | arms a fault alone: `methodNotAllowed`, `lostCreateResponse`, `pageGone`, `tokenExpired`, or a forgery: `forgedUnpublishedKey`, `forgedAlteredBody`, `forgedKeyidWithoutFragment`, `forgedForeignKeyDocument` |
| `POST <base>/sessions/{id}/tokens/{alice\|bob}` | a fresh access token |
| `GET <base>/sessions/{id}/credentials/{alice\|bob}` | the identity's username and password for the OpenID Provider, and the private JWK of the key its identity document lists |
| `POST <base>/sessions/{id}/assertions/{alice\|bob}` | a signed SAML 2.0 assertion about the identity, base64url-encoded, valid for five minutes, with its issuer, subject, recipient and audiences; the body may name the client, `{"client_id": "..."}`, an absolute URI, which becomes the recipient and an audience. Not in a proxy session |
| `POST <base>/sessions/{id}/clients` | registers a client with the OpenID Provider: `{"redirect_uris": [...], "client_id": "..."}`, the identifier optional; `GET` lists them |
| `DELETE <base>/sessions/{id}` | ends the session |
| `GET <base>/sessions/{id}/page` | the session page; it reads the key from its fragment, `#key=…` |

Each exchange in the log is redacted: credentials appear as a fingerprint, the first twelve
hex digits of their SHA-256. It is annotated with:

- what the request addressed;
- whose valid token it carried, and how it was presented;
- whether the session handed out the URL, and how, or which handed-out URL the client built it
  from, by query or by path;
- what the URL last advertised in `Allow`, `Accept-Patch`, `Accept-Query` and `ETag`;
- for a token request, where its credential came from, the credential's header and claims, and
  whether the realm it asks for contains the URL a 401 refused;
- the rules it was a trial of, and how each judged it.

A rule's outcome is *passed* once a request has tried it and none failed it, *failed* with the
first failing request kept as evidence, or *untested*. Eleven rules need a task. Three take the
developer's word for what the client is about to do: create a container, delete one with its
contents, or update a resource's content and metadata in one request. Four arm a fault, which makes the session answer the next request it applies to once
in a way a server may legally answer: refuse a linkset PUT it advertised, lose a create's
answer, refuse an expired page of search results, or refuse an access token as expired. Four
more make it forge its next notification, which the client's inbox must refuse. A developer starts a task on the session
page, or a CI job through the API, then has the client do what the task says. Only MUST rules decide the verdict,
which reads, for example, "no MUST failure in 12 MUST rules exercised, of 18 that apply".

## Exports

The three exports come from one results document, so they agree. In each, a rule is one test:

- **JSON** is the results as the API gives them, with the client under test, the areas in scope,
  when the results began (the session's start or the latest reset), and the harness's version.
- **EARL** is the format of W3C implementation reports. The subject is the client under test,
  a `doap:Project` with its name, release and homepage, or the session when it has no name. A
  rule's test case carries `touchstone:verifies` for each requirement it cites, and a result that
  did not pass says why in `earl:info`.
- **JUnit XML** has one test case per rule, classed by its manifest (`clients/core`). A failure
  of any level is a JUnit failure, so CI shows it; *untested* and *inapplicable* rules are
  skipped. Gate a build on the MUST failures: their message starts with `MUST`.

```bash
curl -s -X POST "$BASE/sessions" -H 'Content-Type: application/json' \
     -d '{"clientUnderTest": {"name": "My LWS client", "version": "'"$VERSION"'"}}' > session.json
# ... run the client's own tests against $(jq -r .storage session.json) ...
curl -s -H "Authorization: Bearer $(jq -r .key session.json)" \
     "$(jq -r .exports.junit session.json)" > touchstone-junit.xml
```

## Authentication

A session offers four ways to authenticate as alice or bob:

- **OpenID sign-in.** The session's OpenID Provider, `<base>/s/{id}/op`, does the authorization
  code flow with PKCE (S256), for public clients. Register the client's redirect URIs first. A
  redirect URI must match exactly, except that one on `http://127.0.0.1` or `http://[::1]` may
  use any port. The provider's sign-in form takes the identity's username and password. Its ID
  Tokens name the client in `azp`, and the client and the session's authorization server in
  `aud`.
- **Self-issued credentials** (the CID suite). Each identity's document, `<base>/s/{id}/id/{name}`,
  lists a P-256 key, whose private JWK the API gives. Sign an ES256 JWT with `sub`, `iss` and
  `client_id` set to the identity's URL, `aud` set to the authorization server, `exp` and `iat`.
  Put the verification method's URL, the JWK's `kid`, in the header's `kid`.
- **A SAML 2.0 assertion** (the SAML suite). The session's SAML identity provider, whose entity
  identifier `samlIdentityProvider.entityId` gives, has no endpoint of its own: the suite leaves
  open how a client obtains an assertion, so `POST …/assertions/{name}` hands one out. It is
  signed RSA-SHA256, names the identity's URL in `NameID`, the client in `Recipient`, and the
  client and the authorization server in `Audience`. Present it as it comes, base64url-encoded,
  with `subject_token_type=urn:ietf:params:oauth:token-type:saml2`.
- **A token from the session**, for a client without authentication yet.

A client exchanges an ID Token, a self-issued credential or an assertion at the authorization
server, whose `as_uri` and `realm` the storage's 401 names (RFC 8693 token exchange). The
authorization server trusts the session's identities, OpenID Provider and SAML identity provider
only. It fetches nothing, so a credential about anyone else is refused.

## Notifications

The storage delivers a webhook notification for every change a subscription covers, and for an
access grant that names an inbox, signed with RFC 9421 HTTP Message Signatures under the key its
storage description publishes. Each delivery is recorded in the traffic log, with the inbox's
answer, and judged.

Notifications go only to `https` inbox URLs whose host resolves to public unicast addresses. The
addresses are checked as the connection is made, so DNS rebinding gains nothing. Redirects are
not followed, a delivery times out after ten seconds, and a session sends at most 500. A
refused delivery is recorded with status 0 and the reason. An inbox on a laptop needs a public
https endpoint, such as a tunnel, unless the service runs locally with `--allow-private-inboxes`.

Four tasks make the session forge the next notification:
- signed with a key the storage description does not publish;
- altered after it was signed;
- under a keyid without a fragment;
- signed with the key of a document under the storage that claims to be its description.

An inbox that verifies as lws10-notifications-webhook section 5.2 says refuses each.

## Proxy mode

A proxy session fronts a real server instead of the session's own storage (CLIENT-TESTING.md
section 10). The server is deployed behind the service as its front door: it is configured with
`<base>/p/<id>/` as the start of its public URLs, so nothing it sends needs rewriting, and every
authentication suite works as it would without the proxy. Register it in a target registry and
pass that with `--proxy-targets`:

```yaml
targets:
  local:
    baseUrl: http://localhost:18090/touchstone/clients/p/local/storage/   # the storage, as clients see it
    adapter: env
    properties:
      proxy.backend: http://127.0.0.1:8090/      # where <base>/p/local/ is forwarded
      proxy.issuer: http://localhost:18090/touchstone/clients/p/local/as   # optional: its authorization server
```

The service forwards `<base>/p/<id>/X` to `proxy.backend` followed by `X`. It forwards
`/.well-known/lws-configuration` followed by the base path and `/p/<id>`, unchanged, to the
backend's origin, where the server's authorization server publishes its metadata.

`POST <base>/sessions` with `{"proxy": "local"}` starts a proxy session. One session holds a
target at a time, and a second gets `409` until the first ends. A request to the target's URLs
with no session holding it is answered `503`, and forwarded nowhere. In a proxy session:

- every request is recorded and judged. Each role is inferred from what the server handed out
  ([OBSERVATION.md](../definitions/OBSERVATION.md) section 11);
- the proxy injects four faults alone: `methodNotAllowed`, `lostCreateResponse`, `pageGone` and
  `tokenExpired`;
- 19 rules are inapplicable, since they need what only the session's own servers know. Their
  results say `"inapplicableBecause": "proxy"`, and starting their tasks gets `409`;
- the session hands out no tokens or SAML assertions, and serves no storage or authorization
  server of its own.
  Its identities and OpenID Provider remain, and work if the server trusts them. With
  `proxy.issuer`, the provider's ID Tokens name that authorization server in `aud`;
- the server delivers its notifications itself, not through the proxy.

The public service registers no proxy target: a stranger's session should not reach a real
server. `GET <base>/proxies` lists the targets and whether a session holds each; the start page
offers them.

## Traps

Every session's storage sets all of these. Each is legal under the drafts, and the server
self-test proves it: every definition passes against a reference deployment with them set. The
exception is PUT on data resources' linksets, which the draft makes optional in so many words;
the self-test leaves it off, because one server test needs a linkset that refuses PUT.

- Container pages are opaque URLs under `_t/p/`; a `?page=` query answers 404.
- Resources the server names get URLs under `_r/`, which do not nest under their container's.
- A linkset is an opaque URL under `_t/l/`, found only through `rel="linkset"`. A data
  resource's linkset accepts PUT and lists it in `Allow`; a container's does not.
- Binary resources refuse PUT, and their `Allow` leaves it out. A resource is binary unless its
  media type is text, JSON, XML or an RDF syntax.
- The root container lists a decoy first. It answers every request with a 401 whose realm does
  not contain it.
- The type index and search show a write only after three seconds.
- The storage does no combined updates of content and metadata, which are optional for servers:
  it ignores `Prefer: set-linkset` and changes only the content.

## Bounds

| Bound | Default |
|---|---|
| live sessions | 100 |
| sessions per address per hour | 20 |
| idle timeout / maximum lifetime | 2 hours / 24 hours |
| request body | 1 MiB (`413`) |
| resources / bytes per storage | 500 / 16 MiB (`507`) |
| requests | a burst of 200, then 20 a second (`429`) |
| exchanges kept | 5,000, and 8 KiB of body text each on average, about 40 million characters; older ones are dropped and counted |
| URLs the ledger remembers | 10,000 in each of its maps; a URL first seen beyond that is not remembered |
| access token lifetime | 1 hour |

Everything is in memory; an ended session leaves nothing. The OpenID Provider holds at most 10
clients, with 5 redirect URIs each, and 100 sign-ins in progress; a sign-in lasts 10 minutes and
an authorization code one minute. A session sends at most 500 notifications.
