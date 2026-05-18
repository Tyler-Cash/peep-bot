# SigNoz alerting design — peep-bot

Date: 2026-05-18

## Context

Earlier today (2026-05-18) an agent built dashboards and saved views in SigNoz
(`https://signoz.tylercash.dev`). That work is passive — you have to *go look*
at it. The active half — alerts that page you when something breaks — was
never built. SigNoz currently has zero alert rules and zero notification
channels configured.

This spec defines the alert taxonomy and provides ready-to-paste payloads.

## What got done in this session

1. **Fixed the existing "Errors — last hour, by service" view.** It had no
   service filter, so it merged prod / staging / local-test signals into one
   stream. Renamed to "Peepbot — errors (prod + staging)" and scoped to
   `service.name IN ('peepbot-backend','peep-bot','peepbot-staging-backend')`.
   Why `peep-bot` and `peepbot-backend` both: the rename only takes effect on
   v3.50.3 deploy; until then `peep-bot` is the dominant prod service.

2. **Fixed boolean precedence in "Peepbot — durable listener failures".**
   The old filter parsed as `A OR (B AND C) OR D` because of missing parens.
   Replaced with explicit grouping: `Outbox markFailed OR markFailed OR
   (Listener AND failed for event)`.

3. **Added 4 saved log views** (all scoped to prod service names):
   - **Peepbot — Discord interaction failures**: 10062 ack-miss, 40060
     already-acknowledged, 429 rate-limit.
   - **Peepbot — Auth/OAuth2 failures**: `OAuth2AuthenticationException`,
     `CsrfException`, `AccessDeniedException`.
   - **Peepbot — JDA WebSocket events**: disconnect, reconnect, identify
     ratelimit. JDA can be effectively offline while the JVM is up — this
     gives you visibility.
   - **Peepbot — TfNSW noteworthy posts**: `TfnswReporter`, `TfnswOrchestrator`,
     `TfnswWeekBeforePoller`, and "posted alert" lines.

4. **Did not create alert rules.** SigNoz requires at least one notification
   channel before saving a rule (even when `disabled: true`), and the MCP
   service account has no admin permission to create channels
   (`authz_forbidden: only admins can access this resource`). Payloads are
   inlined below for hand-creation.

## Why no alerts yet (and how to unblock)

Two options. Pick one before pasting the payloads below.

| Option | What you do | Trade-off |
|---|---|---|
| A | Grant the MCP service-account admin in SigNoz (`claude-living-room@signozserviceaccount.com`) | Lets a future agent create channels and alerts directly; broadens that account's blast radius |
| B | Create one notification channel in the SigNoz UI yourself (Discord webhook recommended for consistency with peep-bot) | One-time manual step; keeps the MCP account read/edit-only |

Then for each alert payload below: paste into Alerts → New Alert → "From
JSON" (or POST to `/api/v2/rules`), set the channel name in
`thresholds.spec[].channels`, and leave `disabled: true` initially. Verify
the query returns sensible data in the alert preview before enabling.

## Alert taxonomy

10 alerts across three tiers. All prod-only (`service.name IN ('peepbot-backend',
'peep-bot')`). After v3.50.3 deploys and `peep-bot` is gone, drop that
alternative from each filter.

### Critical (page) — 1 alert

| # | Name | Signal | Trigger |
|---|---|---|---|
| 1 | Peepbot — backend silent | logs | No log records for 5min — process gone or OTLP pipeline broken |

### Error (notify) — 6 alerts

| # | Name | Signal | Trigger |
|---|---|---|---|
| 2 | Peepbot — error log spike | logs | `severity_text IN ('ERROR','FATAL')` > 20 per 5min |
| 3 | Peepbot — optimistic-lock failures | logs | Any `ObjectOptimisticLockingFailure` in 10min — suspected duplicate-TfNSW-post root cause |
| 4 | Peepbot — Discord interaction ACK miss (10062) | logs | Any `ErrorResponseException: 10062` in 15min — listener-async regression |
| 5 | Peepbot — durable listener saga gave up | logs | Any `Outbox markFailed` in 30min — user-visible event broken |
| 6 | Peepbot — JDA WebSocket disconnect loop | logs | ≥3 `Disconnected from WebSocket` (ERROR) in 15min — bot offline despite live JVM |
| 7 | Peepbot — HTTP 5xx surge | traces | trace error rate on backend > 5% sustained 5min |

### Warning (look-when-convenient) — 3 alerts

| # | Name | Signal | Trigger |
|---|---|---|---|
| 8 | Peepbot — TfNSW orchestrator errors | logs | ≥3 ERROR logs mentioning Tfnsw in 15min |
| 9 | Peepbot — /event p99 latency degraded | traces | p99 on `/event*` routes > 3s for 10min |
| 10 | Peepbot — Discord rate-limit storm | logs | ≥10 429/RateLimit log lines in 10min |

## Ready-to-paste payloads

Every payload below uses `disabled: true` and a placeholder `channels:
["REPLACE-ME"]`. Substitute the real channel name once you have one.

### 1. Backend silent (no logs for 5min)

```json
{
  "alert": "Peepbot — backend silent",
  "alertType": "LOGS_BASED_ALERT",
  "ruleType": "threshold_rule",
  "version": "v5",
  "schemaVersion": "v2alpha1",
  "disabled": true,
  "description": "No log records arriving from the prod backend. Either the process is gone, the container is restarting, or the OTLP logs pipeline is broken.",
  "condition": {
    "compositeQuery": {
      "queryType": "builder",
      "panelType": "graph",
      "queries": [{
        "type": "builder_query",
        "spec": {
          "name": "A",
          "signal": "logs",
          "stepInterval": 60,
          "aggregations": [{"expression": "count()"}],
          "filter": {"expression": "service.name IN ('peepbot-backend','peep-bot')"},
          "legend": "log volume"
        }
      }]
    },
    "selectedQueryName": "A",
    "alertOnAbsent": true,
    "absentFor": 5,
    "thresholds": {
      "kind": "basic",
      "spec": [{"name": "critical", "op": "above", "matchType": "at_least_once", "target": 0, "recoveryTarget": null, "channels": ["REPLACE-ME"]}]
    }
  },
  "evaluation": {"kind": "rolling", "spec": {"evalWindow": "5m", "frequency": "1m"}},
  "notificationSettings": {"renotify": {"enabled": true, "interval": "15m", "alertStates": ["nodata", "firing"]}},
  "labels": {"severity": "critical", "team": "peepbot", "component": "availability"},
  "annotations": {
    "description": "No log records have arrived from the prod backend for 5 minutes.",
    "summary": "Peepbot backend silent"
  }
}
```

### 2. Error log spike

```json
{
  "alert": "Peepbot — error log spike",
  "alertType": "LOGS_BASED_ALERT",
  "ruleType": "threshold_rule",
  "version": "v5",
  "schemaVersion": "v2alpha1",
  "disabled": true,
  "description": "Generic error-rate canary. >20 ERROR/FATAL logs per 5min from prod backend.",
  "condition": {
    "compositeQuery": {
      "queryType": "builder",
      "panelType": "graph",
      "queries": [{
        "type": "builder_query",
        "spec": {
          "name": "A",
          "signal": "logs",
          "stepInterval": 60,
          "aggregations": [{"expression": "count()"}],
          "filter": {"expression": "service.name IN ('peepbot-backend','peep-bot') AND severity_text IN ('ERROR','FATAL')"},
          "groupBy": [{"name": "service.name", "fieldContext": "resource", "fieldDataType": "string"}],
          "legend": "{{service.name}}"
        }
      }]
    },
    "selectedQueryName": "A",
    "thresholds": {
      "kind": "basic",
      "spec": [{"name": "error", "op": "above", "matchType": "in_total", "target": 20, "recoveryTarget": null, "channels": ["REPLACE-ME"]}]
    }
  },
  "evaluation": {"kind": "rolling", "spec": {"evalWindow": "5m", "frequency": "1m"}},
  "notificationSettings": {"groupBy": ["service.name"], "renotify": {"enabled": true, "interval": "30m", "alertStates": ["firing"]}},
  "labels": {"severity": "error", "team": "peepbot"},
  "annotations": {
    "description": "{{$service.name}} emitted {{$value}} ERROR/FATAL log(s) in the last 5m.",
    "summary": "Peepbot error log spike"
  }
}
```

### 3. Optimistic-lock failures

```json
{
  "alert": "Peepbot — optimistic-lock failures",
  "alertType": "LOGS_BASED_ALERT",
  "ruleType": "threshold_rule",
  "version": "v5",
  "schemaVersion": "v2alpha1",
  "disabled": true,
  "description": "ObjectOptimisticLockingFailureException — suspected cause of the duplicate TfNSW post bug. @Retryable re-runs the @Transactional method after Discord side-effect already shipped.",
  "condition": {
    "compositeQuery": {
      "queryType": "builder",
      "panelType": "graph",
      "queries": [{
        "type": "builder_query",
        "spec": {
          "name": "A",
          "signal": "logs",
          "stepInterval": 60,
          "aggregations": [{"expression": "count()"}],
          "filter": {"expression": "service.name IN ('peepbot-backend','peep-bot') AND body CONTAINS 'ObjectOptimisticLockingFailure'"},
          "groupBy": [{"name": "service.name", "fieldContext": "resource", "fieldDataType": "string"}],
          "legend": "{{service.name}}"
        }
      }]
    },
    "selectedQueryName": "A",
    "thresholds": {
      "kind": "basic",
      "spec": [{"name": "error", "op": "above", "matchType": "at_least_once", "target": 0, "recoveryTarget": null, "channels": ["REPLACE-ME"]}]
    }
  },
  "evaluation": {"kind": "rolling", "spec": {"evalWindow": "10m", "frequency": "1m"}},
  "notificationSettings": {"groupBy": ["service.name"], "renotify": {"enabled": true, "interval": "1h", "alertStates": ["firing"]}},
  "labels": {"severity": "error", "team": "peepbot", "component": "persistence"},
  "annotations": {
    "description": "{{$service.name}} hit {{$value}} optimistic-lock failure(s) in 10m. Check TfnswOrchestrator and similar @Retryable methods.",
    "summary": "Peepbot optimistic lock failures"
  }
}
```

### 4. Discord interaction ACK miss (10062)

```json
{
  "alert": "Peepbot — Discord interaction ACK miss (10062)",
  "alertType": "LOGS_BASED_ALERT",
  "ruleType": "threshold_rule",
  "version": "v5",
  "schemaVersion": "v2alpha1",
  "disabled": true,
  "description": "Listener didn't ACK an interaction within Discord's 3s window. Regression of the listener-async fix the ListenerNoCompleteCallsTest guard test exists for.",
  "condition": {
    "compositeQuery": {
      "queryType": "builder",
      "panelType": "graph",
      "queries": [{
        "type": "builder_query",
        "spec": {
          "name": "A",
          "signal": "logs",
          "stepInterval": 60,
          "aggregations": [{"expression": "count()"}],
          "filter": {"expression": "service.name IN ('peepbot-backend','peep-bot') AND body CONTAINS 'ErrorResponseException: 10062'"},
          "legend": "ack-miss"
        }
      }]
    },
    "selectedQueryName": "A",
    "thresholds": {
      "kind": "basic",
      "spec": [{"name": "error", "op": "above", "matchType": "at_least_once", "target": 0, "recoveryTarget": null, "channels": ["REPLACE-ME"]}]
    }
  },
  "evaluation": {"kind": "rolling", "spec": {"evalWindow": "15m", "frequency": "1m"}},
  "notificationSettings": {"renotify": {"enabled": true, "interval": "1h", "alertStates": ["firing"]}},
  "labels": {"severity": "error", "team": "peepbot", "component": "discord"},
  "annotations": {
    "description": "{{$value}} unknown-interaction (10062) error(s) in 15m. Check which listener is blocking the WebSocket read thread.",
    "summary": "Peepbot Discord ACK miss"
  }
}
```

### 5. Durable listener saga gave up

```json
{
  "alert": "Peepbot — durable listener saga gave up",
  "alertType": "LOGS_BASED_ALERT",
  "ruleType": "threshold_rule",
  "version": "v5",
  "schemaVersion": "v2alpha1",
  "disabled": true,
  "description": "Outbox markFailed — durable listener saga exhausted retries. User-visible event-lifecycle step is broken.",
  "condition": {
    "compositeQuery": {
      "queryType": "builder",
      "panelType": "graph",
      "queries": [{
        "type": "builder_query",
        "spec": {
          "name": "A",
          "signal": "logs",
          "stepInterval": 60,
          "aggregations": [{"expression": "count()"}],
          "filter": {"expression": "service.name IN ('peepbot-backend','peep-bot') AND body CONTAINS 'Outbox markFailed'"},
          "legend": "saga gave up"
        }
      }]
    },
    "selectedQueryName": "A",
    "thresholds": {
      "kind": "basic",
      "spec": [{"name": "error", "op": "above", "matchType": "at_least_once", "target": 0, "recoveryTarget": null, "channels": ["REPLACE-ME"]}]
    }
  },
  "evaluation": {"kind": "rolling", "spec": {"evalWindow": "30m", "frequency": "5m"}},
  "notificationSettings": {"renotify": {"enabled": true, "interval": "2h", "alertStates": ["firing"]}},
  "labels": {"severity": "error", "team": "peepbot", "component": "lifecycle"},
  "annotations": {
    "description": "Durable listener saga gave up {{$value}} time(s) in 30m. Open 'Peepbot — durable listener failures' view to find the affected event.",
    "summary": "Peepbot saga markFailed"
  }
}
```

### 6. JDA WebSocket disconnect loop

```json
{
  "alert": "Peepbot — JDA WebSocket disconnect loop",
  "alertType": "LOGS_BASED_ALERT",
  "ruleType": "threshold_rule",
  "version": "v5",
  "schemaVersion": "v2alpha1",
  "disabled": true,
  "description": "Multiple JDA WebSocket disconnects in a short window. Bot is effectively offline even though the JVM may still respond to actuator.",
  "condition": {
    "compositeQuery": {
      "queryType": "builder",
      "panelType": "graph",
      "queries": [{
        "type": "builder_query",
        "spec": {
          "name": "A",
          "signal": "logs",
          "stepInterval": 60,
          "aggregations": [{"expression": "count()"}],
          "filter": {"expression": "service.name IN ('peepbot-backend','peep-bot') AND body CONTAINS 'Disconnected from WebSocket' AND severity_text = 'ERROR'"},
          "legend": "jda disconnects"
        }
      }]
    },
    "selectedQueryName": "A",
    "thresholds": {
      "kind": "basic",
      "spec": [{"name": "error", "op": "above", "matchType": "in_total", "target": 3, "recoveryTarget": null, "channels": ["REPLACE-ME"]}]
    }
  },
  "evaluation": {"kind": "rolling", "spec": {"evalWindow": "15m", "frequency": "1m"}},
  "notificationSettings": {"renotify": {"enabled": true, "interval": "30m", "alertStates": ["firing"]}},
  "labels": {"severity": "error", "team": "peepbot", "component": "discord"},
  "annotations": {
    "description": "{{$value}} JDA WebSocket disconnect(s) in 15m. Open 'Peepbot — JDA WebSocket events' view.",
    "summary": "Peepbot JDA disconnect loop"
  }
}
```

### 7. HTTP 5xx surge (traces error rate)

```json
{
  "alert": "Peepbot — HTTP 5xx surge",
  "alertType": "TRACES_BASED_ALERT",
  "ruleType": "threshold_rule",
  "version": "v5",
  "schemaVersion": "v2alpha1",
  "disabled": true,
  "description": "Backend trace error rate > 5% sustained 5min. Availability SLO breach.",
  "condition": {
    "compositeQuery": {
      "queryType": "builder",
      "panelType": "graph",
      "unit": "percent",
      "queries": [
        {"type": "builder_query", "spec": {"name": "A", "signal": "traces", "stepInterval": 60, "disabled": true,
          "aggregations": [{"expression": "count()"}],
          "filter": {"expression": "service.name IN ('peepbot-backend','peep-bot') AND hasError = true"},
          "groupBy": [{"name": "service.name", "fieldContext": "resource", "fieldDataType": "string"}]
        }},
        {"type": "builder_query", "spec": {"name": "B", "signal": "traces", "stepInterval": 60, "disabled": true,
          "aggregations": [{"expression": "count()"}],
          "filter": {"expression": "service.name IN ('peepbot-backend','peep-bot')"},
          "groupBy": [{"name": "service.name", "fieldContext": "resource", "fieldDataType": "string"}]
        }},
        {"type": "builder_formula", "spec": {"name": "F1", "expression": "(A / B) * 100", "legend": "{{service.name}}"}}
      ]
    },
    "selectedQueryName": "F1",
    "thresholds": {
      "kind": "basic",
      "spec": [{"name": "error", "op": "above", "matchType": "all_the_times", "target": 5, "recoveryTarget": 2, "channels": ["REPLACE-ME"]}]
    }
  },
  "evaluation": {"kind": "rolling", "spec": {"evalWindow": "5m", "frequency": "1m"}},
  "notificationSettings": {"groupBy": ["service.name"], "renotify": {"enabled": true, "interval": "30m", "alertStates": ["firing"]}},
  "labels": {"severity": "error", "team": "peepbot", "component": "http"},
  "annotations": {
    "description": "{{$service.name}} trace error rate is {{$value}}%.",
    "summary": "Peepbot HTTP error rate elevated"
  }
}
```

### 8. TfNSW orchestrator errors

```json
{
  "alert": "Peepbot — TfNSW orchestrator errors",
  "alertType": "LOGS_BASED_ALERT",
  "ruleType": "threshold_rule",
  "version": "v5",
  "schemaVersion": "v2alpha1",
  "disabled": true,
  "description": "TfNSW feed or orchestrator failing. Catches feed outages before users notice missing transport-disruption embeds.",
  "condition": {
    "compositeQuery": {
      "queryType": "builder",
      "panelType": "graph",
      "queries": [{
        "type": "builder_query",
        "spec": {
          "name": "A",
          "signal": "logs",
          "stepInterval": 60,
          "aggregations": [{"expression": "count()"}],
          "filter": {"expression": "service.name IN ('peepbot-backend','peep-bot') AND severity_text = 'ERROR' AND (body CONTAINS 'Tfnsw' OR body CONTAINS 'TfNSW')"},
          "legend": "tfnsw errors"
        }
      }]
    },
    "selectedQueryName": "A",
    "thresholds": {
      "kind": "basic",
      "spec": [{"name": "warning", "op": "above", "matchType": "in_total", "target": 3, "recoveryTarget": null, "channels": ["REPLACE-ME"]}]
    }
  },
  "evaluation": {"kind": "rolling", "spec": {"evalWindow": "15m", "frequency": "5m"}},
  "notificationSettings": {"renotify": {"enabled": true, "interval": "4h", "alertStates": ["firing"]}},
  "labels": {"severity": "warning", "team": "peepbot", "component": "tfnsw"},
  "annotations": {
    "description": "{{$value}} TfNSW error log(s) in 15m. Open 'Peepbot — TfNSW orchestrator activity' view.",
    "summary": "Peepbot TfNSW errors"
  }
}
```

### 9. /event p99 latency degraded

```json
{
  "alert": "Peepbot — /event p99 latency degraded",
  "alertType": "TRACES_BASED_ALERT",
  "ruleType": "threshold_rule",
  "version": "v5",
  "schemaVersion": "v2alpha1",
  "disabled": true,
  "description": "p99 latency on /event* routes > 3s sustained 10min. Frontend will start timing out.",
  "condition": {
    "compositeQuery": {
      "queryType": "builder",
      "panelType": "graph",
      "unit": "ns",
      "queries": [{
        "type": "builder_query",
        "spec": {
          "name": "A",
          "signal": "traces",
          "stepInterval": 60,
          "aggregations": [{"expression": "p99(duration_nano)"}],
          "filter": {"expression": "service.name IN ('peepbot-backend','peep-bot') AND name REGEXP '^http (get|post|put|delete) /event'"},
          "groupBy": [{"name": "name", "fieldContext": "attribute", "fieldDataType": "string"}],
          "legend": "{{name}}"
        }
      }]
    },
    "selectedQueryName": "A",
    "thresholds": {
      "kind": "basic",
      "spec": [{"name": "warning", "op": "above", "matchType": "all_the_times", "target": 3, "targetUnit": "s", "recoveryTarget": null, "channels": ["REPLACE-ME"]}]
    }
  },
  "evaluation": {"kind": "rolling", "spec": {"evalWindow": "10m", "frequency": "1m"}},
  "notificationSettings": {"groupBy": ["name"], "renotify": {"enabled": false, "interval": "30m", "alertStates": ["firing"]}},
  "labels": {"severity": "warning", "team": "peepbot", "component": "http"},
  "annotations": {
    "description": "{{$name}} p99 is {{$value}}.",
    "summary": "Peepbot /event latency degraded"
  }
}
```

### 10. Discord rate-limit storm

```json
{
  "alert": "Peepbot — Discord rate-limit storm",
  "alertType": "LOGS_BASED_ALERT",
  "ruleType": "threshold_rule",
  "version": "v5",
  "schemaVersion": "v2alpha1",
  "disabled": true,
  "description": "Heavy Discord 429 rate-limit activity. Bot is either misbehaving (looped calls) or under burst load.",
  "condition": {
    "compositeQuery": {
      "queryType": "builder",
      "panelType": "graph",
      "queries": [{
        "type": "builder_query",
        "spec": {
          "name": "A",
          "signal": "logs",
          "stepInterval": 60,
          "aggregations": [{"expression": "count()"}],
          "filter": {"expression": "service.name IN ('peepbot-backend','peep-bot') AND (body CONTAINS 'RateLimitedException' OR body REGEXP 'HTTP.*429')"},
          "legend": "429s"
        }
      }]
    },
    "selectedQueryName": "A",
    "thresholds": {
      "kind": "basic",
      "spec": [{"name": "warning", "op": "above", "matchType": "in_total", "target": 10, "recoveryTarget": null, "channels": ["REPLACE-ME"]}]
    }
  },
  "evaluation": {"kind": "rolling", "spec": {"evalWindow": "10m", "frequency": "1m"}},
  "notificationSettings": {"renotify": {"enabled": false, "interval": "1h", "alertStates": ["firing"]}},
  "labels": {"severity": "warning", "team": "peepbot", "component": "discord"},
  "annotations": {
    "description": "{{$value}} Discord 429/rate-limit log(s) in 10m.",
    "summary": "Peepbot Discord rate-limit storm"
  }
}
```

## Follow-up cleanup (not done in this pass)

- Three stale services pollute the service list and the previously-broken
  errors view: `peepbot-local-otel-test`, `peepbot-local-no-exclude`,
  `peepbot-local-option-a`. SigNoz drops services with no spans after a
  retention window; they'll age out on their own. If you want them gone
  sooner, the SigNoz UI's service-management page can remove them.

- Once v3.50.3 ships, the `peep-bot` service name disappears. At that
  point, drop `'peep-bot'` from every filter in this doc and the saved
  views, leaving only `'peepbot-backend'` and `'peepbot-staging-backend'`.

- Carryovers from yesterday's handover that this design didn't fold in but
  would make alerting sharper:
  - Stamp `OTEL_RESOURCE_ATTRIBUTES=service.version=<image-tag>` in the
    portainer compose so a "regressed since last release" overlay becomes
    possible on the dashboards.
  - Add `@Observed` to `TfnswAlertsClient.fetch*` so the TfNSW dashboard
    can show per-source feed latency, and a TfNSW-specific latency alert
    becomes natural.
  - Add a `listener-name` attribute to `lifecycle.process-event` spans so
    funnel dashboards can attribute slowness to a specific listener.

## Alert taxonomy rationale (why these ten)

- **One critical only.** Critical means page-worthy. "The bot is silent" is
  the only condition in this codebase where waking someone up is justified;
  everything else can wait until business hours. Keeping the critical tier
  tight prevents alert fatigue, which is what kills alerting systems.
- **Errors clustered around known regressions.** Alerts 3 (optimistic-lock),
  4 (10062), 5 (saga markFailed), 6 (JDA disconnect) each map to a specific
  failure mode that has bitten this codebase or that the architecture
  documentation flags as a hazard. They are not theoretical.
- **Two latency/availability alerts, both trace-based.** Logs lie about
  performance; trace data doesn't. The 5xx surge alert (#7) uses an error-
  rate formula (errors / total) instead of an absolute count so it scales
  with traffic. The latency alert (#9) is scoped to `/event*` because
  that's the dominant user-facing endpoint family.
- **Warnings are notify-once.** Renotify is disabled on warnings (#9, #10)
  to keep them from becoming background noise. If you're not going to look
  at it within a few hours of the first ping, re-pinging won't help.
