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
import dev.tylercash.event.test.SharedPostgres;
import dev.tylercash.event.test.TestIds;
import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.AutoCompleteQuery;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.interactions.AutoCompleteCallbackAction;
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

/**
 * Integration tests for {@link ContractSlashCommandListenerImpl}.
 *
 * <p>The Spring context is fully wired (real ContractService, UserBalanceService, repositories).
 * Discord-side infrastructure (JDA, channel/message services, pinned-message refresh) is mocked
 * so the tests don't require a live Discord connection.
 *
 * <p><b>Parallel-safety:</b> each test method allocates its own guild id, creator/resolver
 * snowflakes, and unique contract titles via {@link TestIds}. All repository lookups are scoped
 * to the per-test guild so sibling tests sharing the JVM-wide Postgres container cannot collide.
 * No global truncates.
 */
@SpringBootTest(
        classes = PeepBotApplication.class,
        properties = {
            "spring.main.allow-bean-definition-overriding=true",
            "spring.security.oauth2.client.registration.discord.client-id=test",
            "spring.security.oauth2.client.registration.discord.client-secret=test",
            "dev.tylercash.discord.token=dummy",
            "dev.tylercash.discord.guild-id=0",
            "dev.tylercash.frontend.hostname=test.local",
            "dev.tylercash.rate-limit.read-capacity=10000",
            "dev.tylercash.rate-limit.write-capacity=10000"
        })
@ActiveProfiles("local")
class ContractSlashCommandListenerImplIntegrationTest {

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
        SharedPostgres.registerProperties(registry);
    }

    // Per-test isolation: every test method gets its own guild id, snowflakes, and title suffix.
    private long guildId;
    private String creatorSnowflake;
    private String resolverSnowflake;
    private String traderSnowflake;
    private String titleSuffix;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @BeforeEach
    void setUp() {
        guildId = TestIds.nextLong();
        creatorSnowflake = TestIds.nextSnowflake();
        resolverSnowflake = TestIds.nextSnowflake();
        traderSnowflake = TestIds.nextSnowflake();
        titleSuffix = " #" + TestIds.nextLong();

        // Seed a guild row with contracts_enabled = true so FeatureFlagService allows the handler
        // to proceed. Without this row the listener short-circuits with "not enabled" and no
        // contract is persisted.
        jdbc.update(
                "INSERT INTO guild (guild_id, events_role, organiser_role, emoji_accepted, emoji_declined,"
                        + " emoji_maybe, joined_at, active, immich_enabled, google_autocomplete_enabled,"
                        + " rewind_enabled, contracts_enabled)"
                        + " VALUES (?, 'events', 'event-organiser', '✅', '❌', '❓', NOW(),"
                        + " true, false, false, false, true)"
                        + " ON CONFLICT (guild_id) DO UPDATE SET contracts_enabled = true",
                guildId);

        // Stub initChannel path so ContractService.createContract() completes without NPE.
        Category category = mock(Category.class);
        TextChannel fakeChannel = mock(TextChannel.class);
        Message fakeMessage = mock(Message.class);

        lenient()
                .when(discordChannelService.getOrCreateCategory(any(), anyString()))
                .thenReturn(category);
        lenient()
                .when(discordChannelService.createTextChannel(any(), anyString()))
                .thenReturn(fakeChannel);
        lenient().when(fakeChannel.getIdLong()).thenReturn(TestIds.nextLong());
        lenient()
                .when(discordMessageService.sendEmbedWithAttachment(any(), anyList(), any(byte[].class), anyString()))
                .thenReturn(fakeMessage);
        lenient().when(fakeMessage.getIdLong()).thenReturn(TestIds.nextLong());

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
     * Per-test unique title. Sibling tests on the shared DB own their own rows; never assert via
     * "find all" — always look up by this title + guildId.
     */
    private String t(String label) {
        return label + titleSuffix;
    }

    /**
     * Build a pre-stubbed OptionMapping mock.
     *
     * <p>IMPORTANT: always build this mock BEFORE composing it into a {@code
     * when(event.getOption(...))} call. Constructing a mock with internal {@code when()} calls
     * inside an outer {@code thenReturn()} triggers Mockito's "unfinished stubbing" detection.
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

        Guild guild = mock(Guild.class);
        lenient().when(guild.getIdLong()).thenReturn(guildId);
        lenient().when(evt.getGuild()).thenReturn(guild);

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

    /**
     * Build a {@link CommandAutoCompleteInteractionEvent} mock whose focused option has the given
     * name and typed value, plus a stubbed {@link AutoCompleteCallbackAction} return for {@code
     * replyChoices(...)} so the handler's {@code .queue()} call doesn't NPE.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private CommandAutoCompleteInteractionEvent autoCompleteEvent(String focusedFieldName, String typed) {
        CommandAutoCompleteInteractionEvent evt = mock(CommandAutoCompleteInteractionEvent.class);

        Guild guild = mock(Guild.class);
        lenient().when(guild.getIdLong()).thenReturn(guildId);
        lenient().when(evt.getGuild()).thenReturn(guild);

        AutoCompleteQuery focused = mock(AutoCompleteQuery.class);
        lenient().when(focused.getName()).thenReturn(focusedFieldName);
        lenient().when(focused.getValue()).thenReturn(typed);
        lenient().when(evt.getFocusedOption()).thenReturn(focused);

        AutoCompleteCallbackAction action = mock(AutoCompleteCallbackAction.class);
        lenient().when(evt.replyChoices(anyList())).thenReturn(action);

        return evt;
    }

    /**
     * Like {@link #autoCompleteEvent} but captures the {@link Command.Choice} list passed to
     * {@code replyChoices(...)} into the supplied holder, so tests can assert on the choices
     * instead of just verifying the call happened.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private CommandAutoCompleteInteractionEvent autoCompleteEventCapturing(
            String focusedFieldName,
            String typed,
            java.util.concurrent.atomic.AtomicReference<List<Command.Choice>> captured) {
        CommandAutoCompleteInteractionEvent evt = autoCompleteEvent(focusedFieldName, typed);
        AutoCompleteCallbackAction action = mock(AutoCompleteCallbackAction.class);
        lenient().when(evt.replyChoices(anyList())).thenAnswer(inv -> {
            captured.set((List<Command.Choice>) inv.getArgument(0));
            return action;
        });
        return evt;
    }

    /** Convenience: create a contract and return a guild-scoped reference to it from the DB. */
    private Contract createContract(String title, String outcome1, String outcome2) {
        OptionMapping titleOpt = opt(title);
        OptionMapping o1Opt = outcome1 != null ? opt(outcome1) : null;
        OptionMapping o2Opt = outcome2 != null ? opt(outcome2) : null;

        SlashCommandInteractionEvent evt = contractEvent("create", creatorSnowflake);
        when(evt.getOption("title")).thenReturn(titleOpt);
        when(evt.getOption("outcome_1")).thenReturn(o1Opt);
        when(evt.getOption("outcome_2")).thenReturn(o2Opt);
        when(evt.getOption("outcome_3")).thenReturn(null);
        when(evt.getOption("outcome_4")).thenReturn(null);
        when(evt.getOption("outcome_5")).thenReturn(null);
        listener.handleSlashCommand(evt);

        return contractRepository
                .findFirstByServerIdAndStateInAndTitleIgnoreCase(guildId, List.of(ContractState.OPEN), title)
                .orElseThrow(() -> new AssertionError("Contract not created: " + title + " (guild " + guildId + ")"));
    }

    // -------------------------------------------------------------------------
    // create
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("create → contract row persisted in DB with OPEN state and correct outcomes")
    void createContract_persistsContractAndOutcomes() {
        String title = t("Will it rain tomorrow?");
        Contract saved = createContract(title, "YES", "NO");

        assertThat(saved.getTitle()).isEqualTo(title);
        assertThat(saved.getCreatorSnowflake()).isEqualTo(creatorSnowflake);
        assertThat(saved.getServerId()).isEqualTo(guildId);
        assertThat(saved.getOutcomes()).extracting(ContractOutcome::getLabel).containsExactlyInAnyOrder("YES", "NO");
    }

    @Test
    @DisplayName("create with no explicit outcomes → defaults YES/NO")
    void createContract_defaultsToYesNo() {
        Contract saved = createContract(t("Will we win?"), null, null);

        assertThat(saved.getOutcomes()).extracting(ContractOutcome::getLabel).containsExactlyInAnyOrder("YES", "NO");
    }

    @Test
    @DisplayName("create when contracts_enabled = false on guild → no row persisted")
    void createContract_disabledGuild_doesNothing() {
        // Flip the gate off for this test's guild.
        jdbc.update("UPDATE guild SET contracts_enabled = false WHERE guild_id = ?", guildId);

        String title = t("Should not persist");
        OptionMapping titleOpt = opt(title);
        OptionMapping o1Opt = opt("YES");
        OptionMapping o2Opt = opt("NO");
        SlashCommandInteractionEvent evt = contractEvent("create", creatorSnowflake);
        when(evt.getOption("title")).thenReturn(titleOpt);
        when(evt.getOption("outcome_1")).thenReturn(o1Opt);
        when(evt.getOption("outcome_2")).thenReturn(o2Opt);
        when(evt.getOption("outcome_3")).thenReturn(null);
        when(evt.getOption("outcome_4")).thenReturn(null);
        when(evt.getOption("outcome_5")).thenReturn(null);

        listener.handleSlashCommand(evt);

        assertThat(contractRepository.findFirstByServerIdAndStateInAndTitleIgnoreCase(
                        guildId, List.of(ContractState.OPEN), title))
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // trade
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("trade → user balance debited, shares incremented on the outcome")
    void trade_debitsBalanceAndIncrementsShares() {
        String title = t("Trade Test");
        Contract contract = createContract(title, "WIN", "LOSE");
        double sharesBefore = contract.getOutcomes().stream()
                .filter(o -> o.getLabel().equals("WIN"))
                .findFirst()
                .orElseThrow()
                .getSharesOutstanding();

        long balanceBefore = userBalanceService.getBalance(traderSnowflake);

        OptionMapping contractOpt = opt(title);
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
        String title = t("Resolve Test");
        Contract contract = createContract(title, "A", "B");

        // Place a trade on outcome A so there's a winner to credit.
        OptionMapping contractOpt1 = opt(title);
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

        OptionMapping contractOpt2 = opt(title);
        OptionMapping outcomeOpt2 = opt("A");
        SlashCommandInteractionEvent resolveEvt = contractEvent("resolve", resolverSnowflake);
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
        String title = t("Cancel Test");
        Contract contract = createContract(title, "X", "Y");

        long balanceBefore = userBalanceService.getBalance(traderSnowflake);

        OptionMapping contractOpt1 = opt(title);
        OptionMapping outcomeOpt1 = opt("X");
        OptionMapping amountOpt1 = opt("100");
        SlashCommandInteractionEvent tradeEvt = contractEvent("trade", traderSnowflake);
        when(tradeEvt.getOption("contract")).thenReturn(contractOpt1);
        when(tradeEvt.getOption("outcome")).thenReturn(outcomeOpt1);
        when(tradeEvt.getOption("amount")).thenReturn(amountOpt1);
        listener.handleSlashCommand(tradeEvt);

        long balanceAfterTrade = userBalanceService.getBalance(traderSnowflake);
        assertThat(balanceAfterTrade).isLessThan(balanceBefore);

        when(discordAuthService.hasRole(anyLong(), anyLong(), anyString())).thenReturn(true);

        OptionMapping cancelContractOpt = opt(title);
        SlashCommandInteractionEvent cancelEvt = contractEvent("cancel", resolverSnowflake);
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
    @DisplayName("list → reply contains open contract titles scoped to this guild")
    void list_repliesWithOpenContracts() {
        String alpha = t("Alpha Contract");
        String beta = t("Beta Contract");
        createContract(alpha, null, null);
        createContract(beta, null, null);

        SlashCommandInteractionEvent listEvt = contractEvent("list", creatorSnowflake);
        listener.handleSlashCommand(listEvt);

        // The list handler calls event.reply(...).setEphemeral(true).queue() with the joined titles.
        verify(listEvt).reply(org.mockito.ArgumentMatchers.contains(alpha));
        verify(listEvt).reply(org.mockito.ArgumentMatchers.contains(beta));
    }

    // -------------------------------------------------------------------------
    // balance
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // autocomplete (handleAutoComplete)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("autocomplete on disabled guild → no choices")
    void autocomplete_disabledGuild_repliesEmpty() {
        jdbc.update("UPDATE guild SET contracts_enabled = false WHERE guild_id = ?", guildId);

        java.util.concurrent.atomic.AtomicReference<List<Command.Choice>> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        CommandAutoCompleteInteractionEvent evt = autoCompleteEventCapturing("contract", "anything", captured);

        listener.handleAutoComplete(evt);

        assertThat(captured.get()).isEmpty();
    }

    @Test
    @DisplayName("autocomplete on contract field → returns matching open contract titles")
    void autocomplete_contractField_returnsMatchingTitles() {
        String matchingTitle = t("Alpha Match");
        String nonMatching = t("Zebra");
        createContract(matchingTitle, null, null);
        createContract(nonMatching, null, null);

        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<List<Command.Choice>> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        CommandAutoCompleteInteractionEvent evt = autoCompleteEventCapturing("contract", "alpha", captured);

        listener.handleAutoComplete(evt);

        assertThat(captured.get())
                .extracting(Command.Choice::getName)
                .anyMatch(name -> name.equals(matchingTitle))
                .noneMatch(name -> name.equals(nonMatching));
    }

    @Test
    @DisplayName("autocomplete on outcome field → returns outcome labels filtered by typed prefix")
    void autocomplete_outcomeField_returnsOutcomeLabels() {
        String title = t("Outcome AC Test");
        createContract(title, "APPLE", "BANANA");

        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<List<Command.Choice>> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        CommandAutoCompleteInteractionEvent evt = autoCompleteEventCapturing("outcome", "app", captured);
        // The autocomplete handler reads the prior `contract` option to resolve outcomes.
        OptionMapping contractOpt = opt(title);
        lenient().when(evt.getOption("contract")).thenReturn(contractOpt);

        listener.handleAutoComplete(evt);

        assertThat(captured.get()).extracting(Command.Choice::getName).containsExactly("APPLE");
    }

    @Test
    @DisplayName("autocomplete on outcome field without contract context → no choices")
    void autocomplete_outcomeField_noContractContext_repliesEmpty() {
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<List<Command.Choice>> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        CommandAutoCompleteInteractionEvent evt = autoCompleteEventCapturing("outcome", "any", captured);
        lenient().when(evt.getOption("contract")).thenReturn(null);

        listener.handleAutoComplete(evt);

        assertThat(captured.get()).isEmpty();
    }

    @Test
    @DisplayName("autocomplete on unknown field → no choices")
    void autocomplete_unknownField_repliesEmpty() {
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<List<Command.Choice>> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        CommandAutoCompleteInteractionEvent evt = autoCompleteEventCapturing("nonsense", "x", captured);

        listener.handleAutoComplete(evt);

        assertThat(captured.get()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // balance
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("balance → replies with current balance for the requesting user")
    void balance_repliesWithCurrentBalance() {
        SlashCommandInteractionEvent balanceEvt = mock(SlashCommandInteractionEvent.class);
        when(balanceEvt.getName()).thenReturn("balance");
        User user = mock(User.class);
        when(user.getId()).thenReturn(traderSnowflake);
        when(balanceEvt.getUser()).thenReturn(user);
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(guildId);
        when(balanceEvt.getGuild()).thenReturn(guild);

        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(balanceEvt.reply(anyString())).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        listener.handleSlashCommand(balanceEvt);

        long expected = userBalanceService.getBalance(traderSnowflake);
        verify(balanceEvt).reply(org.mockito.ArgumentMatchers.contains(Long.toString(expected)));
    }
}
