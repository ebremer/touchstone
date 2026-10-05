# harness-clients: client sessions

The service that tests LWS clients ([CLIENT-TESTING.md](../CLIENT-TESTING.md)). A client
developer starts a session, points their client at the session's storage, and watches every
request it sends on the session's page. Every request is judged against the client rules,
`definitions/lws10/clients/`, as it is recorded ([`OBSERVATION.md`](../definitions/OBSERVATION.md)).
Phases C1 to C3 are built: sessions, the traffic log, the rules, and the tasks and faults that
let a developer try every rule on purpose.

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

The rules are loaded and checked at start: YAML, schema, JSON-LD and the lint of
`OBSERVATION.md` section 2. A rule that fails stops the service with exit code 2.

Behind a reverse proxy, forward two path prefixes, keeping the paths:

- the public base path, such as `/touchstone/clients/`;
- `/.well-known/lws-configuration` followed by the public base path: each session's
  authorization server publishes its RFC 8414 metadata there.

The service answers CORS itself, so a proxy must not add or replace `Access-Control-*` headers
on these paths.

## A session

`POST <base>/sessions` starts one. The answer, `201`, holds:

- `storage`: the storage URL, the one URL a client needs;
- `tokens`: access tokens for alice, who owns the storage, and bob, who has no access until
  alice grants it;
- `key`: the session key, shown only here, and `pageWithKey`, the session page's URL with
  the key in its fragment.

The session API takes the key as a Bearer token:

| Request | Answer |
|---|---|
| `GET <base>/sessions/{id}` | the session: URLs, identities, traps, limits, expiry |
| `GET <base>/sessions/{id}/exchanges?after=N&limit=M` | the traffic log after exchange `N`, at most `M` (≤ 500) |
| `GET <base>/sessions/{id}/results` | each rule's outcome, trials and first failure with how to fix it, and the verdict |
| `POST <base>/sessions/{id}/reset` | starts the results over; the storage and the log stay |
| `POST <base>/sessions/{id}/tasks/{rule}` | starts a rule's task, arming its fault if it has one; `204`, or `404` for a rule without a task |
| `POST <base>/sessions/{id}/faults/{fault}` | arms a fault alone: `methodNotAllowed`, `lostCreateResponse` or `pageGone` |
| `POST <base>/sessions/{id}/tokens/{alice\|bob}` | a fresh access token |
| `DELETE <base>/sessions/{id}` | ends the session |
| `GET <base>/sessions/{id}/page` | the session page; it reads the key from its fragment, `#key=…` |

Each exchange in the log is redacted: credentials appear as a fingerprint, the first twelve
hex digits of their SHA-256. It is annotated with:

- what the request addressed;
- whose valid token it carried, and how it was presented;
- whether the session handed out the URL, and how, or which handed-out URL the client built it
  from, by query or by path;
- what the URL last advertised in `Allow`, `Accept-Patch`, `Accept-Query` and `ETag`;
- the rules it was a trial of, and how each judged it.

A rule's outcome is *passed* once a request has tried it and none failed it, *failed* with the
first failing request kept as evidence, or *untested*. Five rules need a task. Two take the
developer's word for what the client is about to do: create a container, or delete one with its
contents. Three arm a fault, which makes the session answer the next request it applies to once
in a way a server may legally answer: refuse a linkset PUT it advertised, lose a create's
answer, or refuse an expired page of search results. A developer starts a task on the session
page, or a CI job through the API, then has the client do what the task says. Only MUST rules decide the verdict,
which reads, for example, "no MUST failure in 12 MUST rules exercised, of 18 that apply".

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

## Bounds

| Bound | Default |
|---|---|
| live sessions | 100 |
| sessions per address per hour | 10 |
| idle timeout / maximum lifetime | 2 hours / 24 hours |
| request body | 1 MiB (`413`) |
| resources / bytes per storage | 500 / 16 MiB (`507`) |
| requests | a burst of 200, then 20 a second (`429`) |
| exchanges kept | 5,000; older ones are dropped and counted |
| access token lifetime | 1 hour |

Everything is in memory; an ended session leaves nothing. Notifications are not delivered
until phase C5 brings the outbound guard of CLIENT-TESTING.md section 8.3.
