package dev.tylercash.event.rewind.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.tylercash.event.rewind.TextNormalisationService;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClassifierEvalRunnerTest {

    @Test
    void runner_collectsRunsForEveryConfig() {
        TextNormalisationService service = mock(TextNormalisationService.class);
        when(service.classify(any(), anyString(), anyDouble())).thenReturn("Show");

        List<EvalCase> cases = List.of(
                new EvalCase("A synthetic comedy show event", "Show"),
                new EvalCase("A synthetic trivia night event", "Trivia"));

        ClassifierEvalRunner runner = new ClassifierEvalRunner(
                service, cases, List.of("classifier/prompt-v1.txt", "classifier/prompt-v2.txt"), List.of(0.0, 0.5), 3);

        ClassifierEvalReport report = runner.run();
        String md = report.render();

        // 2 prompts * 2 temps * 2 cases * 3 runs = 24 runs in total.
        // Mock always returns "Show", so accuracy is 50% (correct for the Show case, wrong for Trivia).
        assertThat(md).contains("prompt-v1.txt");
        assertThat(md).contains("prompt-v2.txt");
        assertThat(md).contains("50.0%");
    }
}
