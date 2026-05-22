package dev.tylercash.event.discord;

import dev.tylercash.event.discord.listener.ButtonInteractionListener;
import dev.tylercash.event.discord.listener.GuildLifecycleListener;
import dev.tylercash.event.discord.listener.MessageReceivedListener;
import dev.tylercash.event.discord.listener.ModalInteractionListener;
import dev.tylercash.event.discord.listener.SlashCommandListener;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextScheduledExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.EnumSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.AllArgsConstructor;
import lombok.Data;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@AllArgsConstructor
public class ClientConfiguration {
    private final ButtonInteractionListener buttonInteractionListener;
    private final GuildLifecycleListener guildLifecycleListener;
    private final ModalInteractionListener modalInteractionListener;
    private final SlashCommandListener slashCommandListener;
    private final MessageReceivedListener messageReceivedListener;
    private final DiscordConfiguration discordConfiguration;
    private final DiscordOkHttpObservationInterceptor httpObservationInterceptor;

    @Bean
    public JDA jda() throws InterruptedException {
        // GUILD_MEMBERS: GuildLifecycleListener#onGuildMemberRemove. GUILD_MESSAGES + MESSAGE_CONTENT:
        // MessageReceivedListener reads Message#getAttachments() for the Immich auto-upload, gated behind
        // MESSAGE_CONTENT (privileged — must also be enabled in the Discord developer portal). Interaction
        // listeners (Button/Modal/Slash) require no intents.
        //
        // Wrap JDA's REST submission executors and OkHttp's dispatcher with Micrometer's
        // ContextExecutorService so the calling thread's Observation/trace context propagates to
        // the OkHttp interceptor — without it, `discord.http` spans appear as detached roots.
        ContextSnapshotFactory snapshotFactory =
                ContextSnapshotFactory.builder().build();

        ExecutorService rateLimitElastic =
                ContextExecutorService.wrap(Executors.newWorkStealingPool(), snapshotFactory);
        ScheduledExecutorService rateLimitScheduler =
                ContextScheduledExecutorService.wrap(daemonScheduledPool(2, "JDA-RateLimit"), snapshotFactory);
        ExecutorService okhttpDispatcherExecutor =
                ContextExecutorService.wrap(okhttpDefaultDispatcherExecutor(), snapshotFactory);

        OkHttpClient httpClient = new OkHttpClient.Builder()
                .dispatcher(new Dispatcher(okhttpDispatcherExecutor))
                .addInterceptor(httpObservationInterceptor)
                .build();

        return JDABuilder.createDefault(discordConfiguration.getToken())
                .setHttpClient(httpClient)
                .setRateLimitElastic(rateLimitElastic, true)
                .setRateLimitScheduler(rateLimitScheduler, true)
                .addEventListeners(buttonInteractionListener)
                .addEventListeners(guildLifecycleListener)
                .addEventListeners(modalInteractionListener)
                .addEventListeners(slashCommandListener)
                .addEventListeners(messageReceivedListener)
                .enableIntents(EnumSet.of(
                        GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
                .build()
                .awaitReady();
    }

    private static ScheduledExecutorService daemonScheduledPool(int size, String namePrefix) {
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(size, daemonThreadFactory(namePrefix));
        pool.setRemoveOnCancelPolicy(true);
        return pool;
    }

    /** Mirrors OkHttp's default Dispatcher executor: cached pool, daemon threads, SynchronousQueue. */
    private static ExecutorService okhttpDefaultDispatcherExecutor() {
        return new ThreadPoolExecutor(
                0,
                Integer.MAX_VALUE,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                daemonThreadFactory("OkHttp-Dispatcher"));
    }

    private static ThreadFactory daemonThreadFactory(String namePrefix) {
        AtomicLong counter = new AtomicLong();
        return runnable -> {
            Thread t = new Thread(runnable, namePrefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
