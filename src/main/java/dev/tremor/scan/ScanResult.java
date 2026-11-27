package dev.tremor.scan;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Everything found by scanning one jar or classes directory.
 *
 * @param usages references to the classes of interest (and to local classes that extend them)
 * @param classes every class in the scanned code, keyed by internal name
 * @param problems classes that could not be read and were skipped
 */
public record ScanResult(List<Usage> usages, Map<String, ScannedClass> classes, List<String> problems)
        implements Serializable {

    public ScanResult {
        usages = List.copyOf(usages);
        classes = Map.copyOf(classes);
        problems = List.copyOf(problems);
    }

    public static ScanResult empty() {
        return new ScanResult(List.of(), Map.of(), List.of());
    }
}
