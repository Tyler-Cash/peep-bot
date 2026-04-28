# Guild Selector & Server Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a functional guild-switcher dropdown to the nav and a server settings page where admins can configure a primary location to bias Google Places autocomplete results.

**Architecture:** A new `guild_settings` table stores per-guild configuration (initially primary location lat/lng); these values are folded into the existing `GET /guild` response so the frontend has them immediately. A new `PATCH /guild/{id}/settings` endpoint (admin-only) saves changes. The frontend gains a dropdown GuildSwitcher, a `/guild/[id]/settings` page, and threads location bias through the places autocomplete pipeline.

**Tech Stack:** Spring Boot 3 / Java 21 / Liquibase / JPA (backend); Next.js App Router / TypeScript / SWR / MSW (frontend); Google Places API (New) for geocoding.

---

## File Map

### Backend — create
- `backend/src/main/java/dev/tylercash/event/discord/GuildSettings.java` — JPA entity for guild_settings table
- `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsRepository.java` — JPA repo
- `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsDto.java` — response record
- `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsRequest.java` — PATCH body record
- `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsController.java` — REST controller

### Backend — modify
- `backend/src/main/java/dev/tylercash/event/discord/GuildDto.java` — add `primaryLocationLat`, `primaryLocationLng`
- `backend/src/main/java/dev/tylercash/event/discord/GuildController.java` — inject settings into `toDto()`
- `backend/src/main/resources/db/changelog/db.changelog-master.yaml` — add `guild_settings` changeSet

### Frontend — create
- `frontend/src/app/guild/[id]/settings/page.tsx` — thin server component wrapper
- `frontend/src/components/guild/GuildSettingsForm.tsx` — settings edit form
- `frontend/src/app/api/places/geocode/route.ts` — BFF route: placeId → lat/lng

### Frontend — modify
- `frontend/src/lib/types.ts` — add `primaryLocationLat?`, `primaryLocationLng?` to `Guild`; add `GuildSettingsDto`
- `frontend/src/lib/hooks.ts` — add `useGuildSettings`, `updateGuildSettings`
- `frontend/src/lib/places.ts` — thread `locationBias` param through `searchPlaces`
- `frontend/src/components/nav/GuildSwitcher.tsx` — full rewrite as dropdown
- `frontend/src/components/ui/LocationAutocomplete.tsx` — accept `locationBias` prop
- `frontend/src/app/api/places/autocomplete/route.ts` — inject `locationBias.circle` when lat/lng provided
- `frontend/src/mocks/handlers.ts` — add guild settings mock handlers

---

## Task 1: Backend — Liquibase migration for guild_settings

**Files:**
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml`

- [ ] **Step 1: Add changeSet at the bottom of the master changelog**

  Open `backend/src/main/resources/db/changelog/db.changelog-master.yaml` and append before the final blank line:

  ```yaml
    - changeSet:
        id: create guild_settings table
        author: tyler
        changes:
          - createTable:
              tableName: guild_settings
              columns:
                - column:
                    name: guild_id
                    type: bigint
                    constraints:
                      primaryKey: true
                      nullable: false
                - column:
                    name: primary_location_place_id
                    type: text
                - column:
                    name: primary_location_name
                    type: text
                - column:
                    name: primary_location_lat
                    type: double precision
                - column:
                    name: primary_location_lng
                    type: double precision
  ```

- [ ] **Step 2: Verify migration runs cleanly**

  Start the DB if not running: `docker-compose up -d`

  Run: `cd backend && ./gradlew bootRun "--args=--spring.profiles.active=local,nonprod --spring.datasource.url=jdbc:postgresql://localhost:5432/peepbot --spring.datasource.username=peepbot --spring.datasource.password=peepbot" 2>&1 | grep -E "guild_settings|Liquibase|ERROR" | head -20`

  Expected: see `guild_settings` created, no ERROR lines.

- [ ] **Step 3: Commit**

  ```bash
  git add backend/src/main/resources/db/changelog/db.changelog-master.yaml
  git commit -m "chore: add guild_settings table migration"
  ```

---

## Task 2: Backend — GuildSettings entity, repository, DTOs

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/discord/GuildSettings.java`
- Create: `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsRepository.java`
- Create: `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsDto.java`
- Create: `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsRequest.java`

- [ ] **Step 1: Create the JPA entity**

  Create `backend/src/main/java/dev/tylercash/event/discord/GuildSettings.java`:

  ```java
  package dev.tylercash.event.discord;

  import jakarta.persistence.Column;
  import jakarta.persistence.Entity;
  import jakarta.persistence.Id;
  import jakarta.persistence.Table;
  import lombok.AllArgsConstructor;
  import lombok.Data;
  import lombok.NoArgsConstructor;

  @Entity
  @Table(name = "guild_settings")
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public class GuildSettings {

      @Id
      @Column(name = "guild_id")
      private Long guildId;

      @Column(name = "primary_location_place_id")
      private String primaryLocationPlaceId;

      @Column(name = "primary_location_name")
      private String primaryLocationName;

      @Column(name = "primary_location_lat")
      private Double primaryLocationLat;

      @Column(name = "primary_location_lng")
      private Double primaryLocationLng;
  }
  ```

- [ ] **Step 2: Create the repository**

  Create `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsRepository.java`:

  ```java
  package dev.tylercash.event.discord;

  import org.springframework.data.jpa.repository.JpaRepository;

  public interface GuildSettingsRepository extends JpaRepository<GuildSettings, Long> {}
  ```

- [ ] **Step 3: Create the response DTO**

  Create `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsDto.java`:

  ```java
  package dev.tylercash.event.discord;

  public record GuildSettingsDto(
      String primaryLocationPlaceId,
      String primaryLocationName,
      Double primaryLocationLat,
      Double primaryLocationLng) {}
  ```

- [ ] **Step 4: Create the PATCH request record**

  Create `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsRequest.java`:

  ```java
  package dev.tylercash.event.discord;

  public record GuildSettingsRequest(
      String primaryLocationPlaceId,
      String primaryLocationName,
      Double primaryLocationLat,
      Double primaryLocationLng) {}
  ```

- [ ] **Step 5: Verify compilation**

  Run: `cd backend && ./gradlew compileJava 2>&1 | tail -5`

  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

  ```bash
  git add backend/src/main/java/dev/tylercash/event/discord/GuildSettings.java \
          backend/src/main/java/dev/tylercash/event/discord/GuildSettingsRepository.java \
          backend/src/main/java/dev/tylercash/event/discord/GuildSettingsDto.java \
          backend/src/main/java/dev/tylercash/event/discord/GuildSettingsRequest.java
  git commit -m "feat: add GuildSettings entity, repository, and DTOs"
  ```

---

## Task 3: Backend — GuildSettingsController

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsController.java`

- [ ] **Step 1: Create the controller**

  Create `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsController.java`:

  ```java
  package dev.tylercash.event.discord;

  import io.swagger.v3.oas.annotations.tags.Tag;
  import lombok.RequiredArgsConstructor;
  import org.springframework.http.HttpStatus;
  import org.springframework.security.core.annotation.AuthenticationPrincipal;
  import org.springframework.security.oauth2.core.user.OAuth2User;
  import org.springframework.web.bind.annotation.*;
  import org.springframework.web.server.ResponseStatusException;

  @RestController
  @RequestMapping("/guild/{guildId}/settings")
  @RequiredArgsConstructor
  @Tag(name = "Guild", description = "Discord guild info")
  public class GuildSettingsController {

      private final GuildSettingsRepository settingsRepository;
      private final GuildMembershipService guildMembershipService;
      private final DiscordService discordService;

      @GetMapping
      public GuildSettingsDto getSettings(
              @PathVariable long guildId,
              @AuthenticationPrincipal OAuth2User principal) {
          String snowflake = principal.getAttribute("id");
          guildMembershipService.assertMember(snowflake, guildId);
          return settingsRepository.findById(guildId)
                  .map(s -> new GuildSettingsDto(
                          s.getPrimaryLocationPlaceId(),
                          s.getPrimaryLocationName(),
                          s.getPrimaryLocationLat(),
                          s.getPrimaryLocationLng()))
                  .orElse(new GuildSettingsDto(null, null, null, null));
      }

      @PatchMapping
      public GuildSettingsDto updateSettings(
              @PathVariable long guildId,
              @RequestBody GuildSettingsRequest request,
              @AuthenticationPrincipal OAuth2User principal) {
          String snowflake = principal.getAttribute("id");
          guildMembershipService.assertMember(snowflake, guildId);
          boolean isAdmin = discordService.isUserAdminOfServer(guildId, Long.parseLong(snowflake));
          if (!isAdmin) {
              throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
          }
          GuildSettings settings = settingsRepository.findById(guildId)
                  .orElse(new GuildSettings(guildId, null, null, null, null));
          settings.setPrimaryLocationPlaceId(request.primaryLocationPlaceId());
          settings.setPrimaryLocationName(request.primaryLocationName());
          settings.setPrimaryLocationLat(request.primaryLocationLat());
          settings.setPrimaryLocationLng(request.primaryLocationLng());
          settingsRepository.save(settings);
          return new GuildSettingsDto(
                  settings.getPrimaryLocationPlaceId(),
                  settings.getPrimaryLocationName(),
                  settings.getPrimaryLocationLat(),
                  settings.getPrimaryLocationLng());
      }
  }
  ```

- [ ] **Step 2: Verify compilation**

  Run: `cd backend && ./gradlew compileJava 2>&1 | tail -5`

  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Smoke-test the endpoints locally**

  With the backend running:

  ```bash
  # GET settings (should return nulls initially)
  curl -s http://localhost:8080/api/guild/427465120554418177/settings \
    -H "Cookie: SESSION=<your-session-cookie>" | jq .

  # PATCH settings
  curl -s -X PATCH http://localhost:8080/api/guild/427465120554418177/settings \
    -H "Cookie: SESSION=<your-session-cookie>" \
    -H "Content-Type: application/json" \
    -H "X-XSRF-TOKEN: <your-csrf-token>" \
    -d '{"primaryLocationPlaceId":"ChIJpc8VSEVbExURFEVNxQ7V6_o","primaryLocationName":"Melbourne, VIC, Australia","primaryLocationLat":-37.8136,"primaryLocationLng":144.9631}' | jq .
  ```

  Expected: JSON with the saved values.

- [ ] **Step 4: Commit**

  ```bash
  git add backend/src/main/java/dev/tylercash/event/discord/GuildSettingsController.java
  git commit -m "feat: add GET/PATCH guild settings endpoint"
  ```

---

## Task 4: Backend — Thread primary location into GuildDto

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/GuildDto.java`
- Modify: `backend/src/main/java/dev/tylercash/event/discord/GuildController.java`

- [ ] **Step 1: Add lat/lng fields to GuildDto**

  Replace the contents of `GuildDto.java`:

  ```java
  package dev.tylercash.event.discord;

  public record GuildDto(
      String id,
      String name,
      String initials,
      String iconUrl,
      String color,
      String channel,
      int members,
      Double primaryLocationLat,
      Double primaryLocationLng) {}
  ```

- [ ] **Step 2: Inject GuildSettingsRepository into GuildController and update toDto()**

  Replace `GuildController.java`:

  ```java
  package dev.tylercash.event.discord;

  import io.swagger.v3.oas.annotations.tags.Tag;
  import java.awt.Color;
  import java.util.List;
  import java.util.Objects;
  import lombok.RequiredArgsConstructor;
  import net.dv8tion.jda.api.JDA;
  import net.dv8tion.jda.api.entities.Guild;
  import org.springframework.security.core.annotation.AuthenticationPrincipal;
  import org.springframework.security.oauth2.core.user.OAuth2User;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.RequestMapping;
  import org.springframework.web.bind.annotation.RestController;

  @RestController
  @RequestMapping("/guild")
  @RequiredArgsConstructor
  @Tag(name = "Guild", description = "Discord guild info")
  public class GuildController {
      private final JDA jda;
      private final DiscordConfiguration discordConfiguration;
      private final GuildMembershipService guildMembershipService;
      private final GuildSettingsRepository settingsRepository;

      @GetMapping
      public List<GuildDto> getGuilds(@AuthenticationPrincipal OAuth2User principal) {
          String snowflake = principal.getAttribute("id");
          List<Long> userGuildIds = guildMembershipService.getGuildIdsForUser(snowflake);
          if (userGuildIds.isEmpty()) {
              Guild guild = jda.getGuildById(discordConfiguration.getGuildId());
              return guild != null ? List.of(toDto(guild)) : List.of();
          }
          return userGuildIds.stream()
                  .map(jda::getGuildById)
                  .filter(Objects::nonNull)
                  .map(this::toDto)
                  .toList();
      }

      private GuildDto toDto(Guild guild) {
          String name = guild.getName();
          GuildSettings settings = settingsRepository.findById(Long.parseLong(guild.getId())).orElse(null);
          return new GuildDto(
                  guild.getId(),
                  name,
                  deriveInitials(name),
                  guild.getIconUrl(),
                  deriveColor(guild.getId()),
                  discordConfiguration.getSeperatorChannel(),
                  guild.getMemberCount(),
                  settings != null ? settings.getPrimaryLocationLat() : null,
                  settings != null ? settings.getPrimaryLocationLng() : null);
      }

      private static String deriveInitials(String name) {
          String[] words = name.trim().split("\\s+");
          StringBuilder sb = new StringBuilder();
          if (words.length >= 2) {
              for (String word : words) {
                  if (!word.isEmpty()) {
                      sb.appendCodePoint(word.codePointAt(0));
                  }
                  if (sb.length() >= 2) break;
              }
          } else {
              String word = words[0];
              sb.append(word, 0, Math.min(2, word.length()));
          }
          return sb.toString().toUpperCase();
      }

      private static String deriveColor(String guildId) {
          long id = Long.parseLong(guildId);
          float hue = (Math.abs(id) % 360) / 360.0f;
          Color color = Color.getHSBColor(hue, 0.45f, 0.95f);
          return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
      }
  }
  ```

- [ ] **Step 3: Verify and run Spotless**

  ```bash
  cd backend && ./gradlew compileJava spotlessApply 2>&1 | tail -5
  ```

  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

  ```bash
  git add backend/src/main/java/dev/tylercash/event/discord/GuildDto.java \
          backend/src/main/java/dev/tylercash/event/discord/GuildController.java
  git commit -m "feat: include primary location in guild list response"
  ```

---

## Task 5: Frontend — Update types and add geocode API route

**Files:**
- Modify: `frontend/src/lib/types.ts`
- Create: `frontend/src/app/api/places/geocode/route.ts`

- [ ] **Step 1: Add primaryLocation fields to Guild type, add GuildSettingsDto**

  In `frontend/src/lib/types.ts`, replace the `Guild` type:

  ```ts
  export type Guild = {
    id: string;
    name: string;
    initials: string;
    iconUrl?: string | null;
    color: string;
    channel: string;
    members: number;
    active?: boolean;
    primaryLocationLat?: number | null;
    primaryLocationLng?: number | null;
  };

  export type GuildSettingsDto = {
    primaryLocationPlaceId: string | null;
    primaryLocationName: string | null;
    primaryLocationLat: number | null;
    primaryLocationLng: number | null;
  };
  ```

  (Keep all other types unchanged.)

- [ ] **Step 2: Create the geocode BFF route**

  Create `frontend/src/app/api/places/geocode/route.ts`:

  ```ts
  import { cookies } from "next/headers";

  export async function GET(req: Request) {
    const cookieStore = await cookies();
    const sessionKey = cookieStore.get("SESSION")?.value;
    if (!sessionKey) {
      return Response.json({ error: "unauthorized" }, { status: 401 });
    }

    const url = new URL(req.url);
    const placeId = url.searchParams.get("placeId");
    const sessionToken = url.searchParams.get("sessionToken") ?? "";

    if (!placeId) return Response.json({ error: "placeId required" }, { status: 400 });

    const key = process.env.GOOGLE_MAPS_KEY;
    if (!key) return Response.json({ error: "no key" }, { status: 503 });

    try {
      const detailUrl = new URL(
        `https://places.googleapis.com/v1/places/${encodeURIComponent(placeId)}`,
      );
      if (sessionToken) detailUrl.searchParams.set("sessionToken", sessionToken);

      const res = await fetch(detailUrl.toString(), {
        headers: {
          "X-Goog-Api-Key": key,
          "X-Goog-FieldMask": "id,location",
        },
      });

      if (!res.ok) return Response.json({ error: "places error" }, { status: 502 });

      const data = (await res.json()) as {
        location?: { latitude: number; longitude: number };
      };

      if (!data.location) return Response.json({ error: "no location" }, { status: 404 });

      return Response.json({
        lat: data.location.latitude,
        lng: data.location.longitude,
      });
    } catch {
      return Response.json({ error: "fetch failed" }, { status: 502 });
    }
  }
  ```

- [ ] **Step 3: Commit**

  ```bash
  cd frontend
  git add src/lib/types.ts src/app/api/places/geocode/route.ts
  git commit -m "feat: add GuildSettingsDto type and places geocode BFF route"
  ```

---

## Task 6: Frontend — Update hooks and places library

**Files:**
- Modify: `frontend/src/lib/hooks.ts`
- Modify: `frontend/src/lib/places.ts`

- [ ] **Step 1: Add useGuildSettings hook and updateGuildSettings mutation to hooks.ts**

  At the top of `hooks.ts`, add `GuildSettingsDto` to the imports from `./types`:

  ```ts
  import type {
    EventDetailDto,
    EventDto,
    Guild,
    GuildSettingsDto,
    RewindStats,
    RsvpStatus,
    UserInfo,
  } from "./types";
  ```

  At the end of `hooks.ts`, append:

  ```ts
  export function useGuildSettings(guildId: string | null) {
    return useSWR<GuildSettingsDto>(
      guildId ? `/guild/${guildId}/settings` : null,
      fetcher,
    );
  }

  export async function updateGuildSettings(
    guildId: string,
    settings: GuildSettingsDto,
  ) {
    await apiFetch<GuildSettingsDto>(`/guild/${guildId}/settings`, {
      method: "PATCH",
      body: JSON.stringify(settings),
    });
    await globalMutate(`/guild/${guildId}/settings`);
    await globalMutate("/guild");
  }
  ```

- [ ] **Step 2: Add locationBias param to searchPlaces in places.ts**

  In `frontend/src/lib/places.ts`, update the `searchPlaces` signature and implementation:

  ```ts
  export async function searchPlaces(
    query: string,
    sessionToken: string,
    signal?: AbortSignal,
    locationBias?: { lat: number; lng: number },
  ): Promise<SearchResult> {
    if (MODE === "mock") return mockSearch(query);
    if (!query.trim()) return [];

    if (Date.now() < blockedUntil) {
      return {
        rateLimited: true,
        retryAfter: Math.ceil((blockedUntil - Date.now()) / 1000),
      };
    }

    try {
      const params = new URLSearchParams({ q: query, sessionToken });
      if (locationBias) {
        params.set("lat", String(locationBias.lat));
        params.set("lng", String(locationBias.lng));
      }
      const res = await fetch(`/api/places/autocomplete?${params}`, { signal });

      if (res.status === 429) {
        const retryAfter = Number(res.headers.get("Retry-After") ?? "1");
        blockedUntil = Date.now() + retryAfter * 1000;
        return { rateLimited: true, retryAfter };
      }

      if (!res.ok) return [];
      return (await res.json()) as PlaceSuggestion[];
    } catch {
      return [];
    }
  }
  ```

  Also add a helper for geocoding (used in the settings form):

  ```ts
  export async function geocodePlace(
    placeId: string,
    sessionToken: string,
  ): Promise<{ lat: number; lng: number } | null> {
    if (MODE === "mock") return { lat: -37.8136, lng: 144.9631 };
    try {
      const params = new URLSearchParams({ placeId, sessionToken });
      const res = await fetch(`/api/places/geocode?${params}`);
      if (!res.ok) return null;
      return (await res.json()) as { lat: number; lng: number };
    } catch {
      return null;
    }
  }
  ```

- [ ] **Step 3: Update the autocomplete BFF route to inject locationBias**

  Replace `frontend/src/app/api/places/autocomplete/route.ts`:

  ```ts
  import { cookies } from "next/headers";
  import { checkPlacesRateLimit } from "@/lib/rateLimiter";

  type GoogleSuggestion = {
    placePrediction?: {
      placeId: string;
      structuredFormat?: {
        mainText?: { text: string };
        secondaryText?: { text: string };
      };
      text?: { text: string };
    };
  };

  export async function GET(req: Request) {
    const cookieStore = await cookies();
    const sessionKey = cookieStore.get("SESSION")?.value;
    if (!sessionKey) {
      return Response.json({ error: "unauthorized" }, { status: 401 });
    }

    const url = new URL(req.url);
    const q = url.searchParams.get("q") ?? "";
    const sessionToken = url.searchParams.get("sessionToken") ?? "";
    const latParam = url.searchParams.get("lat");
    const lngParam = url.searchParams.get("lng");

    if (!q.trim()) {
      return Response.json([]);
    }

    const rateLimit = await checkPlacesRateLimit(sessionKey);
    if ("retryAfter" in rateLimit) {
      return Response.json(
        { error: "rate limited" },
        { status: 429, headers: { "Retry-After": String(rateLimit.retryAfter) } },
      );
    }

    const key = process.env.GOOGLE_MAPS_KEY;
    if (!key) return Response.json([]);

    const body: Record<string, unknown> = { input: q, sessionToken };
    if (latParam && lngParam) {
      const lat = parseFloat(latParam);
      const lng = parseFloat(lngParam);
      if (isFinite(lat) && isFinite(lng)) {
        body.locationBias = {
          circle: {
            center: { latitude: lat, longitude: lng },
            radius: 50000.0,
          },
        };
      }
    }

    try {
      const res = await fetch(
        "https://places.googleapis.com/v1/places:autocomplete",
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "X-Goog-Api-Key": key,
          },
          body: JSON.stringify(body),
        },
      );

      if (!res.ok) return Response.json([]);

      const data = (await res.json()) as { suggestions?: GoogleSuggestion[] };

      return Response.json(
        (data.suggestions ?? [])
          .map((s) => s.placePrediction)
          .filter((p): p is NonNullable<typeof p> => Boolean(p))
          .map((p) => ({
            id: p.placeId,
            title: p.structuredFormat?.mainText?.text ?? p.text?.text ?? "",
            subtitle: p.structuredFormat?.secondaryText?.text,
          })),
      );
    } catch {
      return Response.json([]);
    }
  }
  ```

- [ ] **Step 4: Lint and type-check**

  ```bash
  cd frontend && npm run lint && npx tsc --noEmit 2>&1 | tail -20
  ```

  Expected: no errors.

- [ ] **Step 5: Commit**

  ```bash
  git add frontend/src/lib/hooks.ts \
          frontend/src/lib/places.ts \
          frontend/src/app/api/places/autocomplete/route.ts
  git commit -m "feat: add guild settings hooks and wire location bias into places autocomplete"
  ```

---

## Task 7: Frontend — Update LocationAutocomplete to accept locationBias

**Files:**
- Modify: `frontend/src/components/ui/LocationAutocomplete.tsx`

- [ ] **Step 1: Add locationBias prop and thread it through searchPlaces calls**

  Replace the component signature and the two `searchPlaces` call sites in `LocationAutocomplete.tsx`:

  Change the props interface (add `locationBias`):

  ```ts
  export function LocationAutocomplete({
    value,
    onChange,
    placeholder,
    required,
    className,
    recent,
    locationBias,
  }: {
    value: string;
    onChange: (value: string) => void;
    placeholder?: string;
    required?: boolean;
    className?: string;
    recent?: string[];
    locationBias?: { lat: number; lng: number };
  }) {
  ```

  Update the first `searchPlaces` call (inside the `setTimeout`):

  ```ts
  const results = await searchPlaces(value, sessionToken, ctrl.signal, locationBias);
  ```

  Update the retry `searchPlaces` call:

  ```ts
  const retryResults = await searchPlaces(value, sessionToken, undefined, locationBias);
  ```

- [ ] **Step 2: Lint check**

  ```bash
  cd frontend && npm run lint 2>&1 | tail -10
  ```

  Expected: no errors.

- [ ] **Step 3: Commit**

  ```bash
  git add frontend/src/components/ui/LocationAutocomplete.tsx
  git commit -m "feat: thread locationBias prop through LocationAutocomplete"
  ```

---

## Task 8: Frontend — Guild settings page and form

**Files:**
- Create: `frontend/src/app/guild/[id]/settings/page.tsx`
- Create: `frontend/src/components/guild/GuildSettingsForm.tsx`

- [ ] **Step 1: Create the thin page wrapper**

  Create `frontend/src/app/guild/[id]/settings/page.tsx`:

  ```tsx
  import { AppShell } from "@/components/AppShell";
  import { GuildSettingsForm } from "@/components/guild/GuildSettingsForm";

  export default async function GuildSettingsPage({
    params,
  }: {
    params: Promise<{ id: string }>;
  }) {
    const { id } = await params;
    return (
      <AppShell>
        <GuildSettingsForm guildId={id} />
      </AppShell>
    );
  }
  ```

- [ ] **Step 2: Create the GuildSettingsForm component**

  Create `frontend/src/components/guild/GuildSettingsForm.tsx`:

  ```tsx
  "use client";

  import { useRouter } from "next/navigation";
  import { useEffect, useState } from "react";
  import Link from "next/link";
  import { Chunky } from "@/components/ui/Chunky";
  import { Slab } from "@/components/ui/Slab";
  import { LocationAutocomplete } from "@/components/ui/LocationAutocomplete";
  import {
    updateGuildSettings,
    useActiveGuild,
    useCurrentUser,
    useGuildSettings,
  } from "@/lib/hooks";
  import {
    fetchPlaceDetails,
    geocodePlace,
    newPlacesSessionToken,
  } from "@/lib/places";
  import { useMemo, useRef } from "react";

  export function GuildSettingsForm({ guildId }: { guildId: string }) {
    const router = useRouter();
    const { data: user } = useCurrentUser();
    const activeGuild = useActiveGuild();
    const { data: settings, isLoading } = useGuildSettings(guildId);
    const sessionToken = useMemo(newPlacesSessionToken, []);

    const [primaryLocation, setPrimaryLocation] = useState("");
    const [primaryLocationPlaceId, setPrimaryLocationPlaceId] = useState<string | null>(null);
    const [primaryLocationLat, setPrimaryLocationLat] = useState<number | null>(null);
    const [primaryLocationLng, setPrimaryLocationLng] = useState<number | null>(null);
    const [initialized, setInitialized] = useState(false);
    const [submitting, setSubmitting] = useState(false);

    // Populate from loaded settings once
    useEffect(() => {
      if (settings && !initialized) {
        setPrimaryLocation(settings.primaryLocationName ?? "");
        setPrimaryLocationPlaceId(settings.primaryLocationPlaceId ?? null);
        setPrimaryLocationLat(settings.primaryLocationLat ?? null);
        setPrimaryLocationLng(settings.primaryLocationLng ?? null);
        setInitialized(true);
      }
    }, [settings, initialized]);

    // Redirect non-admins
    useEffect(() => {
      if (user && !user.admin) router.push("/");
    }, [user, router]);

    const handleLocationChange = (value: string) => {
      setPrimaryLocation(value);
      // Clear resolved coords when user edits the field manually
      setPrimaryLocationPlaceId(null);
      setPrimaryLocationLat(null);
      setPrimaryLocationLng(null);
    };

    const handleLocationPick = async (placeId: string, displayValue: string) => {
      setPrimaryLocation(displayValue);
      setPrimaryLocationPlaceId(placeId);
      fetchPlaceDetails(placeId, sessionToken);
      const coords = await geocodePlace(placeId, sessionToken);
      if (coords) {
        setPrimaryLocationLat(coords.lat);
        setPrimaryLocationLng(coords.lng);
      }
    };

    if (isLoading || !settings) {
      return <div className="mx-auto max-w-[820px] p-8 text-mute">loading…</div>;
    }

    const onSubmit = async (e: React.FormEvent) => {
      e.preventDefault();
      setSubmitting(true);
      try {
        await updateGuildSettings(guildId, {
          primaryLocationPlaceId,
          primaryLocationName: primaryLocation.trim() || null,
          primaryLocationLat,
          primaryLocationLng,
        });
        router.push("/");
      } finally {
        setSubmitting(false);
      }
    };

    const locationBias =
      activeGuild?.primaryLocationLat != null && activeGuild?.primaryLocationLng != null
        ? { lat: activeGuild.primaryLocationLat, lng: activeGuild.primaryLocationLng }
        : undefined;

    return (
      <div className="mx-auto max-w-[820px] px-5 py-6">
        <div className="flex items-center justify-between mb-5">
          <Link
            href="/"
            className="inline-flex items-center gap-1.5 text-[14px] text-mute hover:text-ink"
          >
            ← back
          </Link>
        </div>

        <header className="flex items-center gap-3 mb-5">
          <span className="inline-flex items-center justify-center w-10 h-10 rounded-[10px] border-[1.5px] border-ink shadow-chunky-sm bg-paper2 text-[18px]">
            ⚙️
          </span>
          <div>
            <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
              SERVER SETTINGS
            </span>
            <h1 className="text-[36px] font-extrabold tracking-[-0.03em] leading-none mt-0.5">
              {activeGuild?.name ?? "server config"}
            </h1>
          </div>
        </header>

        <form onSubmit={onSubmit} className="flex flex-col gap-4">
          <Slab className="p-5 flex flex-col gap-4">
            <Field label="primary location">
              <LocationAutocomplete
                value={primaryLocation}
                onChange={handleLocationChange}
                onPick={handleLocationPick}
                placeholder="e.g. Melbourne, VIC"
                locationBias={locationBias}
              />
              <p className="text-[11.5px] text-mute font-semibold mt-1">
                Used to bias venue search results towards your group's area.
              </p>
            </Field>
          </Slab>

          <div className="flex items-center justify-between">
            <Link
              href="/"
              className="inline-flex items-center gap-1.5 text-[14px] text-mute hover:text-ink"
            >
              cancel
            </Link>
            <Chunky type="submit" variant="leaf" size="lg" disabled={submitting}>
              {submitting ? "saving…" : "save settings"}
            </Chunky>
          </div>
        </form>
      </div>
    );
  }

  function Field({
    label,
    children,
  }: {
    label: string;
    children: React.ReactNode;
  }) {
    return (
      <label className="flex flex-col gap-1.5">
        <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
          {label}
        </span>
        {children}
      </label>
    );
  }
  ```

  **Note:** The form uses an `onPick` callback to capture the `placeId` before the value is flattened to a string. This requires a small extension to `LocationAutocomplete` in the next step.

- [ ] **Step 3: Add onPick callback to LocationAutocomplete**

  In `frontend/src/components/ui/LocationAutocomplete.tsx`, add `onPick` to the props:

  ```ts
  export function LocationAutocomplete({
    value,
    onChange,
    onPick,
    placeholder,
    required,
    className,
    recent,
    locationBias,
  }: {
    value: string;
    onChange: (value: string) => void;
    onPick?: (placeId: string, displayValue: string) => void;
    placeholder?: string;
    required?: boolean;
    className?: string;
    recent?: string[];
    locationBias?: { lat: number; lng: number };
  }) {
  ```

  Update the `pick` function in the component to call `onPick` when present:

  ```ts
  const pick = (s: PlaceSuggestion) => {
    const displayValue = suggestionToLocation(s);
    onChange(displayValue);
    if (onPick && !s.id.startsWith("recent:")) {
      onPick(s.id, displayValue);
    }
    fetchPlaceDetails(s.id, sessionToken);
    setOpen(false);
    inputRef.current?.blur();
  };
  ```

  Remove the existing `fetchPlaceDetails(s.id, sessionToken)` call from `pick` since `GuildSettingsForm.handleLocationPick` now handles it for the settings form and the event forms don't need the placeId. Keep the call here for event forms (it's the billing-only fire-and-forget). When `onPick` is provided, the caller controls geocoding; keep `fetchPlaceDetails` here so event forms still trigger the billing session close.

  Final `pick` function:

  ```ts
  const pick = (s: PlaceSuggestion) => {
    const displayValue = suggestionToLocation(s);
    onChange(displayValue);
    fetchPlaceDetails(s.id, sessionToken);
    if (onPick && !s.id.startsWith("recent:")) {
      onPick(s.id, displayValue);
    }
    setOpen(false);
    inputRef.current?.blur();
  };
  ```

- [ ] **Step 4: Lint and type-check**

  ```bash
  cd frontend && npm run lint && npx tsc --noEmit 2>&1 | tail -20
  ```

  Fix any errors before continuing.

- [ ] **Step 5: Commit**

  ```bash
  git add frontend/src/app/guild/ \
          frontend/src/components/guild/ \
          frontend/src/components/ui/LocationAutocomplete.tsx
  git commit -m "feat: add guild settings page and form"
  ```

---

## Task 9: Frontend — Refactor GuildSwitcher to dropdown

**Files:**
- Modify: `frontend/src/components/nav/GuildSwitcher.tsx`

- [ ] **Step 1: Rewrite GuildSwitcher as a dropdown**

  Replace the full contents of `frontend/src/components/nav/GuildSwitcher.tsx`:

  ```tsx
  "use client";

  import { useEffect, useRef, useState } from "react";
  import { useRouter } from "next/navigation";
  import clsx from "@/lib/clsx";
  import { useActiveGuild, useCurrentUser, useGuilds } from "@/lib/hooks";
  import type { Guild } from "@/lib/types";

  export function GuildSwitcher() {
    const guild = useActiveGuild();
    const [open, setOpen] = useState(false);
    const ref = useRef<HTMLDivElement>(null);

    useEffect(() => {
      const onDocClick = (e: MouseEvent) => {
        if (!ref.current?.contains(e.target as Node)) setOpen(false);
      };
      document.addEventListener("mousedown", onDocClick);
      return () => document.removeEventListener("mousedown", onDocClick);
    }, []);

    if (!guild) {
      return (
        <div className="h-[46px] w-[220px] rounded-[12px] bg-paper2 border-[1.5px] border-ink/20" />
      );
    }

    return (
      <div ref={ref} className="relative shrink-0">
        <button
          type="button"
          onClick={() => setOpen((o) => !o)}
          className={clsx(
            "inline-flex items-center gap-2.5 rounded-[12px] border-[1.5px] border-ink bg-paper pl-1.5 pr-3 py-1.5",
            "shadow-chunky-sm hover:shadow-chunky-md active:shadow-chunky-active active:translate-x-[0.5px] active:translate-y-[0.5px]",
            "transition-[box-shadow,transform] select-none",
          )}
          title={`${guild.name} · #${guild.channel}`}
        >
          <GuildIcon guild={guild} />
          <span className="flex flex-col leading-none">
            <span className="text-[13.5px] font-extrabold tracking-[-0.01em] max-w-[160px] overflow-hidden text-ellipsis whitespace-nowrap">
              {guild.name}
            </span>
            <span className="text-[11.5px] text-mute font-semibold mt-0.5 whitespace-nowrap">
              ● #{guild.channel}
            </span>
          </span>
          <span className="ml-1 text-[10px] text-mute">▾</span>
        </button>

        {open && <GuildDropdown onClose={() => setOpen(false)} />}
      </div>
    );
  }

  function GuildDropdown({ onClose }: { onClose: () => void }) {
    const { data: guilds } = useGuilds();
    const { data: user } = useCurrentUser();
    const router = useRouter();

    return (
      <div className="absolute left-0 top-[calc(100%+6px)] z-30 w-[260px] rounded-[14px] border-[1.5px] border-ink bg-paper shadow-chunky-md overflow-hidden">
        <div className="px-3 pt-2.5 pb-1.5 text-[10.5px] font-extrabold tracking-[0.18em] text-mute uppercase border-b-[1px] border-ink/10">
          your servers
        </div>

        {(guilds ?? []).map((g) => (
          <div
            key={g.id}
            className="flex items-center gap-2.5 px-3 py-2.5 border-b-[1px] border-ink/10 last:border-b-0 hover:bg-paper2"
          >
            <GuildIcon guild={g} />
            <span className="flex-1 min-w-0">
              <span className="block text-[13.5px] font-extrabold tracking-[-0.01em] truncate">
                {g.name}
              </span>
              <span className="block text-[11.5px] text-mute font-semibold">
                #{g.channel}
              </span>
            </span>
            {user?.admin && (
              <button
                type="button"
                title="Server settings"
                onClick={() => {
                  onClose();
                  router.push(`/guild/${g.id}/settings`);
                }}
                className="text-mute hover:text-ink text-[16px] p-1 rounded-[6px] hover:bg-paper2 flex-shrink-0"
              >
                ✏️
              </button>
            )}
          </div>
        ))}

        <div className="px-3 py-2.5">
          <button
            type="button"
            disabled
            className="w-full text-left text-[13px] font-semibold text-mute/50 cursor-not-allowed flex items-center gap-2"
          >
            <span className="inline-flex items-center justify-center w-7 h-7 rounded-[7px] border-[1.5px] border-ink/20 bg-paper2 text-[14px]">
              +
            </span>
            Add a server
          </button>
        </div>
      </div>
    );
  }

  function GuildIcon({ guild }: { guild: Guild }) {
    return (
      <span
        className="inline-flex items-center justify-center w-8 h-8 rounded-[8px] border-[1.5px] border-ink text-[12px] font-extrabold shrink-0"
        style={{ background: guild.color, color: "#0E100D" }}
      >
        {guild.initials}
      </span>
    );
  }
  ```

- [ ] **Step 2: Lint and type-check**

  ```bash
  cd frontend && npm run lint && npx tsc --noEmit 2>&1 | tail -10
  ```

  Expected: no errors.

- [ ] **Step 3: Commit**

  ```bash
  git add frontend/src/components/nav/GuildSwitcher.tsx
  git commit -m "feat: refactor GuildSwitcher to functional dropdown with edit button"
  ```

---

## Task 10: Frontend — MSW mock handlers for guild settings

**Files:**
- Modify: `frontend/src/mocks/handlers.ts`
- Modify: `frontend/src/mocks/fixtures.ts`

- [ ] **Step 1: Add guild settings to the mock fixture**

  In `frontend/src/mocks/fixtures.ts`, find the `guild` fixture object and add the new fields. The guild fixture currently looks like `{ id: "...", name: "...", ... }` — add `primaryLocationLat` and `primaryLocationLng` as null initially:

  Find the guild fixture definition and update it to include:
  ```ts
  primaryLocationLat: null as number | null,
  primaryLocationLng: null as number | null,
  ```

  Also add a mutable settings store alongside the existing `store`:
  ```ts
  export const guildSettings = {
    primaryLocationPlaceId: null as string | null,
    primaryLocationName: null as string | null,
    primaryLocationLat: null as number | null,
    primaryLocationLng: null as string | null,
  };
  ```

- [ ] **Step 2: Add handlers for GET/PATCH guild settings and geocode**

  In `frontend/src/mocks/handlers.ts`, import `guildSettings` from fixtures and add handlers. After the existing `http.get(API("/guild/[^/]+"), ...)` handler, add:

  ```ts
  http.get(API("/guild/[^/]+/settings"), () =>
    HttpResponse.json(guildSettings),
  ),

  http.patch(API("/guild/[^/]+/settings"), async ({ request }) => {
    const body = (await request.json()) as typeof guildSettings;
    Object.assign(guildSettings, body);
    guild.primaryLocationLat = body.primaryLocationLat ?? null;
    guild.primaryLocationLng = body.primaryLocationLng ?? null;
    return HttpResponse.json(guildSettings);
  }),

  http.get(API("/api/places/geocode"), ({ request }) => {
    const url = new URL(request.url);
    const placeId = url.searchParams.get("placeId");
    if (!placeId) return new HttpResponse(null, { status: 400 });
    return HttpResponse.json({ lat: -37.8136, lng: 144.9631 });
  }),
  ```

- [ ] **Step 3: Lint check**

  ```bash
  cd frontend && npm run lint 2>&1 | tail -10
  ```

  Expected: no errors.

- [ ] **Step 4: Commit**

  ```bash
  git add frontend/src/mocks/
  git commit -m "feat: add MSW mock handlers for guild settings and geocode"
  ```

---

## Task 11: End-to-end verification

- [ ] **Step 1: Start backend and frontend**

  Backend: `cd backend && ./gradlew bootRun "--args=--spring.profiles.active=local,nonprod --spring.datasource.url=jdbc:postgresql://localhost:5432/peepbot --spring.datasource.username=peepbot --spring.datasource.password=peepbot --spring.devtools.restart.enabled=false --server.servlet.session.cookie.secure=false --spring.session.cookie.secure=false --dev.tylercash.cors.allowed-origins=http://localhost:5173"`

  Frontend: `cd frontend && npm run dev`

- [ ] **Step 2: Verify GuildSwitcher dropdown**

  Navigate to `http://localhost:5173`. Click the guild pill in the nav bar. Verify:
  - Dropdown opens with "your servers" header
  - The guild is listed with name, channel, and edit pencil icon (if admin)
  - "Add a server" button is visible but disabled (greyed out, not clickable)
  - Clicking outside the dropdown closes it

- [ ] **Step 3: Verify server settings page**

  Click the pencil icon next to the guild. Verify:
  - Navigates to `/guild/<id>/settings`
  - Page shows "SERVER SETTINGS" header with the guild name
  - A "primary location" field with LocationAutocomplete is shown
  - Typing in the field shows Google Places suggestions

- [ ] **Step 4: Save a primary location and verify bias**

  Pick a location (e.g. "Melbourne"). Save. Then open Create Event, type a venue — verify autocomplete suggestions are now geographically biased towards Melbourne.

- [ ] **Step 5: Final lint, format, and Spotless check**

  ```bash
  cd frontend && npm run lint && npm run format:check
  cd ../backend && ./gradlew spotlessCheck
  ```

  Fix any issues before final commit.

- [ ] **Step 6: Final commit if any fixes applied**

  ```bash
  git add -p
  git commit -m "fix: post-review cleanup for guild settings feature"
  ```
