package dev.tylercash.event.global;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * Guard: blocking {@link org.springframework.web.client.RestClient}s (Immich, Places, TfNSW) must
 * use the JDK HttpClient transport, not reactor-netty.
 *
 * <p>spring-webflux is on the classpath for WebClient, which makes reactor-netty the default
 * imperative transport too. reactor-netty allocates socket I/O + TLS buffers in direct (off-heap)
 * memory, which the Paketo memory calculator caps at {@code -XX:MaxDirectMemorySize=10M}. Concurrent
 * gallery thumbnail streaming exhausted that pool ("OutOfMemoryError: Cannot reserve ... direct
 * buffer memory"). Pinning the imperative factory to JDK moves those blocking calls onto heap
 * buffers (counted against the 4G -Xmx). This test loads the real application.yaml and fails if that
 * pin is ever lost.
 */
class HttpClientTransportTest {

    @Test
    void blockingRestClientsUseJdkTransportNotReactorNetty() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(
                        HttpClientAutoConfiguration.class, ImperativeHttpClientAutoConfiguration.class))
                .run(context -> assertThat(context)
                        .getBean(ClientHttpRequestFactory.class)
                        .isInstanceOf(JdkClientHttpRequestFactory.class));
    }
}
