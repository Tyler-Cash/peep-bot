package dev.tylercash.event.rewind.eval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/** Builds a markdown report from a sequence of (config, case, prediction) tuples. */
public final class ClassifierEvalReport {

    public record Run(String prompt, double temperature, String caseName, String expected, String predicted) {}

    private final List<Run> runs;

    public ClassifierEvalReport(List<Run> runs) {
        this.runs = runs;
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Classifier Eval Report\n\n");
        renderAccuracyTable(sb);
        sb.append("\n");
        renderConfusionMatrix(sb);
        sb.append("\n");
        renderWorstCases(sb);
        sb.append("\n");
        renderRecommendation(sb);
        return sb.toString();
    }

    private void renderAccuracyTable(StringBuilder sb) {
        sb.append("## Accuracy by config\n\n");
        sb.append("| Prompt | Temperature | Accuracy |\n");
        sb.append("|---|---|---|\n");
        Map<ConfigKey, List<Run>> grouped = groupByConfig();
        grouped.forEach((cfg, cfgRuns) -> {
            long correct = cfgRuns.stream()
                    .filter(r -> r.expected.equalsIgnoreCase(r.predicted))
                    .count();
            double acc = 100.0 * correct / cfgRuns.size();
            sb.append(String.format(
                    "| %s | %.2f | %.1f%% (%d/%d) |%n", cfg.prompt(), cfg.temperature(), acc, correct, cfgRuns.size()));
        });
    }

    private void renderConfusionMatrix(StringBuilder sb) {
        sb.append("## Confusion matrix (best-accuracy config)\n\n");
        ConfigKey best = bestConfig();
        if (best == null) {
            sb.append("(no runs)\n");
            return;
        }
        sb.append(String.format("Prompt: `%s`, temperature: `%.2f`%n%n", best.prompt(), best.temperature()));
        SortedSet<String> labels = new TreeSet<>();
        Map<String, Map<String, Integer>> matrix = new TreeMap<>();
        for (Run r : runs) {
            if (!r.prompt.equals(best.prompt()) || r.temperature != best.temperature()) continue;
            labels.add(r.expected);
            labels.add(r.predicted);
            matrix.computeIfAbsent(r.expected, k -> new TreeMap<>()).merge(r.predicted, 1, Integer::sum);
        }
        sb.append("| expected \\\\ predicted |");
        for (String l : labels) sb.append(" ").append(l).append(" |");
        sb.append("\n|---|").append("---|".repeat(labels.size())).append("\n");
        for (String row : labels) {
            sb.append("| ").append(row).append(" |");
            for (String col : labels) {
                int n = matrix.getOrDefault(row, Map.of()).getOrDefault(col, 0);
                sb.append(" ").append(n == 0 ? "·" : Integer.toString(n)).append(" |");
            }
            sb.append("\n");
        }
    }

    private void renderWorstCases(StringBuilder sb) {
        sb.append("## Worst 10 cases (best config)\n\n");
        ConfigKey best = bestConfig();
        if (best == null) return;
        record CaseStat(String name, String expected, long wrong, long total) {}
        Map<String, long[]> stats = new HashMap<>();
        Map<String, String> expectedByCase = new HashMap<>();
        for (Run r : runs) {
            if (!r.prompt.equals(best.prompt()) || r.temperature != best.temperature()) continue;
            expectedByCase.put(r.caseName, r.expected);
            long[] s = stats.computeIfAbsent(r.caseName, k -> new long[] {0, 0});
            s[1]++;
            if (!r.expected.equalsIgnoreCase(r.predicted)) s[0]++;
        }
        List<CaseStat> ordered = new ArrayList<>();
        stats.forEach((name, s) -> ordered.add(new CaseStat(name, expectedByCase.get(name), s[0], s[1])));
        ordered.sort((a, b) -> Long.compare(b.wrong(), a.wrong()));
        sb.append("| Event | Expected | Wrong / Total |\n|---|---|---|\n");
        ordered.stream()
                .limit(10)
                .forEach(c -> sb.append(
                        String.format("| %s | %s | %d/%d |%n", c.name(), c.expected(), c.wrong(), c.total())));
    }

    private void renderRecommendation(StringBuilder sb) {
        sb.append("## Recommendation\n\n");
        ConfigKey best = bestConfig();
        if (best == null) {
            sb.append("(no runs)\n");
        } else {
            sb.append(String.format(
                    "Prompt `%s` at temperature `%.2f` produced the highest accuracy.%n",
                    best.prompt(), best.temperature()));
        }
    }

    private record ConfigKey(String prompt, double temperature) {}

    private Map<ConfigKey, List<Run>> groupByConfig() {
        Map<ConfigKey, List<Run>> out = new TreeMap<>((a, b) -> {
            int p = a.prompt().compareTo(b.prompt());
            return p != 0 ? p : Double.compare(a.temperature(), b.temperature());
        });
        for (Run r : runs) {
            out.computeIfAbsent(new ConfigKey(r.prompt, r.temperature), k -> new ArrayList<>())
                    .add(r);
        }
        return out;
    }

    private ConfigKey bestConfig() {
        return groupByConfig().entrySet().stream()
                .max((a, b) -> Double.compare(accuracy(a.getValue()), accuracy(b.getValue())))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static double accuracy(List<Run> runs) {
        if (runs.isEmpty()) return 0;
        long correct = runs.stream()
                .filter(r -> r.expected().equalsIgnoreCase(r.predicted()))
                .count();
        return (double) correct / runs.size();
    }
}
