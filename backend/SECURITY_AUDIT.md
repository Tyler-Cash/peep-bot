# Security Audit Report — Peep Bot Backend

**Date:** 2026-03-07
**Scope:** Full backend security audit across 14 areas (read-only)
**Methodology:** Manual code review against OWASP Top 10 (2021)

---

## Executive Summary

The Peep Bot backend has a solid security foundation with proper session fixation protection, CSRF with XOR masking, guild membership validation at login, safe error handling (no stack traces leaked), and secrets kept out of version control. However, the audit identified **2 critical**, **8 high**, **9 medium**, and **6 low** severity findings across authorization, input validation, Discord bot configuration, deployment, and scheduled jobs.

---

## Findings by Severity

### CRITICAL

#### C1. Missing Authorization on PATCH /event — Any User Can Modify Any Event (IDOR)

| | |
|---|---|
| **File** | `event/EventController.java:74-96` |
| **OWASP** | A01 — Broken Access Control |

The `updateEvent` endpoint retrieves an event by ID and modifies it without verifying that the authenticated user is the event creator or an admin. Any authenticated user can change the name, description, datetime, and capacity of any event, and add +1 attendees to any event.

```java
// Line 77 — no ownership check after this
Event event = eventService.getEvent(eventDto.getId());
event.setCapacity(eventDto.getCapacity());
```

Compare with `cancelEvent` (line 139) which correctly checks `isUserAdminOfServer()`.

**Recommendation:** Add ownership or admin check before allowing modifications:
```java
String discordId = principal.getAttribute("id");
boolean isAdmin = discordService.isUserAdminOfServer(discordConfiguration.getGuildId(), Long.parseLong(discordId));
if (!isAdmin && !event.getCreator().equals(discordId)) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to modify this event");
}
```

---

#### C2. All Discord Gateway Intents Enabled

| | |
|---|---|
| **File** | `discord/ClientConfiguration.java:27` |
| **OWASP** | A05 — Security Misconfiguration |

```java
.enableIntents(EnumSet.allOf(GatewayIntent.class))
```

The bot enables ALL 23 gateway intents including `MESSAGE_CONTENT`, `DIRECT_MESSAGES`, `GUILD_PRESENCES`, etc. The bot only needs interactions (buttons/modals), guild member lookups, and message send/edit. This violates least privilege and requires unnecessary privileged intent approvals from Discord.

**Recommendation:** Replace with explicit minimal set:
```java
.enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MESSAGES)
```

---

#### ~C3.~ ~~Immich API Key Leakage on HTTP Redirect~~ — Downgraded to L6

| | |
|---|---|
| **File** | `global/ServiceConfiguration.java:38-45` |
| **Severity** | **Low** (originally assessed as Critical, downgraded after verification) |

The `immichRestClient` sends the API key as a default `x-api-key` header. The theoretical concern is that if Immich responds with a 3xx redirect to an external domain, the key leaks. In practice, Spring's default `RestClient` uses `HttpURLConnection`, which does **not** forward custom headers on redirects — it strips them at the JDK level. This would only be exploitable with an alternative HTTP client (e.g., Apache HttpClient) that preserves headers across redirects. As defense-in-depth, disabling redirects on this client is still good practice.

---

### HIGH

#### H1. Unbounded `accepted` Set in EventUpdateDto — Mass +1 Injection

| | |
|---|---|
| **File** | `event/model/EventUpdateDto.java:31` |
| **OWASP** | A04 — Insecure Design |

```java
private Set<String> accepted;  // No @Size constraint
```

An attacker can submit thousands of +1 names in a single PATCH request. Each name triggers `attendanceService.recordAttendance()` (EventController.java:90-92), creating unbounded database writes. Combined with C1 (no auth check), this can target any event.

**Recommendation:** Add `@Size(max = 50)` and `@NotNull` to the field.

---

#### H2. NullPointerException on `accepted.forEach()` — Unchecked Null

| | |
|---|---|
| **File** | `event/EventController.java:90` |
| **OWASP** | A04 — Insecure Design |

```java
eventDto.getAccepted().forEach(...)  // accepted can be null — no @NotNull
```

If `accepted` is null in the JSON payload, this throws an unhandled NPE, returning a 500 to the client. The `accepted` field has no `@NotNull` annotation.

**Recommendation:** Add `@NotNull` to the field, or add a null guard before the `.forEach()`.

---

#### H3. Plus-One Name Unsanitized from Discord Modal (Up to 4000 chars)

| | |
|---|---|
| **File** | `discord/listener/ModalInteractionListener.java:70-72` |
| **OWASP** | A03 — Injection |

Discord modal text inputs can be up to 4000 characters. The plus-one name is taken directly from the modal and stored in the database without length or character validation. The `attendance.name` column is `text` type (unbounded).

**Recommendation:** Validate length (e.g., max 100 chars) and trim whitespace before storing.

---

#### H4. Swagger UI and API Docs Publicly Accessible in Production

| | |
|---|---|
| **File** | `security/WebSecurityConfig.java:38-43` |
| **OWASP** | A05 — Security Misconfiguration |

```java
.requestMatchers(new AntPathRequestMatcher("/swagger-ui/**")).permitAll()
.requestMatchers(new AntPathRequestMatcher("/v3/api-docs/**")).permitAll()
```

Swagger UI exposes the full API surface, parameter types, and endpoint descriptions to unauthenticated users in production, aiding reconnaissance.

**Recommendation:** Restrict behind authentication or limit to non-prod profiles with `@Profile("!prod")` on the Springdoc config.

---

#### H5. Prometheus Metrics Publicly Accessible

| | |
|---|---|
| **File** | `security/WebSecurityConfig.java:46-47` |
| **OWASP** | A05 — Security Misconfiguration |

```java
.requestMatchers(new AntPathRequestMatcher("/actuator/prometheus")).permitAll()
```

Prometheus metrics expose JVM internals, request latencies, error rates, and custom business metrics to anyone without authentication.

**Recommendation:** Require authentication, or restrict to internal network via IP-based filtering.

---

#### H6. ShedLock Missing on Channel Sorting Jobs

| | |
|---|---|
| **File** | `discord/DiscordService.java:152-162` |
| **OWASP** | A04 — Insecure Design |

```java
@Scheduled(fixedDelay = 5, timeUnit = MINUTES)
public void sortActiveChannels() { ... }  // No @SchedulerLock

@Scheduled(fixedDelay = 5, timeUnit = MINUTES)
public void sortArchiveChannels() { ... }  // No @SchedulerLock
```

In multi-instance deployments, all instances execute these simultaneously, causing duplicate/conflicting Discord API calls and rate limit exhaustion.

**Recommendation:** Add `@SchedulerLock(name = "sortActiveChannels", lockAtMostFor = "PT4M")` to both methods.

---

#### H7. ShedLock Missing on User Cache Refresh Job

| | |
|---|---|
| **File** | `discord/DiscordUserCacheService.java:68` |
| **OWASP** | A04 — Insecure Design |

The `refreshStaleEntries()` method runs every 60 seconds without distributed locking. N instances = N times the Discord API calls for the same cache refresh.

**Recommendation:** Add `@SchedulerLock(name = "refreshStaleEntries", lockAtMostFor = "PT55S")`.

---

#### H8. Missing Security Headers (CSP, HSTS, X-Frame-Options, Permissions-Policy)

| | |
|---|---|
| **File** | `security/WebSecurityConfig.java:30-57` |
| **OWASP** | A05 — Security Misconfiguration |

No `.headers()` configuration exists in the security filter chain. Spring Security provides some defaults (X-Content-Type-Options, X-Frame-Options DENY), but the following are missing:
- `Content-Security-Policy`
- `Strict-Transport-Security` (HSTS)
- `Permissions-Policy`
- `Referrer-Policy`

**Recommendation:** Add explicit header configuration:
```java
.headers(headers -> headers
    .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'"))
    .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31536000).includeSubDomains(true)))
```

---

### MEDIUM

#### M1. 90-Day Sessions Without Guild Re-Validation

| | |
|---|---|
| **File** | `security/WebSecurityConfig.java:62` + `security/oauth2/CustomOAuth2UserService.java:27-36` |
| **OWASP** | A07 — Identification and Authentication Failures |

Sessions last 90 days. Guild membership is only validated at login. A user removed from the Discord server retains full API access for up to 90 days.

**Recommendation:** Reduce session duration and/or add periodic guild membership checks (e.g., a servlet filter on authenticated requests).

---

#### M2. Unbounded Pagination Size

| | |
|---|---|
| **File** | `event/EventController.java:103` |
| **OWASP** | A04 — Insecure Design |

```java
public Page<EventDto> getEvents(@PageableDefault Pageable pageable)
```

No `max-page-size` is configured in `application.yaml`. Clients can request `?size=999999` to dump the entire database in one query.

**Recommendation:** Add to `application.yaml`:
```yaml
spring.data.web.pageable.max-page-size: 100
```

---

#### M3. Unpinned `micrometer-tracing-bom:latest.release`

| | |
|---|---|
| **File** | `build.gradle:72` |
| **OWASP** | A06 — Vulnerable and Outdated Components |

```gradle
implementation platform('io.micrometer:micrometer-tracing-bom:latest.release')
```

This pulls whatever version is latest at build time, creating non-reproducible builds and supply chain risk.

**Recommendation:** Pin to explicit version (e.g., `1.5.3`).

---

#### M4. Guild ID Leaked in OAuth2 Error Message

| | |
|---|---|
| **File** | `security/oauth2/CustomOAuth2UserService.java:34-35` |
| **OWASP** | A02 — Cryptographic Failures (info disclosure) |

```java
throw new OAuth2AuthenticationException(
    "User not a member of discord server " + discordConfiguration.getGuildId());
```

The server's Discord guild ID is returned in the error message to unauthenticated users.

**Recommendation:** Use a generic message: `"You do not have access to this application"`.

---

#### M5. Internal Config Exposed in ResponseStatusException Messages

| | |
|---|---|
| **File** | `discord/DiscordService.java:80, 251` |
| **OWASP** | A05 — Security Misconfiguration |

```java
// Line 80 — exposes category name constant
throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No category found called \"" + EVENT_CATEGORY + "\"");

// Line 251 — exposes role name
throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No roles found matching name " + role);
```

**Recommendation:** Return generic messages; log specifics server-side.

---

#### M6. CORS Allows All Headers (Wildcard)

| | |
|---|---|
| **File** | `security/CorsConfig.java:26` |
| **OWASP** | A05 — Security Misconfiguration |

```java
configuration.setAllowedHeaders(List.of("*"));
```

**Recommendation:** Whitelist only `Content-Type`, `X-XSRF-TOKEN`, `Accept`.

---

#### M7. Missing Guild Validation on Button/Modal Interactions

| | |
|---|---|
| **File** | `discord/listener/ButtonInteractionListener.java`, `discord/listener/ModalInteractionListener.java` |
| **OWASP** | A01 — Broken Access Control |

Neither listener validates that interactions originate from the configured guild. If the bot is present in multiple guilds, interactions from other guilds would be processed.

**Recommendation:** Check `event.getGuild().getIdLong() == discordConfiguration.getGuildId()` at the start of each handler.

---

#### M8. EventLifecyclePoller Lock Timeout Too Short

| | |
|---|---|
| **File** | `event/statemachine/EventLifecyclePoller.java:25-27` + `global/ShedLockConfig.java:13` |
| **OWASP** | A04 — Insecure Design |

Job runs every 60s, but the global ShedLock default is `PT30S`. If processing takes >30s, the lock expires and another instance starts processing the same events concurrently, risking duplicate state transitions.

**Recommendation:** Add explicit `lockAtMostFor = "PT58S"` on the `@SchedulerLock`.

---

#### M9. Missing Validation Constraints in EventUpdateDto

| | |
|---|---|
| **File** | `event/model/EventUpdateDto.java:24-25` |
| **OWASP** | A03 — Injection |

`name` and `description` fields on the update DTO lack `@Size` constraints present on `EventDto` (4-80 and max 3800 respectively). Updates can bypass the creation-time validation.

**Recommendation:** Add matching `@Size` annotations.

---

### LOW

#### L1. Double Discord API Call in `getMemberFromServer()`

| | |
|---|---|
| **File** | `discord/DiscordService.java:135-139` |

```java
server.retrieveMemberById(userId).complete();       // Line 137 — result discarded
return server.retrieveMemberById(userId).complete(); // Line 138 — actual return
```

Doubles Discord API calls, wasting rate limit budget.

**Recommendation:** Remove line 137.

---

#### L2. PUT Used for Event Creation (Semantic Issue)

| | |
|---|---|
| **File** | `event/EventController.java:46` |

`@PutMapping` is used for `createEvent`, which should be `@PostMapping` per REST conventions (PUT is idempotent replace).

---

#### L3. 404 Handler Redirects Instead of Returning JSON

| | |
|---|---|
| **File** | `global/ErrorHandler.java:29-37` |

All 404 errors redirect to `/login/success` instead of returning a JSON error. This is inconsistent for API clients.

---

#### L4. SameSite Cookie is Lax (Not Strict)

| | |
|---|---|
| **File** | `application.yaml:40` |

`same-site: Lax` allows the session cookie on top-level navigations from external sites. `Strict` would provide stronger CSRF protection.

---

#### L5. Immich Shared Links Created with `allowUpload: true`

| | |
|---|---|
| **File** | `immich/ImmichService.java:56` |

```java
"allowUpload", true
```

Anyone with the shared link can upload files to the Immich album. This is likely intentional (event photo collection), but should be documented as a design decision.

---

## Positive Security Patterns

These are well-implemented security controls already in place:

| Control | Location | Notes |
|---|---|---|
| Session fixation protection | `WebSecurityConfig.java:32` | `newSession` strategy on auth |
| CSRF with XOR masking | `WebSecurityConfig.java:50-51` | `XorCsrfTokenRequestAttributeHandler` |
| Cookie security attributes | `application.yaml:37-40` | HttpOnly, Secure, SameSite set |
| Guild membership validation | `CustomOAuth2UserService.java:27-36` | Checked at login |
| OAuth2 minimal scope | `application.yaml:54` | Only `identify` scope requested |
| Secrets not in VCS | `.gitignore` excludes `application-local.yaml` | Tokens/keys externalized |
| No stack traces in responses | `ErrorHandler.java:42-43` | Generic message returned |
| No secrets logged | All log statements verified | Secrets never interpolated in logs |
| DTO pattern hides internals | `EventDto.java:45-52` | `messageId`, `channelId`, `serverId` excluded from API responses |
| Parameterized native queries | `AttendanceRepository.java:14-23` | `@Param` binding prevents SQL injection |
| `@Valid` on request bodies | `EventController.java:48, 76` | Bean validation enforced |
| Discord markdown sanitization | `DiscordUtil.java:40-43` | `MarkdownSanitizer.escape()` on display names |
| Rate limiter on role pings | `ServiceConfiguration.java:23-34` | 3 pings per 12 hours |
| CI Actions pinned by SHA | `.github/workflows/ci.yml` | All actions use commit hash, not tag |
| Grype vulnerability scanning | `.github/workflows/ci.yml:221` | SBOM + scanner with `fail-build: true` |

---

## Summary Table

| ID | Finding | Severity | OWASP | File |
|---|---|---|---|---|
| C1 | IDOR on PATCH /event — no ownership check | Critical | A01 | EventController.java:74-96 |
| C2 | All Discord gateway intents enabled | Critical | A05 | ClientConfiguration.java:27 |
| L6 | API key on RestClient default header (theoretical redirect risk) | Low | A02 | ServiceConfiguration.java:38-45 |
| H1 | Unbounded `accepted` Set (mass +1 injection) | High | A04 | EventUpdateDto.java:31 |
| H2 | NPE on null `accepted.forEach()` | High | A04 | EventController.java:90 |
| H3 | Unsanitized plus-one name from Discord modal | High | A03 | ModalInteractionListener.java:70-72 |
| H4 | Swagger UI publicly accessible | High | A05 | WebSecurityConfig.java:38-43 |
| H5 | Prometheus metrics publicly accessible | High | A05 | WebSecurityConfig.java:46-47 |
| H6 | ShedLock missing on channel sorting jobs | High | A04 | DiscordService.java:152-162 |
| H7 | ShedLock missing on cache refresh job | High | A04 | DiscordUserCacheService.java:68 |
| H8 | Missing security headers (CSP, HSTS, etc.) | High | A05 | WebSecurityConfig.java:30-57 |
| M1 | 90-day sessions, no guild re-validation | Medium | A07 | WebSecurityConfig.java:62 |
| M2 | Unbounded pagination size | Medium | A04 | EventController.java:103 |
| M3 | Unpinned `latest.release` dependency | Medium | A06 | build.gradle:72 |
| M4 | Guild ID leaked in OAuth2 error | Medium | A02 | CustomOAuth2UserService.java:34-35 |
| M5 | Internal config in ResponseStatusException | Medium | A05 | DiscordService.java:80, 251 |
| M6 | CORS wildcard allowed headers | Medium | A05 | CorsConfig.java:26 |
| M7 | Missing guild validation on interactions | Medium | A01 | ButtonInteractionListener, ModalInteractionListener |
| M8 | ShedLock timeout shorter than job interval | Medium | A04 | EventLifecyclePoller.java:25-27 |
| M9 | Missing @Size on EventUpdateDto fields | Medium | A03 | EventUpdateDto.java:24-25 |
| L1 | Double Discord API call (wasted rate limit) | Low | — | DiscordService.java:137 |
| L2 | PUT used for creation (should be POST) | Low | — | EventController.java:46 |
| L3 | 404 redirects instead of JSON response | Low | — | ErrorHandler.java:34-36 |
| L4 | SameSite=Lax instead of Strict | Low | A05 | application.yaml:40 |
| L5 | Immich shared links allow upload | Low | — | ImmichService.java:56 |

---

## Recommended Remediation Priority

### Immediate (Critical)
1. **C1** — Add ownership/admin check to `updateEvent` endpoint
2. **C2** — Reduce gateway intents to minimum required

### Next Sprint (High)
4. **H1/H2** — Add `@NotNull @Size(max=50)` to `accepted` Set; add null guard in controller
5. **H3** — Validate plus-one name length and content from Discord modal
6. **H4/H5** — Restrict Swagger and Prometheus behind authentication (or profile-gate)
7. **H6/H7** — Add `@SchedulerLock` to sorting and cache refresh jobs
8. **H8** — Configure security headers (CSP, HSTS) in SecurityFilterChain

### Planned (Medium)
9. **M1** — Reduce session TTL or add periodic guild membership checks
10. **M2** — Set `spring.data.web.pageable.max-page-size`
11. **M3** — Pin micrometer-tracing-bom version
12. **M4/M5** — Sanitize error messages to remove internal config details
13. **M6** — Restrict CORS allowed headers
14. **M7** — Add guild validation to button/modal interaction listeners
15. **M8** — Set explicit `lockAtMostFor` on EventLifecyclePoller
16. **M9** — Add `@Size` constraints to EventUpdateDto name/description
