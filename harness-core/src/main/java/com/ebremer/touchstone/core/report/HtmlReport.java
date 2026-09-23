package com.ebremer.touchstone.core.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.Results;
import com.ebremer.touchstone.core.results.RunResult;
import com.ebremer.touchstone.core.results.TestResult;
import freemarker.template.Configuration;
import freemarker.template.TemplateException;

/**
 * FreeMarker HTML coverage + results report (DESIGN.md section 5.5): a per-requirement matrix
 * with MUST/SHOULD/MAY rollups, where every test links to the requirements it verifies and
 * every requirement links to its spec section.
 *
 * <p>{@link #model} is the one computation of what a run found, and the JSON, Markdown and PDF
 * reports render it too. Its verdict is EXECUTION.md section 9's: each test has one level, and
 * only a MUST test that failed or could not tell makes the target non-conformant.
 */
public final class HtmlReport {

    private static final List<String> LEVEL_ORDER = List.of("MUST", "SHOULD", "MAY");
    private static final Configuration FREEMARKER = configure();

    private HtmlReport() {
    }

    public static void write(RunResult run, List<Requirement> catalog, Path file) {
        try (Writer out = Files.newBufferedWriter(file)) {
            FREEMARKER.getTemplate("report.ftl").process(model(run, catalog), out);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write HTML report " + file, e);
        } catch (TemplateException e) {
            throw new IllegalStateException("report template failed", e);
        }
    }

    private static Configuration configure() {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_33);
        cfg.setClassLoaderForTemplateLoading(HtmlReport.class.getClassLoader(), "touchstone");
        cfg.setDefaultEncoding("UTF-8");
        return cfg;
    }

    static Map<String, Object> model(RunResult run, List<Requirement> catalog) {
        Map<String, List<Map<String, Object>>> testsByRequirement = new HashMap<>();
        List<Map<String, Object>> tests = new ArrayList<>();
        Map<String, Requirement> catalogIndex = new HashMap<>();
        catalog.forEach(r -> catalogIndex.put(r.iri(), r));

        for (TestResult test : run.results()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", test.testId());
            t.put("anchor", test.testId().replace('/', '-').replace('#', '-'));
            t.put("label", test.label() == null ? "" : test.label());
            t.put("level", test.level() == null ? "" : test.level());
            t.put("outcome", test.outcome().earl());
            t.put("durationMillis", test.durationMillis());
            List<Map<String, Object>> reqRefs = new ArrayList<>();
            for (String iri : test.requirements()) {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("iri", iri);
                ref.put("slug", slug(iri));
                reqRefs.add(ref);
                testsByRequirement.computeIfAbsent(iri, k -> new ArrayList<>()).add(t);
            }
            t.put("requirements", reqRefs);
            t.put("reason", test.reason() == null ? "" : test.reason());
            t.put("detail", test.outcome() == Outcome.PASSED ? "" : Results.describe(test));
            tests.add(t);
        }

        List<Map<String, Object>> requirements = new ArrayList<>();
        Map<String, long[]> levelStats = new TreeMap<>((a, b) -> Integer.compare(levelRank(a), levelRank(b)));
        for (Requirement req : catalog) {
            List<Map<String, Object>> linked = testsByRequirement.getOrDefault(req.iri(), List.of());
            String result = requirementResult(linked);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("iri", req.iri());
            row.put("slug", slug(req.iri()));
            row.put("level", req.level());
            row.put("summary", req.summary() == null ? "" : req.summary());
            row.put("section", req.section());
            row.put("status", req.status() == null ? "" : req.status());
            row.put("tests", linked);
            row.put("result", result);
            requirements.add(row);

            long[] stats = levelStats.computeIfAbsent(req.level(), k -> new long[3]);
            stats[0]++;
            if (!linked.isEmpty()) {
                stats[1]++;
            }
            if ("FAIL".equals(result)) {
                stats[2]++;
            }
        }

        List<Map<String, Object>> levels = new ArrayList<>();
        levelStats.forEach((level, stats) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("level", level);
            row.put("total", stats[0]);
            row.put("covered", stats[1]);
            row.put("failed", stats[2]);
            levels.add(row);
        });

        Map<String, Object> runInfo = new LinkedHashMap<>();
        runInfo.put("runId", run.runId());
        runInfo.put("targetId", run.targetId());
        runInfo.put("targetBaseUrl", run.targetBaseUrl());
        runInfo.put("startedAt", run.startedAt());
        runInfo.put("tests", run.results().size());
        runInfo.put("passed", run.count(Outcome.PASSED));
        runInfo.put("failed", run.count(Outcome.FAILED));
        runInfo.put("cantTell", run.count(Outcome.CANT_TELL));
        runInfo.put("inapplicable", run.count(Outcome.INAPPLICABLE));
        runInfo.put("mustFailures", run.mustFailures().size());
        runInfo.put("advisoryFailures", run.results().stream()
                .filter(r -> !r.decidesConformance() && r.outcome() == Outcome.FAILED).count());
        runInfo.put("mustInapplicable", run.results().stream()
                .filter(r -> r.decidesConformance() && r.outcome() == Outcome.INAPPLICABLE).count());
        runInfo.put("conformant", run.conformant());

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("run", runInfo);
        model.put("levels", levels);
        model.put("requirements", requirements);
        model.put("tests", tests);
        return model;
    }

    /**
     * A requirement's row: FAIL if a test citing it failed or could not tell, PASS if one
     * passed, INAPPLICABLE if every test citing it was, UNCOVERED if none cites it.
     */
    private static String requirementResult(List<Map<String, Object>> linkedTests) {
        if (linkedTests.isEmpty()) {
            return "UNCOVERED";
        }
        boolean anyPassed = false;
        for (Map<String, Object> t : linkedTests) {
            String outcome = (String) t.get("outcome");
            if (Outcome.FAILED.earl().equals(outcome) || Outcome.CANT_TELL.earl().equals(outcome)) {
                return "FAIL";
            }
            if (Outcome.PASSED.earl().equals(outcome)) {
                anyPassed = true;
            }
        }
        return anyPassed ? "PASS" : "INAPPLICABLE";
    }

    private static int levelRank(String level) {
        int i = LEVEL_ORDER.indexOf(level);
        return i < 0 ? LEVEL_ORDER.size() : i;
    }

    private static String slug(String iri) {
        return iri.substring(iri.lastIndexOf('/') + 1);
    }
}
