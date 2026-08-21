package com.ebremer.touchstone.core.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.results.RunResult;

/**
 * {@code report.md} — the report as Markdown, for the places a conformance result actually
 * gets read: a pull request, an issue, a wiki, a terminal.
 *
 * <p>Rendered from {@link HtmlReport#model}, like {@code report.html}, {@code report.json} and
 * {@code report.pdf}, so all four state one verdict.
 *
 * <p>It keeps the one thing the PDF cannot: links. Every requirement is a link to its section
 * of the specification, which is what turns "this MUST failed" into something a reader can act
 * on without going and looking the clause up.
 */
public final class MarkdownReport {

    private MarkdownReport() {
    }

    public static void write(RunResult run, List<Requirement> catalog, Path file) {
        Map<String, Object> model = HtmlReport.model(run, catalog);
        try (Writer out = Files.newBufferedWriter(file)) {
            render(model, out);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write Markdown report " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void render(Map<String, Object> model, Writer out) throws IOException {
        Map<String, Object> run = (Map<String, Object>) model.get("run");
        boolean conformant = Boolean.TRUE.equals(run.get("conformant"));

        out.write("# Touchstone conformance report\n\n");
        out.write("**" + (conformant ? "CONFORMANT" : "NOT CONFORMANT") + "** — conformance is decided by "
                + "MUST-level failures; this run has " + str(run, "mustFailures") + ".\n\n");

        out.write("| | |\n|---|---|\n");
        out.write(kv("target", str(run, "targetId")));
        out.write(kv("base URL", "`" + str(run, "targetBaseUrl") + "`"));
        out.write(kv("run", "`" + str(run, "runId") + "`"));
        out.write(kv("started", str(run, "startedAt")));
        out.write("\n");

        out.write("## Results\n\n");
        out.write("| passed | failed | errors | skipped |\n|---:|---:|---:|---:|\n");
        out.write("| " + str(run, "passed") + " | " + str(run, "failed") + " | "
                + str(run, "errors") + " | " + str(run, "skipped") + " |\n\n");

        List<Map<String, Object>> levels = (List<Map<String, Object>>) model.get("levels");
        if (levels != null && !levels.isEmpty()) {
            out.write("## Requirement coverage by level\n\n");
            out.write("| level | in catalog | covered | failing |\n|---|---:|---:|---:|\n");
            for (Map<String, Object> l : levels) {
                out.write("| " + esc(str(l, "level")) + " | " + str(l, "total") + " | "
                        + str(l, "covered") + " | " + str(l, "failed") + " |\n");
            }
            out.write("\n");
        }

        List<Map<String, Object>> tests = (List<Map<String, Object>>) model.get("tests");
        if (tests != null && !tests.isEmpty()) {
            out.write("## Tests\n\n");
            out.write("| test | outcome | duration |\n|---|---|---:|\n");
            for (Map<String, Object> t : tests) {
                out.write("| `" + esc(str(t, "id")) + "` | " + esc(str(t, "outcome")) + " | "
                        + str(t, "durationMillis") + " ms |\n");
            }
            out.write("\n");

            // Why a test did not pass is the part a reader came for, and it is multi-line —
            // a fenced block keeps it readable instead of mangling the table it came from.
            boolean any = false;
            for (Map<String, Object> t : tests) {
                String detail = str(t, "detail");
                if (detail.isBlank()) {
                    continue;
                }
                if (!any) {
                    out.write("### Detail\n\n");
                    any = true;
                }
                out.write("**`" + esc(str(t, "id")) + "`** — " + esc(str(t, "outcome")) + "\n\n");
                out.write("```\n" + detail.replace("```", "` ` `") + "\n```\n\n");
            }
        }

        List<Map<String, Object>> reqs = (List<Map<String, Object>>) model.get("requirements");
        if (reqs != null && !reqs.isEmpty()) {
            out.write("## Requirement matrix\n\n");
            out.write("| requirement | level | result | tests |\n|---|---|---|---:|\n");
            for (Map<String, Object> r : reqs) {
                String slug = esc(str(r, "slug"));
                String section = str(r, "section");
                String name = section.isBlank() ? "`" + slug + "`" : "[`" + slug + "`](" + section + ")";
                List<?> linked = (List<?>) r.get("tests");
                out.write("| " + name + " | " + esc(str(r, "level")) + " | " + esc(str(r, "result"))
                        + " | " + (linked == null ? 0 : linked.size()) + " |\n");
            }
            out.write("\n");
        }
    }

    private static String kv(String k, String v) {
        return "| " + k + " | " + v + " |\n";
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    /** A pipe or a newline in a cell ends the cell; neither is allowed to break the table. */
    private static String esc(String s) {
        return s.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }
}
