package dev.tylercash.event.rewind.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClassifierEvalReportTest {

    @Test
    void corpusLoadsThirtyCases() {
        List<EvalCase> cases = EvalCaseLoader.load("classifier-eval/corpus.yaml");
        assertThat(cases).hasSize(30);
        assertThat(cases).allSatisfy(c -> {
            assertThat(c.getName()).isNotBlank();
            assertThat(c.getExpected()).isNotBlank();
        });
    }

    @Test
    void reportRendersAccuracySection() {
        List<ClassifierEvalReport.Run> runs = List.of(
                new ClassifierEvalReport.Run("v2", 0.1, "Beach day at Maroubra", "Outdoor", "Outdoor"),
                new ClassifierEvalReport.Run("v2", 0.1, "Beach day at Maroubra", "Outdoor", "Outdoor"),
                new ClassifierEvalReport.Run("v2", 0.1, "Comedy night with the local stand-up crew", "Show", "Trivia"));

        String md = new ClassifierEvalReport(runs).render();

        assertThat(md).contains("## Accuracy by config");
        assertThat(md).contains("v2");
        assertThat(md).contains("66.7%");
        assertThat(md).contains("## Confusion matrix");
        assertThat(md).contains("## Worst 10 cases");
    }
}
