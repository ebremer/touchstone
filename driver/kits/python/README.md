# Python starter kit for touchstone-driver/1

This kit makes your Python LWS client drivable by Touchstone. Touchstone's driver starts your
adapter, sends it operations such as "create this resource in that container" on stdin, and judges
the requests your client sends to a client session. The protocol is
[`driver/PROTOCOL.md`](../../PROTOCOL.md); this kit handles all of it, so you only map your client to
a class. It needs Python 3.9 or later, and nothing else.

| File | What it is |
|---|---|
| `lws_driver_adapter.py` | `LwsDriverAdapter`, an abstract class with one method per operation of the protocol, each answering `unsupported` until you implement it; `AdapterError`, an outcome that is not a result, such as a 404; and `ClientInfo`, your client's name, version and homepage |
| `adapter_runner.py` | the protocol: the hello, one request a line, one response a line. Copy it as it is |
| `stub.py` | the starting point: implements only `configure` |

## Make it yours

1. Copy this directory into your client's repository (as `lws-driver/`, say), and rename `stub.py`
   to `adapter.py`.
2. In it, give your client's name in `client_info()`, and build your client in `configure` from the
   authentication it is given (section 5.1: `none`, `bearer`, `cid`, `openid`, `saml`). Keep the
   client in an attribute. List the kinds your client supports in `auth_kinds()`.
3. Implement the operations your client has, one method each, named as the operation:
   `list_container`. A method may be `async def`. Call your client's own operation with its own
   options, and return what it returns, as a dict in the shape section 5 gives. Raise an
   `AdapterError` for an outcome that is not a result: `AdapterError.http(404, "...")` for a 404,
   `transport` when there was no response, `AdapterError("verification", "...")` for a notification
   that fails verification. The runner lists the methods you implement in the hello; the others
   answer `unsupported`, and the rules that need them stay untested, never failed.
4. Be a thin, faithful wrapper. Never retry, fall back or fix anything up for your client: the point
   is to see what your client sends.
5. Keep stdout for the protocol. Once it serves, the runner sends `print` to stderr, but anything
   that writes to the process's real stdout breaks the adapter.

## Check it, then drive it

```sh
java -jar touchstone.jar driver check -- python3 adapter.py
```

Use the interpreter that has your client installed (`.venv/bin/python`, or `python` on Windows). The
checker says whether your adapter speaks the protocol, and keeps its stderr in `adapter-check.log`.
Then register it in Touchstone's `adapters.yaml`:

```yaml
clients:
  my-client:
    command: [python3, adapter.py]
    directory: ../my-client/lws-driver
```

and drive it through a client session: `touchstone drive --client my-client`. The results say,
rule by rule, what your client does right and what it gets wrong, with the clause of the
specification behind each.
