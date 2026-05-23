package dev.tylercash.event.rewind.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.rewind.TextNormalisationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Sweeps prompt × temperature combinations against the live Ollama, writes a markdown report to
 * {@code backend/build/reports/classifier-eval/<timestamp>.md}.
 *
 * <p>Run via {@code ./gradlew :backend:classifierEvalTest}; excluded from the default {@code test}
 * task by the {@code classifier-eval} tag.
 */
@SpringBootTest(classes = PeepBotApplication.class)
@ActiveProfiles({"local", "docker"})
@Tag("classifier-eval")
class ClassifierEvalTest {

    @Autowired
    private TextNormalisationService service;

    @Test
    void sweepAndWriteReport() throws Exception {
        assertThat(service.isAvailable())
                .as("Ollama must be available — start it via the project's normal local Ollama"
                        + " setup before running this test.")
                .isTrue();

        List<EvalCase> cases = EvalCaseLoader.load("classifier-eval/corpus.yaml");

        ClassifierEvalRunner runner = new ClassifierEvalRunner(
                service,
                cases,
                List.of("classifier/prompt-v1.txt", "classifier/prompt-v2.txt"),
                List.of(0.0, 0.1, 0.3, 0.5),
                10);

        ClassifierEvalReport report = runner.run();
        String md = report.render();

        Path outDir = Path.of("build/reports/classifier-eval");
        Files.createDirectories(outDir);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path outFile = outDir.resolve(timestamp + ".md");
        Files.writeString(outFile, md);

        System.out.println("Classifier eval report written to: " + outFile.toAbsolutePath());
    }
}
