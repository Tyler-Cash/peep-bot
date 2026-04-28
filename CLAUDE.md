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
    guild-id: <discord-guild-id>

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

# Run tests (uses H2 + Testcontainers)
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
npm run test:e2e  # Playwright smoke suite (against MSW mock backend)
```

## Code Quality Requirements

All code changes **must** pass linting and formatting checks before committing. CI enforces these.

### Backend

- **Spotless** — Java formatter using Palantir style. Run `./gradlew spotlessCheck` to verify, `./gradlew spotlessApply`
  to auto-fix.

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
- **Swagger UI** — Available at `http://localhost:8080/api/swagger-ui.html` (no auth required)
- **Metrics** — Prometheus endpoint at `/api/actuator/prometheus`

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
| `dev.tylercash.discord.guild-id`       | *(required)*                  | Discord server ID — in `application-local.yaml`        |
| `spring.datasource.url`                | *(not set in default yaml)*   | Must be provided via local config or CLI               |
| `server.servlet.session.cookie.secure` | `true`                        | Set to `false` locally to avoid HTTPS redirect         |

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
