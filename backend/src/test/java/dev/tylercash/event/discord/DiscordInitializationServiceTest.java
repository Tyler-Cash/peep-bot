package dev.tylercash.event.discord;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiscordInitializationServiceTest {

    private JDA jda;
    private Guild guild;
    private DiscordConfiguration config;
    private DiscordInitializationService service;

    @BeforeEach
    void setUp() {
        jda = mock(JDA.class);
        guild = mock(Guild.class);
        config = new DiscordConfiguration();
        config.setGuildId(123L);
        config.setSeperatorChannel("organising");
        when(jda.getGuildById(123L)).thenReturn(guild);
        service = new DiscordInitializationService(jda, config);
    }

    @SuppressWarnings("unchecked")
    private Category mockCategoryCreation(String name) {
        Category category = mock(Category.class);
        when(category.getName()).thenReturn(name);
        ChannelAction<Category> action = mock(ChannelAction.class);
        when(guild.createCategory(name)).thenReturn(action);
        when(action.complete()).thenReturn(category);
        return category;
    }

    @SuppressWarnings("unchecked")
    private void mockTextChannelCreation(Category category) {
        ChannelAction<TextChannel> action = mock(ChannelAction.class);
        when(category.createTextChannel(anyString())).thenReturn(action);
        when(action.setPosition(anyInt())).thenReturn(action);
        when(action.complete()).thenReturn(mock(TextChannel.class));
    }

    @Test
    @DisplayName("creates all categories and separator channel when none exist")
    void initializeGuild_createsEverything() {
        when(guild.getCategoriesByName(anyString(), eq(true))).thenReturn(Collections.emptyList());

        Category outings = mockCategoryCreation("outings");
        mockCategoryCreation("outings-archive");
        when(outings.getTextChannels()).thenReturn(Collections.emptyList());
        mockTextChannelCreation(outings);

        service.initializeGuild();

        verify(guild).createCategory("outings");
        verify(guild).createCategory("outings-archive");
        verify(outings).createTextChannel("organising");
    }

    @Test
    @DisplayName("skips creation when categories and separator already exist")
    void initializeGuild_skipsWhenAllExist() {
        Category outings = mock(Category.class);
        when(outings.getName()).thenReturn("outings");
        Category archive = mock(Category.class);
        when(archive.getName()).thenReturn("outings-archive");

        when(guild.getCategoriesByName("outings", true)).thenReturn(List.of(outings));
        when(guild.getCategoriesByName("outings-archive", true)).thenReturn(List.of(archive));

        TextChannel separatorChannel = mock(TextChannel.class);
        when(separatorChannel.getName()).thenReturn("organising");
        when(outings.getTextChannels()).thenReturn(List.of(separatorChannel));

        service.initializeGuild();

        verify(guild, never()).createCategory(anyString());
        verify(outings, never()).createTextChannel(anyString());
    }

    @Test
    @DisplayName("creates only missing category when one already exists")
    void initializeGuild_createsOnlyMissing() {
        Category outings = mock(Category.class);
        when(outings.getName()).thenReturn("outings");

        when(guild.getCategoriesByName("outings", true)).thenReturn(List.of(outings));
        when(guild.getCategoriesByName("outings-archive", true)).thenReturn(Collections.emptyList());

        mockCategoryCreation("outings-archive");

        TextChannel separatorChannel = mock(TextChannel.class);
        when(separatorChannel.getName()).thenReturn("organising");
        when(outings.getTextChannels()).thenReturn(List.of(separatorChannel));

        service.initializeGuild();

        verify(guild, never()).createCategory("outings");
        verify(guild).createCategory("outings-archive");
    }

    @Test
    @DisplayName("creates separator channel when category exists but channel does not")
    void initializeGuild_createsSeparatorInExistingCategory() {
        Category outings = mock(Category.class);
        when(outings.getName()).thenReturn("outings");
        Category archive = mock(Category.class);
        when(archive.getName()).thenReturn("outings-archive");

        when(guild.getCategoriesByName("outings", true)).thenReturn(List.of(outings));
        when(guild.getCategoriesByName("outings-archive", true)).thenReturn(List.of(archive));

        when(outings.getTextChannels()).thenReturn(Collections.emptyList());
        mockTextChannelCreation(outings);

        service.initializeGuild();

        verify(outings).createTextChannel("organising");
    }

    @Test
    @DisplayName("skips separator channel creation when config is blank")
    void initializeGuild_skipsSeparatorWhenBlank() {
        config.setSeperatorChannel("");

        Category outings = mock(Category.class);
        when(outings.getName()).thenReturn("outings");
        when(guild.getCategoriesByName("outings", true)).thenReturn(List.of(outings));
        when(guild.getCategoriesByName("outings-archive", true)).thenReturn(Collections.emptyList());
        mockCategoryCreation("outings-archive");

        service.initializeGuild();

        verify(outings, never()).createTextChannel(anyString());
    }

    @Test
    @DisplayName("throws when guild is not found")
    void initializeGuild_guildNotFound() {
        when(jda.getGuildById(123L)).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service.initializeGuild());
    }
}
