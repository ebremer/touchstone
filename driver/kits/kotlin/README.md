# Kotlin starter kit for `touchstone-driver/1`

Touchstone tests an LWS client by driving it through an **adapter**: a small program, written in the
client's language, that runs the operations Touchstone sends and reports what happened. The adapter
speaks [`touchstone-driver/1`](../../PROTOCOL.md) on stdin and stdout. This kit does the protocol, so
your adapter is only your client.

| File | What it is |
|---|---|
| `LwsDriverAdapter.kt` | the interface: one method per operation of PROTOCOL.md section 5, each answering `unsupported` until you implement it |
| `AdapterError.kt` | an outcome that is not a result, such as a 404 (section 4.2) |
| `ClientInfo.kt` | the client under test, as the hello names it |
| `AdapterRunner.kt` | the protocol: hello, framing, dispatch, errors, shutdown. Copy it as it is |
| `StubAdapter.kt` | your adapter: only `configure` so far, and `main` |

JSON is kotlinx.serialization's `JsonObject` (`kotlinx-serialization-json` 1.12.0, Kotlin 2.4.21).
The build is Maven and makes a fat jar; it needs JDK 17 or later.

## 1. Copy it

Copy this directory into your client's repository, for example as `lws-driver-adapter/`. Rename the
package, the artifact and the client's name in `StubAdapter`. If you move `main`, change the shade
plugin's `mainClass` in `pom.xml`. Add your client library to the `pom.xml` dependencies.

## 2. Implement your client's operations

Keep your client in a property that `configure` builds. Then override the method of each operation
your client has. Call your client's own operation with its own options, and turn its result into the
result shape of PROTOCOL.md section 4.1 and its errors into an `AdapterError`:

```kotlin
override fun head(args: JsonObject): JsonObject {
    val url = args.getValue("url").jsonPrimitive.content
    val m = try {
        client.head(url)                          // your client's own operation
    } catch (e: MyClientHttpException) {
        throw AdapterError.http(e.status, "HEAD $url -> ${e.status}")
    }
    return buildJsonObject {
        put("url", m.url)
        put("status", m.status)
        m.etag?.let { put("etag", it) }
    }
}
```

- Be thin and faithful. Never retry, fall back or fix anything up: the point is to see what your
  client sends.
- An optional argument your client cannot honour is `unsupported`. Never drop one silently.
- Report what your client exposes, and leave out the rest: an optional result member may be absent
  (PROTOCOL.md section 4.1).
- Log to stderr. The runner sends `System.out` there too, so a stray `println` cannot break the
  protocol.
- A `require` that fails answers `invalidArguments`. Any other exception answers `internal`, and the
  adapter keeps running.
- The driver may run a second process of your adapter, to verify notifications. Do not assume yours
  is the only one.

The hello lists the operations you implement. The runner finds them by reflection: a method your
class overrides counts, a default it inherits does not. Kotlin compiles an inherited default into a
bridge method, which the runner skips. Do not compile with `-jvm-default=disable`.

## 3. Build

```sh
mvn -B package
java -jar target/lws-driver-adapter.jar
```

No Maven here? `docker run --rm -v "$PWD":/kit -w /kit maven:3.10.0-eclipse-temurin-25 mvn -B package`.

## 4. Check it

`touchstone` below is Touchstone's CLI, `java -jar harness-cli/target/touchstone.jar` in a Touchstone
checkout. The checker starts your adapter and checks that it speaks the protocol (PROTOCOL.md
section 6):

```sh
touchstone driver check -- java -jar target/lws-driver-adapter.jar
```

It prints one line per check and ends with `34 of 34 checks passed`. Your adapter's stderr goes to
`adapter-check.log` (`--log` to change it). A passing check says the adapter speaks the protocol;
whether your client is conformant is what a drive says.

## 5. Register it and drive your client

Touchstone runs only adapters registered in its `adapters.yaml`:

```yaml
clients:
  my-client:
    command: [java, -jar, target/lws-driver-adapter.jar]
    directory: ../my-client/lws-driver-adapter   # relative to adapters.yaml
```

Start a client-session service, then drive (see `harness-driver/README.md`):

```sh
touchstone drive --client my-client
```
