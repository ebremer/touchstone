package com.ebremer.touchstone.driver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.ebremer.touchstone.driver.protocol.AdapterConnection;
import com.ebremer.touchstone.driver.protocol.BrokenAdapterException;
import com.ebremer.touchstone.driver.protocol.ProtocolSchema;

/**
 * Starts a client's adapter. The driver starts two of each: one that runs the drives, and one
 * that verifies the deliveries to the inbox the driver hosts, so that a delivery never waits for
 * an operation in progress (driver/PROTOCOL.md section 5.2).
 */
@FunctionalInterface
public interface AdapterLauncher {

    /** How long an adapter may take to send its hello. */
    Duration HELLO_WITHIN = Duration.ofSeconds(60);

    /** Starts one adapter and reads its hello. */
    Adapter start();

    /** A running adapter: its connection, and how to stop it. */
    interface Adapter extends AutoCloseable {
        AdapterConnection connection();

        /** Sends {@code shutdown}, then stops the adapter however it takes it. */
        @Override
        void close();
    }

    /**
     * An adapter registered in configuration (CLIENT-TESTING.md section 14.3): a command line, a
     * working directory and an environment, never anything a rule or a tool argument says. Its
     * stderr goes to a log file of its own.
     */
    static AdapterLauncher process(List<String> command, Path directory, Map<String, String> environment, Path log) {
        List<String> line = resolved(command, directory);
        return () -> {
            ProcessBuilder pb = new ProcessBuilder(line);
            if (directory != null) {
                pb.directory(directory.toFile());
            }
            pb.environment().putAll(environment);
            try {
                Files.createDirectories(log.toAbsolutePath().getParent());
                pb.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
                Process p = pb.start();
                AdapterConnection c;
                try {
                    c = new AdapterConnection(p.getInputStream(), p.getOutputStream(), ProtocolSchema.instance(), HELLO_WITHIN);
                } catch (BrokenAdapterException e) {
                    p.destroyForcibly();
                    throw e;
                }
                return new Adapter() {
                    @Override
                    public AdapterConnection connection() {
                        return c;
                    }

                    @Override
                    public void close() {
                        shutdown(c);
                        try {
                            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                                p.destroyForcibly();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            p.destroyForcibly();
                        }
                    }
                };
            } catch (IOException e) {
                throw new BrokenAdapterException("the adapter could not be started: " + e.getMessage());
            }
        };
    }

    /**
     * The command with a relative program, such as {@code build/adapter}, resolved against the working
     * directory when a file is there: Linux and macOS start it so, but Windows looks for it only beside
     * the driver and on the PATH. A bare name, such as {@code node}, is left to the PATH.
     */
    static List<String> resolved(List<String> command, Path directory) {
        String program = command.getFirst();
        Path p = Path.of(program);
        if (directory == null || p.isAbsolute() || p.getNameCount() < 2 && !program.startsWith(".")) {
            return List.copyOf(command);
        }
        for (String candidate : List.of(program, program + ".exe")) {
            Path there = directory.resolve(candidate).normalize();
            if (java.nio.file.Files.isRegularFile(there)) {
                List<String> out = new java.util.ArrayList<>(command);
                out.set(0, there.toAbsolutePath().toString());
                return List.copyOf(out);
            }
        }
        return List.copyOf(command);
    }

    /** Asks an adapter to stop, as politely as it still allows. */
    static void shutdown(AdapterConnection c) {
        if (!c.isBroken()) {
            try {
                c.call("shutdown", null, Duration.ofSeconds(5));
            } catch (BrokenAdapterException ignored) {
                // It is going away either way.
            }
        }
        c.close();
    }

    /** Drains a stream nobody reads, so that a process never blocks writing to it. */
    static void drain(InputStream in) {
        Thread.ofVirtual().start(() -> {
            try (in) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            } catch (IOException ignored) {
                // Nothing to do.
            }
        });
    }
}
