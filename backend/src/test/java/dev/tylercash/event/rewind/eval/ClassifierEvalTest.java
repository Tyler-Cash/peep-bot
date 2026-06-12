package dev.tylercash.event.rewind.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.rewind.RewindConfiguration;
import dev.tylercash.event.rewind.TextNormalisationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;

/**
 * Sweeps prompt × temperature combinations against the live Ollama, writes a markdown report to
 * {@code backend/build/reports/classifier-eval/<timestamp>.md}.
 *
 * <p>Constructs the OllamaChatModel directly rather than via {@code @SpringBootTest} — the eval
 * needs only the classifier + Ollama, not the full Spring context (which would pull in JPA,
 * Liquibase, Postgres, Discord, etc.).
 *
 * <p>Run via {@code ./gradlew :backend:classifierEvalTest}; excluded from the default {@code test}
 * task by the {@code classifier-eval} tag.
 */
@Tag("classifier-eval")
class ClassifierEvalTest {

    @Test
    void sweepAndWriteReport() throws Exception {
        String baseUrl = System.getenv().getOrDefault("CLASSIFIER_EVAL_OLLAMA_URL", "https://ollama.tylercash.dev");
        String modelName = System.getenv().getOrDefault("CLASSIFIER_EVAL_MODEL", "mistral-nemo:12b-instruct-2407-q8_0");

        OllamaApi ollamaApi = OllamaApi.builder().baseUrl(baseUrl).build();
        OllamaChatModel chatModel = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(OllamaChatOptions.builder()
                        .model(modelName)
                        .numPredict(15)
                        .build())
                .build();

        RewindConfiguration config = new RewindConfiguration();
        TextNormalisationService service = new TextNormalisationService(chatModel, config);

        assertThat(service.isAvailable()).isTrue();

        List<EvalCase> cases = EvalCaseLoader.load("classifier-eval/corpus.yaml");

        ClassifierEvalRunner runner = new ClassifierEvalRunner(
                service, cases, List.of("classifier/prompt-v2.txt"), List.of(0.0, 0.1, 0.3, 0.5), 5);

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
