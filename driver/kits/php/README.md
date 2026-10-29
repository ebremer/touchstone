# PHP starter kit for `touchstone-driver/1`

Touchstone tests an LWS client by driving it through an **adapter**: a small program, written in the
client's language, that runs the operations Touchstone sends and reports what happened. The adapter
speaks [`touchstone-driver/1`](../../PROTOCOL.md) on stdin and stdout. This kit does the protocol, so
your adapter is only your client.

| File | What it is |
|---|---|
| `src/LwsDriverAdapter.php` | the interface: one method per operation of PROTOCOL.md section 5 |
| `src/AbstractAdapter.php` | the defaults: every operation but `configure` answers `unsupported` until you implement it |
| `src/AdapterError.php` | an outcome that is not a result, such as a 404 (section 4.2) |
| `src/ClientInfo.php` | the client under test, as the hello names it |
| `src/AdapterRunner.php` | the protocol: hello, framing, dispatch, errors, shutdown. Copy it as it is |
| `stub.php` | your adapter: only `configure` so far, and the entry point |

It needs PHP 8.1 or later and nothing else: JSON is the built-in `json_decode` and `json_encode`.
`composer.json` is there for when you add your client with Composer.

## 1. Copy it

Copy this directory into your client's repository, for example as `lws-driver-adapter/`. Rename the
namespace `Example\LwsDriver` if you like (and in `composer.json`), and the client's name in
`StubAdapter`. If you install your client with Composer, add
`require __DIR__ . '/vendor/autoload.php';` to `stub.php`.

## 2. Implement your client's operations

Keep your client in a property that `configure` builds. Then implement the method of each operation
your client has. Call your client's own operation with its own options, and turn its result into the
result shape of PROTOCOL.md section 4.1 and its errors into an `AdapterError`:

```php
public function head(\stdClass $args): array|\stdClass
{
    try {
        $m = $this->client->head($args->url);     // your client's own operation
    } catch (MyClientHttpException $e) {
        throw AdapterError::http($e->status, "HEAD {$args->url} -> {$e->status}");
    }
    $result = ['url' => $m->url, 'status' => $m->status];
    if ($m->etag !== null) {
        $result['etag'] = $m->etag;
    }
    return $result;
}
```

- Arguments arrive as `json_decode` makes them, objects as `\stdClass`, so an empty object stays one
  when your client sends it on.
- Return an array or a `\stdClass`. A result of `[]` is written as `{}`. Inside a result, write an
  empty object as `new \stdClass()`, since `[]` is an empty list.
- Be thin and faithful. Never retry, fall back or fix anything up: the point is to see what your
  client sends.
- An optional argument your client cannot honour is `unsupported`. Never drop one silently.
- Report what your client exposes, and leave out the rest: an optional result member may be absent
  (PROTOCOL.md section 4.1).
- Log to `STDERR`. The runner sends `echo`, `print` and PHP's warnings there too, so they cannot
  break the protocol.
- An `\InvalidArgumentException` answers `invalidArguments`. Any other exception or error answers
  `internal`, and the adapter keeps running.
- The driver may run a second process of your adapter, to verify notifications. Do not assume yours
  is the only one.

The hello lists the operations you implement. The runner finds them by reflection: a method your
class declares counts, a default it inherits from `AbstractAdapter` does not.

## 3. Run it

There is nothing to build:

```sh
php stub.php
```

No PHP here? `docker run --rm -i -v "$PWD":/kit -w /kit php:8.5.12-cli php stub.php`.

## 4. Check it

`touchstone` below is Touchstone's CLI, `java -jar harness-cli/target/touchstone.jar` in a Touchstone
checkout. The checker starts your adapter and checks that it speaks the protocol (PROTOCOL.md
section 6):

```sh
touchstone driver check -- php stub.php
```

It prints one line per check and ends with `34 of 34 checks passed`. Your adapter's stderr goes to
`adapter-check.log` (`--log` to change it). A passing check says the adapter speaks the protocol;
whether your client is conformant is what a drive says.

## 5. Register it and drive your client

Touchstone runs only adapters registered in its `adapters.yaml`:

```yaml
clients:
  my-client:
    command: [php, stub.php]
    directory: ../my-client/lws-driver-adapter   # relative to adapters.yaml
```

Start a client-session service, then drive (see `harness-driver/README.md`):

```sh
touchstone drive --client my-client
```
