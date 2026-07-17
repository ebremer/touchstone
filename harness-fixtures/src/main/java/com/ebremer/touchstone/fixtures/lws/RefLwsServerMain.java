package com.ebremer.touchstone.fixtures.lws;

/**
 * Standalone entry point for the reference LWS server, for the CLI dev loop:
 * {@code mvn -pl harness-fixtures exec:java -Dexec.args=4711} then point the
 * {@code ref} target at http://localhost:4711/.
 */
public final class RefLwsServerMain {

    private RefLwsServerMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 4711;
        RefLwsServer server = RefLwsServer.start(port);
        System.out.println("reference LWS server listening at " + server.baseUri());
        server.join();
    }
}
