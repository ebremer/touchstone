package com.ebremer.touchstone.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.definitions.ClientRules;
import com.ebremer.touchstone.core.definitions.DefinitionLoader;
import com.ebremer.touchstone.driver.AdapterRegistry;
import com.ebremer.touchstone.driver.DriveNote;
import com.ebremer.touchstone.driver.Driver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "drive",
        mixinStandardHelpOptions = true,
        exitCodeOnExecutionException = TouchstoneCli.HARNESS_ERROR,
        description = {"Drive a client through a client session (CLIENT-TESTING.md section 14): start its registered "
                + "adapter, walk it through every client rule's drive, and write the session's results.",
                "The client and the service are named by their ids in the adapter registry, never by a command or URL."},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
                "0:no MUST rule failed",
                "1:a MUST rule failed, or could not be decided",
                "2:the harness produced no verdict"})
final class DriveCommand implements Callable<Integer> {

    @Option(names = "--client", required = true, description = "Id of the client in the adapter registry.")
    private String client;

    @Option(names = "--service", description = "Id of the client-session service in the registry; optional when it registers one.")
    private String service;

    @Option(names = "--registry", defaultValue = "adapters.yaml", description = "Adapter registry (default: ${DEFAULT-VALUE}).")
    private Path registry;

    @Option(names = "--definitions", defaultValue = "definitions", description = "Definitions directory (default: ${DEFAULT-VALUE}).")
    private Path definitions;

    @Option(names = "--catalog", defaultValue = "catalog", description = "Requirements catalog (default: ${DEFAULT-VALUE}).")
    private Path catalog;

    @Option(names = "--areas", split = ",", description = "Areas in scope: core, authentication, notifications, index (default: all).")
    private List<String> areas;

    @Option(names = "--rule", description = "Drive only this rule; repeatable.")
    private Set<String> rules;

    @Option(names = "--out", description = "Directory for results.json, earl.ttl and junit.xml (default: runs/clients/<time>-<client>).")
    private Path out;

    @Option(names = "--operation-timeout", defaultValue = "60", description = "Seconds one operation may take (default: ${DEFAULT-VALUE}).")
    private int operationTimeout;

    @Option(names = "--inbox-host", defaultValue = "127.0.0.1", description = "Address the driver's inbox binds (default: ${DEFAULT-VALUE}).")
    private String inboxHost;

    @Option(names = "--inbox-port", defaultValue = "0", description = "Port of the driver's inbox (default: a free one).")
    private int inboxPort;

    @Option(names = "--inbox-public-base", description = "The inbox as the session reaches it, ending in /inbox/; needed for a public service.")
    private URI inboxPublicBase;

    @Option(names = "--show-page", description = "Print the session page with its key, which opens it.")
    private boolean showPage;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        PrintWriter err = spec.commandLine().getErr();
        AdapterRegistry adapters;
        ClientRules clientRules;
        URI base;
        try {
            adapters = AdapterRegistry.load(registry);
            base = adapters.service(service);
            clientRules = DefinitionLoader.loadClientRules(definitions, CatalogRepository.load(catalog));
        } catch (RuntimeException e) {
            err.println(TouchstoneCli.describe(e));
            return TouchstoneCli.HARNESS_ERROR;
        }
        Path dir = out != null ? out : Path.of("runs", "clients",
                DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now()) + "-" + client);
        Driver.Options options = new Driver.Options(areas == null ? Driver.AREAS : areas, rules,
                Duration.ofSeconds(operationTimeout), inboxHost, inboxPort, inboxPublicBase, true);
        Driver.Report report;
        try {
            report = Driver.drive(adapters.launcher(client, dir.resolve("adapter-logs")), base, clientRules, options);
        } catch (IOException | RuntimeException e) {
            err.println(TouchstoneCli.describe(e));
            return TouchstoneCli.HARNESS_ERROR;
        }
        write(dir, report);
        print(spec.commandLine().getOut(), report, dir);
        return report.mustFailed() ? 1 : 0;
    }

    private static void write(Path dir, Driver.Report report) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("results.json"), new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(report.withNotes()));
        Files.writeString(dir.resolve("earl.ttl"), report.earl());
        Files.writeString(dir.resolve("junit.xml"), report.junit());
    }

    private void print(PrintWriter out, Driver.Report report, Path dir) {
        JsonNode results = report.results();
        out.printf("%s: %s%n", report.client().name(), results.at("/verdict/text").asText());
        JsonNode c = results.path("counts");
        out.printf("  passed %d, failed %d, cantTell %d, untested %d, inapplicable %d%n", c.path("passed").asInt(),
                c.path("failed").asInt(), c.path("cantTell").asInt(), c.path("untested").asInt(), c.path("inapplicable").asInt());
        for (JsonNode r : results.path("rules")) {
            if (r.path("outcome").asText().equals("failed")) {
                out.printf("  failed %s %s: %s%n", r.path("level").asText(), r.path("rule").asText(), r.path("guidance").asText());
            }
        }
        for (DriveNote n : report.notes()) {
            if (n.status() == DriveNote.Status.STOPPED || n.status() == DriveNote.Status.NOT_STARTED) {
                out.printf("  drive of %s %s%s: %s%n", n.rule(), n.status() == DriveNote.Status.STOPPED ? "stopped" : "not started",
                        n.step() > 0 ? " at step " + n.step() : "", n.reason());
            }
        }
        out.printf("  session %s; results in %s%n", report.sessionId(), dir);
        if (showPage) {
            out.printf("  session page: %s%n", report.pageWithKey());
        }
    }
}
