# CLAUDE.md — discord-events (Peep Bot)

## Project Overview

Peep Bot is a Discord event management application. It consists of a Spring Boot backend (REST API + Discord bot) and a
React/Vite frontend. Users authenticate via Discord OAuth2 and can create/manage events that are surfaced to a Discord
server.

## Repository Structure

```
discord-events/
├── backend/          # Spring Boot 3.5.8, Java 21, Gradle
├── frontend/         # React 19, Vite 7
├── docker-compose.yml  # PostgreSQL database only
├── docs/             # Documentation
└── nginx/            # Empty (nginx not used locally)
```

## Local Development Setup

### Prerequisites

- **Java 21** (Gradle toolchain will use it automatically)
- **Node.js 20.19+ or 22.12+** — Vite 7 requires this minimum. Use nvm to switch if needed.
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

### 3. Start the Backend

From `backend/`:

```bash
./gradlew bootRun "--args=--spring.profiles.active=local \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/peepbot \
  --spring.datasource.username=peepbot \
  --spring.datasource.password=peepbot \
  --spring.devtools.restart.enabled=false \
  --server.servlet.session.cookie.secure=false \
  --spring.session.cookie.secure=false \
  --dev.tylercash.cors.allowed-origins=http://localhost:5173"
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
    allowed-origins: http://localhost:5173
```

### 4. Start the Frontend

From `frontend/`:

```bash
npm install
npm run dev
```

The frontend starts on port 5173 at `http://localhost:5173`. Vite proxies `/api` requests to the backend at
`http://localhost:8080`.

### 5. Log In

Navigate to `http://localhost:5173/login` and click "Log in with Discord". The OAuth2 redirect URI is configured in
`application-local.yaml` (must be registered in the Discord developer portal for your local bot app).

## Common Commands

### Backend

```bash
# Run with local profile
./gradlew bootRun "--args=--spring.profiles.active=local ..."

# Run tests (uses H2 + Testcontainers)
./gradlew test

# Build executable jar
./gradlew bootJar

# Build Docker image (ghcr.io/tyler-cash/peep-bot-backend:latest)
./gradlew bootBuildImage

# Check health
curl http://localhost:8080/api/actuator/health
```

### Frontend

```bash
npm run dev      # Start dev server (port 5173)
npm run build    # Production build
npm run preview  # Preview production build
```

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

- **React 19** + **Vite 7**
- **Redux Toolkit** — state management
- **React Router DOM v7** — routing
- **Bootstrap 5** — styling
- **react-hook-form** — form management
- **Moment.js** — date/time formatting
- API calls go through `frontend/src/api/eventBotApi.js`, which fetches a CSRF token from `/api/csrf` before mutations
  and handles 401 → redirect to Discord OAuth2

## Key Configuration Properties

| Property                               | Default                       | Purpose                                                |
|----------------------------------------|-------------------------------|--------------------------------------------------------|
| `dev.tylercash.cors.allowed-origins`   | `https://event.tylercash.dev` | CORS allowed origins — **must override for local dev** |
| `dev.tylercash.frontend.hostname`      | `event.tylercash.dev`         | Used in OAuth2 redirect after login                    |
| `dev.tylercash.discord.token`          | *(required)*                  | Discord bot token — in `application-local.yaml`        |
| `dev.tylercash.discord.guild-id`       | *(required)*                  | Discord server ID — in `application-local.yaml`        |
| `spring.datasource.url`                | *(not set in default yaml)*   | Must be provided via local config or CLI               |
| `server.servlet.session.cookie.secure` | `true`                        | Set to `false` locally to avoid HTTPS redirect         |

## Gotchas

- **DevTools restart causes port conflicts.** Always pass `--spring.devtools.restart.enabled=false` locally, or set it
  in `application-local.yaml`.
- **Secure cookie = HTTPS redirect.** The default config sets `cookie.secure=true`, which causes Tomcat to redirect HTTP
  to port 8443. Override with `server.servlet.session.cookie.secure=false` and `spring.session.cookie.secure=false`
  locally.
- **CORS defaults to production origin.** `dev.tylercash.cors.allowed-origins` defaults to
  `https://event.tylercash.dev`. Must be overridden to `http://localhost:5173` for local frontend dev.
- **`application-local.yaml` is gitignored.** It contains the Discord bot token and OAuth2 secrets — never commit it.
- **Spring Session JDBC schema.** The `spring.session.jdbc.initialize-schema: never` default means the SPRING_SESSION
  tables must already exist. Liquibase handles this — ensure migrations have run before the app starts.
