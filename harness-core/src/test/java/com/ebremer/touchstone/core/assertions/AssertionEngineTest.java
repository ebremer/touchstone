package com.ebremer.touchstone.core.assertions;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.ebremer.touchstone.core.manifest.Manifest;
import com.ebremer.touchstone.core.results.AssertionResult;
import com.ebremer.touchstone.core.results.Redaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class AssertionEngineTest {

    @TempDir
    Path tmp;

    private static ResponseData response(int status, Map<String, List<String>> headers, String body, String ct) {
        return new ResponseData(status, HttpHeaders.of(headers, (a, b) -> true),
                body.getBytes(StandardCharsets.UTF_8), URI.create("http://sut.example/x"), ct);
    }

    private EvalEnv env(Map<String, String> vars) {
        return new EvalEnv(vars, tmp, null);
    }

    private static Manifest.Expectations expect(Manifest.GraphAssertion graph) {
        return new Manifest.Expectations(null, null, null, graph, null, null);
    }

    @Test
    void statusAndHeaderAssertions() {
        ResponseData data = response(201,
                Map.of("Location", List.of("http://sut.example/x/new"), "Link", List.of("<http://up>; rel=\"up\"")),
                "", null);
        Manifest.Expectations e = new Manifest.Expectations(
                java.util.Set.of(201),
                Map.of(
                        "Location", new Manifest.HeaderAssertion(true, null, null, null, null),
                        "Link", new Manifest.HeaderAssertion(null, null, null, null, "rel=\"up\""),
                        "ETag", new Manifest.HeaderAssertion(null, true, null, null, null)),
                null, null, null, null);
        List<AssertionResult> results = AssertionEngine.evaluate(e, data, env(Map.of()));
        assertThat(results).hasSize(4);
        assertThat(results).allMatch(AssertionResult::passed);
    }

    @Test
    void failedStatusReportsExpectedAndActual() {
        ResponseData data = response(404, Map.of(), "", null);
        Manifest.Expectations e = new Manifest.Expectations(java.util.Set.of(200), null, null, null, null, null);
        List<AssertionResult> results = AssertionEngine.evaluate(e, data, env(Map.of()));
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().passed()).isFalse();
        assertThat(results.getFirst().actual()).isEqualTo("404");
    }

    @Test
    void jsonPointerAssertions() {
        ResponseData data = response(200, Map.of(),
                "{\"totalItems\":1,\"items\":[{\"id\":\"http://sut.example/x/a\"}]}", "application/json");
        Manifest.Expectations e = new Manifest.Expectations(null, null, List.of(
                new Manifest.JsonAssertion("/totalItems",
                        com.fasterxml.jackson.databind.node.IntNode.valueOf(1), null, null, null),
                new Manifest.JsonAssertion("/items", null, null, null, 1),
                new Manifest.JsonAssertion("/items/0/id",
                        com.fasterxml.jackson.databind.node.TextNode.valueOf("${created}"), null, null, null),
                new Manifest.JsonAssertion("/missing", null, null, false, null)),
                null, null, null);
        List<AssertionResult> results = AssertionEngine.evaluate(e, data,
                env(Map.of("created", "http://sut.example/x/a")));
        assertThat(results).hasSize(4);
        assertThat(results).allMatch(AssertionResult::passed);
    }

    @Test
    void graphContainsWithBlankNodeWildcardAndTypedLiteral() {
        String turtle = """
                @prefix ex: <http://example.org/> .
                ex:s ex:p _:b1 .
                _:b1 ex:q "value"@en .
                """;
        ResponseData data = response(200, Map.of(), turtle, "text/turtle");
        Manifest.GraphAssertion g = new Manifest.GraphAssertion(null, List.of(
                new Manifest.TriplePattern("http://example.org/s", "http://example.org/p",
                        new Manifest.ObjectTerm("_:any", null, null, null)),
                new Manifest.TriplePattern("_:any", "http://example.org/q",
                        new Manifest.ObjectTerm(null, "value", "en", null))),
                List.of(new Manifest.TriplePattern("http://example.org/s", "http://example.org/q",
                        new Manifest.ObjectTerm(null, "value", "en", null))),
                null, null);
        List<AssertionResult> results = AssertionEngine.evaluate(expect(g), data, env(Map.of()));
        assertThat(results).hasSize(3);
        assertThat(results).allMatch(AssertionResult::passed);
    }

    @Test
    void isomorphismIsBlankNodeSafe() throws Exception {
        String turtle = """
                @prefix ex: <http://example.org/> .
                ex:s ex:p _:x .
                _:x ex:q "v" .
                """;
        Path fixture = tmp.resolve("fixture.ttl");
        Files.writeString(fixture, """
                @prefix ex: <http://example.org/> .
                ex:s ex:p _:differentLabel .
                _:differentLabel ex:q "v" .
                """);
        ResponseData data = response(200, Map.of(), turtle, "text/turtle");
        Manifest.GraphAssertion g = new Manifest.GraphAssertion(null, null, null, "fixture.ttl", null);
        List<AssertionResult> results = AssertionEngine.evaluate(expect(g), data, env(Map.of()));
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().passed()).isTrue();
    }

    @Test
    void shaclConformanceAndViolation() throws Exception {
        Path shapes = tmp.resolve("shapes.ttl");
        Files.writeString(shapes, """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix ex: <http://example.org/> .
                ex:ThingShape a sh:NodeShape ;
                    sh:targetClass ex:Thing ;
                    sh:property [ sh:path ex:name ; sh:minCount 1 ] .
                """);
        Manifest.GraphAssertion g = new Manifest.GraphAssertion(null, null, null, null, "shapes.ttl");

        ResponseData conforming = response(200, Map.of(), """
                @prefix ex: <http://example.org/> .
                ex:a a ex:Thing ; ex:name "named" .
                """, "text/turtle");
        assertThat(AssertionEngine.evaluate(expect(g), conforming, env(Map.of())))
                .singleElement().matches(AssertionResult::passed);

        ResponseData violating = response(200, Map.of(), """
                @prefix ex: <http://example.org/> .
                ex:a a ex:Thing .
                """, "text/turtle");
        assertThat(AssertionEngine.evaluate(expect(g), violating, env(Map.of())))
                .singleElement().matches(r -> !r.passed());
    }

    @Test
    void bodyAssertions() throws Exception {
        Path fixture = tmp.resolve("body.txt");
        Files.writeString(fixture, "expected content");
        ResponseData data = response(200, Map.of(), "expected content", "text/plain");
        Manifest.Expectations e = new Manifest.Expectations(null, null, null, null,
                new Manifest.BodyAssertion("body.txt", "expected", false), null);
        List<AssertionResult> results = AssertionEngine.evaluate(e, data, env(Map.of()));
        assertThat(results).hasSize(3);
        assertThat(results).allMatch(AssertionResult::passed);
    }

    @Test
    void connegWithoutRefetchFailsInsteadOfCrashing() {
        ResponseData data = response(200, Map.of(), "{}", "application/json");
        Manifest.Expectations e = new Manifest.Expectations(null, null, null, null, null,
                List.of("application/lws+json", "application/ld+json"));
        List<AssertionResult> results = AssertionEngine.evaluate(e, data, env(Map.of()));
        assertThat(results).singleElement().matches(r -> !r.passed());
    }

    @Test
    void redactionStripsCredentialHeaders() {
        Map<String, List<String>> headers = Map.of(
                "Authorization", List.of("Bearer secret-token"),
                "Accept", List.of("text/turtle"));
        Map<String, List<String>> redacted = Redaction.redactHeaders(headers);
        assertThat(redacted.get("Authorization")).containsExactly(Redaction.REDACTED);
        assertThat(redacted.get("Accept")).containsExactly("text/turtle");
    }
}
