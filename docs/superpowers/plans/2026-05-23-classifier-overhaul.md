# Event Classifier Overhaul Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 5-category event classifier with a 10-category one, rewrite the LLM prompt with definitions/tie-breakers/few-shot examples, add an ad-hoc eval harness for tuning prompt and temperature, and queue a backfill that re-classifies all existing events under the new prompt.

**Architecture:** Categories live in `RewindConfiguration#categories` (already a list). The classifier (`TextNormalisationService.classify(Event)`) builds an Ollama prompt from a template; today the template is an inline `String` constant. Refactor to load the template from a classpath resource so the eval harness can swap variants. Add an overload that accepts a prompt resource path and a temperature. Production keeps the one-arg call.

The eval harness is a JUnit test tagged `@Tag("classifier-eval")`. The existing gradle config already excludes `e2e`-tagged tests from `test`; we mirror that pattern and register a `classifierEvalTest` gradle task to run only the new tag.

Backfill is a Spring `ApplicationRunner` gated by a property `peepbot.classifier.backfill.run-before=<ISO timestamp>`. Streams every `EventCategory` row with `assignedAt < runBefore`, calls `embeddingService.classifyEvent(event)` for each at one per second. Empty property → runner is a no-op.

**Tech Stack:** Spring Boot 3 (peep-bot backend), Spring AI Ollama, JUnit 5, AssertJ, Gradle. Existing build script at `backend/build.gradle`.

**Source spec:** `docs/superpowers/specs/2026-05-23-classifier-overhaul-design.md`

---

## Pre-flight notes (for the planner / implementer)

- **Repo:** `/home/tcash/code/peep-bot-classifier` (worktree on `feat/classifier-overhaul`, off `origin/main`).
- **Existing pattern for tag-gated tests:** see `backend/src/test/java/dev/tylercash/event/event/EventServiceE2ETest.java` (uses `@Tag("e2e")`) and the `test` task's `excludeTags 'e2e'` in `backend/build.gradle`. The `e2eTest` gradle task there is the template for `classifierEvalTest`.
- **Existing classifier surface:** `TextNormalisationService.classify(Event)` returns the matched category name, or the literal string `"unknown"` when the LLM output doesn't match any configured category. The eval harness needs to surface `"unknown"` as its own bucket in the confusion matrix.
- **`EventCategory.assignedAt`** is the timestamp the backfill uses. The spec calls it `categorised_at`; the actual field is `assignedAt`.
- **`OllamaChatModel` API:** the existing call is `chatModel.call(prompt)` which uses default options. To override temperature, use `chatModel.call(new Prompt(prompt, OllamaOptions.builder().temperature(t).build())).getResult().getOutput().getContent()`. Verify the exact API before writing — Spring AI's surface has shifted between minor versions; check the `org.springframework.ai.ollama` package version pinned in `backend/build.gradle`.

---

## Task 1: Add new categories to RewindConfiguration

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/rewind/RewindConfiguration.java:13`

**Why this first:** No prompt or backfill change can land without the new category strings being in the configured list — `classify()` filters the LLM response against this list and returns `"unknown"` for unknowns. Doing this first lets the next tasks reference the strings as live values.

- [ ] **Step 1: Update the categories list**

Read `backend/src/main/java/dev/tylercash/event/rewind/RewindConfiguration.java`. The categories field is currently:

```java
private java.util.List<String> categories = java.util.List.of("Food", "Movie", "Game", "Outdoor", "Trivia");
```

Replace with:

```java
private java.util.List<String> categories = java.util.List.of(
        "Food", "Social", "Movie", "Show", "Game", "Trivia", "Outdoor", "Market", "Trip", "Other");
```

Order matches the prompt's category listing for consistency. The strings are exactly what the LLM is asked to emit; do not rename, rephrase, or pluralise.

- [ ] **Step 2: Verify the build still compiles**

Run:

```bash
./gradlew :backend:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run existing tests**

```bash
./gradlew :backend:test
```

Expected: all green. `TextNormalisationServiceTest` doesn't assert on the category list so it'll still pass.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/rewind/RewindConfiguration.java
git commit -m "feat(classifier): expand category list from 5 to 10"
```

---

## Task 2: Externalise the current prompt to a classpath resource

**Files:**
- Create: `backend/src/main/resources/classifier/prompt-v1.txt`
- Modify: `backend/src/main/java/dev/tylercash/event/rewind/TextNormalisationService.java`

**Why this before v2:** Refactor without behaviour change. Load the existing inline template from a resource so the eval harness can compare v1 vs v2 later. After this task, production behaviour is byte-identical to before.

- [ ] **Step 1: Create the v1 prompt resource**

Create `backend/src/main/resources/classifier/prompt-v1.txt` with the EXACT current template:

```
Classify this event into exactly one of the following categories: {categories}. Reply with the category name only. No explanation, no punctuation.

Event name: "{name}"
Location: "{location}"
Date: {date}
Description: "{description}"
Category:
```

Note the placeholder syntax `{name}` etc. — we're switching from `%s` to named placeholders so v2 can reference fields in a different order without breaking. Simple `String.replace("{name}", name)` substitution is fine; no need for a templating library.

- [ ] **Step 2: Refactor TextNormalisationService to load the template from the classpath**

Replace the body of `TextNormalisationService` with:

```java
package dev.tylercash.event.rewind;

import dev.tylercash.event.event.model.Event;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;

@Slf4j
@Service
public class TextNormalisationService {

    private static final String DEFAULT_PROMPT_RESOURCE = "classifier/prompt-v1.txt";

    private final OllamaChatModel chatModel;
    private final RewindConfiguration config;
    private final String defaultPromptTemplate;

    public TextNormalisationService(
            @Autowired(required = false) OllamaChatModel chatModel, RewindConfiguration config) {
        this.chatModel = chatModel;
        this.config = config;
        this.defaultPromptTemplate = loadPromptTemplate(DEFAULT_PROMPT_RESOURCE);
        if (chatModel != null) {
            log.info("TextNormalisationService initialized — LLM normalisation available");
        }
    }

    public boolean isAvailable() {
        return chatModel != null;
    }

    public String classify(Event event) {
        return classify(event, defaultPromptTemplate);
    }

    /** Overload used by the eval harness to swap prompt variants without restarting. */
    public String classify(Event event, String promptTemplate) {
        if (!isAvailable()) {
            throw new IllegalStateException("LLM service is not available for classification");
        }
        try {
            String prompt = renderPrompt(promptTemplate, event);
            String response = callModel(prompt);
            return config.getCategories().stream()
                    .filter(c -> c.equalsIgnoreCase(response))
                    .findFirst()
                    .orElse("unknown");
        } catch (Exception e) {
            log.error("Classification failed for event: {}", e.getMessage());
            throw e;
        }
    }

    static String loadPromptTemplate(String classpathResource) {
        try {
            var resource = new ClassPathResource(classpathResource);
            return new String(
                    FileCopyUtils.copyToByteArray(resource.getInputStream()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load prompt template: " + classpathResource, e);
        }
    }

    private String renderPrompt(String template, Event event) {
        String categories = String.join(", ", config.getCategories());
        String name = event.getName().replace("\"", "'");
        String location = event.getLocation() != null ? event.getLocation().replace("\"", "'") : "";
        String date = event.getDateTime() != null
                ? event.getDateTime().toLocalDate().toString()
                : "unknown";
        String description =
                event.getDescription() != null ? event.getDescription().replace("\"", "'") : "";
        return template
                .replace("{categories}", categories)
                .replace("{name}", name)
                .replace("{location}", location)
                .replace("{date}", date)
                .replace("{description}", description);
    }

    private String callModel(String prompt) {
        String response = chatModel.call(prompt).trim();
        return response.replaceAll("^[\"'`]|[\"'`]$", "").trim();
    }
}
```

The body of the old inline template (the `CLASSIFY_PROMPT_TEMPLATE` constant) is gone — it lives in `prompt-v1.txt` now.

- [ ] **Step 3: Build + run tests**

```bash
./gradlew :backend:test
```

Expected: green. `TextNormalisationServiceTest` exercises the no-model and unavailable-throws paths, both of which still work. The `classify(Event)` happy path isn't covered yet (next task adds coverage of the new overload).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/classifier/prompt-v1.txt \
        backend/src/main/java/dev/tylercash/event/rewind/TextNormalisationService.java
git commit -m "refactor(classifier): load prompt template from classpath"
```

---

## Task 3: Add the new v2 prompt and switch production to it

**Files:**
- Create: `backend/src/main/resources/classifier/prompt-v2.txt`
- Modify: `backend/src/main/java/dev/tylercash/event/rewind/TextNormalisationService.java:DEFAULT_PROMPT_RESOURCE`

**Why this before backfill/eval:** Production switches to the new prompt with the new categories. Once this commit lands, every new event is classified under v2. The backfill (Task 5) and eval harness (Tasks 7–9) operate on this default.

- [ ] **Step 1: Create the v2 prompt resource**

Create `backend/src/main/resources/classifier/prompt-v2.txt` with this exact content (do NOT use real friend-group event names anywhere — all few-shot examples are synthetic):

```
You classify social outings into ONE category for a Discord bot used by a friend group in Sydney. Reply with only the category name — no quotes, no punctuation, no explanation.

Categories:
- Food     — restaurants, dinners, brunch, food tours, cooking together
- Social   — bars, pubs, cocktail spots, house hangs, picnics, "just chill"
- Movie    — cinema, home movie nights
- Show     — live comedy, stand-up, gigs, concerts, theatre, performances
- Game     — board games, video games, escape rooms, arcade, bowling, mini golf
- Trivia   — pub trivia and quiz nights specifically
- Outdoor  — hikes, beach, kayaking, climbing, scenic walks, lookouts, sightseeing
- Market   — markets, food fairs, art fairs, street festivals
- Trip     — overnight or day trips that involve travelling somewhere
- Other    — anything that genuinely doesn't fit above

Tie-breakers:
- If a trip is overnight or requires travel away, Trip wins over the activity (e.g. "weekend in Bowral with wine tasting" -> Trip).
- Words like "comedy", "stand-up", "improv", "gig", "concert", "theatre" -> Show.
- Words like "quiz" or "trivia" -> Trivia, not Show or Game.
- Hiking, beach, lookouts, sightseeing, scenic walks -> Outdoor.
- House hangs, picnics, drinks at a bar -> Social.
- When in doubt between two categories, prefer the more specific one (Trivia > Game, Show > Movie, Market > Outdoor).

Examples:
- "Comedy night with the local stand-up crew" -> Show
- "Tuesday pub trivia at the Toxteth" -> Trivia
- "Beach day at Manly" -> Outdoor
- "Movie marathon at the Hayden Orpheum" -> Movie
- "Drinks at the Baxter Inn" -> Social
- "Long weekend up to Byron" -> Trip
- "Glebe Markets Saturday" -> Market

Event name: "{name}"
Location: "{location}"
Date: {date}
Description: "{description}"
Category:
```

Note: the placeholder for categories was used in v1 but is removed in v2 (categories are baked into the prompt body with their definitions). The `{categories}` substitution in `renderPrompt` still runs and simply does nothing — the placeholder isn't present so `.replace` is a no-op. That's fine.

- [ ] **Step 2: Switch the default prompt resource**

In `TextNormalisationService`, change:

```java
private static final String DEFAULT_PROMPT_RESOURCE = "classifier/prompt-v1.txt";
```

to:

```java
private static final String DEFAULT_PROMPT_RESOURCE = "classifier/prompt-v2.txt";
```

- [ ] **Step 3: Add a happy-path test for the classifier with a mocked OllamaChatModel**

Add to `backend/src/test/java/dev/tylercash/event/rewind/TextNormalisationServiceTest.java`. Existing file body shown for context; **append the new test method**:

```java
    @Test
    @DisplayName("classify returns the matching category from configured list when LLM responds with it")
    void classify_returnsMatchingCategory() {
        org.springframework.ai.ollama.OllamaChatModel chatModel =
                org.mockito.Mockito.mock(org.springframework.ai.ollama.OllamaChatModel.class);
        org.mockito.Mockito.when(chatModel.call(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("Show");

        TextNormalisationService svc = new TextNormalisationService(chatModel, config());

        dev.tylercash.event.event.model.Event event = new dev.tylercash.event.event.model.Event();
        event.setName("Comedy night at the local pub");

        String category = svc.classify(event);

        assertThat(category).isEqualTo("Show");
    }

    @Test
    @DisplayName("classify returns unknown when LLM responds with a category not in the configured list")
    void classify_returnsUnknownForOffListResponse() {
        org.springframework.ai.ollama.OllamaChatModel chatModel =
                org.mockito.Mockito.mock(org.springframework.ai.ollama.OllamaChatModel.class);
        org.mockito.Mockito.when(chatModel.call(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("Cabaret");

        TextNormalisationService svc = new TextNormalisationService(chatModel, config());

        dev.tylercash.event.event.model.Event event = new dev.tylercash.event.event.model.Event();
        event.setName("A genuinely weird event");

        String category = svc.classify(event);

        assertThat(category).isEqualTo("unknown");
    }
```

If Mockito is not already a test dependency, check `backend/build.gradle` — Spring Boot Starter Test pulls it in, so it should already be on the classpath without an explicit declaration.

- [ ] **Step 4: Build + run tests**

```bash
./gradlew :backend:test
```

Expected: green, including the two new tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/classifier/prompt-v2.txt \
        backend/src/main/java/dev/tylercash/event/rewind/TextNormalisationService.java \
        backend/src/test/java/dev/tylercash/event/rewind/TextNormalisationServiceTest.java
git commit -m "feat(classifier): switch production to v2 prompt with definitions and few-shot"
```

---

## Task 4: Add temperature override to the classifier API

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/rewind/TextNormalisationService.java`
- Modify: `backend/src/main/resources/application.yaml`

**Why:** The eval harness needs to sweep temperature. The production path also benefits from setting a deterministic temperature explicitly rather than relying on Ollama's default (which varies by model and is often 0.8 — too random for classification).

- [ ] **Step 1: Verify the OllamaChatModel call signature for temperature override**

Spring AI's API has shifted between versions. Check the version pinned in `backend/build.gradle` (grep for `spring-ai`). Then read the project's existing usages of `OllamaChatModel` to confirm the right call pattern. Common surface:

```java
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaOptions;

String response = chatModel
        .call(new Prompt(promptText, OllamaOptions.builder().temperature(temperature).build()))
        .getResult()
        .getOutput()
        .getContent();
```

If your version uses `.getText()` instead of `.getContent()`, or `OllamaOptions.create()` instead of `.builder()`, use whatever the project's other Spring AI calls use.

- [ ] **Step 2: Add a temperature-aware overload**

In `TextNormalisationService`, add a third overload that takes both a template and a temperature, and refactor `callModel` to use the explicit-temperature path:

```java
    /** Overload used by the eval harness. */
    public String classify(Event event, String promptTemplate, double temperature) {
        if (!isAvailable()) {
            throw new IllegalStateException("LLM service is not available for classification");
        }
        try {
            String prompt = renderPrompt(promptTemplate, event);
            String response = callModel(prompt, temperature);
            return config.getCategories().stream()
                    .filter(c -> c.equalsIgnoreCase(response))
                    .findFirst()
                    .orElse("unknown");
        } catch (Exception e) {
            log.error("Classification failed for event: {}", e.getMessage());
            throw e;
        }
    }
```

Change the two-arg `classify(Event, String)` to delegate to the new three-arg overload with the configured default temperature (added below). Update `classify(Event)` similarly.

Replace `callModel(String)` with:

```java
    private String callModel(String prompt, double temperature) {
        var options = org.springframework.ai.ollama.api.OllamaOptions.builder()
                .temperature(temperature)
                .build();
        var promptObj = new org.springframework.ai.chat.prompt.Prompt(prompt, options);
        String response = chatModel.call(promptObj).getResult().getOutput().getContent().trim();
        return response.replaceAll("^[\"'`]|[\"'`]$", "").trim();
    }
```

Adjust imports / package paths to match the actual Spring AI version's API.

- [ ] **Step 3: Add a configurable default temperature**

Add a field to `RewindConfiguration`:

```java
    private double classifierTemperature = 0.1;
```

Spring Boot will bind `dev.tylercash.rewind.classifier-temperature` to this. Lombok `@Data` generates the getter.

In `TextNormalisationService.classify(Event)`, use the new value:

```java
    public String classify(Event event) {
        return classify(event, defaultPromptTemplate, config.getClassifierTemperature());
    }

    public String classify(Event event, String promptTemplate) {
        return classify(event, promptTemplate, config.getClassifierTemperature());
    }
```

- [ ] **Step 4: Document the property in application.yaml**

Read `backend/src/main/resources/application.yaml`. Find the existing `dev.tylercash.rewind` block (or add one if missing). Add:

```yaml
dev:
  tylercash:
    rewind:
      classifier-temperature: 0.1  # set after eval harness picks the winner
```

If the block already exists, merge in the new key without disturbing the others.

- [ ] **Step 5: Update tests**

The existing `classify_returnsMatchingCategory` test mocked `chatModel.call(String)`. Now production calls `chatModel.call(Prompt)`. Update the mock to match:

```java
        org.mockito.Mockito.when(chatModel.call(org.mockito.ArgumentMatchers.any(
                        org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(/* a ChatResponse wrapping "Show" */);
```

Constructing a real `ChatResponse` is awkward; if so, mock `.call(Prompt)` to return a `ChatResponse` built like:

```java
        org.springframework.ai.chat.model.Generation gen =
                new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage("Show"));
        org.springframework.ai.chat.model.ChatResponse cr =
                new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(gen));
        org.mockito.Mockito.when(chatModel.call(org.mockito.ArgumentMatchers.any(
                        org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(cr);
```

The exact class names depend on the Spring AI version. Run the test, follow the compile errors to the right types.

- [ ] **Step 6: Build + run tests**

```bash
./gradlew :backend:test
```

Expected: green.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/rewind/TextNormalisationService.java \
        backend/src/main/java/dev/tylercash/event/rewind/RewindConfiguration.java \
        backend/src/main/resources/application.yaml \
        backend/src/test/java/dev/tylercash/event/rewind/TextNormalisationServiceTest.java
git commit -m "feat(classifier): plumb temperature through the classifier API"
```

---

## Task 5: Classifier backfill ApplicationRunner

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/rewind/ClassifierBackfillRunner.java`
- Modify: `backend/src/main/resources/application.yaml`
- Modify: `backend/src/main/java/dev/tylercash/event/db/repository/EventCategoryRepository.java` (add a finder)

**Why:** After deploy, every event previously classified under the old 5-category prompt needs to be re-classified under v2. A one-shot runner gated by a property is the simplest restartable approach. With the property unset, the runner is a no-op — safe to leave in the codebase.

- [ ] **Step 1: Add a repository finder for stale categorisations**

In `backend/src/main/java/dev/tylercash/event/db/repository/EventCategoryRepository.java`, add:

```java
    /** Stream all category rows whose label was assigned before the cutoff. Caller iterates lazily. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT ec.eventId FROM EventCategory ec WHERE ec.assignedAt < :cutoff")
    java.util.stream.Stream<java.util.UUID> findEventIdsAssignedBefore(
            @org.springframework.data.repository.query.Param("cutoff") java.time.OffsetDateTime cutoff);
```

Returning a `Stream<UUID>` (not `List<EventCategory>`) keeps memory bounded — the runner can pace itself rather than loading the full backlog into memory.

- [ ] **Step 2: Write the runner**

Create `backend/src/main/java/dev/tylercash/event/rewind/ClassifierBackfillRunner.java`:

```java
package dev.tylercash.event.rewind;

import dev.tylercash.event.db.repository.EventCategoryRepository;
import dev.tylercash.event.db.repository.EventRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-classifies events whose category was assigned before a cutoff timestamp. Gated by the
 * property {@code peepbot.classifier.backfill.run-before}; when unset, the runner is a no-op.
 *
 * <p>Designed for one-off use after a prompt change. Set the property to the deploy timestamp,
 * deploy, watch the log line per 50 events, then unset the property on the next deploy.
 */
@Slf4j
@Component
public class ClassifierBackfillRunner implements ApplicationRunner {

    private final EventCategoryRepository categoryRepository;
    private final EventRepository eventRepository;
    private final EmbeddingService embeddingService;
    private final OffsetDateTime runBefore;
    private final Duration pacing;

    public ClassifierBackfillRunner(
            EventCategoryRepository categoryRepository,
            EventRepository eventRepository,
            EmbeddingService embeddingService,
            @Value("${peepbot.classifier.backfill.run-before:}") String runBefore,
            @Value("${peepbot.classifier.backfill.pacing:PT1S}") Duration pacing) {
        this.categoryRepository = categoryRepository;
        this.eventRepository = eventRepository;
        this.embeddingService = embeddingService;
        this.runBefore = runBefore == null || runBefore.isBlank()
                ? null
                : OffsetDateTime.parse(runBefore);
        this.pacing = pacing;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (runBefore == null) {
            return;
        }
        log.info("Classifier backfill: starting, cutoff={} pacing={}", runBefore, pacing);
        // Don't block startup — fire and forget on a single dedicated thread.
        Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "classifier-backfill");
                    t.setDaemon(true);
                    return t;
                })
                .submit(this::backfillLoop);
    }

    @Transactional(readOnly = true)
    void backfillLoop() {
        long processed = 0;
        try (var ids = categoryRepository.findEventIdsAssignedBefore(runBefore)) {
            for (var iter = ids.iterator(); iter.hasNext(); ) {
                var id = iter.next();
                try {
                    var event = eventRepository.findById(id).orElse(null);
                    if (event != null) {
                        embeddingService.classifyEvent(event);
                    }
                } catch (Exception e) {
                    log.warn("Classifier backfill: reclassify failed for event {}: {}", id, e.getMessage());
                }
                processed++;
                if (processed % 50 == 0) {
                    log.info("Classifier backfill: {} events processed", processed);
                }
                try {
                    Thread.sleep(pacing.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.info("Classifier backfill: interrupted at {} events", processed);
                    return;
                }
            }
        }
        log.info("Classifier backfill: done, {} events processed", processed);
    }
}
```

- [ ] **Step 3: Document the properties in application.yaml**

Add (or merge into an existing `peepbot:` block):

```yaml
peepbot:
  classifier:
    backfill:
      # Set to the deploy timestamp (ISO-8601 with offset, e.g. 2026-05-23T14:00:00+10:00)
      # to trigger a one-shot reclassification of every event whose category was assigned
      # before that time. Leave blank in production after the backfill completes.
      run-before:
      pacing: PT1S
```

- [ ] **Step 4: Test the runner**

Create `backend/src/test/java/dev/tylercash/event/rewind/ClassifierBackfillRunnerTest.java`:

```java
package dev.tylercash.event.rewind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.tylercash.event.db.repository.EventCategoryRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ClassifierBackfillRunnerTest {

    private final EventCategoryRepository categoryRepo = mock(EventCategoryRepository.class);
    private final EventRepository eventRepo = mock(EventRepository.class);
    private final EmbeddingService embeddingService = mock(EmbeddingService.class);

    @Test
    void runner_isNoOp_whenRunBeforeBlank() {
        var runner = new ClassifierBackfillRunner(
                categoryRepo, eventRepo, embeddingService, "", Duration.ZERO);

        runner.run(null);

        verify(categoryRepo, never()).findEventIdsAssignedBefore(any());
    }

    @Test
    void backfillLoop_classifiesEveryStreamedEvent() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Event e1 = new Event();
        e1.setId(id1);
        Event e2 = new Event();
        e2.setId(id2);

        when(categoryRepo.findEventIdsAssignedBefore(any())).thenReturn(Stream.of(id1, id2));
        when(eventRepo.findById(id1)).thenReturn(java.util.Optional.of(e1));
        when(eventRepo.findById(id2)).thenReturn(java.util.Optional.of(e2));

        var runner = new ClassifierBackfillRunner(
                categoryRepo,
                eventRepo,
                embeddingService,
                OffsetDateTime.now().toString(),
                Duration.ZERO);

        runner.backfillLoop();

        verify(embeddingService, times(1)).classifyEvent(e1);
        verify(embeddingService, times(1)).classifyEvent(e2);
    }

    @Test
    void backfillLoop_continues_whenSingleClassifyThrows() {
        UUID good = UUID.randomUUID();
        UUID bad = UUID.randomUUID();
        Event gE = new Event();
        gE.setId(good);
        Event bE = new Event();
        bE.setId(bad);

        when(categoryRepo.findEventIdsAssignedBefore(any())).thenReturn(Stream.of(bad, good));
        when(eventRepo.findById(bad)).thenReturn(java.util.Optional.of(bE));
        when(eventRepo.findById(good)).thenReturn(java.util.Optional.of(gE));
        org.mockito.Mockito.doThrow(new RuntimeException("ollama down"))
                .when(embeddingService).classifyEvent(bE);

        var runner = new ClassifierBackfillRunner(
                categoryRepo,
                eventRepo,
                embeddingService,
                OffsetDateTime.now().toString(),
                Duration.ZERO);

        runner.backfillLoop();

        verify(embeddingService, times(1)).classifyEvent(gE);
    }
}
```

- [ ] **Step 5: Build + run tests**

```bash
./gradlew :backend:test
```

Expected: green.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/rewind/ClassifierBackfillRunner.java \
        backend/src/main/resources/application.yaml \
        backend/src/main/java/dev/tylercash/event/db/repository/EventCategoryRepository.java \
        backend/src/test/java/dev/tylercash/event/rewind/ClassifierBackfillRunnerTest.java
git commit -m "feat(classifier): one-shot backfill runner gated by run-before property"
```

---

## Task 6: Eval harness — corpus, DTO, report

**Files:**
- Create: `backend/src/test/resources/classifier-eval/corpus.yaml`
- Create: `backend/src/test/java/dev/tylercash/event/rewind/eval/EvalCase.java`
- Create: `backend/src/test/java/dev/tylercash/event/rewind/eval/EvalCaseLoader.java`
- Create: `backend/src/test/java/dev/tylercash/event/rewind/eval/ClassifierEvalReport.java`

**Why:** Set up the supporting types before the orchestrator (Task 7) and JUnit driver (Task 8). Each piece is independently testable.

- [ ] **Step 1: Create the corpus**

Create `backend/src/test/resources/classifier-eval/corpus.yaml` with these 30 synthetic events:

```yaml
cases:
  - { name: "Saturday dinner at the new ramen place", expected: Food }
  - { name: "Birthday brunch at Bills", expected: Food }
  - { name: "Italian degustation in Surry Hills", expected: Food }

  - { name: "Dune Part Three at IMAX", expected: Movie }
  - { name: "Movie night at mine — pizza and Shrek", expected: Movie }
  - { name: "Sunday cinema double feature", expected: Movie }

  - { name: "Stand-up comedy night with the local crew", expected: Show }
  - { name: "Live jazz at the Basement", expected: Show }
  - { name: "Hamilton matinee at the Lyric", expected: Show }
  - { name: "Indie gig at the Lansdowne", expected: Show }

  - { name: "Board game night at Steve's", expected: Game }
  - { name: "Escape room — the haunted museum", expected: Game }
  - { name: "Bowling at King Pin", expected: Game }

  - { name: "Tuesday pub quiz at the Lord Roberts", expected: Trivia }
  - { name: "Trivia at Harpoon Harry's", expected: Trivia }
  - { name: "Pop culture trivia night", expected: Trivia }

  - { name: "Royal coastal walk — Bundeena to Otford", expected: Outdoor }
  - { name: "Sunrise hike at Mount Solitary", expected: Outdoor }
  - { name: "Beach day at Maroubra", expected: Outdoor }
  - { name: "Watson's Bay lookout and walk", expected: Outdoor }

  - { name: "Carriageworks Saturday market", expected: Market }
  - { name: "Eveleigh farmers market and breakfast", expected: Market }

  - { name: "After-work drinks at the Shady Pines", expected: Social }
  - { name: "Friday pub session at the Vic", expected: Social }
  - { name: "Picnic at Centennial Park", expected: Social }
  - { name: "House warming at the new flat", expected: Social }

  - { name: "Long weekend in Jervis Bay", expected: Trip }
  - { name: "Day trip to the Blue Mountains", expected: Trip }
  - { name: "Three nights camping at Pretty Beach", expected: Trip }

  - { name: "Group photo shoot at the studio", expected: Other }
```

- [ ] **Step 2: Create the DTO**

Create `backend/src/test/java/dev/tylercash/event/rewind/eval/EvalCase.java`:

```java
package dev.tylercash.event.rewind.eval;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvalCase {
    private String name;
    private String expected;
}
```

- [ ] **Step 3: Create the corpus loader**

Create `backend/src/test/java/dev/tylercash/event/rewind/eval/EvalCaseLoader.java`:

```java
package dev.tylercash.event.rewind.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

public final class EvalCaseLoader {

    private EvalCaseLoader() {}

    public static List<EvalCase> load(String classpathResource) {
        var constructor = new Constructor(Corpus.class, new org.yaml.snakeyaml.LoaderOptions());
        var yaml = new Yaml(constructor);
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
```

SnakeYAML is already a transitive dependency via Spring Boot. If not, add `testImplementation 'org.yaml:snakeyaml'` to `backend/build.gradle` (it's already pulled by other Spring Boot artefacts).

- [ ] **Step 4: Create the report renderer**

Create `backend/src/test/java/dev/tylercash/event/rewind/eval/ClassifierEvalReport.java`:

```java
package dev.tylercash.event.rewind.eval;

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
        var sb = new StringBuilder();
        sb.append("# Classifier Eval Report\n\n");
        renderAccuracyTable(sb);
        sb.append("\n");
        renderConfusionMatrices(sb);
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
        var grouped = groupByConfig();
        grouped.forEach((cfg, cfgRuns) -> {
            long correct = cfgRuns.stream().filter(r -> r.expected.equalsIgnoreCase(r.predicted)).count();
            double acc = 100.0 * correct / cfgRuns.size();
            sb.append(String.format("| %s | %.2f | %.1f%% (%d/%d) |%n", cfg.prompt, cfg.temperature, acc, correct, cfgRuns.size()));
        });
    }

    private void renderConfusionMatrices(StringBuilder sb) {
        sb.append("## Confusion matrix (best-accuracy config)\n\n");
        var best = bestConfig();
        if (best == null) {
            sb.append("(no runs)\n");
            return;
        }
        sb.append(String.format("Prompt: `%s`, temperature: `%.2f`%n%n", best.prompt, best.temperature));
        SortedSet<String> labels = new TreeSet<>();
        Map<String, Map<String, Integer>> matrix = new TreeMap<>();
        for (Run r : runs) {
            if (!r.prompt.equals(best.prompt) || r.temperature != best.temperature) continue;
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
        var best = bestConfig();
        if (best == null) return;
        record CaseStat(String name, String expected, long wrong, long total) {}
        Map<String, long[]> stats = new java.util.HashMap<>();
        Map<String, String> expectedByCase = new java.util.HashMap<>();
        for (Run r : runs) {
            if (!r.prompt.equals(best.prompt) || r.temperature != best.temperature) continue;
            expectedByCase.put(r.caseName, r.expected);
            long[] s = stats.computeIfAbsent(r.caseName, k -> new long[]{0, 0});
            s[1]++;
            if (!r.expected.equalsIgnoreCase(r.predicted)) s[0]++;
        }
        List<CaseStat> ordered = new java.util.ArrayList<>();
        stats.forEach((name, s) -> ordered.add(new CaseStat(name, expectedByCase.get(name), s[0], s[1])));
        ordered.sort((a, b) -> Long.compare(b.wrong, a.wrong));
        sb.append("| Event | Expected | Wrong / Total |\n|---|---|---|\n");
        ordered.stream().limit(10).forEach(c -> sb.append(String.format("| %s | %s | %d/%d |%n", c.name, c.expected, c.wrong, c.total)));
    }

    private void renderRecommendation(StringBuilder sb) {
        sb.append("## Recommendation\n\n");
        var best = bestConfig();
        if (best == null) {
            sb.append("(no runs)\n");
        } else {
            sb.append(String.format("Prompt `%s` at temperature `%.2f` produced the highest accuracy.%n", best.prompt, best.temperature));
        }
    }

    private record ConfigKey(String prompt, double temperature) {}

    private Map<ConfigKey, List<Run>> groupByConfig() {
        Map<ConfigKey, List<Run>> out = new TreeMap<>((a, b) -> {
            int p = a.prompt.compareTo(b.prompt);
            return p != 0 ? p : Double.compare(a.temperature, b.temperature);
        });
        for (Run r : runs) {
            out.computeIfAbsent(new ConfigKey(r.prompt, r.temperature), k -> new java.util.ArrayList<>()).add(r);
        }
        return out;
    }

    private ConfigKey bestConfig() {
        var grouped = groupByConfig();
        return grouped.entrySet().stream()
                .max((a, b) -> Double.compare(accuracy(a.getValue()), accuracy(b.getValue())))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static double accuracy(List<Run> runs) {
        if (runs.isEmpty()) return 0;
        long correct = runs.stream().filter(r -> r.expected().equalsIgnoreCase(r.predicted())).count();
        return (double) correct / runs.size();
    }
}
```

- [ ] **Step 5: Smoke-test the loader and report renderer with a plain unit test**

Create `backend/src/test/java/dev/tylercash/event/rewind/eval/ClassifierEvalReportTest.java`:

```java
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
        var runs = List.of(
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
```

- [ ] **Step 6: Build + run tests**

```bash
./gradlew :backend:test
```

Expected: green.

- [ ] **Step 7: Commit**

```bash
git add backend/src/test/resources/classifier-eval/corpus.yaml \
        backend/src/test/java/dev/tylercash/event/rewind/eval/
git commit -m "feat(classifier): eval corpus, DTO, and markdown report renderer"
```

---

## Task 7: Eval harness — sweep orchestrator

**Files:**
- Create: `backend/src/test/java/dev/tylercash/event/rewind/eval/ClassifierEvalRunner.java`

**Why:** Pure logic that takes a list of prompt resources, a list of temperatures, a runs-per-case integer, and an `EventToCategory` function; calls it N times per (case × config); returns a `ClassifierEvalReport`. Separating this from the JUnit driver lets it be unit-tested without Ollama.

- [ ] **Step 1: Create the orchestrator**

Create `backend/src/test/java/dev/tylercash/event/rewind/eval/ClassifierEvalRunner.java`:

```java
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
```

Note `TextNormalisationService.loadPromptTemplate` was added as a package-private static helper in Task 2 — confirm it's `static` (not instance) so we can call it without a service instance.

- [ ] **Step 2: Unit-test the orchestrator with a stub classifier**

Create `backend/src/test/java/dev/tylercash/event/rewind/eval/ClassifierEvalRunnerTest.java`:

```java
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
                new EvalCase("A comedy show", "Show"),
                new EvalCase("A trivia night", "Trivia"));

        ClassifierEvalRunner runner = new ClassifierEvalRunner(
                service, cases, List.of("classifier/prompt-v1.txt", "classifier/prompt-v2.txt"),
                List.of(0.0, 0.5), 3);

        ClassifierEvalReport report = runner.run();
        String md = report.render();

        // 2 prompts * 2 temps * 2 cases * 3 runs = 24 runs
        assertThat(md).contains("v1");
        assertThat(md).contains("v2");
        // Accuracy: 50% (Show is correct for one case, wrong for the other) at every config.
        assertThat(md).contains("50.0%");
    }
}
```

- [ ] **Step 3: Build + run tests**

```bash
./gradlew :backend:test
```

Expected: green. The new unit test doesn't touch Ollama.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/dev/tylercash/event/rewind/eval/ClassifierEvalRunner.java \
        backend/src/test/java/dev/tylercash/event/rewind/eval/ClassifierEvalRunnerTest.java
git commit -m "feat(classifier): eval sweep orchestrator with unit-level coverage"
```

---

## Task 8: Eval harness — JUnit driver gated by tag

**Files:**
- Create: `backend/src/test/java/dev/tylercash/event/rewind/eval/ClassifierEvalTest.java`
- Modify: `backend/build.gradle` — add `classifierEvalTest` gradle task; exclude tag from default `test`

**Why:** The driver is a Spring Boot integration test that needs Ollama running. Tag-excluded from the default test task so `./gradlew test` doesn't try to hit Ollama in CI.

- [ ] **Step 1: Write the JUnit driver**

Create `backend/src/test/java/dev/tylercash/event/rewind/eval/ClassifierEvalTest.java`:

```java
package dev.tylercash.event.rewind.eval;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Sweeps prompt × temperature combinations against the live Ollama, writes a markdown
 * report to {@code backend/build/reports/classifier-eval/<timestamp>.md}.
 *
 * <p>Run via {@code ./gradlew :backend:classifierEvalTest}; excluded from the default
 * {@code test} task by the {@code classifier-eval} tag.
 */
@SpringBootTest
@ActiveProfiles({"local", "docker"})
@Tag("classifier-eval")
class ClassifierEvalTest {

    @Autowired
    private TextNormalisationService service;

    @Test
    void sweepAndWriteReport() throws Exception {
        assertThat(service.isAvailable())
                .as("Ollama must be available — start it with `docker compose up ollama` or local Ollama")
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
```

If the project uses `@ActiveProfiles("local")` or other variants for Ollama wiring, match what the embedding/normalisation tests use. Check `EmbeddingServiceTest` or `EventServiceE2ETest` for the convention.

- [ ] **Step 2: Register the gradle task**

Read `backend/build.gradle`. After the existing `e2eTest` registration (search for `tasks.register('e2eTest'`), add:

```groovy
tasks.register('classifierEvalTest', Test) {
    description = 'Sweeps the classifier prompt + temperature against the live Ollama. Requires Ollama running.'
    group = 'verification'
    testClassesDirs = sourceSets.test.output.classesDirs
    classpath = sourceSets.test.runtimeClasspath
    useJUnitPlatform {
        includeTags 'classifier-eval'
    }
    shouldRunAfter test
    // Don't fork into many JVMs — this test serialises against a single Ollama
    // and takes ~40 min as-is.
    maxParallelForks = 1
    // Bump heap a bit so Spring contexts plus the long-running run() don't OOM.
    minHeapSize = '1g'
    maxHeapSize = '2g'
}
```

Also update the existing `test` task block to exclude the new tag — find:

```groovy
tasks.named('test') {
    useJUnitPlatform {
        excludeTags 'e2e'
    }
    ...
}
```

and change to:

```groovy
tasks.named('test') {
    useJUnitPlatform {
        excludeTags 'e2e', 'classifier-eval'
    }
    ...
}
```

(Keep everything else inside `tasks.named('test')` exactly as it was — only the `excludeTags` line changes.)

- [ ] **Step 3: Verify the default `test` task still excludes the eval**

```bash
./gradlew :backend:test
```

Expected: green, and `ClassifierEvalTest.sweepAndWriteReport` is NOT executed. The build output should show 0 tests from that class.

- [ ] **Step 4: Verify the eval task is wired (but don't run the full sweep yet)**

```bash
./gradlew :backend:classifierEvalTest --dry-run
```

Expected: shows the task in the plan without running it.

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/java/dev/tylercash/event/rewind/eval/ClassifierEvalTest.java \
        backend/build.gradle
git commit -m "feat(classifier): JUnit driver + gradle task for ad-hoc eval sweep"
```

---

## Task 9: Run the eval, pick a winner, set the default temperature

**Files:**
- Modify: `backend/src/main/resources/application.yaml` (set `dev.tylercash.rewind.classifier-temperature` to the winning value)

**Why:** Ground the temperature default in measured accuracy rather than a guess.

- [ ] **Step 1: Start Ollama**

Whatever the local convention is — `docker compose up ollama -d` or a host service. Confirm by hitting the Ollama endpoint, e.g.:

```bash
curl -s http://localhost:11434/api/tags | head
```

Should return JSON listing installed models.

- [ ] **Step 2: Run the sweep**

```bash
./gradlew :backend:classifierEvalTest
```

This will take ~40 minutes. Tail logs to watch progress; the orchestrator logs `Eval config N/M: prompt=... temperature=...` per config.

- [ ] **Step 3: Read the report**

Open the latest file under `backend/build/reports/classifier-eval/<timestamp>.md`. Note:

- The recommended (prompt, temperature) combo.
- The accuracy gap between v1 and v2 — v2 should clearly win; if it doesn't, the prompt needs more work and Task 10 (below) is required before merging.
- The confusion matrix's hotspots — common misclassifications give the next round of tie-breaker rules to add to v2.
- Per-event flake rates — events that flip-flop suggest the prompt isn't anchoring strongly.

- [ ] **Step 4: Update the default temperature**

In `backend/src/main/resources/application.yaml`, set:

```yaml
dev:
  tylercash:
    rewind:
      classifier-temperature: <winning value>
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/application.yaml
git commit -m "chore(classifier): set default temperature from eval sweep"
```

---

## Task 10 (conditional): Iterate on v2 if accuracy is unsatisfactory

**Trigger:** Task 9's report shows v2 below ~80% on the corpus, OR the recommended config produces obvious systemic misclassifications (e.g. every Outdoor case lands in Other).

**Files:**
- Modify: `backend/src/main/resources/classifier/prompt-v2.txt`

- [ ] **Step 1: Add tie-breakers / examples for the failure modes the report surfaced**

Open the worst-10 list and the confusion matrix. For each repeated misclassification, add either a tie-breaker rule or a few-shot example (still synthetic, not from real friend-group events) that disambiguates.

Example: if "Sunset beach picnic" lands in Outdoor when you want Social, add a tie-breaker:

```
- "Picnic" + a beach/park location -> Social (because the activity is the hanging-out, not the location).
```

- [ ] **Step 2: Re-run the sweep**

```bash
./gradlew :backend:classifierEvalTest
```

- [ ] **Step 3: Compare reports**

The new report and the previous one are both in `backend/build/reports/classifier-eval/`. Diff overall accuracy. If higher, commit the prompt change. If lower, revert the prompt edit and try a different tie-breaker.

- [ ] **Step 4: Commit (only on improvement)**

```bash
git add backend/src/main/resources/classifier/prompt-v2.txt
git commit -m "fix(classifier): tighten v2 prompt for <failure-mode>"
```

Repeat Steps 1–4 until the accuracy stops improving meaningfully.

---

## Task 11: Open the PR

**Files:** none.

- [ ] **Step 1: Push the branch**

```bash
git push -u origin feat/classifier-overhaul
```

- [ ] **Step 2: Open the PR**

```bash
gh pr create --title "feat(classifier): 10-category overhaul + eval harness + backfill" --body "$(cat <<'EOF'
## Summary

- Replaces the 5-category classifier (Food, Movie, Game, Outdoor, Trivia) with a 10-category set (adds Show, Market, Social, Trip, Other; renames the legacy "everything that's not the other four" usage of Trivia).
- Rewrites the LLM prompt with category definitions, tie-breaker rules, and synthetic few-shot examples.
- Adds an ad-hoc eval harness gated by `-PclassifierEval` / the `classifierEvalTest` gradle task. Sweeps prompt × temperature against the live Ollama, writes a markdown report.
- Adds a one-shot `ClassifierBackfillRunner` (gated by `peepbot.classifier.backfill.run-before`) that re-classifies every event categorised before a cutoff timestamp at one per second.

Spec: docs/superpowers/specs/2026-05-23-classifier-overhaul-design.md
Plan: docs/superpowers/plans/2026-05-23-classifier-overhaul.md

## Test plan

- [ ] `./gradlew :backend:test` passes (default tag excludes eval).
- [ ] `./gradlew :backend:classifierEvalTest` produces a report; v2 prompt beats v1; chosen temperature is recorded in `application.yaml`.
- [ ] On deploy, set `peepbot.classifier.backfill.run-before` to the deploy timestamp; watch logs for "Classifier backfill: N events processed" lines; unset on the next deploy.
- [ ] New events post-deploy classify under the v2 prompt with the new category set.
- [ ] Spot-check: comedy-shaped event titles classify as Show, not Trivia.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Return the PR URL**

The `gh pr create` output ends with the URL. Pass it back so the human can review.

---

## Self-review

**Spec coverage:**
- Categories list update → Task 1.
- New prompt with definitions/tie-breakers/few-shot → Task 3.
- Externalise prompt to classpath → Task 2.
- Temperature parameterisation → Task 4.
- Eval harness (corpus, runner, JUnit, gradle) → Tasks 6, 7, 8.
- Run eval, pick a winner → Task 9.
- Iterate (conditional) → Task 10.
- Backfill runner → Task 5.
- PR opening → Task 11.

No spec requirement is missing a task.

**Placeholder scan:**
- No "TBD" / "implement later" lines.
- Each step has the actual code or command to execute.
- Spring AI API signatures are guarded with "verify against the pinned version" — that's a verification step, not a placeholder, and is necessary because the API has shifted between Spring AI minor versions.

**Type consistency:**
- `loadPromptTemplate(String)` is referenced in Task 7 as a static helper; Task 2 introduces it (need to mark it `static` in the implementation to match — the code block in Task 2 already does).
- `EvalCase` shape (name, expected) consistent across Tasks 6, 7, 8.
- `ClassifierEvalReport.Run` record signature is consistent across Tasks 6 and 7.
- Property names: `peepbot.classifier.backfill.run-before`, `peepbot.classifier.backfill.pacing`, `dev.tylercash.rewind.classifier-temperature`. Consistent in `application.yaml`, the runner constructor, and `RewindConfiguration`.
