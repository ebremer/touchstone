package com.ebremer.touchstone.driver;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.ebremer.touchstone.core.definitions.DriverProtocol;
import com.ebremer.touchstone.driver.protocol.AdapterConnection;
import com.ebremer.touchstone.driver.protocol.BrokenAdapterException;
import com.ebremer.touchstone.driver.protocol.Hello;
import com.ebremer.touchstone.driver.protocol.ProtocolSchema;
import com.ebremer.touchstone.driver.protocol.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Checks that an adapter speaks {@code touchstone-driver/1} (driver/PROTOCOL.md section 6): its
 * hello, {@code configure}, an answer within the protocol other than {@code unsupported} to every
 * operation it lists, even when the storage cannot be reached, {@code unsupported} for every operation it does not list and for one the
 * protocol does not define, {@code invalidArguments} for a line that is not a JSON object, and a
 * clean exit after {@code shutdown}. Every line it writes is checked against the schema.
 *
 * <p>A passing check says the adapter speaks the protocol. Whether the client it wraps is conformant
 * is what a drive says.
 */
public final class AdapterChecker {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** A host that never resolves (RFC 6761), so that every listed operation meets an unreachable storage. */
    private static final String NOWHERE = "https://storage.invalid/";

    /** One check and how it went. */
    public record Check(String name, boolean passed, String detail) {
    }

    /** All the checks of one adapter; {@code hello} is null when it never said one. */
    public record Result(Hello hello, List<Check> checks) {

        public boolean passed() {
            return !checks.isEmpty() && checks.stream().allMatch(Check::passed);
        }
    }

    private AdapterChecker() {
    }

    /** Starts the adapter's command and checks it. */
    public static Result check(List<String> command, Path directory, Map<String, String> environment, Path log,
            Duration timeout) {
        List<Check> checks = new ArrayList<>();
        ProcessBuilder pb = new ProcessBuilder(command);
        if (directory != null) {
            pb.directory(directory.toFile());
        }
        pb.environment().putAll(environment);
        Process p;
        try {
            if (log.toAbsolutePath().getParent() != null) {
                java.nio.file.Files.createDirectories(log.toAbsolutePath().getParent());
            }
            pb.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
            p = pb.start();
        } catch (IOException e) {
            checks.add(new Check("start", false, "the command could not be started: " + e.getMessage()));
            return new Result(null, checks);
        }
        try {
            AdapterConnection c;
            try {
                c = new AdapterConnection(p.getInputStream(), p.getOutputStream(), ProtocolSchema.instance(),
                        AdapterLauncher.HELLO_WITHIN);
            } catch (BrokenAdapterException e) {
                checks.add(new Check("hello", false, e.getMessage()));
                return new Result(null, checks);
            }
            Hello hello = c.hello();
            checks.add(new Check("hello", true, hello.client().name() + (hello.language() == null ? "" : " (" + hello.language() + ")")
                    + ", " + hello.operations().size() + " operations, auth " + hello.auth()));
            run(c, hello, timeout, checks);
            checks.add(exit(c, p));
            return new Result(hello, checks);
        } finally {
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    private static void run(AdapterConnection c, Hello hello, Duration timeout, List<Check> checks) {
        try {
            ObjectNode none = JSON.createObjectNode();
            none.putObject("auth").put("type", "none");
            Reply configured = c.call("configure", none, timeout);
            checks.add(new Check("configure", configured.ok(),
                    configured.ok() ? "accepted auth none" : "answered " + configured.error().describe()));
            for (String op : DriverProtocol.operations()) {
                if (op.equals("configure") || op.equals("shutdown")) {
                    continue;
                }
                Reply r = c.call(op, sample(op), timeout);
                if (hello.implementsOperation(op)) {
                    // Only required arguments are sent, so unsupported means the hello lists what the client lacks.
                    boolean answered = r.ok() || !r.error().kind().equals("unsupported");
                    checks.add(new Check("listed operation " + op, answered,
                            r.ok() ? "answered a result" : answered ? "answered " + r.error().describe()
                                    : "answered unsupported: leave it out of the hello, or implement it"));
                } else {
                    boolean unsupported = !r.ok() && r.error().kind().equals("unsupported");
                    checks.add(new Check("unlisted operation " + op, unsupported,
                            unsupported ? "answered unsupported" : "answered " + (r.ok() ? "a result" : r.error().describe())
                                    + ", not unsupported: list it, or leave it out"));
                }
            }
            JsonNode unknown = c.exchangeRaw("{\"id\": 9001, \"op\": \"no_such_operation\", \"args\": {}}", timeout);
            boolean unknownOk = unknown.path("id").asLong() == 9001 && "unsupported".equals(unknown.at("/error/kind").asText());
            checks.add(new Check("an operation the protocol does not define", unknownOk,
                    unknownOk ? "answered unsupported" : "answered " + unknown));
            for (String line : List.of("[1, 2, 3]", "this is not JSON")) {
                JsonNode bad = c.exchangeRaw(line, timeout);
                boolean badOk = bad.path("id").isNull() && "invalidArguments".equals(bad.at("/error/kind").asText());
                checks.add(new Check("a line that is not a JSON object: " + line, badOk,
                        badOk ? "answered invalidArguments, id null" : "answered " + bad));
            }
        } catch (BrokenAdapterException e) {
            checks.add(new Check("protocol", false, e.getMessage()));
        }
    }

    private static Check exit(AdapterConnection c, Process p) {
        if (c.isBroken()) {
            return new Check("shutdown", false, "not tried: the adapter is broken");
        }
        try {
            Reply r = c.call("shutdown", null, Duration.ofSeconds(5));
            c.close();
            if (!r.ok()) {
                return new Check("shutdown", false, "answered " + r.error().describe());
            }
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                return new Check("shutdown", false, "answered, but did not exit within 5 s");
            }
            return new Check("shutdown", p.exitValue() == 0, "exited with status " + p.exitValue());
        } catch (BrokenAdapterException e) {
            return new Check("shutdown", false, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Check("shutdown", false, "interrupted");
        }
    }

    /** Arguments the schema accepts for an operation, every URL naming the unreachable storage. */
    static ObjectNode sample(String op) {
        ObjectNode args = JSON.createObjectNode();
        for (String name : DriverProtocol.required(op)) {
            switch (name) {
                case "body" -> args.putObject("body").put("text", "x");
                case "patch" -> args.putArray("patch");
                case "query", "request", "grant", "headers" -> args.putObject(name);
                case "linkset" -> args.putObject("linkset").putArray("linkset");
                case "topics" -> args.putArray("topics").add(NOWHERE + "topic");
                case "auth" -> args.putObject("auth").put("type", "none");
                case "method" -> args.put("method", "POST");
                case "bodyBase64" -> args.put("bodyBase64", "");
                default -> args.put(name, NOWHERE + name);
            }
        }
        return args;
    }
}
