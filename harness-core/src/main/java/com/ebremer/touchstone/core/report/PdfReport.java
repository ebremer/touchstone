package com.ebremer.touchstone.core.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.results.RunResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * {@code report.pdf} — the printable report.
 *
 * <p>Drawn with PDFBox from {@link HtmlReport#model}, the same map {@code report.html} and
 * {@code report.json} are built from, so all three state one verdict. Rendering the HTML
 * instead would have been less code, but the maintained HTML-to-PDF renderers want XHTML and
 * {@code report.ftl} is HTML5; the ones that parse HTML5 are a dormant project and a
 * third-party fork. Drawing from the model needs no parser and cannot drift.
 *
 * <p>Text is restricted to what the Standard 14 fonts can actually encode (see {@link #wa}).
 * The catalog summaries are full of typographic dashes and quotes, and PDFBox throws on a
 * character its encoding has no glyph for — one em dash in one requirement would otherwise
 * take down the whole report.
 */
public final class PdfReport {

    private static final PDRectangle PAGE = PDRectangle.LETTER;
    private static final float MARGIN = 48f;
    private static final float LEADING = 13f;
    private static final float BODY = 9f;

    /** Standard 14 fonts are WinAnsi; anything outside CP1252 has no glyph and PDFBox throws. */
    private static final CharsetEncoder WIN_ANSI = Charset.forName("windows-1252").newEncoder();

    private PdfReport() {
    }

    public static void write(RunResult run, List<Requirement> catalog, Path file) {
        Map<String, Object> model = HtmlReport.model(run, catalog);
        try (PDDocument doc = new PDDocument()) {
            Canvas c = new Canvas(doc);
            header(c, model);
            totals(c, model);
            levels(c, model);
            tests(c, model);
            requirements(c, model);
            c.finish();
            doc.save(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write PDF report " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void header(Canvas c, Map<String, Object> model) throws IOException {
        Map<String, Object> run = (Map<String, Object>) model.get("run");
        c.text("Touchstone conformance report", c.bold, 17f);
        c.gap(4f);
        c.text("target " + str(run, "targetId") + "  -  " + str(run, "targetBaseUrl"), c.plain, 10f);
        c.text("run " + str(run, "runId") + "  -  started " + str(run, "startedAt"), c.plain, 10f);
        c.gap(10f);

        boolean conformant = Boolean.TRUE.equals(run.get("conformant"));
        c.banner(conformant ? "CONFORMANT" : "NOT CONFORMANT", conformant);
        c.gap(6f);
        c.text("Conformance is decided by MUST-level failures; this run has "
                + str(run, "mustFailures") + ".", c.plain, 9f);
        c.gap(14f);
    }

    @SuppressWarnings("unchecked")
    private static void totals(Canvas c, Map<String, Object> model) throws IOException {
        Map<String, Object> run = (Map<String, Object>) model.get("run");
        c.text("Results", c.bold, 12f);
        c.gap(4f);
        float[] cols = {110f, 70f, 110f, 70f};
        c.row(cols, new String[]{"passed", str(run, "passed"), "errors", str(run, "errors")}, false);
        c.row(cols, new String[]{"failed", str(run, "failed"), "skipped", str(run, "skipped")}, false);
        c.gap(14f);
    }

    @SuppressWarnings("unchecked")
    private static void levels(Canvas c, Map<String, Object> model) throws IOException {
        List<Map<String, Object>> levels = (List<Map<String, Object>>) model.get("levels");
        if (levels == null || levels.isEmpty()) {
            return;
        }
        c.text("Requirement coverage by level", c.bold, 12f);
        c.gap(4f);
        float[] cols = {110f, 90f, 90f, 90f};
        c.row(cols, new String[]{"level", "in catalog", "covered", "failing"}, true);
        for (Map<String, Object> l : levels) {
            c.row(cols, new String[]{str(l, "level"), str(l, "total"), str(l, "covered"), str(l, "failed")}, false);
        }
        c.gap(14f);
    }

    @SuppressWarnings("unchecked")
    private static void tests(Canvas c, Map<String, Object> model) throws IOException {
        List<Map<String, Object>> tests = (List<Map<String, Object>>) model.get("tests");
        if (tests == null || tests.isEmpty()) {
            return;
        }
        c.text("Tests", c.bold, 12f);
        c.gap(4f);
        float[] cols = {300f, 90f, 90f};
        c.row(cols, new String[]{"test", "outcome", "duration"}, true);
        for (Map<String, Object> t : tests) {
            c.row(cols, new String[]{str(t, "id"), str(t, "outcome"), str(t, "durationMillis") + " ms"}, false);
            String detail = str(t, "detail");
            if (!detail.isBlank()) {
                // The reason a test did not pass is the whole point of printing it.
                c.wrapped(detail, c.plain, 8f, MARGIN + 14f);
            }
        }
        c.gap(14f);
    }

    @SuppressWarnings("unchecked")
    private static void requirements(Canvas c, Map<String, Object> model) throws IOException {
        List<Map<String, Object>> reqs = (List<Map<String, Object>>) model.get("requirements");
        if (reqs == null || reqs.isEmpty()) {
            return;
        }
        c.text("Requirement matrix", c.bold, 12f);
        c.gap(4f);
        float[] cols = {250f, 60f, 80f, 90f};
        c.row(cols, new String[]{"requirement", "level", "result", "tests"}, true);
        for (Map<String, Object> r : reqs) {
            List<?> linked = (List<?>) r.get("tests");
            c.row(cols, new String[]{
                    str(r, "slug"), str(r, "level"), str(r, "result"),
                    linked == null ? "0" : String.valueOf(linked.size())}, false);
        }
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * The text a Standard 14 font can render. Characters CP1252 has no code for become '?', and
     * newlines and tabs become spaces so a multi-line failure detail stays on the line it was
     * placed on rather than corrupting the content stream.
     */
    static String wa(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t') {
                out.append(' ');
            } else if (ch < 0x20) {
                out.append(' ');
            } else if (WIN_ANSI.canEncode(ch)) {
                out.append(ch);
            } else {
                out.append('?');
            }
        }
        return out.toString();
    }

    /** A page with a cursor: writes top-down and starts a new page when it runs out of room. */
    private static final class Canvas {

        private final PDDocument doc;
        private final PDFont plain = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private PDPageContentStream cs;
        private float y;
        private int pages;

        Canvas(PDDocument doc) throws IOException {
            this.doc = doc;
            newPage();
        }

        private void newPage() throws IOException {
            if (cs != null) {
                cs.close();
            }
            PDPage page = new PDPage(PAGE);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            pages++;
            y = PAGE.getHeight() - MARGIN;
        }

        private void ensure(float needed) throws IOException {
            if (y - needed < MARGIN) {
                newPage();
            }
        }

        void gap(float h) {
            y -= h;
        }

        void text(String s, PDFont font, float size) throws IOException {
            ensure(size + 4f);
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(MARGIN, y);
            cs.showText(wa(s));
            cs.endText();
            y -= size + 4f;
        }

        /** Wraps to the page width, indented, for prose that will not fit on one line. */
        void wrapped(String s, PDFont font, float size, float x) throws IOException {
            float width = PAGE.getWidth() - x - MARGIN;
            for (String line : wrap(wa(s), font, size, width)) {
                ensure(size + 2f);
                cs.beginText();
                cs.setFont(font, size);
                cs.newLineAtOffset(x, y);
                cs.showText(line);
                cs.endText();
                y -= size + 2f;
            }
        }

        void row(float[] cols, String[] cells, boolean head) throws IOException {
            ensure(LEADING);
            PDFont font = head ? bold : plain;
            float x = MARGIN;
            for (int i = 0; i < cells.length && i < cols.length; i++) {
                cs.beginText();
                cs.setFont(font, BODY);
                cs.newLineAtOffset(x, y);
                cs.showText(clip(wa(cells[i]), font, BODY, cols[i] - 6f));
                cs.endText();
                x += cols[i];
            }
            y -= LEADING;
            if (head) {
                cs.setLineWidth(0.5f);
                cs.moveTo(MARGIN, y + 4f);
                cs.lineTo(PAGE.getWidth() - MARGIN, y + 4f);
                cs.stroke();
                y -= 2f;
            }
        }

        /** The verdict, in a filled bar so it is the first thing seen. */
        void banner(String label, boolean good) throws IOException {
            ensure(24f);
            if (good) {
                cs.setNonStrokingColor(0.886f, 0.957f, 0.898f);
            } else {
                cs.setNonStrokingColor(0.984f, 0.882f, 0.882f);
            }
            cs.addRect(MARGIN, y - 16f, PAGE.getWidth() - 2 * MARGIN, 22f);
            cs.fill();
            cs.setNonStrokingColor(good ? 0.078f : 0.561f, good ? 0.376f : 0.086f, good ? 0.122f : 0.086f);
            cs.beginText();
            cs.setFont(bold, 13f);
            cs.newLineAtOffset(MARGIN + 8f, y - 10f);
            cs.showText(wa(label));
            cs.endText();
            cs.setNonStrokingColor(0f, 0f, 0f);
            y -= 26f;
        }

        void finish() throws IOException {
            if (cs != null) {
                cs.close();
                cs = null;
            }
        }

        private List<String> wrap(String s, PDFont font, float size, float width) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : s.split(" +")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (textWidth(candidate, font, size) > width && !line.isEmpty()) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
            }
            return lines;
        }

        private String clip(String s, PDFont font, float size, float width) throws IOException {
            if (textWidth(s, font, size) <= width) {
                return s;
            }
            String out = s;
            while (!out.isEmpty() && textWidth(out + "...", font, size) > width) {
                out = out.substring(0, out.length() - 1);
            }
            return out + "...";
        }

        private float textWidth(String s, PDFont font, float size) throws IOException {
            return font.getStringWidth(s) / 1000f * size;
        }
    }
}
