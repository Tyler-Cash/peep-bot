# Design: Replace contract/create modal with inline slash command options

**Date:** 2026-04-15

## Problem

`/contract create` currently opens a Discord modal with a single CSV text box for outcomes (e.g. `YES,NO,MAYBE`). This is error-prone and unintuitive. The user wants each outcome to be a separate, clearly labelled input field.

## Approach

Replace the modal entirely with inline slash command options. Discord supports up to 25 options per subcommand, so we pre-register 5 optional `outcome_N` fields alongside the required fields. Users fill in as many outcomes as they need; unused fields are left blank. No popup, no CSV parsing.

## Option registration (`/contract create`)

| Option | Type | Required | Notes |
|---|---|---|---|
| `title` | STRING | Yes | The prediction question |
| `seed` | INTEGER | Yes | Coins to stake (replaces STRING + Long.parseLong) |
| `outcome_1` | STRING | No | First outcome label |
| `outcome_2` | STRING | No | Second outcome label |
| `outcome_3` | STRING | No | Third outcome label |
| `outcome_4` | STRING | No | Fourth outcome label |
| `outcome_5` | STRING | No | Fifth outcome label |

If all outcome fields are blank, the handler defaults to `["YES", "NO"]`.

No `description` option — intentionally excluded.

## File changes

### `ClientConfiguration.java`
Add the options above to the `new SubcommandData("create", ...)` builder. Change nothing else in the command registration block.

### `ContractSlashCommandListenerImpl.java`
Replace `handleCreate`:
- Remove `event.replyModal(modal).queue()`
- Call `event.deferReply(true).queue()`
- Read `title` and `seed` via `event.getOption(...)`
- Collect `outcome_1` through `outcome_5`, filtering null/blank values
- Default to `List.of("YES", "NO")` if the collected list is empty
- Call `contractService.createContract(userId, title, null, outcomeLabels, seedAmount)`
- Send confirmation via `event.getHook().sendMessage(...)`

### `ModalInteractionListener.java`
- Remove the `"contract_create".equals(interaction.getModalId())` branch and `handleContractCreateModal` method
- Remove `ContractService` and `ContractConfiguration` fields and constructor parameters (they were only used by that method); verify no other methods in the class use them before removing

## Error handling
Same as current: wrap in try/catch, send ephemeral error message via hook on failure.

## Out of scope
- No changes to trade, resolve, cancel, or list subcommands
- No changes to frontend
- No changes to `ContractService.createContract`
