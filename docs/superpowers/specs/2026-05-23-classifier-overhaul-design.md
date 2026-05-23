# Event Classifier Overhaul

**Date:** 2026-05-23
**Status:** Design — pending implementation plan

## Problem

Peep-bot's event classifier (Ollama LLM, in `TextNormalisationService`) is
misclassifying. Concrete failure mode: comedy shows land in "Trivia" because
Trivia is acting as a catch-all for "people speaking on a stage" — there's no
home for live performances, music, or theatre. The prompt also has no category
definitions, no disambiguation rules, and no few-shot examples, leaving the
LLM to invent its own semantics.

Existing categories: **Food, Movie, Game, Outdoor, Trivia** (defined at
`RewindConfiguration#categories`). Five buckets is too few to cover how the
group actually socialises.

## Goals

1. Replace the 5-category list with a 10-category list that maps to actual
   activities.
2. Rewrite the LLM prompt with definitions, tie-breaker rules, and few-shot
   examples so the model has a fighting chance at boundary cases.
3. Add an ad-hoc evaluation harness so the prompt + temperature can be tuned
   empirically rather than guessed.
4. Re-classify all existing events under the new prompt.

## Non-goals

- Switching models (stay on Ollama; switch is reversible later).
- Multi-label classification (one event = one category remains).
- Channel routing or role pings based on category (downstream of this work).
- Frontend admin changes from the older
  `2026-04-28-event-categorization-and-admin-panel.md` plan — orthogonal.

## Final category set

10 categories. Names are exactly the strings the LLM and the DB will see.

| Category | Covers |
|---|---|
| **Food** | Restaurants, dinners, brunch, food tours, cooking-together |
| **Movie** | Cinema, home movie nights |
| **Show** | Live comedy, stand-up, gigs, concerts, theatre, performances |
| **Game** | Board games, video games, escape rooms, arcade, bowling, mini golf |
| **Trivia** | Pub trivia and quiz nights specifically |
| **Outdoor** | Hikes, beach, kayaking, climbing, scenic walks, lookouts, sightseeing |
| **Market** | Markets, food fairs, art fairs, street festivals (wandering stalls) |
| **Social** | Bars, pubs, cocktail spots, house hangs, picnics, "just chill" |
| **Trip** | Day trips and overnight trips that involve travelling somewhere |
| **Other** | LLM's escape hatch — anything that genuinely doesn't fit |

Decisions:

- **Social** subsumes "Drinks" and "Hangout" — bars and someone's-place chills
  share the "we're getting together, no specific activity" shape.
- **Outdoor** subsumes "Scenic" — hikes and lookouts both live here. The
  hike-vs-lookout distinction was deemed not worth the LLM confusion.
- **Other** exists explicitly so the model doesn't force-fit weird events.
  Rewind/stats can group it separately or hide it.

## Prompt design

New template lives in `TextNormalisationService` (replacing the existing
inline one). Structure: role → categories with definitions → tie-breakers →
few-shot examples → event fields → "Category:" continuation.

```
You classify social outings into ONE category for a Discord bot used by a
friend group in Sydney. Reply with only the category name — no quotes, no
punctuation, no explanation.

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
- If a trip is overnight or requires travel away, Trip wins over the activity
  (e.g. "weekend in Bowral with wine tasting" → Trip).
- Words like "comedy", "stand-up", "improv", "gig", "concert", "theatre" → Show.
- Words like "quiz" or "trivia" → Trivia, not Show or Game.
- Hiking, beach, lookouts, sightseeing, scenic walks → Outdoor.
- House hangs, picnics, drinks at a bar → Social.
- When in doubt between two categories, prefer the more specific one
  (Trivia > Game, Show > Movie, Market > Outdoor).

Examples:
- "Comedy night with the local stand-up crew" → Show
- "Tuesday pub trivia at the Toxteth" → Trivia
- "Beach day at Manly" → Outdoor
- "Movie marathon at the Hayden Orpheum" → Movie
- "Drinks at the Baxter Inn" → Social
- "Long weekend up to Byron" → Trip
- "Glebe Markets Saturday" → Market

Event name: "<name>"
Location: "<location>"
Date: <date>
Description: "<description>"
Category:
```

The prompt template is loaded from a classpath resource so the eval harness
can sweep variants without recompiling.

**Location:** `backend/src/main/resources/classifier/prompt-v2.txt`.

The existing inline prompt becomes `prompt-v1.txt` so the harness can compare.

## Eval harness

A JUnit test gated by a Gradle property — included in regular `./gradlew test`
runs only when the property is set, so CI doesn't accidentally hit Ollama for
40 minutes.

### Layout

```
backend/src/test/
  java/dev/tylercash/event/rewind/eval/
    ClassifierEvalTest.java       — JUnit driver, @EnabledIfSystemProperty
    ClassifierEvalRunner.java     — orchestrates sweep, calls classifier
    ClassifierEvalReport.java     — aggregates results, renders markdown
    EvalCase.java                 — DTO: name, expected, optional location/desc
  resources/classifier-eval/
    corpus.yaml                   — 30 hand-curated cases (see below)
```

### Corpus (30 cases, hand-curated, synthetic)

Held out from the prompt's few-shot examples (no overlap). All synthetic —
no real friend-group events. Distribution roughly proportional to expected
volume, with extra coverage for confusion-prone categories (Show, Outdoor,
Social).

```yaml
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

### Sweep

| Knob | Values |
|---|---|
| Prompt | `v1` (legacy), `v2` (new) |
| Temperature | `0.0`, `0.1`, `0.3`, `0.5` |
| Runs per (event, config) | `10` |

30 events × 2 prompts × 4 temps × 10 runs = **2,400 Ollama calls**. At ~1s per
call on local Ollama this is ~40 minutes. Acceptable for an ad-hoc job.

### How to run

```
./gradlew :backend:test --tests ClassifierEvalTest -PclassifierEval
```

Without `-PclassifierEval` the test is disabled — regular `./gradlew test`
ignores it.

### Output

Markdown report written to
`backend/build/reports/classifier-eval/<timestamp>.md`. Sections:

1. **Summary table** — overall accuracy per (prompt, temperature).
2. **Confusion matrix** per top config (rows = expected, cols = predicted).
3. **Per-event flake rate** — for each event, % of runs that diverged from
   the modal answer.
4. **Worst-10 events** — events that failed most often, with the answer
   distribution they produced.
5. **Recommendation** — best (prompt, temperature) combo by accuracy.

### Existing classifier API change

`TextNormalisationService.classify(Event)` currently uses the inline prompt
and the chat model's default temperature. The harness needs to vary both.
Minimal-impact refactor:

- Extract prompt to a classpath resource (`prompt-v2.txt`), loaded once.
- Add an overload: `classify(Event event, String promptResource, double temperature)`.
- The current `classify(Event)` delegates to `classify(event, "prompt-v2.txt", configuredTemperature)`.
- `configuredTemperature` comes from a new property
  `peepbot.classifier.temperature` (default `0.1`, set after eval picks a
  winner).

This keeps the production path simple (one-arg call) while letting the
harness sweep configurations.

## Reclassification of existing events

After the new categories deploy, all events classified under the old set
need re-running. Two ways:

| Option | Mechanism |
|---|---|
| **A (rec)** | Add a one-shot Spring `ApplicationRunner` (gated by a property `peepbot.classifier.backfill=true`) that streams every Event with non-null category, calls `embeddingService.classifyEvent(event)` for each, with a configurable rate-limit (say 1 / second) so Ollama isn't hammered. Toggle property on, deploy, watch logs, toggle off. |
| B | Manually call the existing `POST /event/{id}/recategorize` admin endpoint for each event via a script. |

Option A keeps the work inside the JVM where retry/rate-limit logic already
exists, and is restartable if it fails partway. Going with A.

The backfill should:

- Skip events that haven't been classified yet (they'll get the new prompt
  through the normal lifecycle).
- Log progress every 50 events.
- Not block startup — run async on a single dedicated thread.
- Be idempotent: if it crashes and restarts, it picks up where it left off
  by checking a `last_reclassified_at` column added to `event` (or, simpler,
  by comparing the event's `categorised_at` to the deploy timestamp via a
  one-shot property).

Simpler still: skip the column. The runner queries `WHERE category IS NOT
NULL AND categorised_at < ?` where `?` is supplied as a property. After the
deploy completes, the property is removed and the runner becomes a no-op.

## Files touched

| File | Change |
|---|---|
| `backend/src/main/java/dev/tylercash/event/rewind/RewindConfiguration.java` | Categories list → 10 new entries. |
| `backend/src/main/java/dev/tylercash/event/rewind/TextNormalisationService.java` | Prompt → loaded from classpath. Overload accepting prompt resource + temperature. |
| `backend/src/main/resources/classifier/prompt-v2.txt` | **New.** The prompt above. |
| `backend/src/main/resources/classifier/prompt-v1.txt` | **New.** Snapshot of the existing inline prompt for comparison. |
| `backend/src/main/resources/application.yaml` | New property `peepbot.classifier.temperature: 0.1`. New `peepbot.classifier.backfill.*` props for the runner. |
| `backend/src/main/java/dev/tylercash/event/rewind/ClassifierBackfillRunner.java` | **New.** `ApplicationRunner`, gated by property. |
| `backend/src/test/java/dev/tylercash/event/rewind/TextNormalisationServiceTest.java` | Mocks updated to new category strings; new tests for the overload. |
| `backend/src/test/java/dev/tylercash/event/rewind/eval/ClassifierEvalTest.java` | **New.** JUnit driver. |
| `backend/src/test/java/dev/tylercash/event/rewind/eval/ClassifierEvalRunner.java` | **New.** Sweep orchestrator. |
| `backend/src/test/java/dev/tylercash/event/rewind/eval/ClassifierEvalReport.java` | **New.** Markdown renderer. |
| `backend/src/test/resources/classifier-eval/corpus.yaml` | **New.** 30 synthetic events. |

## Validation

1. Existing unit tests pass (`./gradlew test`).
2. `./gradlew :backend:test --tests ClassifierEvalTest -PclassifierEval`
   produces a report. Best config's overall accuracy ≥ 80% (target — actual
   pass bar set after first run).
3. Deploy with `peepbot.classifier.backfill=true` set. Watch logs: every
   event with `categorised_at < deploy_timestamp` gets re-classified at
   ~1/sec. No error spike.
4. Spot-check: the failing example shape ("comedy show with chaotic
   capitalisation") now classifies as `Show` consistently across 10 runs at
   the chosen temperature.

## Open questions for the planner

1. **Where does the prompt loader cache?** Spring `ResourceLoader` once at
   startup, or per-call? Once at startup (immutable string).
2. **Concurrency on backfill** — one thread is the conservative choice. Bump
   later if Ollama can handle it.
3. **Eval test exclusion** — `@EnabledIfSystemProperty` vs JUnit `@Tag` +
   gradle filter. Either works; pick whichever the codebase already uses.

## Out of scope (follow-ups)

- Frontend changes from `2026-04-28-event-categorization-and-admin-panel.md`
  (admin recategorise button) — separate effort.
- Channel routing by category.
- Automatic prompt regeneration / continuous eval.
