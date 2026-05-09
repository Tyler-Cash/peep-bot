package dev.tylercash.event.security.oauth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Pentest finding F-001 regression test.
 *
 * <p>Tomcat's {@code RemoteIpValve} defaults to trusting {@code X-Forwarded-*} from any
 * RFC1918 source. Inside the VPN that hosts the bot, every legitimate caller is in those
 * ranges, so a malicious RFC1918 client could spoof {@code X-Forwarded-Host} and have
 * Spring stamp an attacker-controlled host into the OAuth2 {@code redirect_uri} parameter.
 *
 * <p>The fix in {@code application.yaml} pins {@code server.tomcat.remoteip.internal-proxies}
 * to loopback only (`127\.0\.0\.1|::1`). This test simulates a non-loopback caller (an
 * RFC1918 IP, the same kind of caller the homelab sees in prod) sending a forged
 * {@code X-Forwarded-Host} header and asserts the OAuth2 init {@code Location} header
 * still embeds the actual request host — not the spoofed one.
 */
class OAuth2ForwardedHostHardeningTest extends AbstractHttpIntegrationTest {

    @Test
    void oauth2Init_ignoresForgedXForwardedHost_fromNonLoopbackCaller() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/discord")
                        .header("X-Forwarded-Host", "attacker.controlled")
                        .header("X-Forwarded-Proto", "http")
                        .with(req -> {
                            // Simulate a caller from inside the homelab VPN (RFC1918) — i.e. not
                            // the loopback proxy. With the fix, RemoteIpValve must NOT trust the
                            // X-Forwarded-* headers from this caller.
                            req.setRemoteAddr("10.0.90.5");
                            return req;
                        }))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);

        String location = result.getResponse().getHeader("Location");
        assertThat(location)
                .as("OAuth2 init redirect must not embed a spoofed X-Forwarded-Host into redirect_uri")
                .doesNotContain("attacker.controlled");
        assertThat(location)
                .as("redirect_uri parameter must be present in the OAuth2 authorize URL")
                .contains("redirect_uri=");
    }
}
