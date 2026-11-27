package dev.tremor.project;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Replaces {@code ${name}} expressions, following nested references and stopping on cycles. */
final class PropertyInterpolator {

    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([^}]+)}");

    private final Map<String, String> values;

    PropertyInterpolator(Map<String, String> values) {
        this.values = values;
    }

    /** Returns the interpolated text; unknown expressions are left as they are. */
    String interpolate(String text) {
        return text == null ? null : interpolate(text, new HashSet<>());
    }

    static boolean isFullyResolved(String text) {
        return text != null && !EXPRESSION.matcher(text).find();
    }

    private String interpolate(String text, Set<String> visiting) {
        Matcher m = EXPRESSION.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = values.get(name);
            String replacement;
            if (value == null || !visiting.add(name)) {
                replacement = m.group(0);
            } else {
                replacement = interpolate(value, visiting);
                visiting.remove(name);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }
}
