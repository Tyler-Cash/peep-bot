package dev.tylercash.event.discord;

import dev.tylercash.event.contract.ContractConfiguration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContractGuildResolver {
    private final ContractConfiguration contractConfiguration;

    public long getContractsGuildId() {
        return contractConfiguration.getGuildId();
    }

    public boolean isContractsGuild(long guildId) {
        return contractConfiguration.getGuildId() == guildId;
    }
}
