package com.ebremer.touchstone.driver.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The driver's side of one adapter's standard streams (driver/PROTOCOL.md section 2): it reads the
 * hello, then sends one request at a time and waits for its response.
 *
 * <p>Everything the adapter writes is untrusted (CLIENT-TESTING.md section 14.3): a line is at most
 * 16 MiB, every line is checked against the protocol schema, and a response must come within the
 * operation's timeout. Anything else makes the adapter {@linkplain BrokenAdapterException broken},
 * and the connection is unusable from then on. Nothing an adapter writes is ever run.
 */
public class AdapterConnection implements AutoCloseable {

    /** The longest line an adapter may write (section 2). */
    public static final int MAX_LINE = 16 * 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object EOF = new Object();

    private final OutputStream toAdapter;
    private final BlockingQueue<Object> lines = new LinkedBlockingQueue<>();
    private final ProtocolSchema schema;
    private final Hello hello;
    private long nextId = 1;
    private volatile String broken;

    /**
     * Connects to an adapter and reads its hello.
     *
     * @param fromAdapter the adapter's stdout
     * @param toAdapter   the adapter's stdin
     * @param helloWithin how long the adapter may take to announce itself
     */
    public AdapterConnection(InputStream fromAdapter, OutputStream toAdapter, ProtocolSchema schema, Duration helloWithin) {
        this.toAdapter = toAdapter;
        this.schema = schema;
        Thread reader = Thread.ofVirtual().name("adapter-stdout").unstarted(() -> readLines(fromAdapter));
        reader.start();
        JsonNode first = next(helloWithin, "its hello");
        String problem = schema.checkHello(first);
        if (problem != null) {
            throw breaks("its hello " + problem);
        }
        this.hello = Hello.of(first);
    }

    public Hello hello() {
        return hello;
    }

    /**
     * Sends one request and waits for its response.
     *
     * @throws BrokenAdapterException when the adapter broke the protocol, exited, or did not answer in time
     */
    public synchronized Reply call(String op, ObjectNode args, Duration timeout) {
        if (broken != null) {
            throw new BrokenAdapterException(broken);
        }
        long id = nextId++;
        ObjectNode request = JSON.createObjectNode().put("id", id).put("op", op);
        request.set("args", args == null ? JSON.createObjectNode() : args);
        String problem = schema.checkRequest(request);
        if (problem != null) {
            // The driver's own request, built from a rule's drive: a defect in the rule or the driver.
            throw new IllegalArgumentException(op + ": " + problem);
        }
        try {
            toAdapter.write((JSON.writeValueAsString(request) + "\n").getBytes(StandardCharsets.UTF_8));
            toAdapter.flush();
        } catch (IOException e) {
            throw breaks("its stdin closed (" + e.getMessage() + ")");
        }
        JsonNode line = next(timeout, "the response to " + op);
        problem = schema.checkResponse(line);
        if (problem != null) {
            throw breaks("its response to " + op + " " + problem);
        }
        if (line.path("id").asLong(-1) != id) {
            throw breaks("it answered request " + line.path("id") + " when " + id + " was outstanding");
        }
        problem = schema.checkResult(line, op);
        if (problem != null) {
            throw breaks("its result of " + op + " " + problem);
        }
        return Reply.of(line);
    }

    /**
     * Writes one line exactly as given and reads the response, which must still keep the protocol:
     * for the checker's requests that no driver would build, such as a line that is not JSON or an
     * operation this version does not define (driver/PROTOCOL.md section 6).
     */
    public synchronized JsonNode exchangeRaw(String line, Duration timeout) {
        if (broken != null) {
            throw new BrokenAdapterException(broken);
        }
        try {
            toAdapter.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            toAdapter.flush();
        } catch (IOException e) {
            throw breaks("its stdin closed (" + e.getMessage() + ")");
        }
        JsonNode reply = next(timeout, "the response to " + line);
        String problem = schema.checkResponse(reply);
        if (problem != null) {
            throw breaks("its response to " + line + " " + problem);
        }
        return reply;
    }

    /** Whether the adapter has broken the protocol, and so can no longer be used. */
    public boolean isBroken() {
        return broken != null;
    }

    @Override
    public void close() {
        try {
            toAdapter.close();
        } catch (IOException ignored) {
            // The adapter is going away either way.
        }
    }

    private JsonNode next(Duration timeout, String waitingFor) {
        Object line;
        try {
            line = lines.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw breaks("interrupted while waiting for " + waitingFor);
        }
        if (line == null) {
            throw breaks("it did not send " + waitingFor + " within " + timeout.toSeconds() + " s");
        }
        if (line == EOF) {
            throw breaks(broken != null ? broken : "it closed its stdout before " + waitingFor);
        }
        if (line instanceof String reason) {
            throw breaks(reason);
        }
        return (JsonNode) line;
    }

    private BrokenAdapterException breaks(String reason) {
        if (broken == null) {
            broken = "the adapter broke the protocol: " + reason;
        }
        return new BrokenAdapterException(broken);
    }

    /** Reads lines until end of input; a line too long or not JSON ends reading with its reason. */
    private void readLines(InputStream in) {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                for (int i = 0; i < n; i++) {
                    if (buffer[i] != '\n') {
                        if (line.size() >= MAX_LINE) {
                            lines.add("it wrote a line longer than 16 MiB");
                            return;
                        }
                        line.write(buffer[i]);
                        continue;
                    }
                    String text = line.toString(StandardCharsets.UTF_8);
                    line.reset();
                    if (text.isBlank()) {
                        continue;
                    }
                    try {
                        lines.add(JSON.readTree(text));
                    } catch (IOException e) {
                        lines.add("it wrote a line that is not JSON");
                        return;
                    }
                }
            }
        } catch (IOException e) {
            // Its stdout went away: the same as end of input.
        } finally {
            lines.add(EOF);
        }
    }
}
