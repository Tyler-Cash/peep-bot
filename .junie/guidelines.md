Project development guidelines for advanced contributors

Scope
- This document captures project-specific practices for building, running, and testing the discord-events repository. It assumes familiarity with Spring Boot, React, Docker, OAuth2, and Discord/JDA.

Backend (Spring Boot)
- Language/tooling
  - Java: Spring Boot 3.x (requires Java 17+, recommended Java 21).
  - Dependency helpers in code: Lombok (@RequiredArgsConstructor, @Slf4j/log4j), Resilience4j RateLimiter, Spring Session JDBC, Testcontainers (for integration tests), JDA (Discord bot).
  - Context path is /api/ (see server.servlet.context-path).
- Local configuration
  - Create backend/src/main/resources/application-local.yaml and run with the local profile.
  - Minimum configuration (tokens/secrets are required for real Discord/OAuth flows):
    
    dev.tylercash:
      discord:
        token: "<bot token>"
        guild-id: <numeric guild id>
      frontend:
        protocol: http
        hostname: localhost
        path: /
    
    spring:
      datasource:
        url: jdbc:postgresql://localhost:5432/peepbot
        username: peepbot
        password: peepbot
      security:
        oauth2:
          client:
            registration:
              discord:
                client-id: "<discord app client id>"
                client-secret: "<discord app client secret>"
  - Notes
    - The OAuth2 client configuration uses discord provider endpoints defined in application.yaml (springdoc and provider.discord entries). Redirect URI will be {baseUrl}/login/oauth2/code/discord.
    - Cookies and security: SESSION cookie is http-only, secure, same-site=Lax. When developing through nginx on http://localhost:8000 the app is reverse-proxied; secure cookie semantics are handled via proxy headers. If you access the backend directly on 8080 without nginx, authentication flows and cookies may not behave as intended.
    - Discord server prerequisites: create an “outings” category and an initial text channel (default memories). The separator channel name for sorting is organising (dev.tylercash.discord.seperator-channel). Channel names incorporate an ordinal day (e.g., 30th-dec-...). Timezone handling for naming uses Australia/Sydney.
- Running locally
  - Start infrastructure: docker-compose up -d (postgres at 5432, nginx at 8000).
  - Start frontend: npm start in frontend/ (listens on 3000; nginx proxies it to 8000).
  - Start backend with the local profile from your IDE (IntelliJ) or Gradle/Maven as appropriate:
    - VM/property example: -Dspring.profiles.active=local
    - The backend will serve at http://localhost:8080/api/ and be available via nginx at http://localhost:8000/api/.
  - Useful URLs
    - Frontend: http://localhost:8000/
    - Swagger UI: http://localhost:8000/api/swagger-ui.html
    - DB: localhost:5432 (peepbot/peepbot)
- Build system
  - The repository is set up for Gradle (see backend/.gitignore). If the Gradle wrapper is not present on your machine, either:
    - Use IntelliJ’s Gradle import and run tasks from the IDE;
    - Or install Gradle locally and generate the wrapper: gradle wrapper --gradle-version 8.7 (or compatible with your JDK/Spring Boot version), then use ./gradlew ...
  - Common tasks
    - Run tests: ./gradlew test
    - Run a single test class: ./gradlew test --tests dev.tylercash.event.discord.GenerateAttendanceTitleSimpleTest
    - Boot app: ./gradlew bootRun --args='--spring.profiles.active=local'
  - If you prefer Maven, you can import as a Maven project only if you add a pom; the current repo indicates Gradle usage.

Backend testing
- Test types present
  - Pure unit tests (no Spring context), e.g., dev.tylercash.event.discord.DiscordUtilTest and the example test below.
  - Spring Boot integration tests with Testcontainers for PostgreSQL (EventServiceIntegrationTest). Those also touch DiscordService and thus may require real Discord credentials/bot access; avoid running them unless you’ve configured application-local.yaml and the bot is in the target guild.
- How to run tests
  - Via Gradle (recommended):
    - All tests: ./gradlew test
    - Filtered: ./gradlew test --tests "dev.tylercash.event.discord.*"
  - Via IDE: Right-click test class or package > Run Tests.
- Adding new tests
  - Place unit tests under backend/src/test/java following the existing package structure.
  - Prefer pure unit tests for DiscordUtil, event processors, and other logic to avoid external dependencies.
  - For Spring Boot integration tests:
    - Annotate with @SpringBootTest and use @Testcontainers + PostgreSQLContainer for DB state.
    - Provide DynamicPropertySource for datasource URL/credentials from the container.
    - Be cautious with beans that require JDA configuration; mock or slice test if possible to avoid hitting Discord.
- Example test (sample)
  - Suggested file path: backend/src/test/java/dev/tylercash/event/discord/GenerateAttendanceTitleSimpleTest.java
  - Purpose: Demonstrates a simple pure unit test without Spring/JDA dependencies.
  - To run just this test with Gradle (after adding the file locally):
    ./gradlew test --tests dev.tylercash.event.discord.GenerateAttendanceTitleSimpleTest

Frontend (React, react-scripts)
- package.json scripts
  - npm start: runs on 3000 and proxied by nginx on 8000.
  - npm run build: production build; note CI=false is set to bypass CRA warnings-as-errors.
  - npm test: runs Jest tests if present (currently the project does not include significant FE tests; add under src/ and follow CRA conventions).
- API proxy
  - CRA proxy is defined in package.json (http://localhost:8080). In dev, nginx front-doors both FE and BE at http://localhost:8000 (see nginx/nginx.conf); this avoids local CORS complexity.

Operational and debugging notes
- Discord/JDA
  - Many operations in DiscordService create/manage channels and send messages. These require a valid bot token, the bot to be in the guild, and correct permissions.
  - Channel sorting relies on getMonthDayFromChannelName; the separator channel organising is kept at the top. Only channels after the separator are date-sorted.
- Time and scheduling
  - Scheduling is used for pre-event notifications and maintenance (e.g., MINUTES unit present in DiscordService). Tests pin time via GlobalTestConfiguration.CLOCK.
- Security
  - OAuth2 login via Discord. Success handler redirects to the frontend as configured in dev.tylercash.frontend.* properties.
  - CSRF/CORS are disabled server-side because nginx is used during development. Do not expose the backend directly to the internet with these defaults.
- Logging and diagnostics
  - Log4j2 is used (@Log4j2). Prefer structured logs and avoid logging secrets.
  - When debugging rate-limited flows, note Resilience4j RateLimiter usage around notifications.
- Database
  - Spring Session JDBC stores sessions. Schema initialization is disabled (initialize-schema: never). Ensure the session schema exists. For local dev with a fresh DB, you can enable initialization temporarily or apply Spring Session schema SQL for PostgreSQL.

Pitfalls and tips
- Don’t run integration tests that touch Discord without a configured application-local.yaml and a bot in your dev guild; these may create/delete channels.
- All backend endpoints are under /api/ due to context-path; ensure frontend API client targets /api/.
- Nginx must be running (docker-compose up -d) for the full local auth flow to work reliably (cookies, redirects).
- Channel names incorporate Unicode and emoji; DiscordUtil sanitizes/escapes display names and generates slugs. Be mindful when adding new naming logic.

Verification performed for this document
- Executed existing unit tests and added a new demonstration unit test (GenerateAttendanceTitleSimpleTest) that passes (2/2 tests) without requiring external services.
- Did not execute integration tests that require Discord credentials.

Housekeeping
- Do not commit secrets in application-local.yaml. Use environment variables or local-only files that are gitignored.
- If you add new Gradle plugins or tasks, include the Gradle wrapper to keep builds reproducible.
