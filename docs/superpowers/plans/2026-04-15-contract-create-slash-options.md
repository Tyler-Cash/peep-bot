# Contract Create — Slash Command Options Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `/contract create` Discord modal with inline slash command options so each outcome gets its own labelled field instead of a single CSV box.

**Architecture:** Add `title`, `seed`, and `outcome_1`–`outcome_5` options to the `create` subcommand registration in `ClientConfiguration`. Update `ContractSlashCommandListenerImpl.handleCreate` to read those options directly and call `contractService.createContract` without opening a modal. Remove the now-dead `handleContractCreateModal` method and its dependencies from `ModalInteractionListener`.

**Tech Stack:** Java 21, Spring Boot 3, JDA 5, Mockito 5 (unit tests)

---

### Task 1: Register inline options on `/contract create`

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/ClientConfiguration.java:43`

- [ ] **Step 1: Add options to the `create` subcommand**

Replace:
```java
new SubcommandData("create", "Create a new prediction contract"),
```
With:
```java
new SubcommandData("create", "Create a new prediction contract")
        .addOption(OptionType.STRING, "title", "The prediction question", true)
        .addOption(OptionType.INTEGER, "seed", "Coins you stake as liquidity", true)
        .addOption(OptionType.STRING, "outcome_1", "First outcome (blank = YES)", false)
        .addOption(OptionType.STRING, "outcome_2", "Second outcome (blank = NO)", false)
        .addOption(OptionType.STRING, "outcome_3", "Third outcome", false)
        .addOption(OptionType.STRING, "outcome_4", "Fourth outcome", false)
        .addOption(OptionType.STRING, "outcome_5", "Fifth outcome", false),
```

`OptionType` is already imported. No other changes to this file.

- [ ] **Step 2: Compile check**

```bash
cd backend && ./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/ClientConfiguration.java
git commit -m "feat(contract): register inline options on /contract create subcommand"
```

---

### Task 2: Implement inline `handleCreate` with tests

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/contract/listener/ContractSlashCommandListenerImpl.java:64-93`
- Create: `backend/src/test/java/dev/tylercash/event/contract/listener/ContractSlashCommandListenerImplTest.java`

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/dev/tylercash/event/contract/listener/ContractSlashCommandListenerImplTest.java`:

```java
package dev.tylercash.event.contract.listener;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.tylercash.event.contract.ContractConfiguration;
import dev.tylercash.event.contract.ContractService;
import dev.tylercash.event.contract.UserBalanceService;
import dev.tylercash.event.discord.DiscordAuthService;
import dev.tylercash.event.discord.DiscordConfiguration;
import java.util.List;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContractSlashCommandListenerImplTest {

    ContractService contractService;
    UserBalanceService balanceService;
    DiscordAuthService authService;
    ContractConfiguration contractConfig;
    DiscordConfiguration discordConfig;
    ContractSlashCommandListenerImpl listener;

    SlashCommandInteractionEvent event;
    ReplyCallbackAction deferAction;
    InteractionHook hook;
    @SuppressWarnings("rawtypes")
    WebhookMessageCreateAction hookMessageAction;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        contractService = mock(ContractService.class);
        balanceService = mock(UserBalanceService.class);
        authService = mock(DiscordAuthService.class);
        contractConfig = new ContractConfiguration();
        discordConfig = mock(DiscordConfiguration.class);
        listener = new ContractSlashCommandListenerImpl(
                contractService, balanceService, authService, contractConfig, discordConfig);

        event = mock(SlashCommandInteractionEvent.class);
        deferAction = mock(ReplyCallbackAction.class);
        hook = mock(InteractionHook.class);
        hookMessageAction = mock(WebhookMessageCreateAction.class);

        when(event.getName()).thenReturn("contract");
        when(event.getSubcommandName()).thenReturn("create");
        when(event.deferReply(true)).thenReturn(deferAction);
        when(event.getHook()).thenReturn(hook);
        when(hook.sendMessage(anyString())).thenReturn(hookMessageAction);

        User user = mock(User.class);
        when(user.getId()).thenReturn("user-123");
        when(event.getUser()).thenReturn(user);
    }

    private OptionMapping stringOption(String value) {
        OptionMapping m = mock(OptionMapping.class);
        when(m.getAsString()).thenReturn(value);
        return m;
    }

    private OptionMapping longOption(long value) {
        OptionMapping m = mock(OptionMapping.class);
        when(m.getAsLong()).thenReturn(value);
        return m;
    }

    @Test
    void createWithExplicitOutcomes() {
        when(event.getOption("title")).thenReturn(stringOption("Will we hit 100 members?"));
        when(event.getOption("seed")).thenReturn(longOption(500L));
        when(event.getOption("outcome_1")).thenReturn(stringOption("YES"));
        when(event.getOption("outcome_2")).thenReturn(stringOption("NO"));
        when(event.getOption("outcome_3")).thenReturn(stringOption("MAYBE"));
        when(event.getOption("outcome_4")).thenReturn(null);
        when(event.getOption("outcome_5")).thenReturn(null);

        listener.handleSlashCommand(event);

        verify(contractService).createContract(
                "user-123",
                "Will we hit 100 members?",
                null,
                List.of("YES", "NO", "MAYBE"),
                500L);
        verify(hook).sendMessage(contains("created"));
    }

    @Test
    void createDefaultsToYesNoWhenNoOutcomesProvided() {
        when(event.getOption("title")).thenReturn(stringOption("Will we hit 100 members?"));
        when(event.getOption("seed")).thenReturn(longOption(500L));
        when(event.getOption("outcome_1")).thenReturn(null);
        when(event.getOption("outcome_2")).thenReturn(null);
        when(event.getOption("outcome_3")).thenReturn(null);
        when(event.getOption("outcome_4")).thenReturn(null);
        when(event.getOption("outcome_5")).thenReturn(null);

        listener.handleSlashCommand(event);

        verify(contractService).createContract(
                "user-123",
                "Will we hit 100 members?",
                null,
                List.of("YES", "NO"),
                500L);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.contract.listener.ContractSlashCommandListenerImplTest"
```
Expected: FAIL — `handleCreate` still opens a modal, so `contractService.createContract` is never called.

- [ ] **Step 3: Replace `handleCreate` in `ContractSlashCommandListenerImpl`**

Remove the entire `handleCreate` method (lines 64–93) and replace with:

```java
private void handleCreate(SlashCommandInteractionEvent event) {
    event.deferReply(true).queue();
    try {
        String title = event.getOption("title").getAsString();
        long seedAmount = event.getOption("seed").getAsLong();

        List<String> outcomeLabels = Stream.of("outcome_1", "outcome_2", "outcome_3", "outcome_4", "outcome_5")
                .map(event::getOption)
                .filter(o -> o != null && !o.getAsString().isBlank())
                .map(o -> o.getAsString().trim())
                .toList();
        if (outcomeLabels.isEmpty()) {
            outcomeLabels = List.of("YES", "NO");
        } else if (outcomeLabels.size() == 1) {
            outcomeLabels = List.of(outcomeLabels.get(0), "NO");
        }

        String userId = event.getUser().getId();
        contractService.createContract(userId, title, null, outcomeLabels, seedAmount);

        String emoji = contractConfig.getEmoji().getSuccess();
        event.getHook().sendMessage(emoji + " Prediction contract created!").queue();
    } catch (Exception e) {
        log.warn("Contract creation failed", e);
        event.getHook().sendMessage("❌ Error: " + e.getMessage()).queue();
    }
}
```

Also add `import java.util.stream.Stream;` to the imports at the top of the file. Remove the now-unused imports:
- `net.dv8tion.jda.api.components.label.Label`
- `net.dv8tion.jda.api.components.textinput.TextInput`
- `net.dv8tion.jda.api.components.textinput.TextInputStyle`
- `net.dv8tion.jda.api.modals.Modal`

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.contract.listener.ContractSlashCommandListenerImplTest"
```
Expected: PASS — both tests green.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/contract/listener/ContractSlashCommandListenerImpl.java \
        backend/src/test/java/dev/tylercash/event/contract/listener/ContractSlashCommandListenerImplTest.java
git commit -m "feat(contract): replace modal with inline slash options for /contract create"
```

---

### Task 3: Remove dead modal handler from `ModalInteractionListener`

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/listener/ModalInteractionListener.java`

- [ ] **Step 1: Remove `handleContractCreateModal` and its dispatch branch**

In `handleModalInteraction` (line 70), remove:
```java
if ("contract_create".equals(interaction.getModalId())) {
    handleContractCreateModal(modalInteractionEvent);
    return;
}
```

Delete the entire `handleContractCreateModal` method (lines 114–159).

- [ ] **Step 2: Remove dependencies only used by the deleted method**

In the class, remove:
- The `contractServiceProvider` field (`ObjectProvider<ContractService>`)
- The `contractConfig` field (`ContractConfiguration`)
- Both from the constructor parameters and constructor body assignments

Also remove the now-unused imports:
- `dev.tylercash.event.contract.ContractConfiguration`
- `dev.tylercash.event.contract.ContractService`
- `java.util.Arrays`

Verify the remaining imports still compile — `ObjectProvider` may still be needed for `EventService`.

- [ ] **Step 3: Full test suite**

```bash
cd backend && ./gradlew test
```
Expected: `BUILD SUCCESSFUL`, all tests green.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/listener/ModalInteractionListener.java
git commit -m "chore(contract): remove dead contract_create modal handler"
```
