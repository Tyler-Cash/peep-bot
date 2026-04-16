# Prediction Contract Market — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a LMSR-based prediction contract market to Peep Bot, accessed via Discord slash commands, with per-contract channels and live probability graphs.

**Architecture:** Split the monolithic `DiscordService` into focused sub-services (`DiscordChannelService`, `DiscordMessageService`, `DiscordRoleService`, `DiscordAuthService`). Build a fully isolated `contract/` package on top of these shared services — deletable without touching `event/`.

**Tech Stack:** Spring Boot 3.5.8, JDA 6.4.0, JFreeChart 1.5.4, PostgreSQL/Liquibase, Lombok, Spring Data JPA

**Spec:** `docs/superpowers/specs/2026-04-14-prediction-market-design.md`

---

## Phase 1 — Discord Service Decomposition

### Task 1: Create DiscordAuthService

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/discord/DiscordAuthService.java`

- [ ] **Step 1: Create the service**

```java
package dev.tylercash.event.discord;

import dev.tylercash.event.security.dev.DevUserProperties;
import java.util.Optional;
import lombok.AllArgsConstructor;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class DiscordAuthService {
    private final JDA jda;
    private final DiscordConfiguration discordConfiguration;
    private final Optional<DevUserProperties> devUserProperties;

    public Member getMember(long guildId, long userId) {
        return jda.getGuildById(guildId).retrieveMemberById(userId).complete();
    }

    public boolean isMember(long guildId, long userId) {
        return getMember(guildId, userId) != null;
    }

    public boolean hasRole(long guildId, long userId, String roleName) {
        if (devUserProperties.isPresent() && devUserProperties.get().isForceAdmin()) {
            return true;
        }
        Member member = getMember(guildId, userId);
        return member != null
                && member.getRoles().stream()
                        .anyMatch(role -> role.getName().equalsIgnoreCase(roleName));
    }

    public boolean isEventAdmin(long guildId, long userId) {
        return hasRole(guildId, userId, discordConfiguration.getAdminRole());
    }
}
```

- [ ] **Step 2: Commit**
```bash
cd backend
git add src/main/java/dev/tylercash/event/discord/DiscordAuthService.java
git commit -m "refactor(discord): extract DiscordAuthService from DiscordService"
```

---

### Task 2: Create DiscordRoleService

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/discord/DiscordRoleService.java`

- [ ] **Step 1: Create the service**

```java
package dev.tylercash.event.discord;

import java.util.List;
import lombok.AllArgsConstructor;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@AllArgsConstructor
public class DiscordRoleService {
    private final JDA jda;

    public Role createRole(Guild guild, String name) {
        return guild.createRole().setName(name).complete();
    }

    public void deleteRole(Guild guild, Long roleId) {
        if (roleId == null) return;
        Role role = guild.getRoleById(roleId);
        if (role != null) role.delete().queue();
    }

    public List<Role> getRolesByName(long guildId, String name) {
        List<Role> roles = jda.getGuildById(guildId).getRolesByName(name, true);
        if (roles.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "No roles found matching name " + name);
        }
        return roles;
    }

    public void addRoleToMember(Guild guild, Member member, Long roleId) {
        if (roleId == null) return;
        Role role = guild.getRoleById(roleId);
        if (role != null) guild.addRoleToMember(member, role).queue();
    }

    public void removeRoleFromMember(Guild guild, Member member, Long roleId) {
        if (roleId == null) return;
        Role role = guild.getRoleById(roleId);
        if (role != null && member.getRoles().contains(role)) {
            guild.removeRoleFromMember(member, role).queue();
        }
    }
}
```

- [ ] **Step 2: Commit**
```bash
git add src/main/java/dev/tylercash/event/discord/DiscordRoleService.java
git commit -m "refactor(discord): extract DiscordRoleService from DiscordService"
```

---

### Task 3: Create DiscordChannelService

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/discord/DiscordChannelService.java`

- [ ] **Step 1: Create the service**

```java
package dev.tylercash.event.discord;

import java.util.List;
import lombok.AllArgsConstructor;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@AllArgsConstructor
public class DiscordChannelService {
    private final JDA jda;

    public Category getCategoryByName(long guildId, String name) {
        List<Category> categories = jda.getGuildById(guildId).getCategoriesByName(name, true);
        if (categories.size() > 1) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Found multiple categories named \"" + name + "\"");
        }
        if (categories.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "No category found called \"" + name + "\"");
        }
        return categories.get(0);
    }

    public Category getOrCreateCategory(Guild guild, String name) {
        List<Category> existing = guild.getCategoriesByName(name, true);
        if (!existing.isEmpty()) return existing.get(0);
        return guild.createCategory(name).complete();
    }

    public TextChannel createTextChannel(Category category, String name) {
        return category.createTextChannel(name).setPosition(99).complete();
    }

    public TextChannel createPrivateTextChannel(
            Category category, String name, long deniedRoleId, long allowedRoleId) {
        return category.createTextChannel(name)
                .addRolePermissionOverride(deniedRoleId, 0L, Permission.VIEW_CHANNEL.getRawValue())
                .addRolePermissionOverride(
                        allowedRoleId,
                        Permission.VIEW_CHANNEL.getRawValue()
                                | Permission.MESSAGE_SEND.getRawValue()
                                | Permission.MESSAGE_HISTORY.getRawValue(),
                        0L)
                .complete();
    }

    public TextChannel getTextChannel(long channelId) {
        return jda.getChannelById(TextChannel.class, channelId);
    }

    public void deleteChannel(long channelId) {
        TextChannel channel = jda.getChannelById(TextChannel.class, channelId);
        if (channel != null) channel.delete().queue();
    }

    public void setChannelName(long channelId, String name) {
        TextChannel channel = getTextChannel(channelId);
        if (channel != null && !channel.getName().equals(name)) {
            channel.getManager().setName(name).queue();
        }
    }

    public void moveChannelToCategory(TextChannel channel, Category target) {
        channel.getManager().setParent(target).sync().queue();
    }
}
```

- [ ] **Step 2: Commit**
```bash
git add src/main/java/dev/tylercash/event/discord/DiscordChannelService.java
git commit -m "refactor(discord): extract DiscordChannelService from DiscordService"
```

---

### Task 4: Create DiscordMessageService

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/discord/DiscordMessageService.java`

- [ ] **Step 1: Create the service**

```java
package dev.tylercash.event.discord;

import java.util.Collection;
import java.util.List;
import lombok.AllArgsConstructor;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class DiscordMessageService {
    private final JDA jda;

    public Message sendMessage(TextChannel channel, String content) {
        return channel.sendMessage(content).complete();
    }

    public Message sendEmbed(
            TextChannel channel,
            String content,
            Collection<MessageEmbed> embeds,
            List<ActionRow> components) {
        MessageCreateBuilder builder = new MessageCreateBuilder()
                .addContent(content)
                .addEmbeds(embeds);
        if (components != null && !components.isEmpty()) {
            builder.addComponents(components);
        }
        Message message = channel.sendMessage(builder.build()).complete();
        message.pin().queue();
        return message;
    }

    public Message sendWithAttachment(TextChannel channel, String content, byte[] data, String filename) {
        return channel.sendMessage(content)
                .addFiles(FileUpload.fromData(data, filename))
                .complete();
    }

    public Message sendEmbedWithAttachment(
            TextChannel channel,
            Collection<MessageEmbed> embeds,
            byte[] data,
            String filename) {
        MessageCreateBuilder builder = new MessageCreateBuilder().addEmbeds(embeds);
        Message message = channel.sendMessage(builder.build())
                .addFiles(FileUpload.fromData(data, filename))
                .complete();
        message.pin().queue();
        return message;
    }

    public void editEmbedWithAttachment(
            long channelId,
            long messageId,
            Collection<MessageEmbed> embeds,
            byte[] data,
            String filename) {
        TextChannel channel = jda.getChannelById(TextChannel.class, channelId);
        if (channel == null) return;
        channel.editMessageById(messageId)
                .setEmbeds(embeds)
                .setFiles(FileUpload.fromData(data, filename))
                .queue();
    }

    public void editEmbeds(long channelId, long messageId, Collection<MessageEmbed> embeds) {
        TextChannel channel = jda.getChannelById(TextChannel.class, channelId);
        if (channel == null) return;
        channel.editMessageEmbedsById(messageId, embeds).queue();
    }

    public void editComponents(long channelId, long messageId, List<ActionRow> components) {
        TextChannel channel = jda.getChannelById(TextChannel.class, channelId);
        if (channel == null) return;
        if (components == null || components.isEmpty()) {
            channel.editMessageComponentsById(messageId).queue();
        } else {
            channel.editMessageComponentsById(messageId, components).queue();
        }
    }

    public void deleteMessage(long channelId, long messageId) {
        TextChannel channel = jda.getChannelById(TextChannel.class, channelId);
        if (channel != null) channel.deleteMessageById(messageId).queue();
    }
}
```

- [ ] **Step 2: Commit**
```bash
git add src/main/java/dev/tylercash/event/discord/DiscordMessageService.java
git commit -m "refactor(discord): extract DiscordMessageService from DiscordService"
```

---

### Task 5: Migrate DiscordService — inject sub-services and update event/ callers

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/DiscordService.java`
- Modify: `backend/src/main/java/dev/tylercash/event/event/EventService.java`
- Modify: `backend/src/main/java/dev/tylercash/event/discord/listener/ButtonInteractionListener.java`
- Modify: `backend/src/main/java/dev/tylercash/event/event/statemachine/operation/InitChannelOperation.java`
- Modify: `backend/src/main/java/dev/tylercash/event/discord/DiscordInitializationService.java`

- [ ] **Step 1: Refactor DiscordService to inject and delegate to sub-services**

Replace the constructor fields in `DiscordService`. The class now delegates channel, message, role, and auth operations to the new services. The event-specific orchestration (methods that take `Event` objects) stays here. Update the class fields and methods:

```java
// Replace existing fields with:
private final DiscordConfiguration discordConfiguration;
private final DiscordChannelService discordChannelService;
private final DiscordMessageService discordMessageService;
private final DiscordRoleService discordRoleService;
private final DiscordAuthService discordAuthService;
private final EmbedService embedService;
private final EventRepository eventRepository;
private final FeatureTogglesConfiguration featureToggles;
private final RateLimiter notifyEventRoles;
private final Clock clock;
private final JDA jda;
```

Update each method body to delegate. For example:

```java
public TextChannel createEventChannel(Event event) {
    Category category = discordChannelService.getCategoryByName(
            discordConfiguration.getGuildId(), EVENT_CATEGORY);
    return discordChannelService.createTextChannel(category,
            DiscordUtil.getChannelNameFromEvent(event));
}

public void deleteEventChannel(Event event) {
    discordChannelService.deleteChannel(event.getChannelId());
}

public void archiveEventChannel(Event event) {
    TextChannel channel = discordChannelService.getTextChannel(event.getChannelId());
    Category archive = discordChannelService.getCategoryByName(
            discordConfiguration.getGuildId(), EVENT_ARCHIVE_CATEGORY);
    discordChannelService.moveChannelToCategory(channel, archive);
    discordChannelService.sortChannelsByChannelName(archive); // move sortChannelsByChannelName to DiscordChannelService
}

public void updateEventMessage(Event event) {
    discordMessageService.editEmbeds(
            event.getChannelId(), event.getMessageId(), embedService.getMessage(event, clock));
}

public void removeEventButtons(Event event) {
    discordMessageService.editComponents(event.getChannelId(), event.getMessageId(), List.of());
}

public boolean isUserAdminOfServer(long serverId, long userId) {
    return discordAuthService.isEventAdmin(serverId, userId);
}

public void createEventRoles(Event event) {
    Guild guild = jda.getGuildById(discordConfiguration.getGuildId());
    String baseName = event.getName().length() > 89
            ? event.getName().substring(0, 89) : event.getName();
    if (event.getAcceptedRoleId() == null)
        event.setAcceptedRoleId(discordRoleService.createRole(guild, baseName + " - Accepted").getIdLong());
    if (event.getDeclinedRoleId() == null)
        event.setDeclinedRoleId(discordRoleService.createRole(guild, baseName + " - Declined").getIdLong());
    if (event.getMaybeRoleId() == null)
        event.setMaybeRoleId(discordRoleService.createRole(guild, baseName + " - Maybe").getIdLong());
}

public void deleteEventRoles(Event event) {
    Guild guild = jda.getGuildById(discordConfiguration.getGuildId());
    discordRoleService.deleteRole(guild, event.getAcceptedRoleId());
    discordRoleService.deleteRole(guild, event.getDeclinedRoleId());
    discordRoleService.deleteRole(guild, event.getMaybeRoleId());
}

public void assignEventRole(Event event, String snowflake, AttendanceStatus status) {
    Guild guild = jda.getGuildById(discordConfiguration.getGuildId());
    Member member = guild.retrieveMemberById(snowflake).complete();
    if (member == null) return;
    for (Long roleId : List.of(event.getAcceptedRoleId(), event.getDeclinedRoleId(), event.getMaybeRoleId())) {
        discordRoleService.removeRoleFromMember(guild, member, roleId);
    }
    Long roleId = switch (status) {
        case ACCEPTED -> event.getAcceptedRoleId();
        case DECLINED -> event.getDeclinedRoleId();
        case MAYBE -> event.getMaybeRoleId();
        default -> null;
    };
    discordRoleService.addRoleToMember(guild, member, roleId);
}
```

Also move `sortChannelsByChannelName` and `sortChannelsByEventDate` to delegate to `DiscordChannelService` (move their implementations there, keep the scheduled wrappers in DiscordService).

- [ ] **Step 2: Update DiscordInitializationService to use DiscordChannelService**

```java
// Replace ensureCategory body:
Category ensureCategory(Guild guild, String categoryName) {
    return discordChannelService.getOrCreateCategory(guild, categoryName);
}
```

Add `DiscordChannelService discordChannelService` to the constructor.

- [ ] **Step 3: Run tests to verify nothing is broken**
```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run spotless**
```bash
./gradlew spotlessApply
```

- [ ] **Step 5: Commit**
```bash
git add -p
git commit -m "refactor(discord): delegate DiscordService to extracted sub-services"
```

---

### Task 6: Add SlashCommandListener + update ClientConfiguration

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/discord/listener/SlashCommandListener.java`
- Modify: `backend/src/main/java/dev/tylercash/event/discord/ClientConfiguration.java`

- [ ] **Step 1: Create SlashCommandListener skeleton**

```java
package dev.tylercash.event.discord.listener;

import lombok.extern.Slf4j;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SlashCommandListener extends ListenerAdapter {
    // ContractSlashCommandListener injected via ObjectProvider to avoid circular deps
    private final ObjectProvider<dev.tylercash.event.contract.listener.ContractSlashCommandListener>
            contractListenerProvider;

    public SlashCommandListener(
            ObjectProvider<dev.tylercash.event.contract.listener.ContractSlashCommandListener> contractListenerProvider) {
        this.contractListenerProvider = contractListenerProvider;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String name = event.getName();
        if ("contract".equals(name) || "balance".equals(name)) {
            contractListenerProvider.getObject().handleSlashCommand(event);
        }
    }
}
```

- [ ] **Step 2: Update ClientConfiguration to register SlashCommandListener**

```java
@Bean
public JDA jda() throws InterruptedException {
    return JDABuilder.createDefault(discordConfiguration.getToken())
            .addEventListeners(buttonInteractionListener)
            .addEventListeners(modalInteractionListener)
            .addEventListeners(slashCommandListener)
            .enableIntents(EnumSet.allOf(GatewayIntent.class))
            .build()
            .awaitReady();
}
```

Add `private final SlashCommandListener slashCommandListener;` to the fields.

- [ ] **Step 3: Run tests**
```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL (SlashCommandListener won't wire until ContractSlashCommandListener exists — keep it `@Lazy` or use `ObjectProvider` as shown)

- [ ] **Step 4: Commit**
```bash
git add src/main/java/dev/tylercash/event/discord/listener/SlashCommandListener.java \
        src/main/java/dev/tylercash/event/discord/ClientConfiguration.java
git commit -m "feat(discord): add SlashCommandListener routing for /contract commands"
```

---

## Phase 2 — Contract Feature

### Task 7: JFreeChart dependency + Liquibase migrations

**Files:**
- Modify: `backend/build.gradle`
- Create: `backend/src/main/resources/db/changelog/contract/db.changelog-contract.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml`

- [ ] **Step 1: Add JFreeChart to build.gradle**

In the `dependencies` block, add:
```gradle
implementation 'org.jfree:jfreechart:1.5.4'
```

- [ ] **Step 2: Create the contract Liquibase changelog**

```yaml
databaseChangeLog:
  - changeSet:
      id: create-user-balance-table
      author: tylercash
      changes:
        - createTable:
            tableName: user_balance
            columns:
              - column:
                  name: snowflake
                  type: text
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: balance
                  type: bigint
                  defaultValueNumeric: 1000
                  constraints:
                    nullable: false
              - column:
                  name: updated_at
                  type: timestamptz
                  constraints:
                    nullable: false

  - changeSet:
      id: create-contract-table
      author: tylercash
      changes:
        - createTable:
            tableName: contract
            columns:
              - column:
                  name: id
                  type: uuid
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: title
                  type: text
                  constraints:
                    nullable: false
              - column:
                  name: description
                  type: text
              - column:
                  name: state
                  type: text
                  constraints:
                    nullable: false
              - column:
                  name: creator_snowflake
                  type: text
                  constraints:
                    nullable: false
              - column:
                  name: channel_id
                  type: bigint
              - column:
                  name: message_id
                  type: bigint
              - column:
                  name: server_id
                  type: bigint
              - column:
                  name: b_parameter
                  type: double precision
                  constraints:
                    nullable: false
              - column:
                  name: seed_amount
                  type: bigint
                  constraints:
                    nullable: false
              - column:
                  name: winning_outcome_id
                  type: uuid
              - column:
                  name: created_at
                  type: timestamptz
                  constraints:
                    nullable: false

  - changeSet:
      id: create-contract-outcome-table
      author: tylercash
      changes:
        - createTable:
            tableName: contract_outcome
            columns:
              - column:
                  name: id
                  type: uuid
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: contract_id
                  type: uuid
                  constraints:
                    nullable: false
                    foreignKeyName: fk_contract_outcome_contract
                    references: contract(id)
              - column:
                  name: label
                  type: text
                  constraints:
                    nullable: false
              - column:
                  name: shares_outstanding
                  type: double precision
                  defaultValueNumeric: 0
                  constraints:
                    nullable: false

  - changeSet:
      id: create-contract-trade-table
      author: tylercash
      changes:
        - createTable:
            tableName: contract_trade
            columns:
              - column:
                  name: id
                  type: bigint
                  autoIncrement: true
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: contract_id
                  type: uuid
                  constraints:
                    nullable: false
                    foreignKeyName: fk_contract_trade_contract
                    references: contract(id)
              - column:
                  name: outcome_id
                  type: uuid
                  constraints:
                    nullable: false
                    foreignKeyName: fk_contract_trade_outcome
                    references: contract_outcome(id)
              - column:
                  name: snowflake
                  type: text
                  constraints:
                    nullable: false
              - column:
                  name: shares_bought
                  type: double precision
                  constraints:
                    nullable: false
              - column:
                  name: cost_paid
                  type: bigint
                  constraints:
                    nullable: false
              - column:
                  name: prob_before
                  type: jsonb
                  constraints:
                    nullable: false
              - column:
                  name: traded_at
                  type: timestamptz
                  constraints:
                    nullable: false
        - createIndex:
            indexName: idx_contract_trade_contract_id
            tableName: contract_trade
            columns:
              - column:
                  name: contract_id
```

- [ ] **Step 3: Include in master changelog**

Add at the end of `db.changelog-master.yaml`:
```yaml
- include:
    file: db/changelog/contract/db.changelog-contract.yaml
```

- [ ] **Step 4: Run tests to verify migrations apply**
```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL (Liquibase runs against H2 in tests)

- [ ] **Step 5: Commit**
```bash
git add build.gradle \
        src/main/resources/db/changelog/contract/ \
        src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "feat(contract): add JFreeChart dependency and Liquibase migrations"
```

---

### Task 8: ContractConfiguration + ContractState

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/contract/ContractConfiguration.java`
- Create: `backend/src/main/java/dev/tylercash/event/contract/model/ContractState.java`

- [ ] **Step 1: Create ContractState enum**

```java
package dev.tylercash.event.contract.model;

public enum ContractState {
    CREATED,
    INIT_CHANNEL,
    OPEN,
    RESOLVED,
    CANCELLED
}
```

- [ ] **Step 2: Create ContractConfiguration**

```java
package dev.tylercash.event.contract;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "dev.tylercash.contract")
public class ContractConfiguration {
    private String resolverRole = "prediction-resolver";
    private String categoryName = "prediction contracts";
    private long defaultBalance = 1000L;
    private long negativeTradeCap = 100L;
    private long minSeedAmount = 100L;
}
```

- [ ] **Step 3: Add config to application.yaml (defaults only — nothing required)**

In `backend/src/main/resources/application.yaml`, verify `dev.tylercash.contract` prefix is recognised (Spring auto-binds `@ConfigurationProperties`). No YAML entry needed since all fields have defaults.

- [ ] **Step 4: Commit**
```bash
git add src/main/java/dev/tylercash/event/contract/
git commit -m "feat(contract): add ContractConfiguration and ContractState"
```

---

### Task 9: Contract entity models + repositories

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/contract/model/Contract.java`
- Create: `backend/src/main/java/dev/tylercash/event/contract/model/ContractOutcome.java`
- Create: `backend/src/main/java/dev/tylercash/event/contract/model/ContractTrade.java`
- Create: `backend/src/main/java/dev/tylercash/event/contract/repository/ContractRepository.java`
- Create: `backend/src/main/java/dev/tylercash/event/contract/repository/ContractOutcomeRepository.java`
- Create: `backend/src/main/java/dev/tylercash/event/contract/repository/ContractTradeRepository.java`

- [ ] **Step 1: Create Contract entity**

```java
package dev.tylercash.event.contract.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@Table(name = "contract")
@NoArgsConstructor
public class Contract {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContractState state = ContractState.CREATED;

    @Column(name = "creator_snowflake", nullable = false)
    private String creatorSnowflake;

    @Column(name = "channel_id")
    private Long channelId;

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "server_id")
    private Long serverId;

    @Column(name = "b_parameter", nullable = false)
    private double bParameter;

    @Column(name = "seed_amount", nullable = false)
    private long seedAmount;

    @Column(name = "winning_outcome_id")
    private UUID winningOutcomeId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "contract", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<ContractOutcome> outcomes = new ArrayList<>();
}
```

- [ ] **Step 2: Create ContractOutcome entity**

```java
package dev.tylercash.event.contract.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@Table(name = "contract_outcome")
@NoArgsConstructor
public class ContractOutcome {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", nullable = false)
    @JsonIgnore
    private Contract contract;

    @Column(nullable = false)
    private String label;

    @Column(name = "shares_outstanding", nullable = false)
    private double sharesOutstanding = 0.0;
}
```

- [ ] **Step 3: Create ContractTrade entity**

```java
package dev.tylercash.event.contract.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

@Data
@Entity
@Table(name = "contract_trade")
@NoArgsConstructor
public class ContractTrade {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "outcome_id", nullable = false)
    private UUID outcomeId;

    @Column(nullable = false)
    private String snowflake;

    @Column(name = "shares_bought", nullable = false)
    private double sharesBought;

    @Column(name = "cost_paid", nullable = false)
    private long costPaid;

    @Type(JsonBinaryType.class)
    @Column(name = "prob_before", columnDefinition = "jsonb", nullable = false)
    private JsonNode probBefore;

    @Column(name = "traded_at", nullable = false)
    private Instant tradedAt;
}
```

Note: Add `io.hypersistence:hypersistence-utils-hibernate-63:3.9.0` to build.gradle for `JsonBinaryType`. Add to `dependencies` block:
```gradle
implementation 'io.hypersistence:hypersistence-utils-hibernate-63:3.9.0'
```

- [ ] **Step 4: Create repositories**

```java
// ContractRepository.java
package dev.tylercash.event.contract.repository;

import dev.tylercash.event.contract.model.Contract;
import dev.tylercash.event.contract.model.ContractState;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractRepository extends JpaRepository<Contract, UUID> {
    List<Contract> findByStateIn(List<ContractState> states);
    Contract findByChannelId(long channelId);
}
```

```java
// ContractOutcomeRepository.java
package dev.tylercash.event.contract.repository;

import dev.tylercash.event.contract.model.ContractOutcome;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractOutcomeRepository extends JpaRepository<ContractOutcome, UUID> {
    List<ContractOutcome> findByContractId(UUID contractId);
}
```

```java
// ContractTradeRepository.java
package dev.tylercash.event.contract.repository;

import dev.tylercash.event.contract.model.ContractTrade;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractTradeRepository extends JpaRepository<ContractTrade, Long> {
    List<ContractTrade> findByContractIdOrderByTradedAtAsc(UUID contractId);
    List<ContractTrade> findByContractIdAndSnowflake(UUID contractId, String snowflake);
}
```

- [ ] **Step 5: Run tests**
```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: spotlessApply + commit**
```bash
./gradlew spotlessApply
git add src/main/java/dev/tylercash/event/contract/ build.gradle
git commit -m "feat(contract): add entity models and repositories"
```

---

### Task 10: UserBalance entity + UserBalanceService

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/contract/model/UserBalance.java`
- Create: `backend/src/main/java/dev/tylercash/event/contract/repository/UserBalanceRepository.java`
- Create: `backend/src/main/java/dev/tylercash/event/contract/UserBalanceService.java`
- Test: `backend/src/test/java/dev/tylercash/event/contract/UserBalanceServiceTest.java`

- [ ] **Step 1: Create UserBalance entity**

```java
package dev.tylercash.event.contract.model;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@Table(name = "user_balance")
@NoArgsConstructor
public class UserBalance {
    @Id
    private String snowflake;

    @Column(nullable = false)
    private long balance;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

- [ ] **Step 2: Create UserBalanceRepository**

```java
package dev.tylercash.event.contract.repository;

import dev.tylercash.event.contract.model.UserBalance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserBalanceRepository extends JpaRepository<UserBalance, String> {}
```

- [ ] **Step 3: Write failing test**

```java
package dev.tylercash.event.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.tylercash.event.contract.model.UserBalance;
import dev.tylercash.event.contract.repository.UserBalanceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserBalanceServiceTest {
    @Mock UserBalanceRepository repo;
    @Mock ContractConfiguration config;
    @Mock Clock clock;

    @InjectMocks UserBalanceService service;

    @Test
    void newUserGetsDefaultBalance() {
        when(repo.findById("123")).thenReturn(Optional.empty());
        when(config.getDefaultBalance()).thenReturn(1000L);
        when(clock.instant()).thenReturn(Instant.EPOCH);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        long balance = service.getBalance("123");

        assertThat(balance).isEqualTo(1000L);
    }

    @Test
    void existingUserReturnsStoredBalance() {
        UserBalance ub = new UserBalance();
        ub.setSnowflake("456");
        ub.setBalance(250L);
        when(repo.findById("456")).thenReturn(Optional.of(ub));

        long balance = service.getBalance("456");

        assertThat(balance).isEqualTo(250L);
    }

    @Test
    void deductReducesBalance() {
        UserBalance ub = new UserBalance();
        ub.setSnowflake("789");
        ub.setBalance(500L);
        when(repo.findById("789")).thenReturn(Optional.of(ub));
        when(clock.instant()).thenReturn(Instant.EPOCH);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.deduct("789", 200L);

        verify(repo).save(argThat(b -> b.getBalance() == 300L));
    }

    @Test
    void negativeBalanceAllowsTradeUpToCap() {
        UserBalance ub = new UserBalance();
        ub.setSnowflake("neg");
        ub.setBalance(-500L);
        when(repo.findById("neg")).thenReturn(Optional.of(ub));
        when(config.getNegativeTradeCap()).thenReturn(100L);

        long maxTrade = service.getMaxTrade("neg");

        assertThat(maxTrade).isEqualTo(100L);
    }

    @Test
    void positiveBalanceAllowsTradeUpToBalance() {
        UserBalance ub = new UserBalance();
        ub.setSnowflake("pos");
        ub.setBalance(750L);
        when(repo.findById("pos")).thenReturn(Optional.of(ub));
        when(config.getNegativeTradeCap()).thenReturn(100L);

        long maxTrade = service.getMaxTrade("pos");

        assertThat(maxTrade).isEqualTo(750L);
    }
}
```

- [ ] **Step 4: Run test — expect failure**
```bash
./gradlew test --tests "dev.tylercash.event.contract.UserBalanceServiceTest"
```
Expected: FAIL — `UserBalanceService` does not exist

- [ ] **Step 5: Implement UserBalanceService**

```java
package dev.tylercash.event.contract;

import dev.tylercash.event.contract.model.UserBalance;
import dev.tylercash.event.contract.repository.UserBalanceRepository;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class UserBalanceService {
    private final UserBalanceRepository repo;
    private final ContractConfiguration config;
    private final Clock clock;

    public long getBalance(String snowflake) {
        return getOrCreate(snowflake).getBalance();
    }

    public long getMaxTrade(String snowflake) {
        long balance = getBalance(snowflake);
        return balance < 0 ? config.getNegativeTradeCap() : balance;
    }

    @Transactional
    public void deduct(String snowflake, long amount) {
        UserBalance ub = getOrCreate(snowflake);
        ub.setBalance(ub.getBalance() - amount);
        ub.setUpdatedAt(clock.instant());
        repo.save(ub);
    }

    @Transactional
    public void credit(String snowflake, long amount) {
        UserBalance ub = getOrCreate(snowflake);
        ub.setBalance(ub.getBalance() + amount);
        ub.setUpdatedAt(clock.instant());
        repo.save(ub);
    }

    private UserBalance getOrCreate(String snowflake) {
        return repo.findById(snowflake).orElseGet(() -> {
            UserBalance ub = new UserBalance();
            ub.setSnowflake(snowflake);
            ub.setBalance(config.getDefaultBalance());
            ub.setUpdatedAt(clock.instant());
            return repo.save(ub);
        });
    }
}
```

- [ ] **Step 6: Run tests — expect pass**
```bash
./gradlew test --tests "dev.tylercash.event.contract.UserBalanceServiceTest"
```
Expected: BUILD SUCCESSFUL, 5 tests passing

- [ ] **Step 7: spotlessApply + commit**
```bash
./gradlew spotlessApply
git add src/main/java/dev/tylercash/event/contract/model/UserBalance.java \
        src/main/java/dev/tylercash/event/contract/repository/UserBalanceRepository.java \
        src/main/java/dev/tylercash/event/contract/UserBalanceService.java \
        src/test/java/dev/tylercash/event/contract/UserBalanceServiceTest.java
git commit -m "feat(contract): add UserBalance entity and UserBalanceService"
```

---

### Task 11: LmsrService (TDD)

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/contract/LmsrService.java`
- Test: `backend/src/test/java/dev/tylercash/event/contract/LmsrServiceTest.java`

- [ ] **Step 1: Write failing tests**

```java
package dev.tylercash.event.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LmsrServiceTest {
    private LmsrService lmsr;

    @BeforeEach
    void setUp() {
        lmsr = new LmsrService();
    }

    @Test
    void binaryMarketInitialProbabilityIs50Percent() {
        // b = seed / ln(2), both outcomes start at q=0
        double b = LmsrService.computeB(500L, 2);
        double[] q = {0.0, 0.0};
        double prob = lmsr.probability(q, 0, b);
        assertThat(prob).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void maxLossEqualsSeedAmount() {
        // Market maker's max loss = b * ln(n) = seed_amount
        long seed = 500L;
        int n = 2;
        double b = LmsrService.computeB(seed, n);
        double maxLoss = b * Math.log(n);
        assertThat(maxLoss).isCloseTo(seed, within(0.01));
    }

    @Test
    void costToBuySharesIsPositive() {
        double b = LmsrService.computeB(500L, 2);
        double[] q = {0.0, 0.0};
        long cost = lmsr.costToBuy(q, 0, 10.0, b);
        assertThat(cost).isGreaterThan(0);
    }

    @Test
    void buyingSharesIncreasesOutcomeProbability() {
        double b = LmsrService.computeB(500L, 2);
        double[] q = {0.0, 0.0};
        double probBefore = lmsr.probability(q, 0, b);
        q[0] += 10.0;
        double probAfter = lmsr.probability(q, 0, b);
        assertThat(probAfter).isGreaterThan(probBefore);
    }

    @Test
    void sharesToBuyForCostIsConsistentWithCostFunction() {
        double b = LmsrService.computeB(500L, 2);
        double[] q = {0.0, 0.0};
        double shares = lmsr.sharesToBuyForCost(q, 0, 100L, b);
        long costCheck = lmsr.costToBuy(q, 0, shares, b);
        // Should be within 1 coin due to rounding
        assertThat(Math.abs(costCheck - 100L)).isLessThanOrEqualTo(1L);
    }

    @Test
    void multiOutcomeInitialProbabilitiesAreEqual() {
        int n = 4;
        double b = LmsrService.computeB(1000L, n);
        double[] q = {0.0, 0.0, 0.0, 0.0};
        for (int i = 0; i < n; i++) {
            assertThat(lmsr.probability(q, i, b)).isCloseTo(1.0 / n, within(1e-9));
        }
    }

    @Test
    void allProbabilitiesSumToOne() {
        double b = LmsrService.computeB(500L, 3);
        double[] q = {5.0, 10.0, 2.0};
        double sum = 0;
        for (int i = 0; i < 3; i++) sum += lmsr.probability(q, i, b);
        assertThat(sum).isCloseTo(1.0, within(1e-9));
    }
}
```

- [ ] **Step 2: Run tests — expect failure**
```bash
./gradlew test --tests "dev.tylercash.event.contract.LmsrServiceTest"
```
Expected: FAIL — `LmsrService` does not exist

- [ ] **Step 3: Implement LmsrService**

```java
package dev.tylercash.event.contract;

import org.springframework.stereotype.Service;

@Service
public class LmsrService {

    /**
     * Compute the LMSR liquidity parameter b such that the market maker's
     * maximum loss equals seedAmount.
     * b = seedAmount / ln(numOutcomes)
     */
    public static double computeB(long seedAmount, int numOutcomes) {
        return seedAmount / Math.log(numOutcomes);
    }

    /**
     * LMSR cost function: C(q) = b * ln(sum(exp(q_i / b)))
     */
    private double cost(double[] q, double b) {
        double sum = 0;
        for (double qi : q) sum += Math.exp(qi / b);
        return b * Math.log(sum);
    }

    /**
     * Cost in coins to buy `shares` of outcome `outcomeIndex`.
     * Returns Math.round(C(q after) - C(q before)).
     */
    public long costToBuy(double[] q, int outcomeIndex, double shares, double b) {
        double[] qAfter = q.clone();
        qAfter[outcomeIndex] += shares;
        return Math.round(cost(qAfter, b) - cost(q, b));
    }

    /**
     * Probability of outcome i: exp(q_i / b) / sum(exp(q_j / b))
     */
    public double probability(double[] q, int outcomeIndex, double b) {
        double denom = 0;
        for (double qi : q) denom += Math.exp(qi / b);
        return Math.exp(q[outcomeIndex] / b) / denom;
    }

    /**
     * Binary search: find shares such that costToBuy ≈ targetCost.
     * Used to translate a coin amount into a share quantity.
     */
    public double sharesToBuyForCost(double[] q, int outcomeIndex, long targetCost, double b) {
        double lo = 0, hi = targetCost * 2.0; // generous upper bound
        for (int i = 0; i < 64; i++) {
            double mid = (lo + hi) / 2;
            long c = costToBuy(q, outcomeIndex, mid, b);
            if (c < targetCost) lo = mid;
            else hi = mid;
        }
        return (lo + hi) / 2;
    }
}
```

- [ ] **Step 4: Run tests — expect pass**
```bash
./gradlew test --tests "dev.tylercash.event.contract.LmsrServiceTest"
```
Expected: BUILD SUCCESSFUL, 7 tests passing

- [ ] **Step 5: spotlessApply + commit**
```bash
./gradlew spotlessApply
git add src/main/java/dev/tylercash/event/contract/LmsrService.java \
        src/test/java/dev/tylercash/event/contract/LmsrServiceTest.java
git commit -m "feat(contract): implement LmsrService with tests"
```

---

### Task 12: ContractGraphService

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/contract/ContractGraphService.java`

- [ ] **Step 1: Implement ContractGraphService**

```java
package dev.tylercash.event.contract;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tylercash.event.contract.model.ContractOutcome;
import dev.tylercash.event.contract.model.ContractTrade;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;
import org.jfree.chart.encoders.EncoderUtil;
import org.jfree.chart.encoders.ImageFormat;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ContractGraphService {

    private static final Color BACKGROUND = new Color(0x1E, 0x1F, 0x22);
    private static final Color PLOT_BACKGROUND = new Color(0x2B, 0x2D, 0x31);
    private static final Color GRIDLINE = new Color(0x3D, 0x3F, 0x44);
    private static final Color TEXT = new Color(0xDC, 0xDD, 0xDE);

    private static final Color[] SERIES_COLORS = {
        new Color(0x57, 0xF2, 0x87), // green
        new Color(0xED, 0x42, 0x45), // red
        new Color(0x5B, 0x65, 0xF2), // blue
        new Color(0xF0, 0xA7, 0x32), // orange
        new Color(0xE0, 0x91, 0xFF), // purple
    };

    /**
     * Render the probability history for a contract as a PNG.
     *
     * @param outcomes the contract outcomes (for labels)
     * @param trades   ordered list of trades (each has prob_before snapshot)
     * @param createdAt the contract creation timestamp (first data point at equal prob)
     * @return PNG bytes
     */
    public byte[] renderChart(List<ContractOutcome> outcomes, List<ContractTrade> trades, Instant createdAt) {
        TimeSeriesCollection dataset = new TimeSeriesCollection();

        TimeSeries[] series = new TimeSeries[outcomes.size()];
        for (int i = 0; i < outcomes.size(); i++) {
            series[i] = new TimeSeries(outcomes.get(i).getLabel());
            dataset.addSeries(series[i]);
        }

        // Synthetic first point: equal probability at creation time
        double initialProb = 100.0 / outcomes.size();
        for (TimeSeries s : series) {
            s.addOrUpdate(new Millisecond(Date.from(createdAt)), initialProb);
        }

        // One data point per trade using prob_before snapshot
        for (ContractTrade trade : trades) {
            JsonNode probBefore = trade.getProbBefore();
            for (int i = 0; i < outcomes.size(); i++) {
                ContractOutcome outcome = outcomes.get(i);
                JsonNode node = probBefore.get(outcome.getId().toString());
                if (node != null) {
                    series[i].addOrUpdate(
                            new Millisecond(Date.from(trade.getTradedAt())),
                            node.asDouble() * 100.0);
                }
            }
        }

        JFreeChart chart = ChartFactory.createTimeSeriesChart(
                null, null, "Probability (%)", dataset, true, false, false);

        styleChart(chart, outcomes.size());

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            EncoderUtil.writeBufferedImage(chart.createBufferedImage(600, 200), ImageFormat.PNG, out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Failed to render contract graph", e);
            throw new RuntimeException("Chart rendering failed", e);
        }
    }

    private void styleChart(JFreeChart chart, int numSeries) {
        chart.setBackgroundPaint(BACKGROUND);
        chart.getLegend().setBackgroundPaint(BACKGROUND);
        chart.getLegend().setItemPaint(TEXT);

        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(PLOT_BACKGROUND);
        plot.setDomainGridlinePaint(GRIDLINE);
        plot.setRangeGridlinePaint(GRIDLINE);
        plot.setOutlineVisible(false);

        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        domainAxis.setLabelPaint(TEXT);
        domainAxis.setTickLabelPaint(TEXT);

        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setRange(0, 100);
        rangeAxis.setLabelPaint(TEXT);
        rangeAxis.setTickLabelPaint(TEXT);

        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, false);
        for (int i = 0; i < numSeries; i++) {
            Color c = SERIES_COLORS[i % SERIES_COLORS.length];
            renderer.setSeriesPaint(i, c);
            renderer.setSeriesStroke(i, new BasicStroke(2.5f));
        }
        plot.setRenderer(renderer);
    }
}
```

- [ ] **Step 2: Run tests**
```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: spotlessApply + commit**
```bash
./gradlew spotlessApply
git add src/main/java/dev/tylercash/event/contract/ContractGraphService.java
git commit -m "feat(contract): add ContractGraphService for JFreeChart PNG rendering"
```

---

### Task 13: ContractService

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/contract/ContractService.java`
- Test: `backend/src/test/java/dev/tylercash/event/contract/ContractServiceTest.java`

- [ ] **Step 1: Write key tests**

```java
package dev.tylercash.event.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tylercash.event.contract.model.*;
import dev.tylercash.event.contract.repository.*;
import dev.tylercash.event.discord.DiscordAuthService;
import dev.tylercash.event.discord.DiscordChannelService;
import dev.tylercash.event.discord.DiscordMessageService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ContractServiceTest {
    @Mock ContractRepository contractRepo;
    @Mock ContractOutcomeRepository outcomeRepo;
    @Mock ContractTradeRepository tradeRepo;
    @Mock UserBalanceService balanceService;
    @Mock LmsrService lmsr;
    @Mock ContractGraphService graphService;
    @Mock DiscordChannelService channelService;
    @Mock DiscordMessageService messageService;
    @Mock DiscordAuthService authService;
    @Mock ContractConfiguration config;
    @Mock Clock clock;

    @InjectMocks ContractService service;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(Instant.EPOCH);
    }

    @Test
    void createContractDeductsSeedFromBalance() {
        when(config.getMinSeedAmount()).thenReturn(100L);
        when(balanceService.getMaxTrade(any())).thenReturn(500L);
        when(contractRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.createContract("creator", "Will X happen?", null, List.of("YES", "NO"), 500L);

        verify(balanceService).deduct("creator", 500L);
    }

    @Test
    void createContractBelowMinSeedThrows() {
        when(config.getMinSeedAmount()).thenReturn(100L);

        assertThatThrownBy(() ->
                service.createContract("creator", "title", null, List.of("YES", "NO"), 50L))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void resolveContractPaysTradersOnWinningOutcome() {
        UUID contractId = UUID.randomUUID();
        UUID outcomeId = UUID.randomUUID();

        Contract contract = new Contract();
        contract.setId(contractId);
        contract.setState(ContractState.OPEN);
        contract.setSeedAmount(500L);
        contract.setCreatorSnowflake("creator");
        contract.setCreatedAt(Instant.EPOCH);

        ContractOutcome outcome = new ContractOutcome();
        outcome.setId(outcomeId);
        outcome.setLabel("YES");
        contract.setOutcomes(List.of(outcome));

        ContractTrade trade = new ContractTrade();
        trade.setContractId(contractId);
        trade.setOutcomeId(outcomeId);
        trade.setSnowflake("bettor");
        trade.setSharesBought(10.0);
        trade.setCostPaid(80L);
        trade.setProbBefore(new ObjectMapper().createObjectNode());
        trade.setTradedAt(Instant.EPOCH);

        when(contractRepo.findById(contractId)).thenReturn(Optional.of(contract));
        when(tradeRepo.findByContractIdOrderByTradedAtAsc(contractId)).thenReturn(List.of(trade));
        when(contractRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.resolveContract(contractId, outcomeId, "resolver");

        // bettor gets 1 coin per share = 10 coins
        verify(balanceService).credit("bettor", 10L);
    }
}
```

- [ ] **Step 2: Run tests — expect failure**
```bash
./gradlew test --tests "dev.tylercash.event.contract.ContractServiceTest"
```
Expected: FAIL

- [ ] **Step 3: Implement ContractService**

```java
package dev.tylercash.event.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.tylercash.event.contract.model.*;
import dev.tylercash.event.contract.repository.*;
import dev.tylercash.event.discord.DiscordAuthService;
import dev.tylercash.event.discord.DiscordChannelService;
import dev.tylercash.event.discord.DiscordConfiguration;
import dev.tylercash.event.discord.DiscordMessageService;
import io.micrometer.observation.annotation.Observed;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@AllArgsConstructor
public class ContractService {
    private final ContractRepository contractRepo;
    private final ContractOutcomeRepository outcomeRepo;
    private final ContractTradeRepository tradeRepo;
    private final UserBalanceService balanceService;
    private final LmsrService lmsr;
    private final ContractGraphService graphService;
    private final DiscordChannelService channelService;
    private final DiscordMessageService messageService;
    private final DiscordAuthService authService;
    private final ContractConfiguration config;
    private final DiscordConfiguration discordConfig;
    private final JDA jda;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    @Transactional
    @Observed(name = "contract.create")
    public Contract createContract(
            String creatorSnowflake,
            String title,
            String description,
            List<String> outcomeLabels,
            long seedAmount) {

        if (seedAmount < config.getMinSeedAmount()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Seed amount must be at least " + config.getMinSeedAmount() + " coins");
        }
        if (outcomeLabels.size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least 2 outcomes required");
        }

        // Deduct seed from creator
        balanceService.deduct(creatorSnowflake, seedAmount);

        Contract contract = new Contract();
        contract.setTitle(title);
        contract.setDescription(description);
        contract.setCreatorSnowflake(creatorSnowflake);
        contract.setSeedAmount(seedAmount);
        contract.setBParameter(LmsrService.computeB(seedAmount, outcomeLabels.size()));
        contract.setState(ContractState.CREATED);
        contract.setServerId(discordConfig.getGuildId());
        contract.setCreatedAt(clock.instant());
        contract = contractRepo.save(contract);

        // Create outcomes
        List<ContractOutcome> outcomes = new ArrayList<>();
        for (String label : outcomeLabels) {
            ContractOutcome outcome = new ContractOutcome();
            outcome.setContract(contract);
            outcome.setLabel(label);
            outcome.setSharesOutstanding(0.0);
            outcomes.add(outcomeRepo.save(outcome));
        }
        contract.setOutcomes(outcomes);
        contractRepo.save(contract);

        // Create Discord channel
        initChannel(contract);

        return contract;
    }

    private void initChannel(Contract contract) {
        Category category = channelService.getOrCreateCategory(
                jda.getGuildById(discordConfig.getGuildId()), config.getCategoryName());
        String channelName = slugify(contract.getTitle());
        TextChannel channel = channelService.createTextChannel(category, channelName);
        contract.setChannelId(channel.getIdLong());
        contract.setState(ContractState.INIT_CHANNEL);

        // Render initial chart (equal probability)
        byte[] chart = graphService.renderChart(contract.getOutcomes(), List.of(), contract.getCreatedAt());
        MessageEmbed embed = buildEmbed(contract);
        net.dv8tion.jda.api.entities.Message msg = messageService.sendEmbedWithAttachment(
                channel, List.of(embed), chart, "chart.png");
        contract.setMessageId(msg.getIdLong());
        contract.setState(ContractState.OPEN);
        contractRepo.save(contract);
    }

    @Transactional
    @Observed(name = "contract.trade")
    public void trade(UUID contractId, UUID outcomeId, String snowflake, long coinAmount) {
        Contract contract = getOpenContract(contractId);

        long maxTrade = balanceService.getMaxTrade(snowflake);
        if (coinAmount > maxTrade) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Trade amount exceeds limit of " + maxTrade + " coins");
        }

        ContractOutcome targetOutcome = contract.getOutcomes().stream()
                .filter(o -> o.getId().equals(outcomeId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Outcome not found"));

        double[] q = getSharesArray(contract);
        int idx = getOutcomeIndex(contract, outcomeId);
        double b = contract.getBParameter();

        // Snapshot probabilities before trade
        ObjectNode probBefore = objectMapper.createObjectNode();
        for (ContractOutcome o : contract.getOutcomes()) {
            probBefore.put(o.getId().toString(), lmsr.probability(q, getOutcomeIndex(contract, o.getId()), b));
        }

        double shares = lmsr.sharesToBuyForCost(q, idx, coinAmount, b);
        long actualCost = lmsr.costToBuy(q, idx, shares, b);

        // Deduct coins
        balanceService.deduct(snowflake, actualCost);

        // Update shares outstanding
        targetOutcome.setSharesOutstanding(targetOutcome.getSharesOutstanding() + shares);
        outcomeRepo.save(targetOutcome);

        // Record trade
        ContractTrade trade = new ContractTrade();
        trade.setContractId(contractId);
        trade.setOutcomeId(outcomeId);
        trade.setSnowflake(snowflake);
        trade.setSharesBought(shares);
        trade.setCostPaid(actualCost);
        trade.setProbBefore(probBefore);
        trade.setTradedAt(clock.instant());
        tradeRepo.save(trade);

        // Update Discord pinned message
        refreshPinnedMessage(contract);

        // Log activity in channel
        double[] qAfter = getSharesArray(contract);
        double probAfter = lmsr.probability(qAfter, idx, b) * 100;
        double probBeforeVal = probBefore.get(targetOutcome.getId().toString()).asDouble() * 100;
        long newBalance = balanceService.getBalance(snowflake);
        TextChannel channel = channelService.getTextChannel(contract.getChannelId());
        messageService.sendMessage(channel, String.format(
                "<@%s> bought %.1f shares of **%s** for **%d 🪙** · %s %.0f%% → %.0f%% · balance %d 🪙",
                snowflake, shares, targetOutcome.getLabel(), actualCost,
                targetOutcome.getLabel(), probBeforeVal, probAfter, newBalance));
    }

    @Transactional
    @Observed(name = "contract.resolve")
    public void resolveContract(UUID contractId, UUID winningOutcomeId, String resolverSnowflake) {
        Contract contract = getOpenContract(contractId);

        ContractOutcome winner = contract.getOutcomes().stream()
                .filter(o -> o.getId().equals(winningOutcomeId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Outcome not found"));

        List<ContractTrade> allTrades = tradeRepo.findByContractIdOrderByTradedAtAsc(contractId);

        // Pay out 1 coin per winning share per trader
        Map<String, Double> winningShares = new HashMap<>();
        long totalCostPaid = 0;
        for (ContractTrade t : allTrades) {
            totalCostPaid += t.getCostPaid();
            if (t.getOutcomeId().equals(winningOutcomeId)) {
                winningShares.merge(t.getSnowflake(), t.getSharesBought(), Double::sum);
            }
        }
        long totalPayout = 0;
        for (Map.Entry<String, Double> entry : winningShares.entrySet()) {
            long payout = Math.round(entry.getValue());
            balanceService.credit(entry.getKey(), payout);
            totalPayout += payout;
        }

        // Creator gets seed + collected coins - payouts
        long creatorReturn = contract.getSeedAmount() + totalCostPaid - totalPayout;
        balanceService.credit(contract.getCreatorSnowflake(), creatorReturn);

        contract.setWinningOutcomeId(winningOutcomeId);
        contract.setState(ContractState.RESOLVED);
        contractRepo.save(contract);

        refreshPinnedMessage(contract);

        TextChannel channel = channelService.getTextChannel(contract.getChannelId());
        messageService.sendMessage(channel, String.format(
                "✅ **Prediction contract resolved!** Winning outcome: **%s** · %d 🪙 paid out to %d traders",
                winner.getLabel(), totalPayout, winningShares.size()));
    }

    @Transactional
    @Observed(name = "contract.cancel")
    public void cancelContract(UUID contractId, String resolverSnowflake) {
        Contract contract = getOpenContract(contractId);

        List<ContractTrade> allTrades = tradeRepo.findByContractIdOrderByTradedAtAsc(contractId);

        // Refund all trades
        for (ContractTrade trade : allTrades) {
            balanceService.credit(trade.getSnowflake(), trade.getCostPaid());
        }

        // Refund seed to creator
        balanceService.credit(contract.getCreatorSnowflake(), contract.getSeedAmount());

        contract.setState(ContractState.CANCELLED);
        contractRepo.save(contract);

        TextChannel channel = channelService.getTextChannel(contract.getChannelId());
        messageService.sendMessage(channel, "❌ **Prediction contract cancelled.** All trades refunded.");
    }

    public List<Contract> listOpenContracts() {
        return contractRepo.findByStateIn(List.of(ContractState.OPEN));
    }

    private Contract getOpenContract(UUID id) {
        Contract contract = contractRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contract not found"));
        if (contract.getState() != ContractState.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Contract is not open");
        }
        return contract;
    }

    private void refreshPinnedMessage(Contract contract) {
        List<ContractTrade> trades = tradeRepo.findByContractIdOrderByTradedAtAsc(contract.getId());
        byte[] chart = graphService.renderChart(contract.getOutcomes(), trades, contract.getCreatedAt());
        MessageEmbed embed = buildEmbed(contract);
        messageService.editEmbedWithAttachment(
                contract.getChannelId(), contract.getMessageId(), List.of(embed), chart, "chart.png");
    }

    private MessageEmbed buildEmbed(Contract contract) {
        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("📈 " + contract.getTitle())
                .setColor(0xF0A732)
                .setImage("attachment://chart.png");

        StringBuilder desc = new StringBuilder();
        desc.append("**State:** ").append(contract.getState().name()).append("\n");
        if (contract.getDescription() != null) {
            desc.append(contract.getDescription()).append("\n");
        }
        desc.append("\n**Outcomes:**\n");

        double[] q = getSharesArray(contract);
        double b = contract.getBParameter();
        for (int i = 0; i < contract.getOutcomes().size(); i++) {
            ContractOutcome o = contract.getOutcomes().get(i);
            double prob = lmsr.probability(q, i, b) * 100;
            desc.append(String.format("• **%s** — %.1f%%\n", o.getLabel(), prob));
        }
        desc.append("\nUse `/contract trade` to participate · `/balance` to check coins");

        eb.setDescription(desc.toString());

        if (contract.getState() == ContractState.RESOLVED) {
            contract.getOutcomes().stream()
                    .filter(o -> o.getId().equals(contract.getWinningOutcomeId()))
                    .findFirst()
                    .ifPresent(w -> eb.setFooter("✅ Resolved: " + w.getLabel()));
        }

        return eb.build();
    }

    private double[] getSharesArray(Contract contract) {
        return contract.getOutcomes().stream()
                .mapToDouble(ContractOutcome::getSharesOutstanding)
                .toArray();
    }

    private int getOutcomeIndex(Contract contract, UUID outcomeId) {
        for (int i = 0; i < contract.getOutcomes().size(); i++) {
            if (contract.getOutcomes().get(i).getId().equals(outcomeId)) return i;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Outcome not found");
    }

    private String slugify(String title) {
        return title.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .substring(0, Math.min(title.length(), 90));
    }
}
```

- [ ] **Step 4: Run tests — expect pass**
```bash
./gradlew test --tests "dev.tylercash.event.contract.ContractServiceTest"
```
Expected: BUILD SUCCESSFUL, 3 tests passing

- [ ] **Step 5: spotlessApply + commit**
```bash
./gradlew spotlessApply
git add src/main/java/dev/tylercash/event/contract/ContractService.java \
        src/test/java/dev/tylercash/event/contract/ContractServiceTest.java
git commit -m "feat(contract): implement ContractService with create/trade/resolve/cancel"
```

---

### Task 14: ContractSlashCommandListener + register /contract commands

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/contract/listener/ContractSlashCommandListener.java`
- Modify: `backend/src/main/java/dev/tylercash/event/discord/ClientConfiguration.java`

- [ ] **Step 1: Implement ContractSlashCommandListener**

```java
package dev.tylercash.event.contract.listener;

import dev.tylercash.event.contract.ContractConfiguration;
import dev.tylercash.event.contract.ContractService;
import dev.tylercash.event.contract.UserBalanceService;
import dev.tylercash.event.contract.model.Contract;
import dev.tylercash.event.contract.model.ContractOutcome;
import dev.tylercash.event.contract.repository.ContractRepository;
import dev.tylercash.event.discord.DiscordAuthService;
import dev.tylercash.event.discord.DiscordConfiguration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.components.label.Label;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class ContractSlashCommandListener {
    private final ContractService contractService;
    private final ContractRepository contractRepo;
    private final UserBalanceService balanceService;
    private final DiscordAuthService authService;
    private final ContractConfiguration contractConfig;
    private final DiscordConfiguration discordConfig;

    public void handleSlashCommand(SlashCommandInteractionEvent event) {
        String name = event.getName();
        String sub = event.getSubcommandName();

        if ("balance".equals(name)) {
            handleBalance(event);
            return;
        }

        if ("contract".equals(name)) {
            switch (sub != null ? sub : "") {
                case "create" -> handleCreate(event);
                case "trade" -> handleTrade(event);
                case "resolve" -> handleResolve(event);
                case "cancel" -> handleCancel(event);
                case "list" -> handleList(event);
                default -> event.reply("Unknown subcommand.").setEphemeral(true).queue();
            }
        }
    }

    private void handleBalance(SlashCommandInteractionEvent event) {
        long balance = balanceService.getBalance(event.getUser().getId());
        event.reply("Your balance: **" + balance + " 🪙 peep coins**")
                .setEphemeral(true)
                .queue();
    }

    private void handleCreate(SlashCommandInteractionEvent event) {
        // Open a modal to collect contract details
        TextInput title = TextInput.create("title", TextInputStyle.SHORT)
                .setPlaceholder("Will we hit 100 members by June?")
                .setRequiredRange(5, 200)
                .build();
        TextInput description = TextInput.create("description", TextInputStyle.PARAGRAPH)
                .setPlaceholder("Optional description")
                .setRequired(false)
                .build();
        TextInput outcomes = TextInput.create("outcomes", TextInputStyle.SHORT)
                .setPlaceholder("YES,NO  (comma separated, leave blank for YES/NO)")
                .setRequired(false)
                .build();
        TextInput seedAmount = TextInput.create("seed", TextInputStyle.SHORT)
                .setPlaceholder("500")
                .setRequiredRange(1, 10)
                .build();

        Modal modal = Modal.create("contract_create", "Create Prediction Contract")
                .addComponents(
                        Label.of("Title", title),
                        Label.of("Description (optional)", description),
                        Label.of("Outcomes (comma-separated, blank = YES/NO)", outcomes),
                        Label.of("Seed amount (🪙 coins you stake)", seedAmount))
                .build();

        event.replyModal(modal).queue();
        // Modal submission is handled by a ModalInteractionListener extension — see note below
    }

    private void handleTrade(SlashCommandInteractionEvent event) {
        String contractIdStr = getOption(event, "contract");
        String outcomeIdStr = getOption(event, "outcome");
        String amountStr = getOption(event, "amount");

        if (contractIdStr == null || outcomeIdStr == null || amountStr == null) {
            event.reply("Missing required options.").setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();
        try {
            UUID contractId = UUID.fromString(contractIdStr);
            UUID outcomeId = UUID.fromString(outcomeIdStr);
            long amount = Long.parseLong(amountStr);
            if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");

            contractService.trade(contractId, outcomeId, event.getUser().getId(), amount);
            event.getHook().sendMessage("Trade placed! ✅").queue();
        } catch (Exception e) {
            log.warn("Trade failed", e);
            event.getHook().sendMessage("Trade failed: " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handleResolve(SlashCommandInteractionEvent event) {
        if (!authService.hasRole(discordConfig.getGuildId(),
                event.getUser().getIdLong(), contractConfig.getResolverRole())) {
            event.reply("You don't have the **" + contractConfig.getResolverRole() + "** role.")
                    .setEphemeral(true).queue();
            return;
        }

        String contractIdStr = getOption(event, "contract");
        String outcomeIdStr = getOption(event, "outcome");
        if (contractIdStr == null || outcomeIdStr == null) {
            event.reply("Missing required options.").setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();
        try {
            contractService.resolveContract(
                    UUID.fromString(contractIdStr),
                    UUID.fromString(outcomeIdStr),
                    event.getUser().getId());
            event.getHook().sendMessage("✅ Contract resolved!").queue();
        } catch (Exception e) {
            log.warn("Resolve failed", e);
            event.getHook().sendMessage("Resolve failed: " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handleCancel(SlashCommandInteractionEvent event) {
        if (!authService.hasRole(discordConfig.getGuildId(),
                event.getUser().getIdLong(), contractConfig.getResolverRole())) {
            event.reply("You don't have the **" + contractConfig.getResolverRole() + "** role.")
                    .setEphemeral(true).queue();
            return;
        }

        String contractIdStr = getOption(event, "contract");
        if (contractIdStr == null) {
            event.reply("Missing contract ID.").setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();
        try {
            contractService.cancelContract(UUID.fromString(contractIdStr), event.getUser().getId());
            event.getHook().sendMessage("❌ Contract cancelled. All trades refunded.").queue();
        } catch (Exception e) {
            log.warn("Cancel failed", e);
            event.getHook().sendMessage("Cancel failed: " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handleList(SlashCommandInteractionEvent event) {
        List<Contract> open = contractService.listOpenContracts();
        if (open.isEmpty()) {
            event.reply("No open prediction contracts.").setEphemeral(true).queue();
            return;
        }
        StringBuilder sb = new StringBuilder("**Open prediction contracts:**\n");
        for (Contract c : open) {
            sb.append("• **").append(c.getTitle()).append("** — ID: `").append(c.getId()).append("`\n");
            for (ContractOutcome o : c.getOutcomes()) {
                sb.append("  · ").append(o.getLabel()).append(" — outcome ID: `").append(o.getId()).append("`\n");
            }
        }
        event.reply(sb.toString()).setEphemeral(true).queue();
    }

    private String getOption(SlashCommandInteractionEvent event, String name) {
        OptionMapping opt = event.getOption(name);
        return opt != null ? opt.getAsString() : null;
    }
}
```

**Note on `/contract create` modal submission:** The modal data is received via `ModalInteractionEvent`. Add a handler to `ModalInteractionListener` that checks for `modalId == "contract_create"` and calls:
```java
// In ModalInteractionListener.handleModalInteraction(), add before the existing event lookup:
if ("contract_create".equals(interaction.getModalId())) {
    String title = interaction.getValue("title").getAsString();
    String desc = interaction.getValue("description") != null
            ? interaction.getValue("description").getAsString() : null;
    String outcomesRaw = interaction.getValue("outcomes") != null
            ? interaction.getValue("outcomes").getAsString() : "";
    List<String> outcomes = outcomesRaw.isBlank()
            ? List.of("YES", "NO")
            : Arrays.stream(outcomesRaw.split(",")).map(String::trim)
                    .filter(s -> !s.isBlank()).toList();
    long seed = Long.parseLong(interaction.getValue("seed").getAsString().trim());
    contractServiceProvider.getObject().createContract(
            modalInteractionEvent.getUser().getId(), title, desc, outcomes, seed);
    modalInteractionEvent.reply("✅ Prediction contract created!").setEphemeral(true).queue();
    return;
}
```

Add `ObjectProvider<ContractService> contractServiceProvider` to `ModalInteractionListener` constructor (use `ObjectProvider` to avoid circular dependency).

- [ ] **Step 2: Register /contract commands in ClientConfiguration**

Add to `ClientConfiguration.jda()` bean, after `.awaitReady()`:

```java
JDA builtJda = JDABuilder.createDefault(discordConfiguration.getToken())
        .addEventListeners(buttonInteractionListener)
        .addEventListeners(modalInteractionListener)
        .addEventListeners(slashCommandListener)
        .enableIntents(EnumSet.allOf(GatewayIntent.class))
        .build()
        .awaitReady();

builtJda.getGuildById(discordConfiguration.getGuildId())
        .updateCommands()
        .addCommands(
            net.dv8tion.jda.api.interactions.commands.build.Commands.slash("contract", "Prediction contract market")
                .addSubcommands(
                    new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("create", "Create a new prediction contract"),
                    new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("trade", "Trade on a prediction contract")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "contract", "Contract ID", true)
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "outcome", "Outcome ID", true)
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "amount", "Coins to spend", true),
                    new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("resolve", "Resolve a prediction contract")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "contract", "Contract ID", true)
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "outcome", "Winning outcome ID", true),
                    new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("cancel", "Cancel a prediction contract")
                        .addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING, "contract", "Contract ID", true),
                    new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("list", "List open prediction contracts")),
            net.dv8tion.jda.api.interactions.commands.build.Commands.slash("balance", "Check your peep coin balance"))
        .queue();

return builtJda;
```

- [ ] **Step 3: Run all tests**
```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: spotlessApply + commit**
```bash
./gradlew spotlessApply
git add src/main/java/dev/tylercash/event/contract/listener/ \
        src/main/java/dev/tylercash/event/discord/ClientConfiguration.java \
        src/main/java/dev/tylercash/event/discord/listener/ModalInteractionListener.java
git commit -m "feat(contract): add ContractSlashCommandListener and register /contract commands"
```

---

### Task 15: Update DiscordInitializationService for prediction contracts category

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/DiscordInitializationService.java`

- [ ] **Step 1: Ensure prediction contracts category is created at startup**

Add `ContractConfiguration contractConfig` to the constructor. In `initializeGuild()`:

```java
ensureCategory(guild, EVENT_CATEGORY);
ensureCategory(guild, EVENT_ARCHIVE_CATEGORY);
ensureCategory(guild, contractConfig.getCategoryName()); // "prediction contracts"
ensureSeparatorChannel(outings);
```

- [ ] **Step 2: Run tests**
```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: spotlessApply + commit**
```bash
./gradlew spotlessApply
git add src/main/java/dev/tylercash/event/discord/DiscordInitializationService.java
git commit -m "feat(contract): ensure prediction contracts category on startup"
```

---

### Task 16: Commit spec + final test run

- [ ] **Step 1: Commit spec document**
```bash
git add docs/superpowers/specs/2026-04-14-prediction-market-design.md \
        docs/superpowers/plans/
git commit -m "docs: add prediction contract market spec and implementation plan"
```

- [ ] **Step 2: Full test suite**
```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, all tests passing

- [ ] **Step 3: Spotless final check**
```bash
./gradlew spotlessCheck
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Manual verification checklist**
  - Start backend with local profile, verify Liquibase migrations apply cleanly
  - Check Discord server: "prediction contracts" category should exist after startup
  - Run `/contract create` — modal appears with title/description/outcomes/seed fields
  - Submit modal — channel appears under "prediction contracts", pinned message with graph
  - Run `/contract list` — shows the open contract with IDs
  - Run `/contract trade` with contract/outcome/amount — graph updates, activity message appears
  - Run `/balance` as a new user — shows 1000 🪙
  - Set balance negative in DB manually; try `/contract trade` with amount > 100 — rejected
  - Run `/contract resolve` without resolver role — rejected
  - Assign resolver role; run `/contract resolve` — payouts distributed, pinned message shows RESOLVED
  - Run `/contract cancel` on a different open contract — all trades refunded
