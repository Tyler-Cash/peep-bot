package dev.tylercash.event.rewind.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

public final class EvalCaseLoader {

    private EvalCaseLoader() {}

    public static List<EvalCase> load(String classpathResource) {
        Constructor constructor = new Constructor(Corpus.class, new LoaderOptions());
        Yaml yaml = new Yaml(constructor);
        try (var in = new ClassPathResource(classpathResource).getInputStream()) {
            Corpus corpus = yaml.load(in);
            return corpus == null ? List.of() : corpus.getCases();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load corpus: " + classpathResource, e);
        }
    }

    public static class Corpus {
        private List<EvalCase> cases;

        public List<EvalCase> getCases() {
            return cases;
        }

        public void setCases(List<EvalCase> cases) {
            this.cases = cases;
        }
    }
}
