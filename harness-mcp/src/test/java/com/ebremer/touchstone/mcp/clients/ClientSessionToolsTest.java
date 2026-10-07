package com.ebremer.touchstone.mcp.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import com.ebremer.touchstone.clients.ClientLab;
import com.ebremer.touchstone.clients.ClientLabConfig;
import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.definitions.DefinitionLoader;
import com.ebremer.touchstone.mcp.config.ClientSessionProperties;
import com.ebremer.touchstone.mcp.dto.Dtos.ClientExchangeDto;
import com.ebremer.touchstone.mcp.dto.Dtos.ClientFindingDto;
import com.ebremer.touchstone.mcp.dto.Dtos.ClientFindingsDto;
import com.ebremer.touchstone.mcp.dto.Dtos.ClientSessionDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The read-only client tools (CLIENT-TESTING.md phase C7) against a live client-session service:
 * an agent reads the session, its findings and an exchange, only of a registered service, and
 * never sees the session key or the client's token.
 */
class ClientSessionToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static ClientLab lab;
    private static String base;

    @BeforeAll
    static void start() {
        int port = freePort();
        base = "http://localhost:" + port + "/touchstone/clients";
        lab = ClientLab.start(ClientLabConfig.defaults(URI.create(base), "127.0.0.1", port),
                DefinitionLoader.loadClientRules(Path.of("..", "definitions"), CatalogRepository.load(Path.of("..", "catalog"))));
    }

    @AfterAll
    static void stop() {
        lab.close();
    }

    @Test
    void anAgentReadsTheSessionItsFindingsAndAnExchange() throws Exception {
        JsonNode session = JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(base + "/sessions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"clientUnderTest\": {\"name\": \"Agent's client\"}}")).build(),
                HttpResponse.BodyHandlers.ofString()).body());
        String page = session.get("pageWithKey").asText();
        String key = session.get("key").asText();
        String storage = session.get("storage").asText();
        String alice = session.at("/tokens/alice").asText();
        // One request the rule passes, one it fails: the token in the query string.
        HTTP.send(HttpRequest.newBuilder(URI.create(storage)).header("Authorization", "Bearer " + alice)
                .header("Accept", "application/lws+json").build(), HttpResponse.BodyHandlers.discarding());
        HTTP.send(HttpRequest.newBuilder(URI.create(storage + "?access_token=" + alice))
                .header("Accept", "application/lws+json").build(), HttpResponse.BodyHandlers.discarding());

        ClientSessionTools tools = new ClientSessionTools(new ClientSessionProperties(List.of(base + "/"), null));
        ClientSessionDto s = tools.getClientSession(page);
        assertThat(s.session()).isEqualTo(session.get("id").asText());
        assertThat(s.clientUnderTest()).containsEntry("name", "Agent's client");
        assertThat(s.storage()).isEqualTo(storage);
        assertThat(s.page()).doesNotContain("key");
        assertThat(s.verdict()).startsWith("1 MUST rule failed");
        assertThat(s.recorded()).isEqualTo(2);

        ClientFindingsDto findings = tools.getClientFindings(page, null, null);
        assertThat(findings.rules()).extracting(ClientFindingDto::rule).contains("client-token-in-authorization-header");
        ClientFindingDto failure = findings.rules().stream()
                .filter(f -> f.rule().equals("client-token-in-authorization-header")).findFirst().orElseThrow();
        assertThat(failure.guidance()).contains("Authorization: Bearer");
        assertThat(failure.source()).isNotEmpty();
        long seq = ((Number) failure.evidence().get("seq")).longValue();
        assertThat(tools.getClientFindings(page, "untested", "MUST").rules())
                .allSatisfy(f -> assertThat(f.level()).isEqualTo("MUST"))
                .anySatisfy(f -> assertThat(f.task()).isNotNull());

        ClientExchangeDto exchange = tools.getClientExchange(page, seq);
        assertThat(exchange.exchange().get("method")).isEqualTo("GET");
        assertThat(JSON.writeValueAsString(exchange)).doesNotContain(alice).doesNotContain(key).contains("redacted");
        assertThatThrownBy(() -> tools.getClientExchange(page, 99)).hasMessageContaining("no exchange 99 yet");

        // The configured session is read when a call names none.
        ClientSessionTools configured = new ClientSessionTools(new ClientSessionProperties(List.of(base), page));
        assertThat(configured.getClientFindings(null, "all", null).rules()).hasSize(53);
    }

    @Test
    void theToolsReadOnlyRegisteredServicesAndNeverRepeatTheKey() {
        ClientSessionTools tools = new ClientSessionTools(new ClientSessionProperties(List.of(base), null));
        String secret = "s3cr3t-key-value";
        assertThatThrownBy(() -> tools.getClientSession("http://169.254.169.254/sessions/abc/page#key=" + secret))
                .hasMessageContaining("not a session page of a registered client-session service")
                .hasMessageNotContaining(secret);
        assertThatThrownBy(() -> tools.getClientSession(base + "/sessions/abc/page"))
                .hasMessageContaining("has no key");
        assertThatThrownBy(() -> tools.getClientSession(base + "/sessions/abc/page#key=" + secret))
                .hasMessageContaining("has ended").hasMessageNotContaining(secret);
        assertThatThrownBy(() -> tools.getClientSession(null)).hasMessageContaining("name a session");
        assertThatThrownBy(() -> new ClientSessionTools(new ClientSessionProperties(List.of(), null))
                .getClientSession(base + "/sessions/abc/page#key=x")).hasMessageContaining("touchstone.clients.services");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
