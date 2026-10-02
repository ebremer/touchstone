package com.ebremer.touchstone.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** The fixture host's per-test inboxes (EXECUTION.md section 5.4). */
class InboxesTest {

    private static final Map<String, List<String>> LWS_JSON = Map.of(
            "Content-Type", List.of("application/lws+json"),
            "Authorization", List.of("Bearer secret"));

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aDeliveryIsRecordedWithItsActivitiesAsAnArray() {
        Inboxes inboxes = new Inboxes();
        String id = inboxes.open();

        assertThat(inboxes.record(id, "POST", LWS_JSON,
                utf8("{\"type\":\"Notification\",\"activity\":{\"type\":[\"Create\"]}}"))).isEqualTo(202);
        assertThat(inboxes.record(id, "POST", LWS_JSON,
                utf8("{\"type\":\"Notification\",\"activity\":[{\"type\":[\"Update\"]},{\"type\":[\"Delete\"]}]}")))
                .isEqualTo(202);

        JsonNode deliveries = inboxes.view(id).path("deliveries");
        assertThat(deliveries).hasSize(2);
        JsonNode first = deliveries.get(0);
        assertThat(first.path("contentType").asText()).isEqualTo("application/lws+json");
        assertThat(first.path("body").path("type").asText()).isEqualTo("Notification");
        assertThat(first.path("activities")).hasSize(1);
        assertThat(deliveries.get(1).path("activities")).hasSize(2);
        assertThat(first.path("headers").path("authorization").get(0).asText()).isEqualTo("[redacted]");
        assertThat(first.path("headers").path("content-type").get(0).asText()).isEqualTo("application/lws+json");
    }

    @Test
    void aBodyThatIsNotJsonIsKeptAsAString() {
        Inboxes inboxes = new Inboxes();
        String id = inboxes.open();
        inboxes.record(id, "POST", Map.of(), utf8("not json"));

        JsonNode d = inboxes.view(id).path("deliveries").get(0);
        assertThat(d.path("body").asText()).isEqualTo("not json");
        assertThat(d.path("activities")).isEmpty();
        assertThat(d.path("contentType").isNull()).isTrue();
    }

    @Test
    void onlyOpenInboxesAcceptDeliveriesAndAFullOneRefusesMore() {
        Inboxes inboxes = new Inboxes();
        assertThat(inboxes.record("no-such-inbox", "POST", Map.of(), utf8("{}"))).isEqualTo(404);
        assertThat(inboxes.view("no-such-inbox")).isNull();

        String id = inboxes.open();
        for (int i = 0; i < Inboxes.MAX_DELIVERIES; i++) {
            assertThat(inboxes.record(id, "POST", Map.of(), utf8("{}"))).isEqualTo(202);
        }
        assertThat(inboxes.record(id, "POST", Map.of(), utf8("{}"))).isEqualTo(429);

        inboxes.close(id);
        assertThat(inboxes.record(id, "POST", Map.of(), utf8("{}"))).isEqualTo(404);
    }

    @Test
    void aScriptedInboxAnswersItsStatusesInOrderTheLastRepeatingAndRecordsEach() throws Exception {
        Inboxes inboxes = new Inboxes();
        String id = inboxes.open();
        assertThat(inboxes.script(id, Templates.JSON.readTree("{\"respond\": [503, 202]}"))).isEqualTo(204);

        assertThat(inboxes.record(id, "POST", LWS_JSON, utf8("{}"))).isEqualTo(503);
        assertThat(inboxes.record(id, "POST", LWS_JSON, utf8("{}"))).isEqualTo(202);
        assertThat(inboxes.record(id, "POST", LWS_JSON, utf8("{}"))).isEqualTo(202);

        JsonNode deliveries = inboxes.view(id).path("deliveries");
        assertThat(deliveries).hasSize(3);
        assertThat(deliveries.get(0).path("status").asInt()).isEqualTo(503);
        assertThat(deliveries.get(2).path("status").asInt()).isEqualTo(202);
    }

    @Test
    void aScriptMustBeAShortListOfStatusesForAnOpenInbox() throws Exception {
        Inboxes inboxes = new Inboxes();
        String id = inboxes.open();
        assertThat(inboxes.script("no-such-inbox", Templates.JSON.readTree("{\"respond\": [410]}"))).isEqualTo(404);
        assertThat(inboxes.script(id, Templates.JSON.readTree("{\"respond\": []}"))).isEqualTo(400);
        assertThat(inboxes.script(id, Templates.JSON.readTree("{\"respond\": [99]}"))).isEqualTo(400);
        assertThat(inboxes.script(id, Templates.JSON.readTree("{\"respond\": \"410\"}"))).isEqualTo(400);
        assertThat(inboxes.script(id, null)).isEqualTo(400);
        // an unscripted inbox answers 202, and says so
        inboxes.record(id, "POST", LWS_JSON, utf8("{}"));
        assertThat(inboxes.view(id).path("deliveries").get(0).path("status").asInt()).isEqualTo(202);
    }
}
