package com.ebremer.touchstone.core.engine;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.ebremer.touchstone.core.results.AssertionResult;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The matching rules of EXECUTION.md sections 7 and 8, for judges of HTTP messages other than
 * the engine: the client service applies them to the requests clients send (OBSERVATION.md
 * section 5). One implementation serves both, so a term means the same on a request as on a
 * response.
 *
 * <p>Client rules have no variables, so their values are taken literally; an expression is a
 * definition error the lint already refuses. Each check returns null when every expectation
 * holds, or the first that does not, as a failed {@link AssertionResult}.
 */
public final class Matching {

    private static final Templates.Resolver LITERAL = expression -> {
        throw new IllegalArgumentException("client rules have no variables: ${" + expression + "}");
    };

    private Matching() {
    }

    /** EXECUTION.md section 7.1. */
    public static boolean status(JsonNode spec, int status) {
        return MessageChecks.statusMatches(spec, status);
    }

    /** The statuses a {@code statusCode} value accepts, for a report. */
    public static String statusText(JsonNode spec) {
        return MessageChecks.statusText(spec);
    }

    /** A media type's essence (EXECUTION.md section 8): type/subtype in lower case, parameters dropped; null for null. */
    public static String essence(String mediaType) {
        return Headers.essence(mediaType);
    }

    /** Whether a regular expression of EXECUTION.md section 8 finds a match in {@code text}. */
    public static boolean find(String regex, String text) {
        return Pattern.compile(regex).matcher(text).find();
    }

    /** {@code linkHeaders} (EXECUTION.md section 7.4) on a message's Link field lines; targets resolve against {@code base}. */
    public static AssertionResult links(JsonNode expectations, List<String> fieldLines, URI base) {
        List<AssertionResult> results = new ArrayList<>();
        return MessageChecks.links(expectations, fieldLines, base, LITERAL, (k, v) -> { }, results)
                ? null : results.getLast();
    }

    /** {@code otherHeaders} (EXECUTION.md section 7.5); {@code header} returns a field's lines by name, case-insensitively. */
    public static AssertionResult headers(JsonNode expectations, Function<String, List<String>> header) {
        List<AssertionResult> results = new ArrayList<>();
        return MessageChecks.headers(expectations, header, LITERAL, (k, v) -> { }, results) ? null : results.getLast();
    }

    /** {@code json} (EXECUTION.md section 7.8) on a parsed body; {@code equalsIri} resolves against {@code base}. */
    public static AssertionResult json(JsonNode expectations, JsonNode root, URI base) {
        List<AssertionResult> results = new ArrayList<>();
        return JsonChecks.check("json", root, expectations, LITERAL, base, (k, v) -> { }, results)
                ? null : results.getLast();
    }
}
