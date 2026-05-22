package dev.tylercash.event.db.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.db.repository.GuildMemberRepository.StaleMemberRef;
import dev.tylercash.event.discord.Guild;
import dev.tylercash.event.discord.GuildRepository;
import dev.tylercash.event.discord.model.GuildMember;
import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import dev.tylercash.event.test.TestIds;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GuildMemberRepositoryStaleEntriesIntegrationTest extends AbstractHttpIntegrationTest {

    @Autowired
    GuildMemberRepository memberRepository;

    @Autowired
    GuildRepository guildRepository;

    @Test
    void surfacesEventCreatorPairWhenMemberRowMissing() {
        long guildId = TestIds.nextLong();
        String creator = TestIds.nextSnowflake();
        guildRepository.save(Guild.withDefaults(guildId));
        fixtures.seedEvent(guildId, creator, "creator-event");

        List<StaleMemberRef> stale = memberRepository.findStaleOrMissing(Instant.now(), 10_000);

        assertThat(stale).anyMatch(r -> r.getGuildId() == guildId && creator.equals(r.getSnowflake()));
    }

    @Test
    void excludesPairOncePersistedAndFresh() {
        long guildId = TestIds.nextLong();
        String creator = TestIds.nextSnowflake();
        guildRepository.save(Guild.withDefaults(guildId));
        fixtures.seedEvent(guildId, creator, "fresh-event");
        fixtures.registerMember(creator, guildId, "Creator", "creator_user");

        Instant cutoff = Instant.now().minus(30, ChronoUnit.MINUTES);
        List<StaleMemberRef> stale = memberRepository.findStaleOrMissing(cutoff, 10_000);

        assertThat(stale).noneMatch(r -> r.getGuildId() == guildId && creator.equals(r.getSnowflake()));
    }

    @Test
    void includesPairWhenMemberRowIsOlderThanCutoff() {
        long guildId = TestIds.nextLong();
        String creator = TestIds.nextSnowflake();
        guildRepository.save(Guild.withDefaults(guildId));
        fixtures.seedEvent(guildId, creator, "stale-event");
        fixtures.registerMember(creator, guildId, "Creator", "creator_user");

        // backdate the freshly-written row so it falls outside the cutoff
        GuildMember row = memberRepository
                .findByGuildIdAndSnowflake(guildId, creator)
                .orElseThrow();
        row.setUpdatedAt(Instant.now().minus(2, ChronoUnit.HOURS));
        memberRepository.save(row);

        Instant cutoff = Instant.now().minus(30, ChronoUnit.MINUTES);
        List<StaleMemberRef> stale = memberRepository.findStaleOrMissing(cutoff, 10_000);

        assertThat(stale).anyMatch(r -> r.getGuildId() == guildId && creator.equals(r.getSnowflake()));
    }

    @Test
    void excludesPairWhenGuildIsInactive() {
        long guildId = TestIds.nextLong();
        String creator = TestIds.nextSnowflake();
        Guild g = Guild.withDefaults(guildId);
        g.setActive(false);
        guildRepository.save(g);
        fixtures.seedEvent(guildId, creator, "inactive-guild-event");

        List<StaleMemberRef> stale = memberRepository.findStaleOrMissing(Instant.now(), 10_000);

        assertThat(stale).noneMatch(r -> r.getGuildId() == guildId && creator.equals(r.getSnowflake()));
    }
}
