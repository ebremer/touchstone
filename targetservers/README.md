# Target servers

LWS server implementations to run Touchstone against, each built from source into a container, all behind one
router. Every target is published at

```
<origin>/targets/<name>/
```

where `<name>` is both the directory here and the compose service. The router finds the container by that name on
the compose network, so a new implementation needs no proxy configuration: a container that keeps the contract
below is reachable as soon as it starts.

| Target | Implementation | Built from (default) | Storage | Authorization server key |
|---|---|---|---|---|
| `halcyon` | Halcyon, module HalcyonLWS | [halcyon-project/Halcyon](https://github.com/halcyon-project/Halcyon) `next` `00f79d5` | `/targets/halcyon/lws/` | in its LWS store (TDB2), not a file |
| `lws-server` | lws-server | [ebremer/lws-server](https://github.com/ebremer/lws-server) `master` `ad6831f` | `/targets/lws-server/` | `/data/lws-data/keys/oauth-es256.jwk` |
| `sparq-lws` | sparq-lws-core, LWS 1.0 mode | [sparq-org/sparq PR #6650](https://github.com/sparq-org/sparq/pull/6650) `5501efa` | `/targets/sparq-lws/` (in memory) | `/data/as-key.json` |
| `css` | Community Solid Server with LWS | [jeswr/CommunitySolidServer PR #126](https://github.com/jeswr/CommunitySolidServer/pull/126) `311b367` | `/targets/css/touchstone/` | `/data/files/.internal/lws/keys/lws-jwks$.json` |
| `lws-drupal` | lws-drupal (Drupal 11) | [ebremer/lws-drupal](https://github.com/ebremer/lws-drupal) `c18d4be` | `/targets/lws-drupal/lws/touchstone/` | the newest `/data/private/lws_authz/keys/*.jwk` |
| `lwsd` | lwsd (JavaScriptSolidServer with lwsd's lws plugin) | [linkedwebstorage/lwsd](https://github.com/linkedwebstorage/lwsd) `main` `dd1871b` | `/targets/lwsd/` | `/data/as-key.json` |

The key paths are inside each container's `/data` volume. A deployment that lets Touchstone mint its own tokens
(`HarnessIssuedTokens`) reads the key from there, for example with `docker compose exec`.

## Layout

```
compose.yaml        the router and every target, on the network lws-targets
compose.local.yaml  local mode for run-all.sh: each server may fetch from the harness on the private network
.env.example        the settings compose.yaml reads
run-all.sh          build, start and test them all with Touchstone (see "Testing them all")
router/             nginx: /targets/<name>/ to http://<name>:8080
tools/              run-all.sh's helpers: lws.mjs (keys, tokens, summaries), matrix.py (the requirements matrix)
<name>/             one target: Dockerfile, usually an entrypoint.sh that turns the contract's environment into
                    the server's own configuration, and touchstone.env, how run-all.sh tests it
```

## The contract

A target container:

1. **Listens for plain HTTP on port 8080.** TLS ends in front of the router.
2. **Takes its public base URL from `LWS_PUBLIC_URL`**: `<origin>/targets/<name>`, without a trailing slash. Every
   URL it publishes (storage, resources, `Link` and `Location` headers, the storage description, the authorization
   server's metadata) starts with it.
3. **Serves from its own root**, unless its mode says otherwise. The router's `$prefix_mode` map
   (`router/nginx.conf`) gives each target one of three:
   - `strip`, the default: `/targets/<name>` is taken off the request path, and the server answers as if at its
     own root;
   - `keep`, for a server that matches request paths against its base URL (`css`): the path arrives as requested;
   - `whole`, for a server that mounts itself at a path prefix and answers its RFC 8414 URL itself (`lwsd`):
     every path arrives as requested, the metadata URL of item 4 included.

   The prefix is also sent as `X-Forwarded-Prefix`. `Host`, `X-Forwarded-Proto` and `X-Forwarded-Host` carry the
   public host and scheme, and `X-Forwarded-For` the client's address as the outer proxy saw it, so one trusted hop
   is right.
4. **Has its authorization server's issuer at `LWS_PUBLIC_URL`.** RFC 8414 puts the metadata of the issuer
   `<origin>/targets/<name>` at `<origin>/.well-known/lws-configuration/targets/<name>`. The router sends that URL to
   `/.well-known/lws-configuration` of the target's own root (`strip`), to
   `/targets/<name>/.well-known/lws-configuration` (`keep`), or unchanged (`whole`).
5. **Gives the storage to `LWS_OWNER`**: the agent, or for lws-server the agents, with full control. Where the
   server records its owner only once, at the storage's first start, changing `LWS_OWNER` later changes nothing.
6. **Keeps its state in `/data`**, a volume, and runs as a user other than root.
   Where the server can be told to trust more certificate authorities, it takes them from `LWS_EXTRA_CA`, a PEM file
   (Halcyon, lws-server and lws-drupal do; run-all.sh uses it for its private fixture host).
7. **Builds from source at a pinned commit**: `ARG <NAME>_REPO` and `ARG <NAME>_REF`, fetched in a stage named
   `source`. Another commit is a build argument (`HALCYON_REF=… docker compose build halcyon`); a local checkout
   replaces the stage with a named build context:

   ```sh
   docker build --build-context source=../../Halcyon -t lws-targets/halcyon halcyon
   ```

   or, with compose, an override file:

   ```yaml
   services:
     halcyon:
       build:
         additional_contexts:
           source: /path/to/Halcyon
   ```

Each target's `entrypoint.sh` lists the further settings it takes, such as the page size of container listings
(4 by default, so that Touchstone's paging tests apply to small containers).

## Testing them all

With Docker (and its compose plugin) and nothing else:

```sh
git clone https://github.com/ebremer/touchstone && cd touchstone
targetservers/run-all.sh                # or: targetservers/run-all.sh css lwsd
```

`run-all.sh` builds the harness image and every target server from source, starts them on a Docker network of their
own, runs Touchstone against each in turn, and stops them. The results go in `targetservers/runs/<UTC time>/`: each
run's report bundle (HTML, Markdown, JSON, EARL, JUnit) under `reports/`, `summary.md` (a table of the totals and
verdicts) and `matrix.html` (every requirement against every target, as on a published conformance page). It needs
no account, key or public address: everything a run needs is made for it.

- **Local mode.** The servers answer at `http://router:8080/targets/<name>/` on the project's network, and the harness
  runs there too. Its fixture host (the identity documents, OpenID Provider, SAML identity provider and webhook sink
  the tests use) is the service `fixtures` in front of it: `https://fixtures/<name>/`, with a certificate from a CA
  made for the run, for the servers that resolve did:web (whose documents are only ever fetched over HTTPS), and
  `http://fixtures:18080/<name>/` for the others (sparq-lws-core trusts only its built-in roots). `compose.local.yaml`
  tells each server to fetch from that private address, which the servers refuse by default, and the three that use
  HTTPS to trust the run's CA; it changes nothing else.
- **Identities.** Where the harness can read a server's signing key, it mints alice's and bob's access tokens itself
  (`HarnessIssuedTokens`), and the storage belongs to alice as the fixture host serves her. Otherwise (Halcyon,
  lws-server) the storage belongs to a did:key made for the run, and alice and bob exchange did:key credentials for
  their tokens, as any client would. The keys stay in `targetservers/.local/<project>/`.
- **Each target's `touchstone.env`** says where runs go (a container alice creates, when the storage root itself is
  readable anonymously), how alice and bob get their tokens, where the server keeps its key, and its name and language.
- `--keep` leaves the servers running; `--project <name>` runs a second set beside another (a deployment's, say);
  `--module <m>` runs part of the suite. Halcyon and lws-server take longest: without a way to mint an expired token,
  the expired-token test waits for a real one to expire.
- **Exit status:** `0` when every target is conformant, `1` when every target was tested and one is not (the
  Community Solid Server, at its pinned commit), and `2` when a target could not be tested.
- **Time and space.** From a fresh clone, with nothing cached, it took 17 minutes on a large cloud VM (D-0096). The
  images take about 5 GB, and building them takes several times that again for base images and build cache.

To add an implementation to the run, give its directory a `touchstone.env` as well, and its switch, if it needs
one, in `compose.local.yaml`.

## Running them

```sh
cp .env.example .env     # then set LWS_ORIGIN and the owners
docker compose up -d --build
```

The router listens on `LWS_TARGETS_BIND` (`127.0.0.1:8480` by default). Put it behind the TLS proxy of `LWS_ORIGIN`,
passing two prefixes through unchanged; with nginx:

```nginx
location ^~ /targets/ {
    proxy_pass http://127.0.0.1:8480;    # no URI: the request line goes through as the client sent it
    proxy_http_version 1.1;
    proxy_set_header Host              $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Host  $host;
    proxy_set_header X-Forwarded-For   $remote_addr;
    proxy_set_header Upgrade           $http_upgrade;
    proxy_set_header Connection        $connection_upgrade;
}
location ^~ /.well-known/lws-configuration/targets/ {
    # the same
}
```

Without the proxy, `LWS_ORIGIN=http://localhost:8480` serves them on this machine, with `LWS_REQUIRE_HTTPS=false`
for lws-server; the servers that fetch identity documents still need to reach those over the network.

The servers fetch agents' identity documents, so Touchstone's fixture host must be reachable from inside the
containers at the URL the run gives it, as from anywhere else.

## Adding an implementation

1. Make a directory `<name>/` with a `Dockerfile` (and an entrypoint) that keeps the contract. `<name>` is lower-case
   letters, digits and hyphens.
2. Add a service `<name>` to `compose.yaml` on the network `lws-targets`, with `LWS_PUBLIC_URL` set to
   `${LWS_ORIGIN}/targets/<name>`, `LWS_OWNER`, and a volume for `/data`. Anything else it needs, such as a
   database, goes on a network of its own that the router is not on, as `lws-drupal-db` does.
3. If it needs its path prefix kept (`keep` or `whole`), add it to `$prefix_mode` in `router/nginx.conf` and
   rebuild the router.
4. `docker compose up -d --build <name>`. It answers at `<origin>/targets/<name>/` at once.
5. Register a Touchstone target for it in your own registry (`targets.yaml`, see
   [the targets documentation](../docs/targets.md)), with `baseUrl` a container in its storage that alice may
   create containers in.

## Testing them with Touchstone

A registry entry for a target whose authorization server key Touchstone may use (`HarnessIssuedTokens`), with the
fixture host at a public URL (`ReachableFixtures`), so that alice is the agent the fixture host serves:

```yaml
targets:
  css:
    baseUrl: https://storage.example/targets/css/touchstone/runs/
    adapter: env
    capabilities: [Authentication, HarnessIssuedTokens, ReachableFixtures]
    properties:
      webid.alice: https://fixtures.example/css/agents/alice
      webid.bob: https://fixtures.example/css/agents/bob
      fixtures.baseUrl: https://fixtures.example/css/
      fixtures.bind: 127.0.0.1:18110
      # as.signingKey: the private JWK, supplied at run time, never written here
```

`LWS_OWNER` for that target is then `https://fixtures.example/css/agents/alice`.
