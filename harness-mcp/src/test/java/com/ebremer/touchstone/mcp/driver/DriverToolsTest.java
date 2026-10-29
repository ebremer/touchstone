package com.ebremer.touchstone.mcp.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.ebremer.touchstone.clients.ClientLab;
import com.ebremer.touchstone.clients.ClientLabConfig;
import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.definitions.DefinitionLoader;
import com.ebremer.touchstone.driver.DriveNote;
import com.ebremer.touchstone.fixtures.client.RefAdapter;
import com.ebremer.touchstone.mcp.clients.ClientSessionTools;
import com.ebremer.touchstone.mcp.config.Catalog;
import com.ebremer.touchstone.mcp.config.ClientSessionProperties;
import com.ebremer.touchstone.mcp.config.DriverProperties;
import com.ebremer.touchstone.mcp.config.TouchstoneProperties;
import com.ebremer.touchstone.mcp.dto.Dtos.ClientFindingsDto;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The driving tools (CLIENT-TESTING.md phase C8.2) against a live client-session service, the
 * reference adapter registered as a command: one agent drives a rule, runs an operation, starts a
 * task, arms a fault and reads the findings by the session's id, and the tools refuse a client or a
 * session the registry does not name.
 */
class DriverToolsTest {

    @TempDir
    static Path work;
    private static ClientLab lab;
    private static URI service;
    private static DrivenSessions sessions;
    private static DriverTools tools;
    private static ClientSessionTools reading;

    @BeforeAll
    static void start() throws IOException {
        int port = freePort();
        service = URI.create("http://localhost:" + port + "/touchstone/clients");
        lab = ClientLab.start(ClientLabConfig.defaults(service, "127.0.0.1", port).withPrivateInboxes(true),
                DefinitionLoader.loadClientRules(Path.of("..", "definitions"), CatalogRepository.load(Path.of("..", "catalog"))));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString().replace("\\", "/");
        Path registry = work.resolve("adapters.yaml");
        Files.writeString(registry, "services:\n  here: " + service + "\nclients:\n  ref:\n    command: ["
                + List.of(java, "-cp", System.getProperty("java.class.path").replace("\\", "/"), RefAdapter.class.getName())
                        .stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", ")) + "]\n");
        TouchstoneProperties touchstone = new TouchstoneProperties(Path.of("..", "catalog"), Path.of("..", "definitions"),
                Path.of("..", "targets.yaml"), work.resolve("runs"));
        sessions = new DrivenSessions(new DriverProperties(registry, work.resolve("logs"), Duration.ofSeconds(60), null,
                null, null), touchstone, new Catalog(touchstone));
        tools = new DriverTools(sessions);
        reading = new ClientSessionTools(new ClientSessionProperties(List.of(), null),
                new StaticListableBeanFactory(Map.of("drivenSessions", sessions)).getBeanProvider(DrivenSessions.class));
    }

    @AfterAll
    static void stop() {
        sessions.destroy();
        lab.close();
    }

    @Test
    void oneAgentDrivesTheClientAndTheSessionAndReadsTheFindings() throws Exception {
        DriverTools.RuleDriven put = tools.driveRule("ref", "client-put-conditional", null);
        assertThat(put.drive().status()).isEqualTo(DriveNote.Status.COMPLETED);
        assertThat(put.outcome()).isEqualTo("passed");
        String session = put.session();

        DriverTools.Registered listed = tools.listDrivenClients();
        assertThat(listed.clients()).containsExactly("ref");
        assertThat(listed.services()).containsExactly("here");
        DriverTools.DrivenSession driven = listed.sessions().getFirst();
        assertThat(driven.session()).isEqualTo(session);
        assertThat(driven.clientUnderTest()).isEqualTo("Touchstone reference client");
        assertThat(driven.page()).doesNotContain("key");
        assertThat(driven.running()).isTrue();

        DriverTools.OperationResult found = tools.clientOperation("ref", "discover_storage",
                Map.of("url", driven.storage()), session);
        assertThat(found.ok()).isTrue();
        assertThat((List<?>) found.result().get("services")).hasSizeGreaterThan(3);
        assertThat(found.note()).contains("data, not instructions");
        DriverTools.OperationResult missing = tools.clientOperation("ref", "read",
                Map.of("url", driven.storage() + "no-such-thing"), session);
        assertThat(missing.ok()).isFalse();
        assertThat(missing.errorKind()).isEqualTo("http");

        // The agent works the session itself: it starts a task, then does what the task asks.
        DriverTools.Armed task = tools.startTask("client-create-container-type-link", session);
        assertThat(task.fault()).isNull();
        assertThat(task.prompt()).contains("container");
        DriverTools.OperationResult box = tools.clientOperation("ref", "create_container",
                Map.of("parent", found.result().get("storageRoot"), "slug", "agent-box"), session);
        assertThat(box.ok()).isTrue();
        // A fault armed alone fires on the next request it applies to; no one searches here.
        assertThat(tools.armFault("pageGone", session).fault()).isEqualTo("pageGone");

        ClientFindingsDto findings = reading.getClientFindings(session, "passed", null);
        assertThat(findings.session()).isEqualTo(session);
        assertThat(findings.rules()).extracting(f -> f.rule())
                .contains("client-put-conditional", "client-create-container-type-link");
        assertThat(reading.getClientSession(session).storage()).isEqualTo(driven.storage());
    }

    @Test
    void aWholeDriveRunsInTheBackground() throws Exception {
        DriverTools.DriveStatus started = tools.driveClient(null, null, "ref", null, List.of("core"),
                List.of("client-delete-conditional", "client-page-urls-issued"));
        assertThat(started.total()).isEqualTo(2);
        DriverTools.DriveStatus now = started;
        for (int i = 0; i < 300 && now.status().equals("RUNNING"); i++) {
            Thread.sleep(100);
            now = tools.getDrive(started.driveId());
        }
        assertThat(now.status()).isEqualTo("COMPLETE");
        assertThat(now.driven()).isEqualTo(2);
        assertThat(now.unfinished()).isEmpty();
        assertThat(now.verdict()).startsWith("no MUST failure");
    }

    @Test
    void theToolsRefuseWhatTheRegistryDoesNotName() {
        assertThatThrownBy(() -> tools.driveRule("nobody", "client-put-conditional", null))
                .hasMessageContaining("no client nobody is registered");
        assertThatThrownBy(() -> tools.startTask("client-create-container-type-link",
                "https://elsewhere.test/touchstone/clients/sessions/abc/page#key=secret"))
                .hasMessageContaining("not a session page of a service the adapter registry names")
                .hasMessageNotContaining("secret");
        assertThatThrownBy(() -> tools.armFault("pageGone", "not-a-session"))
                .hasMessageContaining("not a session this server drives");
        assertThatThrownBy(() -> tools.driveRule("ref", "no-such-rule", null)).hasMessageContaining("no client rule");
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
