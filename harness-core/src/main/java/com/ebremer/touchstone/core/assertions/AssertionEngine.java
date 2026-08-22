package com.ebremer.touchstone.core.assertions;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.ebremer.touchstone.core.exec.TemplateEngine;
import com.ebremer.touchstone.core.manifest.Manifest;
import com.ebremer.touchstone.core.results.AssertionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;

/**
 * Evaluates a step's declarative expectations against a response — the assertion
 * vocabulary of DESIGN.md paragraph 5.2: status, headers, JSON pointers, graph
 * containment/isomorphism/SHACL, body, and conneg equivalence. Evaluation never
 * throws: anything unevaluable becomes a failed assertion with the reason.
 */
public final class AssertionEngine {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AssertionEngine() {
    }

    public static List<AssertionResult> evaluate(Manifest.Expectations expect, ResponseData data, EvalEnv env) {
        List<AssertionResult> out = new ArrayList<>();
        if (expect.status() != null) {
            out.add(status(expect, data));
        }
        if (expect.headers() != null) {
            expect.headers().forEach((name, assertion) -> headers(out, name, assertion, data, env));
        }
        if (expect.json() != null) {
            json(out, expect.json(), data, env);
        }
        if (expect.graph() != null) {
            graph(out, expect.graph(), data, env);
        }
        if (expect.body() != null) {
            body(out, expect.body(), data, env);
        }
        if (expect.connegAccepts() != null) {
            conneg(out, expect.connegAccepts(), expect.graph(), data, env);
        }
        return List.copyOf(out);
    }

    // ---- status ----

    private static AssertionResult status(Manifest.Expectations expect, ResponseData data) {
        boolean ok = expect.status().contains(data.status());
        return new AssertionResult("status", ok, expect.status().toString(), String.valueOf(data.status()));
    }

    // ---- headers ----

    private static void headers(List<AssertionResult> out, String name, Manifest.HeaderAssertion h,
                                ResponseData data, EvalEnv env) {
        List<String> values = data.headers().allValues(name);
        String first = values.isEmpty() ? null : values.getFirst();
        String actual = values.isEmpty() ? "(absent)" : String.join(", ", values);
        if (h.present() != null) {
            out.add(new AssertionResult("header " + name + " present", !values.isEmpty() == h.present(),
                    h.present() ? "present" : "not required", actual));
        }
        if (h.absent() != null) {
            out.add(new AssertionResult("header " + name + " absent", values.isEmpty() == h.absent(),
                    h.absent() ? "absent" : "may be present", actual));
        }
        if (h.equalsValue() != null) {
            String expected = TemplateEngine.resolve(h.equalsValue(), env.vars());
            out.add(new AssertionResult("header " + name + " equals", expected.equals(first), expected, actual));
        }
        if (h.matches() != null) {
            // ANY value, not just the first — matching what `contains` a few lines below has
            // always done. A field like Link is legally repeated (RFC 8288), and a response
            // carrying rel="up", rel="type" and rel="linkset" as three fields would answer a
            // regex for the third by testing only the first and reporting no match, while
            // printing the matching text in `actual`. The two spellings of "look at this header"
            // disagreeing on which values they look at is a trap, not a feature.
            Pattern pattern = Pattern.compile(h.matches());
            boolean ok = values.stream().anyMatch(v -> pattern.matcher(v).find());
            out.add(new AssertionResult("header " + name + " matches", ok, "regex " + h.matches(), actual));
        }
        if (h.contains() != null) {
            String needle = TemplateEngine.resolve(h.contains(), env.vars());
            boolean ok = values.stream().anyMatch(v -> v.contains(needle));
            out.add(new AssertionResult("header " + name + " contains", ok, "contains " + needle, actual));
        }
    }

    // ---- json pointers ----

    private static void json(List<AssertionResult> out, List<Manifest.JsonAssertion> assertions,
                             ResponseData data, EvalEnv env) {
        JsonNode root;
        try {
            root = JSON.readTree(data.body());
        } catch (Exception e) {
            out.add(AssertionResult.failed("json body parses", "valid JSON", "unparseable: " + e.getMessage()));
            return;
        }
        for (Manifest.JsonAssertion a : assertions) {
            JsonNode node = root.at(a.pointer());
            String at = "json " + a.pointer();
            if (a.exists() != null) {
                out.add(new AssertionResult(at + " exists", !node.isMissingNode() == a.exists(),
                        a.exists() ? "present" : "missing", node.isMissingNode() ? "missing" : node.toString()));
            }
            if (a.equalsValue() != null) {
                JsonNode expected = expand(a.equalsValue(), env.vars());
                out.add(new AssertionResult(at + " equals", !node.isMissingNode() && node.equals(expected),
                        expected.toString(), node.isMissingNode() ? "(missing)" : node.toString()));
            }
            if (a.matches() != null) {
                // A non-value node is matched against its JSON text rather than failing outright.
                // The spec repeatedly allows "X, or an array containing X" — a contained
                // resource's `type` is the example — and the assertion vocabulary has no OR, so
                // without this a manifest could only test one of the two shapes and would call a
                // server conforming in the other way non-conforming. Value nodes keep asText(),
                // so nothing that passed before changes.
                String text = node.isMissingNode() ? null
                        : node.isValueNode() ? node.asText() : node.toString();
                boolean ok = text != null && Pattern.compile(a.matches()).matcher(text).find();
                out.add(new AssertionResult(at + " matches", ok, "regex " + a.matches(),
                        text == null ? "(missing)" : text));
            }
            if (a.count() != null) {
                boolean ok = !node.isMissingNode() && node.size() == a.count();
                out.add(new AssertionResult(at + " count", ok, String.valueOf(a.count()),
                        node.isMissingNode() ? "(missing)" : String.valueOf(node.size())));
            }
        }
    }

    private static JsonNode expand(JsonNode node, Map<String, String> vars) {
        if (node.isTextual()) {
            return TextNode.valueOf(TemplateEngine.resolve(node.asText(), vars));
        }
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            node.properties().forEach(e -> copy.set(e.getKey(), expand(e.getValue(), vars)));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(v -> copy.add(expand(v, vars)));
            return copy;
        }
        return node;
    }

    // ---- graph ----

    private static void graph(List<AssertionResult> out, Manifest.GraphAssertion g,
                              ResponseData data, EvalEnv env) {
        Model model;
        try {
            model = Graphs.parse(data, g.parseAs());
        } catch (Exception e) {
            out.add(AssertionResult.failed("response parses as RDF", "parseable graph", String.valueOf(e.getMessage())));
            return;
        }
        if (g.contains() != null) {
            for (Manifest.TriplePattern t : g.contains()) {
                String rendered = Graphs.render(t, env.vars());
                out.add(new AssertionResult("graph contains " + rendered,
                        Graphs.matches(model, t, env.vars()), "present", summarize(model)));
            }
        }
        if (g.notContains() != null) {
            for (Manifest.TriplePattern t : g.notContains()) {
                String rendered = Graphs.render(t, env.vars());
                out.add(new AssertionResult("graph does not contain " + rendered,
                        !Graphs.matches(model, t, env.vars()), "absent", summarize(model)));
            }
        }
        if (g.isomorphicTo() != null) {
            try {
                Model fixture = Graphs.parseFile(env.manifestDir().resolve(g.isomorphicTo()), data.uri());
                out.add(new AssertionResult("graph isomorphic to " + g.isomorphicTo(),
                        model.isIsomorphicWith(fixture),
                        fixture.size() + " triples", model.size() + " triples"));
            } catch (Exception e) {
                out.add(AssertionResult.failed("graph isomorphic to " + g.isomorphicTo(),
                        "comparable fixture", String.valueOf(e.getMessage())));
            }
        }
        if (g.shacl() != null) {
            try {
                Shapes shapes = Shapes.parse(Graphs.loadGraph(env.manifestDir().resolve(g.shacl())));
                ValidationReport report = ShaclValidator.get().validate(shapes, model.getGraph());
                String detail = report.conforms() ? "conforms"
                        : report.getEntries().stream()
                                .map(entry -> entry.message() + " at " + entry.focusNode())
                                .reduce((a, b) -> a + "; " + b).orElse("nonconformant");
                out.add(new AssertionResult("graph conforms to shapes " + g.shacl(),
                        report.conforms(), "conforms", detail));
            } catch (Exception e) {
                out.add(AssertionResult.failed("graph conforms to shapes " + g.shacl(),
                        "evaluable shapes", String.valueOf(e.getMessage())));
            }
        }
    }

    private static String summarize(Model model) {
        return model.size() + " triples in response graph";
    }

    // ---- body ----

    private static void body(List<AssertionResult> out, Manifest.BodyAssertion b,
                             ResponseData data, EvalEnv env) {
        if (b.empty() != null) {
            boolean empty = data.body().length == 0;
            out.add(new AssertionResult("body empty", empty == b.empty(),
                    b.empty() ? "empty" : "non-empty", data.body().length + " bytes"));
        }
        if (b.matches() != null) {
            boolean ok = Pattern.compile(b.matches()).matcher(data.bodyText()).find();
            out.add(new AssertionResult("body matches", ok, "regex " + b.matches(),
                    Redacted.preview(data)));
        }
        if (b.equalsRef() != null) {
            try {
                byte[] expected = Files.readAllBytes(env.manifestDir().resolve(b.equalsRef()));
                out.add(new AssertionResult("body equals " + b.equalsRef(),
                        Arrays.equals(expected, data.body()),
                        expected.length + " bytes", data.body().length + " bytes"));
            } catch (Exception e) {
                out.add(AssertionResult.failed("body equals " + b.equalsRef(),
                        "readable fixture", String.valueOf(e.getMessage())));
            }
        }
    }

    /** Small helper to keep body previews short in assertion actuals. */
    private static final class Redacted {
        static String preview(ResponseData data) {
            String text = data.bodyText();
            return text.length() > 120 ? text.substring(0, 120) + "..." : text;
        }
    }

    // ---- conneg equivalence ----

    private static void conneg(List<AssertionResult> out, List<String> accepts,
                               Manifest.GraphAssertion graphHint, ResponseData data, EvalEnv env) {
        if (env.refetchWithAccept() == null) {
            out.add(AssertionResult.failed("conneg equivalence", "executor-backed refetch",
                    "no refetch available in this evaluation context"));
            return;
        }
        try {
            Model reference = null;
            String referenceType = null;
            for (String accept : accepts) {
                ResponseData variant = env.refetchWithAccept().apply(accept);
                if (variant.status() / 100 != 2) {
                    out.add(AssertionResult.failed("conneg " + accept + " retrievable",
                            "2xx", String.valueOf(variant.status())));
                    return;
                }
                Model model = Graphs.parse(variant, accept);
                if (reference == null) {
                    reference = model;
                    referenceType = accept;
                } else {
                    out.add(new AssertionResult(
                            "conneg graphs equivalent: " + referenceType + " vs " + accept,
                            reference.isIsomorphicWith(model),
                            "isomorphic", reference.size() + " vs " + model.size() + " triples"));
                }
            }
        } catch (Exception e) {
            out.add(AssertionResult.failed("conneg equivalence", "comparable representations",
                    String.valueOf(e.getMessage())));
        }
    }
}
