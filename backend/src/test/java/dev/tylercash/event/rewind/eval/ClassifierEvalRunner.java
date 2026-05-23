package dev.tylercash.event.rewind.eval;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.rewind.TextNormalisationService;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClassifierEvalRunner {

    private final TextNormalisationService service;
    private final List<EvalCase> cases;
    private final List<String> promptResources;
    private final List<Double> temperatures;
    private final int runsPerConfig;

    public ClassifierEvalRunner(
            TextNormalisationService service,
            List<EvalCase> cases,
            List<String> promptResources,
            List<Double> temperatures,
            int runsPerConfig) {
        this.service = service;
        this.cases = cases;
        this.promptResources = promptResources;
        this.temperatures = temperatures;
        this.runsPerConfig = runsPerConfig;
    }

    public ClassifierEvalReport run() {
        List<ClassifierEvalReport.Run> runs = new ArrayList<>();
        int totalConfigs = promptResources.size() * temperatures.size();
        int configIdx = 0;
        for (String promptResource : promptResources) {
            String template = TextNormalisationService.loadPromptTemplate(promptResource);
            for (Double temperature : temperatures) {
                configIdx++;
                log.info(
                        "Eval config {}/{}: prompt={} temperature={}",
                        configIdx,
                        totalConfigs,
                        promptResource,
                        temperature);
                for (EvalCase ec : cases) {
                    Event event = new Event();
                    event.setName(ec.getName());
                    for (int i = 0; i < runsPerConfig; i++) {
                        String predicted;
                        try {
                            predicted = service.classify(event, template, temperature);
                        } catch (Exception e) {
                            log.warn("classify threw for {}: {}", ec.getName(), e.getMessage());
                            predicted = "error";
                        }
                        runs.add(new ClassifierEvalReport.Run(
                                promptResource, temperature, ec.getName(), ec.getExpected(), predicted));
                    }
                }
            }
        }
        return new ClassifierEvalReport(runs);
    }
}
