package com.ebremer.touchstone.core.results;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DESIGN.md paragraph 7.2: strip before anything leaves the harness. A trace is copied
 * verbatim into run.json, report.html and every MCP get_trace, so anything a credential
 * can ride in has to be scrubbed at construction — not only the Authorization header.
 */
class RedactionTest {

    @Test
    void credentialHeadersAreReplacedAndOthersSurvive() {
        Map<String, List<String>> headers = Map.of(
                "Authorization", List.of("Bearer eyJhbGciOi.secret.value"),
                "DPoP", List.of("proof"),
                "Set-Cookie", List.of("session=abc"),
                "Content-Type", List.of("application/lws+json"),
                "WWW-Authenticate", List.of("Bearer realm=\"storage\", error=\"invalid_token\""));

        Map<String, List<String>> out = Redaction.redactHeaders(headers);

        assertThat(out.get("Authorization")).containsExactly(Redaction.REDACTED);
        assertThat(out.get("DPoP")).containsExactly(Redaction.REDACTED);
        assertThat(out.get("Set-Cookie")).containsExactly(Redaction.REDACTED);
        assertThat(out.get("Content-Type")).containsExactly("application/lws+json");
        assertThat(out.get("WWW-Authenticate"))
                .as("a challenge is the evidence a 401 test exists to capture, not a secret")
                .containsExactly("Bearer realm=\"storage\", error=\"invalid_token\"");
    }

    @Test
    void aTokenExchangeResponseBodyIsScrubbed() {
        // The shape of core 5.2.3: the storage-facing access token comes back in the body.
        String body = """
                {"access_token":"eyJhbGciOiJSUzI1NiJ9.SECRET","issued_token_type":"urn:ietf:params:oauth:token-type:access_token",
                 "token_type":"Bearer","expires_in":300,"refresh_token":"RT-SECRET"}""";

        String out = Redaction.redactBody(body);

        assertThat(out).doesNotContain("eyJhbGciOiJSUzI1NiJ9.SECRET", "RT-SECRET");
        assertThat(out).contains("\"access_token\":\"" + Redaction.REDACTED + "\"");
        assertThat(out).contains("\"refresh_token\":\"" + Redaction.REDACTED + "\"");
        assertThat(out)
                .as("everything that is not a credential still has to be readable")
                .contains("urn:ietf:params:oauth:token-type:access_token")
                .contains("\"expires_in\":300");
    }

    @Test
    void aTokenExchangeRequestFormBodyIsScrubbed() {
        String body = "grant_type=urn:ietf:params:oauth:grant-type:token-exchange"
                + "&subject_token=ID-TOKEN-SECRET&subject_token_type=urn:ietf:params:oauth:token-type:id_token"
                + "&audience=https://storage.example/";

        String out = Redaction.redactBody(body);

        assertThat(out).doesNotContain("ID-TOKEN-SECRET");
        assertThat(out).contains("subject_token=" + Redaction.REDACTED);
        assertThat(out).contains("grant_type=urn:ietf:params:oauth:grant-type:token-exchange");
        assertThat(out).contains("audience=https://storage.example/");
    }

    @Test
    void aTokenInAQueryStringIsScrubbed() {
        URI uri = URI.create("https://sut.example/c/note?access_token=SECRET&depth=1");

        assertThat(Redaction.redactUri(uri))
                .hasToString("https://sut.example/c/note?access_token=" + Redaction.REDACTED + "&depth=1");
    }

    @Test
    void anOrdinaryUriIsUntouched() {
        URI uri = URI.create("https://sut.example/c/note");
        assertThat(Redaction.redactUri(uri)).isSameAs(uri);
        assertThat(Redaction.redactUri(null)).isNull();
    }

    @Test
    void theTraceItselfRedactsEveryChannelAtConstruction() {
        HttpExchangeTrace trace = HttpExchangeTrace.of(
                "POST",
                URI.create("https://as.example/token?access_token=LEAK"),
                Map.of("Authorization", List.of("Bearer LEAK")),
                "subject_token=LEAK&grant_type=x",
                200,
                Map.of("Content-Type", List.of("application/json")),
                "{\"access_token\":\"LEAK\"}");

        assertThat(trace.uri().toString()).doesNotContain("LEAK");
        assertThat(trace.requestHeaders().get("Authorization")).containsExactly(Redaction.REDACTED);
        assertThat(trace.requestBody()).doesNotContain("LEAK");
        assertThat(trace.responseBody()).doesNotContain("LEAK");
    }

    @Test
    void bodiesStayTruncated() {
        String big = "x".repeat(5000);
        String out = Redaction.redactBody(big);
        assertThat(out).hasSizeLessThan(big.length()).contains("[truncated 2952 chars]");
    }

    @Test
    void anEmptyOrAbsentBodyIsReturnedAsIs() {
        assertThat(Redaction.redactBody(null)).isNull();
        assertThat(Redaction.redactBody("")).isEmpty();
    }
}
