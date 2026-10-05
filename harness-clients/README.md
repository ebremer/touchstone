# harness-clients: client sessions

The service that tests LWS clients ([CLIENT-TESTING.md](../CLIENT-TESTING.md)). A client
developer starts a session, points their client at the session's storage, and watches every
request it sends on the session's page. This is phase C1: the sessions and their traffic log.
The rules that judge the traffic come in phase C2.

## Running it

```bash
./mvnw -pl harness-clients -am package -DskipTests
java -jar harness-clients/target/touchstone-clients.jar \
    --public-base https://example.org/touchstone/clients --port 18090 --trust-forwarded-for
```

| Option | Default | Meaning |
|---|---|---|
| `--public-base` | `http://localhost:<port>/touchstone/clients` | The service's public URL: a path, no trailing slash. Every URL a session hands out starts with it. |
| `--bind` | `127.0.0.1` | The interface to listen on. |
| `--port` | `18090` | The port. |
| `--trust-forwarded-for` | off | Take the client's address from `X-Forwarded-For`, for the per-address session limit. Only behind a proxy that sets it. |

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
| `POST <base>/sessions/{id}/tokens/{alice\|bob}` | a fresh access token |
| `DELETE <base>/sessions/{id}` | ends the session |
| `GET <base>/sessions/{id}/page` | the session page; it reads the key from its fragment, `#key=…` |

Each exchange in the log is redacted: credentials appear as a fingerprint, the first twelve
hex digits of their SHA-256. It is annotated with:

- what the request addressed;
- whose valid token it carried, and how it was presented;
- whether the session handed out the URL, and how, or whether the client built it;
- what the URL last advertised in `Allow`, `Accept-Patch`, `Accept-Query` and `ETag`.

## Traps

Every session's storage sets all of these. Each is legal under the drafts, and the server
self-test proves it: every definition passes against a reference deployment with them set.

- Container pages are opaque URLs under `_t/p/`; a `?page=` query answers 404.
- Resources the server names get URLs under `_r/`, which do not nest under their container's.
- A linkset is an opaque URL under `_t/l/`, found only through `rel="linkset"`.
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
