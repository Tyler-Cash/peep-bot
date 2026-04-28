package dev.tylercash.event.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.tylercash.event.discord.GuildDto;
import dev.tylercash.event.discord.GuildSettingsDto;
import dev.tylercash.event.discord.model.DiscordUserCache;
import dev.tylercash.event.event.model.AttendanceRecord;
import dev.tylercash.event.event.model.AttendanceStatus;
import dev.tylercash.event.event.model.AttendanceSummary;
import dev.tylercash.event.event.model.Attendee;
import dev.tylercash.event.event.model.AttendeeDto;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventDetailDto;
import dev.tylercash.event.event.model.EventDto;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.gallery.GalleryAlbumDto;
import dev.tylercash.event.rewind.model.AttendeeStatDto;
import dev.tylercash.event.rewind.model.EventCategoryDto;
import dev.tylercash.event.rewind.model.EventSummaryDto;
import dev.tylercash.event.rewind.model.GraphEdgeDto;
import dev.tylercash.event.rewind.model.GraphNodeDto;
import dev.tylercash.event.rewind.model.RewindStatsDto;
import dev.tylercash.event.rewind.model.SocialGraphDto;
import jakarta.persistence.Entity;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * For every DTO returned from a controller, asserts the serialized JSON does
 * not leak sensitive field names. Also asserts no {@code @Entity} is returned
 * directly from a controller (those should always be wrapped in a DTO).
 *
 * <p>Sensitive-field detection is keyword-based on the JSON keys, so it catches
 * future regressions even if the offending field is added on an unrelated DTO.
 */
class ResponseSerializationTest {

    /** Substrings (case-insensitive) that must never appear as a JSON key in a controller response. */
    private static final List<String> FORBIDDEN_KEY_SUBSTRINGS = List.of(
            "accesstoken",
            "refreshtoken",
            "clientsecret",
            "password",
            "passwordhash",
            "secret",
            "apikey",
            "discordtoken",
            "oauth2token",
            "bottoken",
            "sessionid",
            "csrftoken",
            "sharekey", // GalleryAlbumDto must not leak the per-user Immich share key
            "avatarbytes" // DiscordUserCache.avatarBytes must never escape via a JSON DTO
            );

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * Population helpers — one per controller-returned DTO type. Anything new
     * that ships out of a controller should be added here.
     */
    static List<Object> populatedControllerDtos() {
        List<Object> out = new ArrayList<>();
        out.add(new UserInfoDto("alice", "Alice", "111", true, "/api/avatar/111"));
        out.add(new GuildDto("123", "Guild", "G", "icon", "#fff", "channel", 5, 1.0, 2.0));
        out.add(new GuildSettingsDto("place-id", "place-name", 1.0, 2.0));

        Event event =
                new Event(42L, 311L, 100L, "fuzz-event", "111", ZonedDateTime.parse("2099-01-01T00:00:00Z"), "desc");
        event.setId(UUID.randomUUID());
        event.setState(EventState.PLANNED);
        Attendee a = Attendee.createDiscordAttendee("111", "Alice");
        event.getAccepted().add(a);
        out.add(new EventDto(event));
        out.add(new EventDto(event, "Alice", "alice", "social"));

        AttendanceRecord rec = new AttendanceRecord();
        rec.setSnowflake("111");
        rec.setName("Alice");
        rec.setStatus(AttendanceStatus.ACCEPTED);
        rec.setRecordedAt(Instant.parse("2099-01-01T00:00:00Z"));
        AttendanceSummary summary = new AttendanceSummary(List.of(rec), List.of(), List.of());
        DiscordUserCache user = new DiscordUserCache(
                "111",
                "Alice",
                "alice",
                Instant.parse("2099-01-01T00:00:00Z"),
                new byte[] {1, 2, 3},
                "image/webp",
                new HashSet<>(Set.of(311L)));
        out.add(new EventDetailDto(event, true, summary, Map.of("111", user), "social"));

        out.add(new AttendeeDto(a));

        out.add(new GalleryAlbumDto(
                event.getId(),
                "fuzz-event",
                event.getDateTime(),
                "album-1",
                "/thumb",
                "/open",
                7,
                List.of(new AttendeeDto(a))));

        EventCategoryDto cat = new EventCategoryDto("social", 5, 25);
        AttendeeStatDto attStat = new AttendeeStatDto("Alice", 5, "/api/avatar/111");
        SocialGraphDto graph = new SocialGraphDto(
                List.of(new GraphNodeDto("111", "Alice", "/api/avatar/111", 1)),
                List.of(new GraphEdgeDto("111", "111", 1)));
        EventSummaryDto summ = new EventSummaryDto(event.getId(), "fuzz", event.getDateTime());
        RewindStatsDto stats = new RewindStatsDto(
                10,
                5,
                20,
                3.0,
                List.of(cat),
                List.of(attStat),
                List.of(attStat),
                graph,
                Map.of("Jan", 1),
                Map.of("Mon", 1),
                summ,
                summ,
                3,
                true,
                2099);
        out.add(stats);
        return out;
    }

    @ParameterizedTest
    @MethodSource("populatedControllerDtos")
    void serializedDto_doesNotLeakForbiddenKeys(Object dto) throws Exception {
        String json = MAPPER.writeValueAsString(dto);
        JsonNode root = MAPPER.readTree(json);
        Set<String> keys = collectAllKeys(root);
        for (String forbidden : FORBIDDEN_KEY_SUBSTRINGS) {
            assertThat(keys)
                    .as(
                            "DTO %s leaked a key containing '%s' in JSON: %s",
                            dto.getClass().getSimpleName(), forbidden, json)
                    .noneMatch(k -> k.toLowerCase(Locale.ROOT).contains(forbidden));
        }
    }

    /**
     * Specifically: the avatar bytes blob must not appear in any JSON-shaped
     * controller response. (The avatar endpoint returns raw bytes via
     * {@code ResponseEntity<byte[]>}, not a JSON DTO — that's exempt.)
     */
    @Test
    void discordUserCache_avatarBytes_doNotSerialize_via_anyDtoFixture() throws Exception {
        for (Object dto : populatedControllerDtos()) {
            String json = MAPPER.writeValueAsString(dto);
            assertThat(json)
                    .as(
                            "DTO %s serialized avatarBytes inline; that is a hard PII leak.",
                            dto.getClass().getSimpleName())
                    .doesNotContainIgnoringCase("avatarBytes");
        }
    }

    /**
     * Reflective tripwire: walk every {@code @RestController} on the classpath
     * and assert no controller method returns a {@code @Entity} type directly
     * (or wrapped in {@code Page}/{@code List}/{@code Optional}/{@code ResponseEntity}).
     * Returning entities is an antipattern that bypasses any DTO scrubbing.
     */
    @Test
    void noControllerReturnsAnEntityDirectly() {
        // The set of @RestController classes is small and known. We assert the
        // annotation below, so a renamed/moved controller will fail loudly.
        List<Class<?>> controllers = List.of(
                dev.tylercash.event.rewind.RewindController.class,
                dev.tylercash.event.discord.GuildSettingsController.class,
                dev.tylercash.event.global.SecurityController.class,
                dev.tylercash.event.event.EventController.class,
                dev.tylercash.event.gallery.GalleryController.class,
                dev.tylercash.event.security.CsrfController.class,
                dev.tylercash.event.discord.AvatarController.class,
                dev.tylercash.event.discord.GuildController.class);
        List<String> offenders = new ArrayList<>();
        for (Class<?> c : controllers) {
            assertThat(c.isAnnotationPresent(RestController.class))
                    .as("%s should be @RestController", c.getSimpleName())
                    .isTrue();
            for (Method m : c.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(RequestMapping.class) && m.getAnnotations().length == 0) {
                    continue;
                }
                if (!isMappedEndpoint(m)) continue;
                if (returnsEntity(m)) {
                    offenders.add(c.getSimpleName() + "#" + m.getName() + " returns "
                            + m.getGenericReturnType().getTypeName());
                }
            }
        }
        assertThat(offenders)
                .as("Controllers should not return JPA @Entity types directly — wrap in a DTO.")
                .isEmpty();
    }

    private static boolean isMappedEndpoint(Method m) {
        for (var a : m.getAnnotations()) {
            String name = a.annotationType().getSimpleName();
            if (name.endsWith("Mapping")) return true;
        }
        return false;
    }

    private static boolean returnsEntity(Method m) {
        Class<?> returnType = m.getReturnType();
        Deque<Class<?>> stack = new ArrayDeque<>();
        stack.push(returnType);
        // Also probe generic type arguments one level deep.
        var gen = m.getGenericReturnType();
        if (gen instanceof java.lang.reflect.ParameterizedType pt) {
            for (var arg : pt.getActualTypeArguments()) {
                if (arg instanceof Class<?> ac) stack.push(ac);
            }
        }
        Set<Class<?>> seen = new HashSet<>();
        while (!stack.isEmpty()) {
            Class<?> c = stack.pop();
            if (c == null || !seen.add(c)) continue;
            if (c.isAnnotationPresent(Entity.class)) return true;
        }
        return false;
    }

    private static Set<String> collectAllKeys(JsonNode root) {
        Set<String> keys = new HashSet<>();
        Deque<JsonNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            JsonNode n = stack.pop();
            if (n.isObject()) {
                Iterator<String> it = n.fieldNames();
                while (it.hasNext()) {
                    String k = it.next();
                    keys.add(k);
                    stack.push(n.get(k));
                }
            } else if (n.isArray()) {
                n.forEach(stack::push);
            }
        }
        return keys;
    }
}
