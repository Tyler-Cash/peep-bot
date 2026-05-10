package dev.tylercash.event.openapi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.discord.AvatarDownloadService;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.immich.ImmichService;
import dev.tylercash.event.test.SharedPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
        classes = PeepBotApplication.class,
        properties = {
            "spring.security.oauth2.client.registration.discord.client-id=test",
            "spring.security.oauth2.client.registration.discord.client-secret=test",
            "dev.tylercash.discord.token=dummy",
            "dev.tylercash.discord.guild-id=0",
            "dev.tylercash.frontend.hostname=test.local",
            "dev.tylercash.openapi.public=true",
        })
@AutoConfigureMockMvc
@ActiveProfiles("local")
class OpenApiSpecGenerationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        SharedPostgres.registerProperties(r);
    }

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    @MockitoBean
    ImmichService immichService;

    @MockitoBean
    AvatarDownloadService avatarDownloadService;

    @MockitoBean
    EmbeddingModel embeddingModel;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    /**
     * Writes the OpenAPI spec to backend/openapi.json, sorted and formatted
     * deterministically so the file is committable and CI can fail on drift.
     *
     * <p>This test runs as part of {@code ./gradlew test}, so the file is
     * always regenerated on every full test run. The frontend codegen step
     * consumes it via a relative path.
     */
    @Test
    void writesOpenApiSpecToBackendRoot() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();

        String raw = result.getResponse().getContentAsString();
        JsonNode tree = objectMapper.readTree(raw);

        // Pretty-print with sorted keys at every level so the output is
        // byte-stable across runs (and future Spring/Springdoc bumps).
        ObjectMapper pretty = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .enable(SerializationFeature.INDENT_OUTPUT);
        Object sorted = pretty.treeToValue(tree, Object.class);
        String formatted = pretty.writeValueAsString(sorted);

        Path target = Paths.get("openapi.json"); // CWD is backend/ when run via ./gradlew test
        Files.writeString(target, formatted + "\n");
    }
}
