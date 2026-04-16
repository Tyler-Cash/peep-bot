# Prediction Contract Market Design

**Date:** 2026-04-14
**Status:** Approved

---

## Preamble: Event Contracts, Not Gambling

This feature implements a prediction contract market. Under the Commodity Futures Trading Commission's (CFTC) characterisation, event contracts are commodity derivatives — instruments that allow participants to hedge against real-world uncertainty by taking a financial position on an observable outcome. The CFTC distinguishes these from gambling on the basis that participants are engaging in price discovery and risk transfer, not a game of chance with a house edge. Platforms like Kalshi operate under this framework.

Peep Bot's prediction contract market follows the same model: every contract resolves on an observable real-world event, the LMSR market maker provides neutral liquidity (no house edge), and all positions are priced transparently. Peep coins are the platform's unit of account.

---

## Overview

A prediction contract market for Peep Bot that:
- Creates a Discord channel per prediction contract under a "prediction contracts" category
- Prices outcomes using the Logarithmic Market Scoring Rule (LMSR)
- Supports multi-outcome contracts (defaulting to binary YES/NO)
- Tracks user balances in peep coins (default 1,000 on first interaction)
- Allows all interactions via Discord slash commands
- Logs every trade as an activity message in the contract channel
- Is fully isolated from the events feature and deletable without breaking anything else

---

## Architecture

### Option chosen: Extract shared Discord layer (Option 2)

The existing `DiscordService` (425 lines) is decomposed into focused sub-services. Both the `event` and `contract` packages depend on these shared services and never import each other.

```
dev.tylercash.event/
├── discord/                          ← shared (refactored)
│   ├── DiscordChannelService         ← channel/category CRUD
│   ├── DiscordMessageService         ← message/embed/attachment ops
│   ├── DiscordRoleService            ← role CRUD and assignment
│   ├── DiscordAuthService            ← permission/role checks
│   ├── ClientConfiguration           ← registers all listeners + slash commands
│   ├── DiscordConfiguration          ← existing config properties
│   ├── DiscordInitializationService  ← guild startup checks
│   ├── EmbedService                  ← event embeds (stays)
│   └── listener/
│       ├── ButtonInteractionListener
│       ├── ModalInteractionListener
│       └── SlashCommandListener      ← new: routes slash commands
├── event/                            ← unchanged logic, updated imports
│   └── ...
└── contract/                         ← new, fully self-contained
    ├── ContractService
    ├── LmsrService
    ├── ContractGraphService
    ├── UserBalanceService
    ├── ContractConfiguration
    ├── model/
    │   ├── Contract
    │   ├── ContractOutcome
    │   ├── ContractTrade
    │   └── ContractState             ← CREATED, INIT_CHANNEL, OPEN, RESOLVED, CANCELLED
    ├── repository/
    │   ├── ContractRepository
    │   ├── ContractOutcomeRepository
    │   ├── ContractTradeRepository
    │   └── UserBalanceRepository
    └── listener/
        └── ContractSlashCommandListener
```

**Deletion contract:** removing the `contract/` package and its Liquibase changelog leaves the codebase fully functional. The discord/ refactor is independently valuable and stays.

---

## Data Model

All tables introduced in a dedicated Liquibase changelog file (`db/changelog/contract/db.changelog-contract.yaml`) included from the master changelog.

### `user_balance`
| Column | Type | Notes |
|---|---|---|
| `snowflake` | text PK | Discord user ID |
| `balance` | bigint | Signed. Can be negative. Default 1,000. |
| `updated_at` | timestamptz | |

Row created lazily on first interaction. No row = 1,000 coin balance.

### `contract`
| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `title` | text | |
| `description` | text | nullable |
| `state` | text | ContractState enum |
| `creator_snowflake` | text | |
| `channel_id` | bigint | nullable, set after INIT_CHANNEL |
| `message_id` | bigint | nullable, pinned message |
| `server_id` | bigint | |
| `b_parameter` | double | LMSR liquidity parameter |
| `seed_amount` | bigint | coins staked by creator |
| `winning_outcome_id` | UUID FK | nullable, set on resolution |
| `created_at` | timestamptz | |

### `contract_outcome`
| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `contract_id` | UUID FK | |
| `label` | text | e.g. "YES", "NO", or custom |
| `shares_outstanding` | double | q_i in LMSR, starts at 0 |

Binary default = two rows ("YES", "NO"). Custom polls have 3+ rows.

### `contract_trade`
| Column | Type | Notes |
|---|---|---|
| `id` | bigint PK auto | |
| `contract_id` | UUID FK | |
| `outcome_id` | UUID FK | |
| `snowflake` | text | trader's Discord ID |
| `shares_bought` | double | LMSR shares acquired |
| `cost_paid` | bigint | coins paid (positive = bought) |
| `prob_before` | jsonb | `{"YES": 0.62, "NO": 0.38}` snapshot pre-trade |
| `traded_at` | timestamptz | |

Immutable audit trail. `prob_before` powers the graph — each row is one data point on the chart.

---

## LMSR Mechanics

Implemented in `LmsrService`. All arithmetic in `double`; coin amounts rounded to `long` via `Math.round()`.

**Setup at creation:**
```
b = seed_amount / ln(num_outcomes)
```
Creator's maximum possible loss is capped at exactly `seed_amount`.

**Cost to buy Δ shares of outcome i:**
```
cost = b · ln( Σ exp((q_j + δ_ij·Δ) / b) ) − b · ln( Σ exp(q_j / b) )
```
where δ_ij = 1 if j == i, else 0.

**Probability of outcome i:**
```
P(i) = exp(q_i / b) / Σ exp(q_j / b)
```

**On resolution (winning outcome w):**
- Each trader with trades on w receives 1 coin per share bought across all their trades on w
- Creator receives: `seed_amount + total_coins_paid_by_all_traders − total_payout_to_winners`
- Balances updated in a single transaction

**On cancellation:**
- Each trader refunded their `cost_paid` exactly
- Creator refunded `seed_amount`

**Trade limits:**
- Positive balance: may trade up to current balance
- Negative balance: trade capped at `contract.negative-trade-cap` (default 100 coins, configurable)

---

## Discord Integration

### Channel lifecycle

1. `/contract create` → modal (title, description, binary or custom options, seed amount)
   - Any server member can create a prediction contract — the seed amount requirement is the natural gate
   - Seed amount deducted from creator balance immediately
   - Channel created under "prediction contracts" category, named as slugified title (e.g. `will-we-hit-100-members`)
   - Pinned message posted with initial graph (equal probabilities) + instructions
   - State: CREATED → INIT_CHANNEL → OPEN

2. `/contract trade <market> <outcome> <amount>`
   - LMSR cost computed; user balance checked against limits
   - Trade recorded, `shares_outstanding` updated on outcome row
   - Pinned message re-rendered: new PNG uploaded, message edited
   - Activity log message posted to channel

3. `/contract resolve <market> <outcome>` *(resolver role only)*
   - Winner declared, payouts computed and applied in one transaction
   - Pinned message updated to RESOLVED state with final graph
   - Channel retained for audit history

4. `/contract cancel <market>` *(resolver role only)*
   - All trades refunded at cost_paid, seed returned to creator
   - Pinned message updated to CANCELLED state

5. `/contract list` — lists OPEN prediction contracts in the server
6. `/balance` — shows caller's peep coin balance

### Activity log format (posted per trade)
```
@tyler bought 18.4 shares of YES for 200 🪙 · YES 55% → 62% · balance 800 🪙
```

### Slash command registration

`SlashCommandListener` in `discord/listener/` handles `CommandListUpdateAction` at startup via `ClientConfiguration`. Routes `/contract` commands to `ContractSlashCommandListener`.

---

## Graph Generation

`ContractGraphService` in `contract/` package.

- **Library:** JFreeChart 1.5.4 (`org.jfree:jfreechart:1.5.4`) added to `build.gradle`
- **Input:** ordered list of `ContractTrade` rows for a market
- **Chart type:** `XYLineChart` — one `XYSeries` per outcome
- **X-axis:** trade timestamp
- **Y-axis:** probability 0–100%
- **Initial point:** equal probability at contract creation time (added as a synthetic first data point)
- **Output:** `byte[]` PNG

The PNG is sent to Discord via JDA `channel.sendFiles(FileUpload.fromData(png, "chart.png"))`. The pinned message is then edited via `message.editMessageAttachments(...)` to reference `attachment://chart.png`. This pattern allows the chart to refresh on every trade with a clean inline embed.

---

## Spring Configuration

`ContractConfiguration` (`@ConfigurationProperties(prefix = "dev.tylercash.contract")`):

| Property | Default | Purpose |
|---|---|---|
| `resolver-role` | `"prediction-resolver"` | Discord role name that can resolve/cancel |
| `category-name` | `"prediction contracts"` | Discord category name |
| `default-balance` | `1000` | Starting peep coins for new users |
| `negative-trade-cap` | `100` | Max trade (coins) when balance is negative |
| `min-seed-amount` | `100` | Minimum seed liquidity to create a contract |

---

## Refactoring Scope (DiscordService decomposition)

`DiscordService` split into:

| New service | Responsibility |
|---|---|
| `DiscordChannelService` | Create/delete/archive/sort channels and categories |
| `DiscordMessageService` | Send/edit/pin messages, upload attachments, build embeds |
| `DiscordRoleService` | Create/delete/assign Discord roles |
| `DiscordAuthService` | Role-based permission checks (replaces `isUserAdminOfServer`) |

All existing call sites in `event/` updated to inject the new sub-services. `EmbedService` and `EmbedRenderer` stay as-is (event-specific).

---

## Verification

1. **Unit tests** — `LmsrService`: verify cost function, probability calculation, max-loss bound, and payout totals for binary and multi-outcome cases
2. **Integration tests** — `ContractService`: create contract, place trades, resolve, verify balances updated correctly using Testcontainers (following existing `e2eTest` pattern)
3. **Manual Discord test:**
   - `/contract create` — confirm channel appears under "prediction contracts" category, pinned message with graph posted
   - `/contract trade` — confirm graph updates, activity message logged, balance changes
   - `/balance` on new user — confirm 1,000 default
   - `/balance` on user with negative balance — confirm trade cap enforced
   - `/contract resolve` — confirm payouts correct, pinned message shows RESOLVED
   - Attempt `/contract resolve` without resolver role — confirm rejection
