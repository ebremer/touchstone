// The touchstone-driver/1 adapter for Factoidal's LWS client (driver/PROTOCOL.md; README.md here; D-0109).
//
// Factoidal (github.com/danbri/factoidal-core) is RDF and LWS written in Lean 4 and compiled to one
// wasm module. Its LWS client is a Lean state machine that chooses and builds every request, reached
// through the npm module @factoidal/core/lws/client: createLwsClient(options) gives a client whose
// run({kind, url, ...}) runs one operation and answers a done document with a `report`, the result
// in the shapes of lws-driver/1, or throws an LwsClientError whose `report` names the error the same
// way. This adapter is built on the JavaScript starter kit (driver/kits/js): it turns each operation
// of the protocol into one run of the client, and the client's reports into the protocol's results
// and errors. It sends no request, reads no header and retries nothing: that is all the client's.
//
//   npm ci && node adapter.mjs
//   FACTOIDAL_CLIENT_MODULE=/opt/factoidal/lws/client.mjs node adapter.mjs

import { readFileSync } from "node:fs";
import { dirname, isAbsolute, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { AdapterError, LwsDriverAdapter } from "../../kits/js/lws-driver-adapter.mjs";
import { serve } from "../../kits/js/adapter-runner.mjs";

/** The client's module: FACTOIDAL_CLIENT_MODULE, or the package package.json here pins. */
function clientModule() {
  const named = process.env.FACTOIDAL_CLIENT_MODULE;
  if (!named) {
    return import.meta.resolve("@factoidal/core/lws/client");
  }
  return pathToFileURL(isAbsolute(named) ? named : resolve(named)).href;
}

const MODULE = clientModule();
const { createLwsClient, LwsClientError } = await import(MODULE);

function packageVersion() {
  try {
    return JSON.parse(readFileSync(join(dirname(fileURLToPath(MODULE)), "..", "package.json"), "utf8")).version;
  } catch {
    return undefined;
  }
}

const VERSION = packageVersion();

/** The token type of a SAML 2.0 assertion given as an RFC 8693 subject token. */
const SAML2 = "urn:ietf:params:oauth:token-type:saml2";

/** The client's error names (lws-driver/1), as touchstone-driver/1 kinds; any other is an HTTP status's. */
const KINDS = new Map([
  ["AuthenticationError", "authentication"],
  ["ProtocolError", "protocol"],
  ["SignatureVerificationError", "verification"],
  ["TransportError", "transport"],
  ["InvalidArguments", "invalidArguments"],
  ["Unsupported", "unsupported"],
  ["InternalError", "internal"],
]);

/** The error a run of the client ended with, as the protocol's error. */
function reported(e) {
  if (!(e instanceof LwsClientError)) {
    return e;
  }
  const report = e.report ?? {};
  let status = Number.isInteger(report.status) ? report.status : e.detail?.status;
  if (!(Number.isInteger(status) && status >= 100 && status <= 599)) {
    status = undefined;
  }
  const kind = KINDS.get(report.kind) ?? (status !== undefined ? "http" : "internal");
  const message = report.message ?? e.message;
  return new AdapterError(kind, message, status);
}

/** A request body (section 4) as bytes, and the content type it implies. */
function bodyOf(body, contentType) {
  if (typeof body.text === "string") {
    return { bytes: new TextEncoder().encode(body.text), contentType };
  }
  if (typeof body.base64 === "string") {
    if (!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(body.base64)) {
      throw AdapterError.invalidArguments("body.base64 is not base64");
    }
    return { bytes: new Uint8Array(Buffer.from(body.base64, "base64")), contentType };
  }
  if ("json" in body) {
    return { bytes: new TextEncoder().encode(JSON.stringify(body.json)), contentType: contentType ?? "application/json" };
  }
  throw AdapterError.invalidArguments("a body has text, base64 or json");
}

/** A JSON document the client sends as it is: a JSON Patch, a linkset. */
function jsonBytes(value) {
  return new TextEncoder().encode(JSON.stringify(value));
}

/**
 * A write's precondition, in the client's members: If-Match with a tag, and the whole If-None-Match
 * (`*`, or entity tags) in `ifNoneMatchTags`, alone or with the If-Match.
 */
function precondition(args) {
  const { ifMatch, ifNoneMatch } = args;
  const members = {};
  if (ifMatch != null) {
    Object.assign(members, { precondition: "ifMatch", tag: ifMatch });
  }
  if (ifNoneMatch != null) {
    members.ifNoneMatchTags = [ifNoneMatch];
  }
  return members;
}

/** A sequence the client followed to its end, cut at `limit` (section 4). */
function limited(items, limit) {
  const most = limit ?? 1000;
  return { items: items.slice(0, most), truncated: items.length > most };
}

/** An access document's policies, as the client's members: the client writes the document itself. */
function accessMembers(doc) {
  const policies = [].concat(doc.access ?? []);
  if (policies.length === 0) {
    throw AdapterError.invalidArguments("an access document has a policy in access");
  }
  const members = {
    policies: policies.map((policy) => {
      const target = policy?.target;
      const one = {
        actions: [].concat(policy?.action ?? []),
        assignee: policy?.assignee,
        targets: [].concat(target?.value ?? target ?? []).filter((t) => typeof t === "string"),
      };
      if (policy?.constraint != null) {
        one.constraint = policy.constraint;
      }
      return one;
    }),
  };
  if (typeof doc.inbox === "string") {
    members.inbox = doc.inbox;
  }
  return members;
}

class FactoidalAdapter extends LwsDriverAdapter {
  #client = null;

  clientInfo() {
    return { name: "Factoidal LWS client", version: VERSION, homepage: "https://github.com/danbri/factoidal-core" };
  }

  authKinds() {
    return ["none", "bearer", "cid", "openid", "saml"];
  }

  async configure(args) {
    const auth = args.auth;
    const options = { fetch: wire(args.userAgent, args.timeoutSeconds) };
    const result = {};
    switch (auth?.type) {
      case "none":
        break;
      case "bearer":
        options.bearer = { token: auth.token };
        break;
      case "cid": {
        const kid = auth.kid ?? auth.privateJwk?.kid;
        if (typeof kid !== "string") {
          throw AdapterError.invalidArguments("cid needs a kid, as an argument or in the private JWK");
        }
        options.selfIssued = { subject: auth.agent, keyId: kid, jwk: auth.privateJwk };
        result.agent = auth.agent;
        break;
      }
      case "openid":
        options.idToken = auth.idToken;
        break;
      case "saml":
        options.subjectToken = auth.assertion;
        options.subjectTokenType = SAML2;
        break;
      default:
        throw AdapterError.unsupported(`authentication ${auth?.type}`);
    }
    // A new client: the old one's tokens go with it (section 5.1). The engine is loaded once.
    options.engine = this.#client?.engine;
    this.#client = await createLwsClient(options);
    return result;
  }

  /** One run of the client: its done document. */
  async #run(kind, url, members = {}) {
    if (this.#client === null) {
      this.#client = await createLwsClient({});
    }
    try {
      return await this.#client.run({ ...members, kind, url });
    } catch (e) {
      throw reported(e);
    }
  }

  /** One run of the client: the result it reports. */
  async #report(kind, url, members) {
    return (await this.#run(kind, url, members)).report;
  }

  // ---- core ----

  discoverStorage(args) {
    return this.#report("discover", args.url);
  }

  head(args) {
    return this.#report("head", args.url);
  }

  read(args) {
    const members = {};
    for (const name of ["accept", "ifNoneMatch", "rangeStart", "rangeEnd"]) {
      if (args[name] != null) {
        members[name] = args[name];
      }
    }
    return this.#report("read", args.url, members);
  }

  async listContainer(args) {
    return limited((await this.#report("list", args.url)).items, args.limit);
  }

  create(args) {
    const members = {};
    if (args.body != null) {
      const { bytes, contentType } = bodyOf(args.body, args.contentType ?? undefined);
      members.body = bytes;
      if (contentType !== undefined) {
        members.mediaType = contentType;
      }
    } else if (args.contentType != null) {
      members.mediaType = args.contentType;
    }
    if (args.slug != null) {
      members.slug = args.slug;
    }
    if (args.types != null) {
      members.types = args.types;
    }
    if (args.links != null) {
      members.linkObjects = args.links;
    }
    return this.#report("create", args.container, members);
  }

  createContainer(args) {
    const members = { container: true };
    if (args.slug != null) {
      members.slug = args.slug;
    }
    return this.#report("create", args.parent, members);
  }

  update(args) {
    const { bytes, contentType } = bodyOf(args.body, args.contentType ?? undefined);
    const members = { body: bytes, ...precondition(args) };
    if (contentType !== undefined) {
      members.mediaType = contentType;
    }
    if (args.links != null) {
      members.linkObjects = args.links;
    }
    return this.#report("update", args.url, members);
  }

  patch(args) {
    return this.#report("patch", args.url, { body: jsonBytes(args.patch), ...precondition({ ifMatch: args.ifMatch }) });
  }

  async delete(args) {
    const members = precondition({ ifMatch: args.ifMatch });
    if (args.recursive) {
      members.recursive = true;
    }
    await this.#run("delete", args.url, members);
    return {};
  }

  readLinkset(args) {
    return this.#report("readLinkset", args.url);
  }

  updateLinkset(args) {
    const members = { linksetUrl: args.linksetUrl, body: jsonBytes(args.linkset), ...precondition({ ifMatch: args.ifMatch }) };
    return this.#report("putLinkset", args.linksetUrl, members);
  }

  patchLinkset(args) {
    const members = { linksetUrl: args.linksetUrl, body: jsonBytes(args.patch), ...precondition({ ifMatch: args.ifMatch }) };
    return this.#report("updateLinkset", args.linksetUrl, members);
  }

  move(args) {
    return this.#report("move", args.url, { destination: args.destination, ...precondition({ ifMatch: args.ifMatch }) });
  }

  async requestAccess(args) {
    return { location: (await this.#report("requestAccess", args.serviceUrl, accessMembers(args.request))).location };
  }

  getAccessRequest(args) {
    return this.#report("getAccess", args.url);
  }

  async listAccessRequests(args) {
    return limited((await this.#report("list", args.serviceUrl)).items, args.limit);
  }

  async cancelAccessRequest(args) {
    await this.#run("delete", args.url);
    return {};
  }

  async grantAccess(args) {
    return { location: (await this.#report("grantAccess", args.serviceUrl, accessMembers(args.grant))).location };
  }

  getAccessGrant(args) {
    return this.#report("getAccess", args.url);
  }

  async listAccessGrants(args) {
    return limited((await this.#report("list", args.serviceUrl)).items, args.limit);
  }

  async revokeAccessGrant(args) {
    await this.#run("revokeAccess", args.url);
    return {};
  }

  // ---- notifications ----

  subscribe(args) {
    // serviceUrl is the notification service endpoint: with `endpoint` the client POSTs the
    // subscription to it, with no discovery.
    const members = { topics: args.topics, inbox: args.inbox, endpoint: args.serviceUrl };
    if (args.expires != null) {
      members.expires = args.expires;
    }
    return this.#report("subscribe", args.serviceUrl, members);
  }

  async listSubscriptions(args) {
    return limited((await this.#report("list", args.serviceUrl)).items, args.limit);
  }

  getSubscription(args) {
    return this.#report("getSubscription", args.url);
  }

  async unsubscribe(args) {
    await this.#run("unsubscribe", args.url);
    return {};
  }

  async verifyNotification(args) {
    const headers = [];
    for (const [name, values] of Object.entries(args.headers)) {
      for (const value of values) {
        headers.push([name, value]);
      }
    }
    const members = { method: args.method, headers, body: bodyOf({ base64: args.bodyBase64 }).bytes };
    if (args.trustedStorages != null) {
      members.trustedStorages = args.trustedStorages;
    }
    const done = await this.#run("verifyNotification", args.url, members);
    if (done.accept !== true) {
      const report = done.report ?? {};
      throw new AdapterError("verification", report.message ?? `the client refused the notification (${done.reason ?? done.status})`);
    }
    return done.report;
  }

  // ---- index ----

  async listTypes(args) {
    const { items, truncated } = limited((await this.#report("typeIndex", args.serviceUrl, { endpoint: args.serviceUrl })).types, args.limit);
    return { types: items, truncated };
  }

  async search(args) {
    return limited((await this.#report("search", args.serviceUrl, { endpoint: args.serviceUrl, query: args.query })).items, args.limit);
  }
}

/**
 * The client's fetch: Node's, with only what configure asked for, a user agent and a timeout. The
 * request is otherwise the client's.
 */
function wire(userAgent, timeoutSeconds) {
  if (userAgent == null && timeoutSeconds == null) {
    return fetch;
  }
  return (url, init = {}) => {
    const headers = new Headers(init.headers ?? []);
    if (userAgent != null) {
      headers.set("user-agent", userAgent);
    }
    const next = { ...init, headers };
    if (timeoutSeconds != null) {
      next.signal = AbortSignal.timeout(timeoutSeconds * 1000);
    }
    return fetch(url, next);
  };
}

serve(new FactoidalAdapter());
