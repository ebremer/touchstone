# Java starter kit for touchstone-driver/1

This kit makes your Java LWS client drivable by Touchstone. Touchstone's driver starts your adapter,
sends it operations such as "create this resource in that container" on stdin, and judges the
requests your client sends to a client session. The protocol is
[`driver/PROTOCOL.md`](../../PROTOCOL.md); this kit handles all of it, so you only map your client to
an interface.

| File | What it is |
|---|---|
| `LwsDriverAdapter.java` | one method per operation of the protocol; each default answers `unsupported` |
| `AdapterError.java` | an outcome that is not a result, such as a 404 |
| `ClientInfo.java` | your client's name, version and homepage, as the session's report names it |
| `AdapterRunner.java` | the protocol: the hello, one request a line, one response a line. Copy it as it is |
| `StubAdapter.java` | the starting point: implements only `configure` |

## Make it yours

1. Copy this directory into your client's repository. Rename the package and the artifact in
   `pom.xml`, and add your client library as a dependency.
2. In `StubAdapter` (rename it too), give your client's name, and build your client in `configure`
   from the authentication it is given (section 5.1: `none`, `bearer`, `cid`, `openid`, `saml`).
   List the kinds your client supports in `auth()`.
3. Implement the operations your client has, one method each. Call your client's own operation with
   its own options, and return what it returns in the shape section 5 gives. Throw an `AdapterError`
   for an outcome that is not a result: `AdapterError.http(404, "...")` for a 404, `transport` when
   there was no response. The runner lists the methods you implement in the hello; the others answer
   `unsupported`, and the rules that need them stay untested, never failed.
4. Be a thin, faithful wrapper. Never retry, fall back or fix anything up for your client: the point
   is to see what your client sends.

## Check it, then drive it

```sh
mvn package
java -jar touchstone.jar driver check -- java -jar target/lws-driver-adapter.jar
```

The checker says whether your adapter speaks the protocol. Then register it in Touchstone's
`adapters.yaml`:

```yaml
clients:
  my-client:
    command: [java, -jar, target/lws-driver-adapter.jar]
    directory: ../my-client
```

and drive it through a client session: `touchstone drive --client my-client`. The results say,
rule by rule, what your client does right and what it gets wrong, with the clause of the
specification behind each.
