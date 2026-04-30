package dev.tylercash.event.contract.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.contract.ContractGraphService;
import dev.tylercash.event.contract.ContractPinnedMessageService;
import dev.tylercash.event.contract.UserBalanceService;
import dev.tylercash.event.contract.model.Contract;
import dev.tylercash.event.contract.model.ContractOutcome;
import dev.tylercash.event.contract.model.ContractState;
import dev.tylercash.event.contract.repository.ContractRepository;
import dev.tylercash.event.discord.DiscordAuthService;
import dev.tylercash.event.discord.DiscordChannelService;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordMessageService;
import dev.tylercash.event.discord.DiscordService;
import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link ContractSlashCommandListenerImpl}.
 *
 * <p>The Spring context is fully wired (real ContractService, UserBalanceService, repositories).
 * Discord-side infrastructure (JDA, channel/message services, pinned-message refresh) is mocked
 * so that the tests don't require a live Discord connection.
 */
@SpringBootTest(
        classes = PeepBotApplication.class,
        properties = {
            "spring.main.allow-bean-definition-overriding=true",
            "spring.security.oauth2.client.registration.discord.client-id=test",
            "spring.security.oauth2.client.registration.discord.client-secret=test",
            "dev.tylercash.discord.token=dummy",
            "dev.tylercash.discord.guild-id=0",
            "dev.tylercash.rate-limit.read-capacity=10000",
            "dev.tylercash.rate-limit.write-capacity=10000"
        })
@Testcontainers
@ActiveProfiles("local")
class ContractSlashCommandListenerImplIntegrationTest {

    private static final String CREATOR_SNOWFLAKE = "777888999";
    private static final String RESOLVER_SNOWFLAKE = "111000111";

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    // Mock Discord infrastructure so ContractService.initChannel() doesn't need a live JDA.
    @MockitoBean
    DiscordChannelService discordChannelService;

    @MockitoBean
    DiscordMessageService discordMessageService;

    @MockitoBean
    ContractPinnedMessageService contractPinnedMessageService;

    @MockitoBean
    ContractGraphService contractGraphService;

    // Mock auth so resolver-role checks are controllable per test.
    @MockitoBean
    DiscordAuthService discordAuthService;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:0.8.0-pg17");

    @Autowired
    ContractSlashCommandListenerImpl listener;

    @Autowired
    ContractRepository contractRepository;

    @Autowired
    UserBalanceService userBalanceService;

    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // Shared Discord stub objects — created once, reused across @BeforeEach setups
    // to avoid re-creating final-ish JDA types.
    private TextChannel fakeChannel;
    private Message fakeMessage;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM contract_trade");
        jdbc.execute("DELETE FROM contract_outcome");
        jdbc.execute("DELETE FROM contract");
        jdbc.execute("DELETE FROM user_balance");

        // Stub initChannel path so ContractService.createContract() completes without NPE.
        Category category = mock(Category.class);
        fakeChannel = mock(TextChannel.class);
        fakeMessage = mock(Message.class);

        lenient()
                .when(discordChannelService.getOrCreateCategory(any(), anyString()))
                .thenReturn(category);
        lenient()
                .when(discordChannelService.createTextChannel(any(), anyString()))
                .thenReturn(fakeChannel);
        lenient().when(fakeChannel.getIdLong()).thenReturn(12345L);
        lenient()
                .when(discordMessageService.sendEmbedWithAttachment(any(), anyList(), any(byte[].class), anyString()))
                .thenReturn(fakeMessage);
        lenient().when(fakeMessage.getIdLong()).thenReturn(67890L);

        // buildEmbed and renderChart must return non-null so List.of(embed) doesn't NPE.
        lenient()
                .when(contractPinnedMessageService.buildEmbed(any(), anyList()))
                .thenReturn(mock(MessageEmbed.class));
        lenient()
                .when(contractGraphService.renderChart(anyList(), anyList(), any(), any(double.class)))
                .thenReturn(new byte[0]);

        // sendMessage is a no-op for resolve/cancel notification banners.
        lenient().when(discordMessageService.sendMessage(any(), anyString())).thenReturn(null);

        // getTextChannel used during resolve/cancel result announcements.
        lenient().when(discordChannelService.getTextChannel(anyLong())).thenReturn(fakeChannel);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Build a pre-stubbed OptionMapping mock.
     * <p>
     * IMPORTANT: always build this mock BEFORE composing it into a
     * {@code when(event.getOption(...))} call. Constructing a mock with internal
     * {@code when()} calls inside an outer {@code thenReturn()} triggers Mockito's
     * "unfinished stubbing" detection.
     */
    private OptionMapping opt(String value) {
        OptionMapping m = mock(OptionMapping.class);
        lenient().when(m.getAsString()).thenReturn(value);
        return m;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private SlashCommandInteractionEvent contractEvent(String subcommand, String userId) {
        SlashCommandInteractionEvent evt = mock(SlashCommandInteractionEvent.class);
        lenient().when(evt.getName()).thenReturn("contract");
        lenient().when(evt.getSubcommandName()).thenReturn(subcommand);

        User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(userId);
        lenient().when(user.getIdLong()).thenReturn(Long.parseLong(userId));
        lenient().when(evt.getUser()).thenReturn(user);

        ReplyCallbackAction deferAction = mock(ReplyCallbackAction.class);
        lenient().when(evt.deferReply(true)).thenReturn(deferAction);

        InteractionHook hook = mock(InteractionHook.class);
        lenient().when(evt.getHook()).thenReturn(hook);
        WebhookMessageCreateAction hookAction = mock(WebhookMessageCreateAction.class);
        lenient().when(hook.sendMessage(anyString())).thenReturn(hookAction);

        ReplyCallbackAction replyAction = mock(ReplyCallbackAction.class);
        lenient().when(evt.reply(anyString())).thenReturn(replyAction);
        lenient().when(replyAction.setEphemeral(true)).thenReturn(replyAction);

        return evt;
    }

    /** Convenience: create a contract and return a ref to it from the DB. */
    private Contract createContract(String title, String outcome1, String outcome2) {
        // Pre-build option mocks before composing them into when() calls.
        OptionMapping titleOpt = opt(title);
        OptionMapping o1Opt = outcome1 != null ? opt(outcome1) : null;
        OptionMapping o2Opt = outcome2 != null ? opt(outcome2) : null;

        SlashCommandInteractionEvent evt = contractEvent("create", CREATOR_SNOWFLAKE);
        when(evt.getOption("title")).thenReturn(titleOpt);
        when(evt.getOption("outcome_1")).thenReturn(o1Opt);
        when(evt.getOption("outcome_2")).thenReturn(o2Opt);
        when(evt.getOption("outcome_3")).thenReturn(null);
        when(evt.getOption("outcome_4")).thenReturn(null);
        when(evt.getOption("outcome_5")).thenReturn(null);
        listener.handleSlashCommand(evt);

        return contractRepository
                .findFirstByStateInAndTitleIgnoreCase(List.of(ContractState.OPEN), title)
                .orElseThrow(() -> new AssertionError("Contract not created: " + title));
    }

    // -------------------------------------------------------------------------
    // create
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("create → contract row persisted in DB with OPEN state and correct outcomes")
    void createContract_persistsContractAndOutcomes() {
        Contract saved = createContract("Will it rain tomorrow?", "YES", "NO");

        assertThat(saved.getTitle()).isEqualTo("Will it rain tomorrow?");
        assertThat(saved.getCreatorSnowflake()).isEqualTo(CREATOR_SNOWFLAKE);
        assertThat(saved.getOutcomes()).extracting(ContractOutcome::getLabel).containsExactlyInAnyOrder("YES", "NO");
    }

    @Test
    @DisplayName("create with no explicit outcomes → defaults YES/NO")
    void createContract_defaultsToYesNo() {
        Contract saved = createContract("Will we win?", null, null);

        assertThat(saved.getOutcomes()).extracting(ContractOutcome::getLabel).containsExactlyInAnyOrder("YES", "NO");
    }

    // -------------------------------------------------------------------------
    // trade
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("trade → user balance debited, shares incremented on the outcome")
    void trade_debitsBalanceAndIncrementsShares() {
        Contract contract = createContract("Trade Test", "WIN", "LOSE");
        double sharesBefore = contract.getOutcomes().stream()
                .filter(o -> o.getLabel().equals("WIN"))
                .findFirst()
                .orElseThrow()
                .getSharesOutstanding();

        String traderSnowflake = "222333444";
        long balanceBefore = userBalanceService.getBalance(traderSnowflake);

        // Pre-build option mocks before composing them into when() calls.
        OptionMapping contractOpt = opt("Trade Test");
        OptionMapping outcomeOpt = opt("WIN");
        OptionMapping amountOpt = opt("50");

        SlashCommandInteractionEvent tradeEvt = contractEvent("trade", traderSnowflake);
        when(tradeEvt.getOption("contract")).thenReturn(contractOpt);
        when(tradeEvt.getOption("outcome")).thenReturn(outcomeOpt);
        when(tradeEvt.getOption("amount")).thenReturn(amountOpt);
        listener.handleSlashCommand(tradeEvt);

        long balanceAfter = userBalanceService.getBalance(traderSnowflake);
        assertThat(balanceAfter).isLessThan(balanceBefore);

        Contract updated = contractRepository.findById(contract.getId()).orElseThrow();
        double sharesAfter = updated.getOutcomes().stream()
                .filter(o -> o.getLabel().equals("WIN"))
                .findFirst()
                .orElseThrow()
                .getSharesOutstanding();
        assertThat(sharesAfter).isGreaterThan(sharesBefore);
    }

    // -------------------------------------------------------------------------
    // resolve
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("resolve → contract state RESOLVED, winning outcome ID set, winner credited")
    void resolve_transitionsToResolvedAndCreditsWinners() {
        Contract contract = createContract("Resolve Test", "A", "B");

        // Place a trade on outcome A so there's a winner to credit.
        String traderSnowflake = "555666777";
        OptionMapping contractOpt1 = opt("Resolve Test");
        OptionMapping outcomeOpt1 = opt("A");
        OptionMapping amountOpt1 = opt("100");
        SlashCommandInteractionEvent tradeEvt = contractEvent("trade", traderSnowflake);
        when(tradeEvt.getOption("contract")).thenReturn(contractOpt1);
        when(tradeEvt.getOption("outcome")).thenReturn(outcomeOpt1);
        when(tradeEvt.getOption("amount")).thenReturn(amountOpt1);
        listener.handleSlashCommand(tradeEvt);
        long balanceAfterTrade = userBalanceService.getBalance(traderSnowflake);

        // Resolver has the required role.
        when(discordAuthService.hasRole(anyLong(), anyLong(), anyString())).thenReturn(true);

        OptionMapping contractOpt2 = opt("Resolve Test");
        OptionMapping outcomeOpt2 = opt("A");
        SlashCommandInteractionEvent resolveEvt = contractEvent("resolve", RESOLVER_SNOWFLAKE);
        when(resolveEvt.getOption("contract")).thenReturn(contractOpt2);
        when(resolveEvt.getOption("outcome")).thenReturn(outcomeOpt2);
        listener.handleSlashCommand(resolveEvt);

        Contract resolved = contractRepository.findById(contract.getId()).orElseThrow();
        assertThat(resolved.getState()).isEqualTo(ContractState.RESOLVED);
        assertThat(resolved.getWinningOutcomeId()).isNotNull();

        long balanceAfterResolve = userBalanceService.getBalance(traderSnowflake);
        assertThat(balanceAfterResolve).isGreaterThan(balanceAfterTrade);
    }

    // -------------------------------------------------------------------------
    // cancel
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("cancel → contract state CANCELLED, trades refunded to buyers")
    void cancel_transitionsToCancelledAndRefundsTrades() {
        Contract contract = createContract("Cancel Test", "X", "Y");

        String traderSnowflake = "888777666";
        long balanceBefore = userBalanceService.getBalance(traderSnowflake);

        OptionMapping contractOpt1 = opt("Cancel Test");
        OptionMapping outcomeOpt1 = opt("X");
        OptionMapping amountOpt1 = opt("100");
        SlashCommandInteractionEvent tradeEvt = contractEvent("trade", traderSnowflake);
        when(tradeEvt.getOption("contract")).thenReturn(contractOpt1);
        when(tradeEvt.getOption("outcome")).thenReturn(outcomeOpt1);
        when(tradeEvt.getOption("amount")).thenReturn(amountOpt1);
        listener.handleSlashCommand(tradeEvt);

        long balanceAfterTrade = userBalanceService.getBalance(traderSnowflake);
        assertThat(balanceAfterTrade).isLessThan(balanceBefore);

        // Cancel — resolver role granted
        when(discordAuthService.hasRole(anyLong(), anyLong(), anyString())).thenReturn(true);

        OptionMapping cancelContractOpt = opt("Cancel Test");
        SlashCommandInteractionEvent cancelEvt = contractEvent("cancel", RESOLVER_SNOWFLAKE);
        when(cancelEvt.getOption("contract")).thenReturn(cancelContractOpt);
        listener.handleSlashCommand(cancelEvt);

        Contract cancelled = contractRepository.findById(contract.getId()).orElseThrow();
        assertThat(cancelled.getState()).isEqualTo(ContractState.CANCELLED);

        long balanceAfterCancel = userBalanceService.getBalance(traderSnowflake);
        assertThat(balanceAfterCancel).isGreaterThanOrEqualTo(balanceBefore);
    }

    // -------------------------------------------------------------------------
    // list
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("list → reply contains open contract titles")
    void list_repliesWithOpenContracts() {
        createContract("Alpha Contract", null, null);
        createContract("Beta Contract", null, null);

        SlashCommandInteractionEvent listEvt = contractEvent("list", CREATOR_SNOWFLAKE);
        listener.handleSlashCommand(listEvt);

        // The list handler calls event.reply(...).setEphemeral(true).queue()
        verify(listEvt).reply(org.mockito.ArgumentMatchers.contains("Alpha Contract"));
    }
}
