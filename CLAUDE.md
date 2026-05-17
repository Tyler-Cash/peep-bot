# CLAUDE.md — discord-events (Peep Bot)

## Project Overview

Peep Bot is a Discord event management application. It consists of a Spring Boot backend (REST API + Discord bot) and a
Next.js frontend. Users authenticate via Discord OAuth2 and can create/manage events that are surfaced to a Discord
server.

## Repository Structure

```
discord-events/
├── backend/          # Spring Boot 3.5.8, Java 21, Gradle
├── frontend/         # Next.js 15 (App Router), React 19, TypeScript, Tailwind
├── docker-compose.yml  # PostgreSQL database only
├── docs/             # Documentation
└── nginx/            # Empty (nginx not used locally)
```

## Local Development Setup

### Prerequisites

- **Java 21** (Gradle toolchain will use it automatically)
- **Node.js 20+** — use nvm to switch if needed.
- **Docker Desktop** — for the PostgreSQL database

### 1. Start the Database

```bash
docker-compose up -d
```

This starts PostgreSQL 17.5 on port 5432 with credentials `peepbot/peepbot`, database `peepbot`.

### 2. Configure `application-local.yaml`

The file `backend/src/main/resources/application-local.yaml` is **gitignored** and must be created locally. It must
contain at minimum:

```yaml
dev.tylercash:
  discord:
    token: "<discord-bot-token>"

spring:
  security:
    oauth2:
      client:
        registration:
          discord:
            client-id: "<oauth2-client-id>"
            client-secret: "<oauth2-client-secret>"
```

You also need to set the datasource URL and local-specific overrides. These can either go in `application-local.yaml` or
be passed as CLI args (see below).

#### Dev Auto-Login (optional)

To skip Discord OAuth2 login during local development, add to `application-local.yaml`:

```yaml
dev.tylercash:
  dev-user:
    enabled: true
    username: "dev-user"
    discord-id: "YOUR_DISCORD_ID"
    force-admin: true
```

When `enabled: true` and the `local` profile is active, all requests are automatically authenticated as the configured
mock user. Set `force-admin: true` to bypass Discord role checks for admin operations. The Discord bot token and OAuth2
credentials are still needed if you want the bot functionality to work, but web login is bypassed.

### 3. Start the Backend

From `backend/`:

```bash
./gradlew bootRun "--args=--spring.profiles.active=local,nonprod \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/peepbot \
  --spring.datasource.username=peepbot \
  --spring.datasource.password=peepbot \
  --spring.devtools.restart.enabled=false \
  --server.servlet.session.cookie.secure=false \
  --spring.session.cookie.secure=false \
  --dev.tylercash.cors.allowed-origins=http://localhost:3000"
```

The backend starts on port 8080 with context path `/api/`.

Alternatively, put these properties in `application-local.yaml` to avoid passing them every time:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/peepbot
    username: peepbot
    password: peepbot
  devtools:
    restart:
      enabled: false
  session:
    cookie:
      secure: false

server:
  servlet:
    session:
      cookie:
        secure: false

dev.tylercash:
  cors:
    allowed-origins: http://localhost:3000
```

### 4. Start the Frontend

From `frontend/`:

```bash
npm install
npm run dev
```

The frontend starts on port 3000 at `http://localhost:3000`. By default it runs in **mock mode**
(`NEXT_PUBLIC_API_MODE=mock`), serving an in-browser MSW backend so you can develop without the Java backend running.
To talk to the real backend instead, start it with `NEXT_PUBLIC_API_MODE=live` and ensure it points at
`http://localhost:8080/api`.

### 5. Log In

Navigate to `http://localhost:3000/login` and click "Log in with Discord". The OAuth2 redirect URI is configured in
`application-local.yaml` (must be registered in the Discord developer portal for your local bot app).

## Common Commands

### Backend

```bash
# Run with local profile
./gradlew bootRun "--args=--spring.profiles.active=local,nonprod ..."

# Run tests (uses Testcontainers; one shared Postgres per JVM via SharedPostgres)
./gradlew test
./gradlew e2eTest

# Build executable jar
./gradlew bootJar

# Build Docker image (ghcr.io/tyler-cash/peep-bot-backend:latest)
./gradlew bootBuildImage

# Check health
curl http://localhost:8080/api/actuator/health
```

### Frontend

```bash
npm run dev       # Start Next.js dev server (port 3000)
npm run build     # Production build
npm run start     # Run the production build
npm run lint      # Run ESLint (next lint)
npm run typecheck # TypeScript --noEmit
npm run test      # Vitest (unit + API route tests)
npm run test:e2e  # Playwright smoke suite (MSW mock backend; requires `npm run build` first under CI=1)
```

## Code Quality Requirements

All code changes **must** pass linting and formatting checks before committing. CI enforces these.

### Backend

- **Spotless** — Java formatter using Palantir style. Run `./gradlew spotlessCheck` to verify, `./gradlew spotlessApply`
  to auto-fix.

### Test infrastructure (backend)

- **One Postgres testcontainer per JVM** via `SharedPostgres` (in `backend/src/test/java/.../test/`), not one per
  class. Tests own their data: no global `@BeforeEach` truncate. Use `TestIds.nextLong() / nextSnowflake()` to
  generate unique IDs per test method, and scope assertions to those IDs (never "find all + size N").
- HTTP integration tests extend `AbstractHttpIntegrationTest`. Use `fixtures.registerMember(...)` /
  `fixtures.seedEvent(...)` to seed.
- For tests that scan global DB state (schedulers, retry pollers, the saga, `SPRING_SESSION` assertions), use
  `SharedPostgres.registerIsolatedDatabase(registry, ThisClass.class)` for a per-class database within the
  shared container.

### Frontend

- **ESLint** — Configured in `frontend/eslint.config.mjs` (next lint). Run `npm run lint` to check.
- **TypeScript** — `npm run typecheck` runs `tsc --noEmit`. CI enforces it.

## Architecture Notes

### Backend

- **Spring Boot 3.5.8** — REST API with context path `/api/`
- **JDA 5.6.1** — Discord bot (Java Discord API)
- **Spring Security + OAuth2** — Discord SSO login (`/api/oauth2/authorization/discord`)
- **Spring Session JDBC** — Sessions persisted to PostgreSQL (30-day expiry), cookie name `SESSION`
- **Liquibase** — Database migrations in `backend/src/main/resources/db/changelog/`
- **Spring Batch + ShedLock** — Scheduled/distributed jobs
- **Resilience4j** — Circuit breaker for Discord API calls
- **CORS** — Configured via `dev.tylercash.cors.allowed-origins` property (production: `https://event.tylercash.dev`)
- **CSRF** — `CookieCsrfTokenRepository` with `XorCsrfTokenRequestAttributeHandler`; frontend reads the `XSRF-TOKEN`
  cookie and sends `X-XSRF-TOKEN` header on mutations
- **Swagger UI / OpenAPI** — `http://localhost:8080/api/swagger-ui.html`. Gated behind
  `dev.tylercash.openapi.public` (true in `application-nonprod.yaml`, false by default in prod). The
  `OpenApiSpecGenerationTest` writes `backend/openapi.json` on every backend test run; the frontend codegen
  consumes that file.
- **Metrics** — Prometheus endpoint at `/api/actuator/prometheus`
- **Discord listener offload.** All `ListenerAdapter` subclasses under `discord/listener/` must do the minimum
  possible work on the JDA WebSocket read thread: resolve the target entity, then either reply (modal/ephemeral)
  or `deferEdit().queue()` / `deferReply().queue()` to ack the interaction within Discord's 3-second budget,
  then dispatch the remaining work to the `@Qualifier("discordListenerExecutor")` `Executor`. User-visible
  message edits go through `interaction.getHook().editOriginal*().queue()` — never `.complete()`. The
  `ListenerNoCompleteCallsTest` guard test enforces this and prevents `ErrorResponseException: 10062` regressions.

### Multi-guild support

Per-guild configuration (events role, admin role, separator channel, emoji overrides, primary location, feature flags) lives in the `guild` table — one row per guild the bot is in. Rows are upserted automatically on `GuildJoinEvent`. Login is unconditional: any Discord user can authenticate; the frontend renders an "Add Peep Bot to your server" CTA when `GET /guild` returns `[]`. Prediction contracts are gated per-guild by the `contracts_enabled` flag (toggled via the bot-admin panel).

**TfNSW transport notices (F-???):** `tfnsw_enabled` per-guild flag opts the guild into Sydney transport + traffic disruption posts. On event creation, `TfnswEventCreatedListener` invokes `TfnswOrchestrator`, which resolves the event's coords via `PlacesDetailsClient` (lazily caching `event.location_lat/lng`), fetches GTFS-R alerts (Sydney Trains, Metro, Trip Replacements) and Live Traffic NSW GeoJSON (major events, hazards), filters to noteworthy items via `TfnswNoteworthyFilter`, and posts a Discord embed. `TfnswWeekBeforePoller` (daily 06:00 Sydney) re-runs the check 7 days out and posts an "Update:" embed only when the snapshot hash in `tfnsw_event_snapshot` differs. Events without a `locationPlaceId` are skipped — guild primary location is **not** used as a fallback. Major-station allowlist and nearest-station resolution are backed by `GtfsStopsIndex`, which loads `tfnsw/sydney-rail-stops.csv` at startup; refresh that CSV from the live TfNSW GTFS static feed when stop IDs are renumbered or new stations open.

**Authorization choke-point:** every guild-scoped endpoint must call
`GuildMembershipService.assertMember(snowflake, guildId)` after resolving the guild. This is the IDOR boundary
— when adding a new endpoint that takes any guild-scoped resource, route through it.

**Anonymous session persistence (F-002):** `AnonymousSkippingSessionRepository` wraps the JDBC session repository
as the `@Primary` `SessionRepository` bean and drops `save()` for purely-anonymous sessions. This prevents a
DB-fill DoS on `GET /api/csrf`. Don't unwrap it.

### Frontend

- **Next.js 15** (App Router) + **React 19** + **TypeScript**
- **Tailwind CSS** — styling
- **SWR** — data fetching / cache
- **MSW** — in-browser mock backend (`NEXT_PUBLIC_API_MODE=mock` is the default; switch to `live` to hit the real
  Spring Boot backend)
- **Vitest** — unit tests; **Playwright** — e2e smoke suite (runs against MSW)
- API calls go through `frontend/src/lib/api.ts`, which fetches a CSRF token from `/api/csrf` before mutations,
  retries on 429 using `Retry-After`, and throws `UnauthorizedError` on 401/403

## Key Configuration Properties

| Property                               | Default                       | Purpose                                                |
|----------------------------------------|-------------------------------|--------------------------------------------------------|
| `dev.tylercash.cors.allowed-origins`   | `https://event.tylercash.dev` | CORS allowed origins — **must override for local dev** |
| `dev.tylercash.frontend.hostname`      | `event.tylercash.dev`         | Used in OAuth2 redirect after login                    |
| `dev.tylercash.discord.token`          | *(required)*                  | Discord bot token — in `application-local.yaml`        |
| `spring.datasource.url`                | *(not set in default yaml)*   | Must be provided via local config or CLI               |
| `server.servlet.session.cookie.secure` | `true`                        | Set to `false` locally to avoid HTTPS redirect         |
| `dev.tylercash.bot-admins[]`           | *(empty)*                     | Snowflakes allowed to hit `/admin/*` endpoints         |
| `dev.tylercash.openapi.public`         | `false`                       | Set `true` in nonprod to expose `/v3/api-docs`         |
| `dev.tylercash.oauth2.redirect-uri`    | `{baseUrl}/login/oauth2/code/discord` | Pin to literal URL in prod so `X-Forwarded-Host` can't poison the OAuth callback |
| `dev.tylercash.tfnsw.api-key`          | *(empty)*                     | TfNSW Open Data API key (env var `TFNSW_API_KEY`); empty disables the feature globally |
| `server.tomcat.remoteip.internal-proxies` | docker-bridge + loopback   | Trusted proxy CIDRs for `X-Forwarded-*` (F-001)        |

## Commit Conventions

This project uses [Conventional Commits](https://www.conventionalcommits.org/) with semantic-release for automated
versioning. Both backend and frontend share a single version — one release versions both.

| Prefix                                    | Effect         | Example                          |
|-------------------------------------------|----------------|----------------------------------|
| `feat:`                                   | Minor bump     | `feat: add event reminders`      |
| `fix:`                                    | Patch bump     | `fix: correct date parsing`      |
| `feat!:` or `BREAKING CHANGE:` in footer  | Major bump     | `feat!: redesign event API`      |
| `chore:`, `ci:`, `docs:`, `refactor:`, `test:` | No release | `chore: update dependencies`     |

Scope is optional: `feat(discord):`, `fix(deps):`, etc.

## Gotchas

- **DevTools restart causes port conflicts.** Always pass `--spring.devtools.restart.enabled=false` locally, or set it
  in `application-local.yaml`.
- **Secure cookie = HTTPS redirect.** The default config sets `cookie.secure=true`, which causes Tomcat to redirect HTTP
  to port 8443. Override with `server.servlet.session.cookie.secure=false` and `spring.session.cookie.secure=false`
  locally.
- **CORS defaults to production origin.** `dev.tylercash.cors.allowed-origins` defaults to
  `https://event.tylercash.dev`. Must be overridden to `http://localhost:3000` for local frontend dev.
- **`application-local.yaml` is gitignored.** It contains the Discord bot token and OAuth2 secrets — never commit it.
- **Spring Session JDBC schema.** The `spring.session.jdbc.initialize-schema: never` default means the SPRING_SESSION
  tables must already exist. Liquibase handles this — ensure migrations have run before the app starts.
