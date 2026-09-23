package com.ebremer.touchstone.fixtures.lws;

/**
 * Standalone entry point for the reference LWS server, for the CLI dev loop:
 * {@code mvn -pl harness-fixtures exec:java -Dexec.args=4711} then point the
 * {@code ref} target at http://localhost:4711/.
 *
 * <p>A second argument, {@code broken}, starts the deliberately broken twin instead: a server
 * that claims to protect its resources but never challenges or refuses. Declared with the
 * Authentication capability, it shows what a failing run looks like.
 */
public final class RefLwsServerMain {

    private RefLwsServerMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 4711;
        boolean broken = args.length > 1 && args[1].equalsIgnoreCase("broken");
        RefLwsServer server = broken ? RefLwsServer.startBroken(port) : RefLwsServer.start(port);
        System.out.println((broken ? "broken " : "") + "reference LWS server listening at " + server.baseUri());
        server.join();
    }
}
