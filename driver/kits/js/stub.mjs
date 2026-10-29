// The starting point: an adapter that implements only configure, so every other operation answers
// unsupported. It passes `touchstone driver check -- node stub.mjs` as it is. Replace the client
// name, keep your client in a field that configure builds, and implement the operations your client
// has, one by one.

import { AdapterError, LwsDriverAdapter } from "./lws-driver-adapter.mjs";
import { serve } from "./adapter-runner.mjs";

class StubAdapter extends LwsDriverAdapter {
  clientInfo() {
    return { name: "My LWS client (JavaScript)", version: "0.0.0" };
  }

  authKinds() {
    return ["none"];
  }

  configure(args) {
    const type = args.auth?.type;
    if (!this.authKinds().includes(type)) {
      throw AdapterError.unsupported(`authentication ${type}`);
    }
    // Build your client here, with the authentication args.auth describes.
    return {};
  }
}

serve(new StubAdapter());
