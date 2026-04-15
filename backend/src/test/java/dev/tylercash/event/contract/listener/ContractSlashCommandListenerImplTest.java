package dev.tylercash.event.contract.listener;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

import dev.tylercash.event.contract.ContractConfiguration;
import dev.tylercash.event.contract.ContractService;
import dev.tylercash.event.contract.UserBalanceService;
import dev.tylercash.event.discord.DiscordAuthService;
import dev.tylercash.event.discord.DiscordConfiguration;
import java.util.List;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContractSlashCommandListenerImplTest {

    ContractService contractService;
    UserBalanceService balanceService;
    DiscordAuthService authService;
    ContractConfiguration contractConfig;
    DiscordConfiguration discordConfig;
    ContractSlashCommandListenerImpl listener;

    SlashCommandInteractionEvent event;
    ReplyCallbackAction deferAction;
    InteractionHook hook;

    @SuppressWarnings("rawtypes")
    WebhookMessageCreateAction hookMessageAction;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        contractService = mock(ContractService.class);
        balanceService = mock(UserBalanceService.class);
        authService = mock(DiscordAuthService.class);
        contractConfig = new ContractConfiguration();
        discordConfig = mock(DiscordConfiguration.class);
        listener = new ContractSlashCommandListenerImpl(
                contractService, balanceService, authService, contractConfig, discordConfig);

        event = mock(SlashCommandInteractionEvent.class);
        deferAction = mock(ReplyCallbackAction.class);
        hook = mock(InteractionHook.class);
        hookMessageAction = mock(WebhookMessageCreateAction.class);

        when(event.getName()).thenReturn("contract");
        when(event.getSubcommandName()).thenReturn("create");
        when(event.deferReply(true)).thenReturn(deferAction);
        when(event.getHook()).thenReturn(hook);
        when(hook.sendMessage(anyString())).thenReturn(hookMessageAction);

        User user = mock(User.class);
        when(user.getId()).thenReturn("user-123");
        when(event.getUser()).thenReturn(user);
    }

    private OptionMapping stringOption(String value) {
        OptionMapping m = mock(OptionMapping.class);
        lenient().when(m.getAsString()).thenReturn(value);
        return m;
    }

    private OptionMapping longOption(long value) {
        OptionMapping m = mock(OptionMapping.class);
        lenient().when(m.getAsLong()).thenReturn(value);
        return m;
    }

    @Test
    void createWithExplicitOutcomes() {
        OptionMapping title = stringOption("Will we hit 100 members?");
        OptionMapping seed = longOption(500L);
        OptionMapping o1 = stringOption("YES");
        OptionMapping o2 = stringOption("NO");
        OptionMapping o3 = stringOption("MAYBE");
        when(event.getOption("title")).thenReturn(title);
        when(event.getOption("seed")).thenReturn(seed);
        when(event.getOption("outcome_1")).thenReturn(o1);
        when(event.getOption("outcome_2")).thenReturn(o2);
        when(event.getOption("outcome_3")).thenReturn(o3);
        when(event.getOption("outcome_4")).thenReturn(null);
        when(event.getOption("outcome_5")).thenReturn(null);

        listener.handleSlashCommand(event);

        verify(contractService).createContract(
                "user-123",
                "Will we hit 100 members?",
                null,
                List.of("YES", "NO", "MAYBE"),
                500L);
        verify(hook).sendMessage(contains("created"));
    }

    @Test
    void createDefaultsToYesNoWhenNoOutcomesProvided() {
        OptionMapping title = stringOption("Will we hit 100 members?");
        OptionMapping seed = longOption(500L);
        when(event.getOption("title")).thenReturn(title);
        when(event.getOption("seed")).thenReturn(seed);
        when(event.getOption("outcome_1")).thenReturn(null);
        when(event.getOption("outcome_2")).thenReturn(null);
        when(event.getOption("outcome_3")).thenReturn(null);
        when(event.getOption("outcome_4")).thenReturn(null);
        when(event.getOption("outcome_5")).thenReturn(null);

        listener.handleSlashCommand(event);

        verify(contractService).createContract(
                "user-123",
                "Will we hit 100 members?",
                null,
                List.of("YES", "NO"),
                500L);
    }

    @Test
    void createDefaultsSecondOutcomeToNoWhenOnlyFirstProvided() {
        OptionMapping title = stringOption("Will we win?");
        OptionMapping seed = longOption(200L);
        OptionMapping o1 = stringOption("WIN");
        when(event.getOption("title")).thenReturn(title);
        when(event.getOption("seed")).thenReturn(seed);
        when(event.getOption("outcome_1")).thenReturn(o1);
        when(event.getOption("outcome_2")).thenReturn(null);
        when(event.getOption("outcome_3")).thenReturn(null);
        when(event.getOption("outcome_4")).thenReturn(null);
        when(event.getOption("outcome_5")).thenReturn(null);

        listener.handleSlashCommand(event);

        verify(contractService).createContract(
                "user-123",
                "Will we win?",
                null,
                List.of("WIN", "NO"),
                200L);
    }
}
