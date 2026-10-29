# C++ starter kit for touchstone-driver/1

This kit makes your C++ LWS client drivable by Touchstone. Touchstone's driver starts your adapter,
sends it operations such as "create this resource in that container" on stdin, and judges the
requests your client sends to a client session. The protocol is
[`driver/PROTOCOL.md`](../../PROTOCOL.md); this kit handles all of it, so you only map your client to
an interface.

| File | What it is |
|---|---|
| `include/lwsdriver/lws_driver_adapter.hpp` | one virtual method per operation of the protocol; each default answers `unsupported` |
| `include/lwsdriver/adapter_error.hpp` | an outcome that is not a result, such as a 404 |
| `include/lwsdriver/client_info.hpp` | your client's name, version and homepage, as the session's report names it |
| `include/lwsdriver/adapter_runner.hpp`, `src/adapter_runner.cpp` | the protocol: the hello, one request a line, one response a line. Copy them as they are |
| `src/stub_adapter.cpp` | the starting point: implements only `configure` |
| `CMakeLists.txt` | builds the stub; fetches [nlohmann/json](https://github.com/nlohmann/json) 3.12.0 (MIT) |

You need CMake 3.24 or later and a C++17 compiler.

## Make it yours

1. Copy this directory into your client's repository. Rename the project and the executable in
   `CMakeLists.txt`, and link your client library to the executable.
2. In `stub_adapter.cpp` (rename the class too), give your client's name, and build your client in
   `configure()` from the authentication it is given (section 5.1: `none`, `bearer`, `cid`,
   `openid`, `saml`). List the kinds your client supports in `auth()`.
3. Implement the operations your client has: override each one's method, `public` and marked
   `override`. Arguments and results are `lwsdriver::Json` (`nlohmann::json`) objects. Call your
   client's own operation with its own options, and return what it returns in the shape section 5
   gives. Throw an `AdapterError` for an outcome that is not a result: `AdapterError::http(404,
   "...")` for a 404, `transport` when there was no response. Any other exception answers
   `internal`, and the adapter keeps running. `lwsdriver::member(args, "accept")` reads an optional
   argument, absent and `null` alike. `delete` is a C++ keyword, so its method is `delete_`.
4. The runner lists the methods you override in the hello. It sees them at compile time, from your
   class: `lwsdriver::serve(adapter)` takes your adapter as its own class, so call it with that, not
   with an `LwsDriverAdapter&`. The others answer `unsupported`, and the rules that need them stay
   untested, never failed.
5. Be a thin, faithful wrapper. Never retry, fall back or fix anything up for your client: the point
   is to see what your client sends. Log to stderr, never to stdout.

## Check it, then drive it

```sh
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build
java -jar touchstone.jar driver check -- build/lws-driver-adapter
```

On Windows the executable is `build/lws-driver-adapter.exe` (`build/Release/` with Visual Studio,
which also wants `--config Release` when building). MinGW links it statically, so it runs without
MinGW's DLLs on the `PATH`.

The checker says whether your adapter speaks the protocol. Then register it in Touchstone's
`adapters.yaml`:

```yaml
clients:
  my-client:
    command: [build/lws-driver-adapter]
    directory: ../my-client
```

A relative program is found in `directory`, on Windows too. Then drive it through a client session:
`touchstone drive --client my-client`. The results say, rule by rule, what your client does right
and what it gets wrong, with the clause of the specification behind each.
