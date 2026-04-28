package dev.tylercash.event.discord;

import dev.tylercash.event.db.repository.DiscordGuildMemberRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class GuildMembershipService {
    private final DiscordGuildMemberRepository memberRepository;

    public boolean isMember(String snowflake, long guildId) {
        return memberRepository.existsByGuildIdAndSnowflake(guildId, snowflake);
    }

    public void assertMember(String snowflake, long guildId) {
        if (!isMember(snowflake, guildId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not a member of this server");
        }
    }

    public List<Long> getGuildIdsForUser(String snowflake) {
        return memberRepository.findGuildIdsBySnowflake(snowflake);
    }
}
