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
 * FreeMarker HTML coverage + results report (DESIGN.md paragraph 5.5): a
 * per-requirement matrix with MUST/SHOULD/MAY rollups, where every test links to
 * the requirements it verifies and every requirement links to its spec section.
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
        long mustFailures = 0;
        Map<String, Requirement> catalogIndex = new HashMap<>();
        catalog.forEach(r -> catalogIndex.put(r.iri(), r));

        for (TestResult test : run.results()) {
            Map<String, Object> t = new LinkedHashMap<>();
            String anchor = test.manifestId().replace('/', '-');
            t.put("id", test.manifestId());
            t.put("anchor", anchor);
            t.put("outcome", test.outcome().name());
            t.put("durationMillis", test.durationMillis());
            List<Map<String, Object>> reqRefs = new ArrayList<>();
            boolean must = false;
            for (String iri : test.requirements()) {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("iri", iri);
                ref.put("slug", slug(iri));
                reqRefs.add(ref);
                Requirement req = catalogIndex.get(iri);
                if (req != null && "MUST".equals(req.level())) {
                    must = true;
                }
                testsByRequirement.computeIfAbsent(iri, k -> new ArrayList<>()).add(t);
            }
            t.put("requirements", reqRefs);
            boolean bad = test.outcome() == Outcome.FAILED || test.outcome() == Outcome.ERROR;
            t.put("detail", bad || test.outcome() == Outcome.SKIPPED ? Results.describe(test) : "");
            if (bad && must) {
                mustFailures++;
            }
            tests.add(t);
        }
        tests.sort((a, b) -> ((String) a.get("id")).compareTo((String) b.get("id")));

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
        runInfo.put("passed", run.count(Outcome.PASSED));
        runInfo.put("failed", run.count(Outcome.FAILED));
        runInfo.put("errors", run.count(Outcome.ERROR));
        runInfo.put("skipped", run.count(Outcome.SKIPPED));
        runInfo.put("mustFailures", mustFailures);
        runInfo.put("conformant", mustFailures == 0);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("run", runInfo);
        model.put("levels", levels);
        model.put("requirements", requirements);
        model.put("tests", tests);
        return model;
    }

    private static String requirementResult(List<Map<String, Object>> linkedTests) {
        if (linkedTests.isEmpty()) {
            return "UNCOVERED";
        }
        boolean anyPassed = false;
        for (Map<String, Object> t : linkedTests) {
            String outcome = (String) t.get("outcome");
            if ("FAILED".equals(outcome) || "ERROR".equals(outcome)) {
                return "FAIL";
            }
            if ("PASSED".equals(outcome)) {
                anyPassed = true;
            }
        }
        return anyPassed ? "PASS" : "SKIPPED";
    }

    private static int levelRank(String level) {
        int i = LEVEL_ORDER.indexOf(level);
        return i < 0 ? LEVEL_ORDER.size() : i;
    }

    private static String slug(String iri) {
        return iri.substring(iri.lastIndexOf('/') + 1);
    }
}
