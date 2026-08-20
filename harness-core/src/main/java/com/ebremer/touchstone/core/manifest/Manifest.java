package com.ebremer.touchstone.core.manifest;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One declarative conformance test, loaded from a YAML manifest that validates
 * against the frozen manifest schema v1 (docs/manifest-schema, DESIGN.md paragraph 5.2).
 * Records mirror the schema; {@code sourceFile} anchors relative references
 * (bodyRef, equalsRef, isomorphicTo, shacl).
 *
 * <p>{@code defaultIdentity} is null when the manifest declares no {@code as}, which
 * lets the executor fall back to the target's default identity; an explicit
 * {@code as: anonymous} is preserved and always wins (D-0028).
 */
public record Manifest(
        String id,
        String title,
        String description,
        List<String> requirements,
        List<String> capabilities,
        List<String> tags,
        String defaultIdentity,
        List<Step> steps,
        Path sourceFile) {

    /** The module segment of the id ({@code core} in {@code core/some-test}). */
    public String module() {
        return id.substring(0, id.indexOf('/'));
    }

    public record Step(
            String name,
            String identity,
            RequestSpec request,
            String rawRequest,
            Expectations expect,
            Map<String, String> bind,
            Integer timeoutMillis) {
    }

    public record RequestSpec(
            String method,
            String target,
            Map<String, List<String>> headers,
            String body,
            String bodyRef) {
    }

    public record Expectations(
            Set<Integer> status,
            Map<String, HeaderAssertion> headers,
            List<JsonAssertion> json,
            GraphAssertion graph,
            BodyAssertion body,
            List<String> connegAccepts) {
    }

    public record HeaderAssertion(
            Boolean present,
            Boolean absent,
            String equalsValue,
            String matches,
            String contains) {
    }

    public record JsonAssertion(
            String pointer,
            JsonNode equalsValue,
            String matches,
            Boolean exists,
            Integer count) {
    }

    public record GraphAssertion(
            String parseAs,
            List<TriplePattern> contains,
            List<TriplePattern> notContains,
            String isomorphicTo,
            String shacl) {
    }

    /** {@code o} is either an IRI/"_:any" template string or a literal. */
    public record TriplePattern(String s, String p, ObjectTerm o) {
    }

    public record ObjectTerm(String iri, String value, String lang, String datatype) {

        public boolean isLiteral() {
            return iri == null;
        }
    }

    public record BodyAssertion(String equalsRef, String matches, Boolean empty) {
    }
}
