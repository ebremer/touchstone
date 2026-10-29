// The protocol, so that your adapter is only your client (driver/PROTOCOL.md sections 2 and 3): it
// writes the hello, reads one request a line from stdin, calls the method of LwsDriverAdapter for its
// operation, and writes one response a line to stdout. stdout carries protocol lines only: log to
// stderr. Once this module is loaded, console.log, console.info and console.debug go there too.
//
//   import { serve } from "./adapter-runner.mjs";
//   serve(new MyAdapter());
//
// Copy it as it is: it follows touchstone-driver/1, and nothing in it is particular to a client.

import { createInterface } from "node:readline";

import { AdapterError, LwsDriverAdapter, OPERATIONS } from "./lws-driver-adapter.mjs";

export const PROTOCOL = "touchstone-driver/1";

// Whatever the adapter or its client logs goes to stderr, which the driver keeps as the adapter's log.
for (const name of ["log", "info", "debug", "dirxml"]) {
  console[name] = console.error;
}
console.dir = (item) => console.error(item);

/**
 * Serves the driver on stdin and stdout until end of input or shutdown, then exits with status 0.
 * It exits even when your client still holds a connection open.
 */
export async function serve(adapter) {
  await write(hello(adapter));
  const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });
  for await (const line of lines) {
    if (line.trim() === "") {
      continue;
    }
    const { response, shutdown } = await respond(adapter, line);
    await write(response);
    if (shutdown) {
      break;
    }
  }
  process.exit(0);
}

/** The hello: the client, its authentication, and every operation the adapter implements. */
export function hello(adapter) {
  const info = adapter.clientInfo();
  const client = { name: info.name };
  if (info.version) {
    client.version = info.version;
  }
  if (info.homepage) {
    client.homepage = info.homepage;
  }
  const operations = [];
  for (const [op, method] of OPERATIONS) {
    if (implemented(adapter, method)) {
      operations.push(op);
    }
  }
  return {
    hello: {
      protocol: PROTOCOL,
      client,
      language: "javascript",
      auth: [...new Set(adapter.authKinds())],
      operations,
    },
  };
}

/** Whether the adapter's class implements a method itself, rather than leaving LwsDriverAdapter's default. */
function implemented(adapter, method) {
  return typeof adapter[method] === "function" && adapter[method] !== LwsDriverAdapter.prototype[method];
}

/** The response to one request line, and whether it was shutdown. */
async function respond(adapter, line) {
  let request;
  try {
    request = JSON.parse(line);
  } catch {
    request = undefined;
  }
  if (request === null || typeof request !== "object" || Array.isArray(request)) {
    return { response: failure(null, AdapterError.invalidArguments("a request is a JSON object")), shutdown: false };
  }
  const id = request.id ?? null;
  const op = request.op;
  if (op === "shutdown") {
    return { response: { id, ok: true, result: {} }, shutdown: true };
  }
  try {
    const method = typeof op === "string" ? OPERATIONS.get(op) : undefined;
    if (method === undefined || typeof adapter[method] !== "function") {
      throw AdapterError.unsupported(String(op));
    }
    const args = request.args ?? {};
    if (typeof args !== "object" || Array.isArray(args)) {
      throw AdapterError.invalidArguments("args is a JSON object");
    }
    const result = (await adapter[method](args)) ?? {};
    if (typeof result !== "object" || Array.isArray(result)) {
      throw new TypeError(`${method} returned ${JSON.stringify(result)}, not an object`);
    }
    return { response: { id, ok: true, result: withoutNulls(result) }, shutdown: false };
  } catch (e) {
    return { response: failure(id, e), shutdown: false };
  }
}

/**
 * A result with its null members left out: the protocol reads null and absent alike (section 4),
 * and the driver checks results against a schema that wants absent members left out.
 */
function withoutNulls(value) {
  if (Array.isArray(value)) {
    return value.map(withoutNulls);
  }
  if (value !== null && typeof value === "object") {
    const prototype = Object.getPrototypeOf(value);
    if (prototype === Object.prototype || prototype === null) {
      const object = {};
      for (const [name, member] of Object.entries(value)) {
        if (member !== null && member !== undefined) {
          object[name] = withoutNulls(member);
        }
      }
      return object;
    }
  }
  return value;
}

function failure(id, e) {
  if (e instanceof AdapterError) {
    const error = { kind: e.kind };
    if (e.status !== null) {
      error.status = e.status;
    }
    error.message = e.message || e.kind;
    return { id, ok: false, error };
  }
  // A bug in the adapter or the client: report it, and keep running.
  console.error(e);
  return { id, ok: false, error: { kind: "internal", message: String(e) } };
}

function write(message) {
  let line;
  try {
    line = JSON.stringify(message);
  } catch (e) {
    // A result that cannot be serialized (a BigInt, a cycle) is a bug: report it.
    line = JSON.stringify(failure(message.id ?? null, new Error(`the result is not JSON: ${e.message}`)));
  }
  return new Promise((resolve, reject) => {
    process.stdout.write(`${line}\n`, (error) => (error ? reject(error) : resolve()));
  });
}
