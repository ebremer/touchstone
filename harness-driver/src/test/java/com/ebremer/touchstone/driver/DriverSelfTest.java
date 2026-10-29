package com.ebremer.touchstone.driver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.ebremer.touchstone.clients.ClientLab;
import com.ebremer.touchstone.clients.ClientLabConfig;
import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.definitions.ClientRules;
import com.ebremer.touchstone.core.definitions.DefinitionLoader;
import com.ebremer.touchstone.driver.protocol.AdapterConnection;
import com.ebremer.touchstone.driver.protocol.ProtocolSchema;
import com.ebremer.touchstone.fixtures.client.RefAdapter;
import com.ebremer.touchstone.fixtures.client.RefLwsClient.Flaw;
import com.ebremer.touchstone.fixtures.client.Twins;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Proving the driver (CLIENT-TESTING.md section 14.5): the reference client, behind the reference
 * adapter and driven with nobody at the page, passes every rule, and every drive runs to its end;
 * behind the same adapter, each broken twin fails exactly the rules aimed at it, as in the client
 * rules' own self-test. So every drive reaches the mistake its rule exists to catch.
 */
class DriverSelfTest {

    static final ClientRules RULES = DefinitionLoader.loadClientRules(Path.of("..", "definitions"),
            CatalogRepository.load(Path.of("..", "catalog")));
    private static ClientLab lab;
    private static URI service;

    @BeforeAll
    static void start() {
        int port = freePort();
        service = URI.create("http://localhost:" + port + "/touchstone/clients");
        ClientLabConfig d = ClientLabConfig.defaults(service, "127.0.0.1", port);
        // As in the client rules' self-test: one session per client from this address, an index
        // without lag, and notifications to an inbox on this machine.
        ClientLabConfig config = new ClientLabConfig(d.publicBase(), d.bindHost(), d.port(), false, 100, 100,
                d.idleTimeout(), d.maxLifetime(), d.maxBodyBytes(), d.maxRecordedResponseBytes(), d.maxExchanges(),
                d.maxResources(), d.maxStorageBytes(), d.requestBurst(), d.requestsPerSecond(), d.tokenLifetime(),
                Duration.ZERO, d.maxDeliveries(), true);
        lab = ClientLab.start(config, RULES);
    }

    @AfterAll
    static void stop() {
        lab.close();
    }

    @Test
    void theReferenceClientPassesEveryRuleAndEveryDriveRunsToItsEnd() throws Exception {
        // With the pauses: they cost the reference a few seconds, and the twins none, as the index here does not lag.
        Driver.Report report = Driver.drive(inProcess(Flaw.NONE), service, RULES, Driver.Options.defaults());
        Set<String> unfinished = new TreeSet<>();
        report.notes().stream()
                .filter(n -> n.status() != DriveNote.Status.COMPLETED && n.status() != DriveNote.Status.NO_DRIVE)
                .forEach(n -> unfinished.add(n.toString()));
        assertThat(unfinished).isEmpty();
        assertThat(notPassed(report.results())).as(report.results().toPrettyString()).isEmpty();
        assertThat(report.results().get("rules")).hasSize(RULES.rules().size());
        assertThat(report.results().at("/verdict/text").asText())
                .isEqualTo("no MUST failure in 41 MUST rules exercised, of 41 that apply");
        assertThat(report.mustFailed()).isFalse();
        assertThat(report.earl()).contains("earl:automatic").doesNotContain("earl:semiAuto");
        assertThat(report.client().name()).isEqualTo("Touchstone reference client");
        assertThat(report.withNotes().at("/rules/0/drive/status").asText()).isNotEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Flaw.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void eachTwinFailsExactlyTheRulesAimedAtIt(Flaw flaw) throws Exception {
        Driver.Report report = Driver.drive(inProcess(flaw), service, RULES, Driver.Options.defaults().withoutPauses());
        Set<String> failed = new TreeSet<>();
        for (JsonNode r : report.results().get("rules")) {
            if (r.get("outcome").asText().equals("failed")) {
                failed.add(r.get("rule").asText());
            }
        }
        assertThat(failed).as(flaw + "\n" + report.notes()).isEqualTo(Twins.AIMED.get(flaw));
    }

    /**
     * Phase C8.3's acceptance (CLIENT-TESTING.md section 13): an adapter built from the Java starter
     * kit, its files compiled as they are, over the reference client passes the drive of C8.1.
     */
    @Test
    void anAdapterBuiltFromTheJavaKitPassesTheDrive(@org.junit.jupiter.api.io.TempDir Path work) throws Exception {
        Driver.Report report = Driver.drive(JavaKit.launcher(JavaKit.compile(work)), service, RULES,
                Driver.Options.defaults().withoutPauses());
        assertThat(report.notes()).filteredOn(n -> n.status() != DriveNote.Status.COMPLETED
                && n.status() != DriveNote.Status.NO_DRIVE).isEmpty();
        assertThat(notPassed(report.results())).as(report.results().toPrettyString()).isEmpty();
        assertThat(report.client().name()).isEqualTo("Touchstone reference client, through the Java kit");
    }

    @Test
    void anAdapterInAProcessOfItsOwnIsDrivenTheSameWay() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        AdapterLauncher launcher = AdapterLauncher.process(
                List.of(java, "-cp", System.getProperty("java.class.path"), RefAdapter.class.getName()),
                null, Map.of(), Path.of("target", "adapter-logs", "reference.log"));
        Driver.Report report = Driver.drive(launcher, service, RULES, Driver.Options.defaults()
                .withRules(Set.of("client-put-conditional", "client-no-repeat-after-405-415")));
        assertThat(report.notes()).extracting(DriveNote::status).containsOnly(DriveNote.Status.COMPLETED);
        for (JsonNode r : report.results().get("rules")) {
            if (Set.of("client-put-conditional", "client-no-repeat-after-405-415").contains(r.path("rule").asText())) {
                assertThat(r.path("outcome").asText()).as(r.toPrettyString()).isEqualTo("passed");
            }
        }
    }

    @Test
    void aRuleOutOfScopeIsNotDrivenAndAnAreaWithoutItsServiceNeverStarts() throws Exception {
        Driver.Report report = Driver.drive(inProcess(Flaw.NONE), service, RULES,
                Driver.Options.defaults().withAreas(List.of("core")).withoutPauses());
        assertThat(report.notes()).allSatisfy(n -> assertThat(RULES.find(n.rule()).orElseThrow().area()).isEqualTo("core"));
        assertThat(report.results().at("/verdict/text").asText()).startsWith("no MUST failure");
    }

    private static Set<String> notPassed(JsonNode results) {
        Set<String> out = new TreeSet<>();
        for (JsonNode r : results.get("rules")) {
            if (!r.get("outcome").asText().equals("passed")) {
                out.add(r.get("rule").asText() + ": " + r.get("outcome").asText());
            }
        }
        return out;
    }

    /** The reference adapter on a thread of its own, its standard streams pipes to the driver. */
    static AdapterLauncher inProcess(Flaw flaw) {
        return () -> {
            try {
                PipedInputStream adapterIn = new PipedInputStream(1 << 16);
                PipedOutputStream toAdapter = new PipedOutputStream(adapterIn);
                PipedInputStream fromAdapter = new PipedInputStream(1 << 16);
                PipedOutputStream adapterOut = new PipedOutputStream(fromAdapter);
                Thread adapter = Thread.ofPlatform().daemon().name("ref-adapter-" + flaw).start(() -> {
                    try (adapterOut) {
                        RefAdapter.serve(adapterIn, adapterOut, flaw);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                AdapterConnection c = new AdapterConnection(fromAdapter, toAdapter, ProtocolSchema.instance(),
                        Duration.ofSeconds(10));
                return new AdapterLauncher.Adapter() {
                    @Override
                    public AdapterConnection connection() {
                        return c;
                    }

                    @Override
                    public void close() {
                        AdapterLauncher.shutdown(c);
                        try {
                            adapter.join(5000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                };
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
