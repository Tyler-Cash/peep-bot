package dev.tylercash.event.global;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.db.repository.GuildMemberRepository;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.InstallUrlController;
import dev.tylercash.event.test.SharedPostgres;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Guards that the stereotype auto-instrumentation aspects actually weave: a pointcut that matches
 * nothing is a silent no-op (the app still works, just with empty traces — exactly the symptom this
 * instrumentation exists to fix), so we assert real spans are produced for a controller call and a
 * repository call by exercising the live, AOP-proxied beans.
 *
 * <p>{@link GuildMemberRepository#count()} is inherited from {@code CrudRepository}, so it also
 * pins {@link RepositoryMethodObservationAspect}'s interface-name resolution: the span must be named
 * for the concrete user repository ({@code GuildMemberRepository}), not the Spring Data base type.
 */
@SpringBootTest(
        classes = PeepBotApplication.class,
        properties = {
            "spring.security.oauth2.client.registration.discord.client-id=test",
            "spring.security.oauth2.client.registration.discord.client-secret=test",
            "dev.tylercash.discord.token=dummy",
            "dev.tylercash.discord.guild-id=0",
            "dev.tylercash.frontend.hostname=test.local",
            "dev.tylercash.rate-limit.read-capacity=10000",
            "dev.tylercash.rate-limit.write-capacity=10000"
        })
@ActiveProfiles("local")
class StereotypeMethodObservationAspectTest {

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private InstallUrlController installUrlController;

    @Autowired
    private GuildMemberRepository guildMemberRepository;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.registerProperties(registry);
    }

    @Test
    void controllerCall_producesAControllerMethodSpan() {
        List<String> contextualNames = recordContextualNames(ControllerMethodObservationAspect.OBSERVATION_NAME);

        installUrlController.get();

        assertThat(contextualNames)
                .as("a @RestController call must open a controller.method observation")
                .contains("InstallUrlController#get");
    }

    @Test
    void repositoryCall_producesARepositorySpanNamedForTheUserInterface() {
        List<String> contextualNames = recordContextualNames(RepositoryMethodObservationAspect.OBSERVATION_NAME);

        // count() is declared on CrudRepository; the span must still be named for the concrete
        // repository interface, not the Spring Data base type.
        guildMemberRepository.count();

        assertThat(contextualNames)
                .as("a Spring Data repository call must open a repository.method observation named "
                        + "for the user interface, not the Spring Data base interface")
                .contains("GuildMemberRepository#count")
                .doesNotContain("CrudRepository#count");
    }

    private List<String> recordContextualNames(String observationName) {
        List<String> contextualNames = new CopyOnWriteArrayList<>();
        observationRegistry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return observationName.equals(context.getName());
            }

            @Override
            public void onStart(Observation.Context context) {
                contextualNames.add(context.getContextualName());
            }
        });
        return contextualNames;
    }
}
