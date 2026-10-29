// The types of adapter-runner.mjs.

import type { AuthKind, ClientInfo, LwsDriverAdapter, Operation } from "./lws-driver-adapter.mjs";

export declare const PROTOCOL: "touchstone-driver/1";

/** The line an adapter writes first (section 3). */
export interface Hello {
  hello: {
    protocol: "touchstone-driver/1";
    client: ClientInfo;
    language: string;
    auth: AuthKind[];
    operations: Operation[];
  };
}

/**
 * Serves the driver on stdin and stdout until end of input or shutdown, then exits with status 0:
 * it never returns.
 */
export declare function serve(adapter: LwsDriverAdapter): Promise<never>;

/** The hello: the client, its authentication, and every operation the adapter implements. */
export declare function hello(adapter: LwsDriverAdapter): Hello;
