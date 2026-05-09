package dev.tylercash.event.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * F-003: when {@code dev.tylercash.openapi.public} is false (the prod default),
 * Swagger UI and the OpenAPI spec must require authentication. The test profile
 * inherits the default ({@code false}) — only {@code application-nonprod.yaml}
 * flips it to true.
 */
@TestPropertySource(properties = "dev.tylercash.openapi.public=false")
class SwaggerGatingIntegrationTest extends AbstractHttpIntegrationTest {

    @Test
    void apiDocs_isUnauthenticated_returns401WhenOpenapiNotPublic() throws Exception {
        MvcResult result =
                mockMvc.perform(MockMvcRequestBuilders.get("/v3/api-docs")).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("OpenAPI spec must require auth when dev.tylercash.openapi.public=false")
                .isEqualTo(401);
    }

    @Test
    void swaggerUi_isUnauthenticated_returns401WhenOpenapiNotPublic() throws Exception {
        MvcResult result =
                mockMvc.perform(MockMvcRequestBuilders.get("/swagger-ui.html")).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("Swagger UI must require auth when dev.tylercash.openapi.public=false")
                .isEqualTo(401);
    }
}
