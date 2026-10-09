package com.ebremer.touchstone.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.ebremer.touchstone.core.engine.Http.Req;
import com.ebremer.touchstone.core.engine.Http.Resp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

/** 429 and 503 with Retry-After (EXECUTION.md section 4.5), against scripted answers and a recording sleeper. */
class RateLimitsTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final Req REQ = new Req("GET", URI.create("http://127.0.0.1/r"), List.of(), null, null);

    private final List<Long> slept = new ArrayList<>();
    private final RateLimits limits = new RateLimits(30, 3, slept::add, Clock.fixed(NOW, ZoneOffset.UTC));

    // ------------------------------------------------------------------ Retry-After (RFC 9110 section 10.2.3)

    @Test
    void retryAfterIsSecondsOrAnHttpDateInAnyOfItsThreeForms() {
        assertThat(RateLimits.retryAfter("5", NOW)).isEqualTo(5L);
        assertThat(RateLimits.retryAfter(" 0 ", NOW)).isZero();
        Instant later = NOW.plusSeconds(12);
        assertThat(RateLimits.retryAfter(DateTimeFormatter.RFC_1123_DATE_TIME.format(later.atOffset(ZoneOffset.UTC)), NOW))
                .isEqualTo(12L);
        assertThat(RateLimits.retryAfter(DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss 'GMT'", Locale.US)
                .format(later.atOffset(ZoneOffset.UTC)), NOW)).isEqualTo(12L);
        assertThat(RateLimits.retryAfter(DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.US)
                .format(later.atOffset(ZoneOffset.UTC)), NOW)).isEqualTo(12L);
        // A date already past asks for no wait at all; part of a second counts as a whole one.
        assertThat(RateLimits.retryAfter(DateTimeFormatter.RFC_1123_DATE_TIME.format(NOW.minusSeconds(60).atOffset(ZoneOffset.UTC)), NOW))
                .isZero();
        assertThat(RateLimits.retryAfter(DateTimeFormatter.RFC_1123_DATE_TIME.format(NOW.plusSeconds(3).atOffset(ZoneOffset.UTC)),
                NOW.minusMillis(400))).isEqualTo(4L);
    }

    @Test
    void anythingElseIsNoRetryAfter() {
        assertThat(RateLimits.retryAfter(null, NOW)).isNull();
        assertThat(RateLimits.retryAfter("", NOW)).isNull();
        assertThat(RateLimits.retryAfter("-1", NOW)).isNull();
        assertThat(RateLimits.retryAfter("1.5", NOW)).isNull();
        assertThat(RateLimits.retryAfter("soon", NOW)).isNull();
        assertThat(RateLimits.retryAfter("99999999999999999999999", NOW)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void onlyAStatusNamedAsAnIntegerIsExpected() {
        JsonNode list = Templates.JSON.createArrayNode().add(200).add(429);
        assertThat(RateLimits.named(list, 429)).isTrue();
        assertThat(RateLimits.named(list, 503)).isFalse();
        assertThat(RateLimits.named(IntNode.valueOf(503), 503)).isTrue();
        assertThat(RateLimits.named(TextNode.valueOf("4xx"), 429)).isFalse();
        assertThat(RateLimits.named(null, 429)).isFalse();
    }

    // ------------------------------------------------------------------ sending again

    @Test
    void aRefusalIsWaitedOutAsAskedAndTheRequestSentAgain() throws Exception {
        Script script = new Script(resp(429, "2"), resp(503, "1"), resp(200, null));
        RateLimits.Result result = limits.send(REQ, script::next);

        assertThat(result.response().status()).isEqualTo(200);
        assertThat(result.limited()).isFalse();
        assertThat(result.retried()).extracting(r -> r.resp().status()).containsExactly(429, 503);
        assertThat(result.retried().getFirst().note()).contains("429 Too Many Requests").contains("2 s");
        assertThat(slept).containsExactly(2L, 1L);
        assertThat(script.sent).isEqualTo(3);
    }

    @Test
    void withoutRetryAfterNothingIsSentAgain() throws Exception {
        Script script = new Script(resp(429, null), resp(200, null));
        RateLimits.Result result = limits.send(REQ, script::next);

        assertThat(result.limited()).isTrue();
        assertThat(result.response().status()).isEqualTo(429);
        assertThat(result.unresolved()).contains("without a Retry-After");
        assertThat(slept).isEmpty();
        assertThat(script.sent).isEqualTo(1);
    }

    @Test
    void aWaitLongerThanTheCapIsNotMade() throws Exception {
        Script script = new Script(resp(503, "31"), resp(200, null));
        RateLimits.Result result = limits.send(REQ, script::next);

        assertThat(result.limited()).isTrue();
        assertThat(result.unresolved()).contains("503 Service Unavailable").contains("longer than the 30 s");
        assertThat(slept).isEmpty();
        assertThat(script.sent).isEqualTo(1);
    }

    @Test
    void retriesAreBounded() throws Exception {
        Script script = new Script(resp(429, "1"), resp(429, "1"), resp(429, "1"), resp(429, "1"), resp(200, null));
        RateLimits.Result result = limits.send(REQ, script::next);

        assertThat(result.limited()).isTrue();
        assertThat(result.unresolved()).contains("after 3 retries");
        assertThat(result.retried()).hasSize(3);
        assertThat(slept).containsExactly(1L, 1L, 1L);
        assertThat(script.sent).isEqualTo(4);
    }

    @Test
    void aStepThatNamesTheStatusGetsItAsItCame() throws Exception {
        Script script = new Script(resp(429, "1"), resp(200, null));
        RateLimits.Result result = limits.send(REQ, script::next, Templates.JSON.createArrayNode().add(429));

        assertThat(result.response().status()).isEqualTo(429);
        assertThat(result.limited()).isFalse();
        assertThat(script.sent).isEqualTo(1);
    }

    @Test
    void aClassOfStatusesDoesNotNameARefusal() throws Exception {
        // A negative test that expects "4xx" learns nothing from a rate limit: the request was
        // not evaluated, so it is sent again when the server says when.
        Script script = new Script(resp(429, "0"), resp(400, null));
        RateLimits.Result result = limits.send(REQ, script::next, TextNode.valueOf("4xx"));

        assertThat(result.response().status()).isEqualTo(400);
        assertThat(slept).containsExactly(0L);
    }

    @Test
    void otherStatusesAreLeftAlone() throws Exception {
        Script script = new Script(resp(500, "1"));
        assertThat(limits.send(REQ, script::next).response().status()).isEqualTo(500);
        assertThat(script.sent).isEqualTo(1);
    }

    private static Resp resp(int status, String retryAfter) {
        Map<String, List<String>> headers = retryAfter == null ? Map.of() : Map.of("Retry-After", List.of(retryAfter));
        return new Resp(status, HttpHeaders.of(headers, (a, b) -> true), new byte[0]);
    }

    /** Answers with the given responses in order. */
    private static final class Script {
        private final Deque<Resp> answers;
        int sent;

        Script(Resp... answers) {
            this.answers = new ArrayDeque<>(List.of(answers));
        }

        Resp next(Req req) {
            sent++;
            return answers.size() > 1 ? answers.poll() : answers.peek();
        }
    }
}
