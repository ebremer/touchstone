package com.ebremer.touchstone.core.exec;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code ${...}} placeholder resolution for manifest strings. Variables:
 * {@code run.root}, {@code test.container}, plus step bindings. Unresolvable
 * variables are execution errors, never silent pass-through (schema RATIONALE).
 */
public final class TemplateEngine {

    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z0-9._-]+)}");

    private TemplateEngine() {
    }

    public static String resolve(String template, Map<String, String> vars) {
        if (template == null) {
            return null;
        }
        Matcher m = VAR.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = vars.get(name);
            if (value == null) {
                throw new IllegalStateException("unresolved template variable ${" + name + "} in: " + template);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
