package dev.tylercash.event.discord;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import dev.tylercash.event.contract.ContractConfiguration;
import java.util.Collections;
import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiscordInitializationServiceTest {

    private JDA jda;
    private net.dv8tion.jda.api.entities.Guild jdaGuild;
    private DiscordChannelService discordChannelService;
    private ContractConfiguration contractConfig;
    private DiscordUserCacheService discordUserCacheService;
    private ContractGuildResolver contractGuildResolver;
    private GuildRegistrationService guildRegistrationService;
    private GuildRepository guildRepository;
    private DiscordInitializationService service;

    @BeforeEach
    void setUp() {
        jda = mock(JDA.class);
        jdaGuild = mock(net.dv8tion.jda.api.entities.Guild.class);
        when(jdaGuild.getIdLong()).thenReturn(123L);
        when(jdaGuild.getName()).thenReturn("Test Guild");

        discordChannelService = mock(DiscordChannelService.class);
        discordUserCacheService = mock(DiscordUserCacheService.class);
        contractConfig = new ContractConfiguration();
        contractGuildResolver = mock(ContractGuildResolver.class);
        guildRegistrationService = mock(GuildRegistrationService.class);
        guildRepository = mock(GuildRepository.class);

        service = new DiscordInitializationService(
                jda,
                discordChannelService,
                contractConfig,
                discordUserCacheService,
                contractGuildResolver,
                guildRegistrationService,
                guildRepository);
    }

    @SuppressWarnings("unchecked")
    private void mockTextChannelCreation(Category category) {
        ChannelAction<TextChannel> action = mock(ChannelAction.class);
        when(category.createTextChannel(anyString())).thenReturn(action);
        when(action.setPosition(anyInt())).thenReturn(action);
        when(action.complete()).thenReturn(mock(TextChannel.class));
    }

    private Guild makeRow(String separatorChannel) {
        Guild row = Guild.withDefaults(123L);
        row.setSeparatorChannel(separatorChannel);
        return row;
    }

    @Test
    @DisplayName("creates all categories and separator channel when none exist")
    void initialise_createsEverything() {
        Category outings = mock(Category.class);
        when(outings.getName()).thenReturn("outings");
        Category archive = mock(Category.class);
        when(archive.getName()).thenReturn("outings-archive");

        when(discordChannelService.getOrCreateCategory(jdaGuild, "outings")).thenReturn(outings);
        when(discordChannelService.getOrCreateCategory(jdaGuild, "outings-archive"))
                .thenReturn(archive);
        when(contractGuildResolver.isContractsGuild(123L)).thenReturn(false);
        when(outings.getTextChannels()).thenReturn(Collections.emptyList());
        mockTextChannelCreation(outings);

        service.initialise(jdaGuild, makeRow("organising"));

        verify(discordChannelService).getOrCreateCategory(jdaGuild, "outings");
        verify(discordChannelService).getOrCreateCategory(jdaGuild, "outings-archive");
        verify(outings).createTextChannel("organising");
    }

    @Test
    @DisplayName("skips creation when categories and separator already exist")
    void initialise_skipsWhenAllExist() {
        Category outings = mock(Category.class);
        when(outings.getName()).thenReturn("outings");
        Category archive = mock(Category.class);
        when(archive.getName()).thenReturn("outings-archive");

        when(discordChannelService.getOrCreateCategory(jdaGuild, "outings")).thenReturn(outings);
        when(discordChannelService.getOrCreateCategory(jdaGuild, "outings-archive"))
                .thenReturn(archive);
        when(contractGuildResolver.isContractsGuild(123L)).thenReturn(false);

        TextChannel separatorChannel = mock(TextChannel.class);
        when(separatorChannel.getName()).thenReturn("organising");
        when(outings.getTextChannels()).thenReturn(List.of(separatorChannel));

        service.initialise(jdaGuild, makeRow("organising"));

        verify(outings, never()).createTextChannel(anyString());
    }

    @Test
    @DisplayName("creates only missing category when one already exists")
    void initialise_createsOnlyMissing() {
        Category outings = mock(Category.class);
        when(outings.getName()).thenReturn("outings");
        Category archive = mock(Category.class);
        when(archive.getName()).thenReturn("outings-archive");

        when(discordChannelService.getOrCreateCategory(jdaGuild, "outings")).thenReturn(outings);
        when(discordChannelService.getOrCreateCategory(jdaGuild, "outings-archive"))
                .thenReturn(archive);
        when(contractGuildResolver.isContractsGuild(123L)).thenReturn(false);

        TextChannel separatorChannel = mock(TextChannel.class);
        when(separatorChannel.getName()).thenReturn("organising");
        when(outings.getTextChannels()).thenReturn(List.of(separatorChannel));

        service.initialise(jdaGuild, makeRow("organising"));

        verify(discordChannelService).getOrCreateCategory(jdaGuild, "outings");
        verify(discordChannelService).getOrCreateCategory(jdaGuild, "outings-archive");
    }

    @Test
    @DisplayName("creates separator channel when category exists but channel does not")
    void initialise_createsSeparatorInExistingCategory() {
        Category outings = mock(Category.class);
        when(outings.getName()).thenReturn("outings");
        Category archive = mock(Category.class);
        when(archive.getName()).thenReturn("outings-archive");

        when(discordChannelService.getOrCreateCategory(jdaGuild, "outings")).thenReturn(outings);
        when(discordChannelService.getOrCreateCategory(jdaGuild, "outings-archive"))
                .thenReturn(archive);
        when(contractGuildResolver.isContractsGuild(123L)).thenReturn(false);
        when(outings.getTextChannels()).thenReturn(Collections.emptyList());
        mockTextChannelCreation(outings);

        service.initialise(jdaGuild, makeRow("organising"));

        verify(outings).createTextChannel("organising");
    }

    @Test
    @DisplayName("skips separator channel creation when separator name is blank")
    void initialise_skipsSeparatorWhenBlank() {
        Category outings = mock(Category.class);
        when(outings.getName()).thenReturn("outings");
        Category archive = mock(Category.class);
        when(archive.getName()).thenReturn("outings-archive");

        when(discordChannelService.getOrCreateCategory(jdaGuild, "outings")).thenReturn(outings);
        when(discordChannelService.getOrCreateCategory(jdaGuild, "outings-archive"))
                .thenReturn(archive);
        when(contractGuildResolver.isContractsGuild(123L)).thenReturn(false);

        service.initialise(jdaGuild, makeRow(""));

        verify(outings, never()).createTextChannel(anyString());
    }

    @Test
    @DisplayName("ensures contracts category when guild is the contracts guild")
    void initialise_ensuresContractsCategoryForContractsGuild() {
        Category outings = mock(Category.class);
        when(outings.getName()).thenReturn("outings");
        Category archive = mock(Category.class);
        Category contracts = mock(Category.class);

        when(discordChannelService.getOrCreateCategory(jdaGuild, "outings")).thenReturn(outings);
        when(discordChannelService.getOrCreateCategory(jdaGuild, "outings-archive"))
                .thenReturn(archive);
        when(discordChannelService.getOrCreateCategory(jdaGuild, contractConfig.getCategoryName()))
                .thenReturn(contracts);
        when(contractGuildResolver.isContractsGuild(123L)).thenReturn(true);
        when(outings.getTextChannels()).thenReturn(Collections.emptyList());
        mockTextChannelCreation(outings);

        service.initialise(jdaGuild, makeRow("organising"));

        verify(discordChannelService).getOrCreateCategory(jdaGuild, contractConfig.getCategoryName());
    }
}
