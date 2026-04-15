package dev.tylercash.event.contract;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "dev.tylercash.contract")
public class ContractConfiguration {
    private String resolverRole = "prediction-resolver";
    private String categoryName = "prediction contracts";
    private long defaultBalance = 1000L;
    private long negativeTradeCap = 100L;
    private long minSeedAmount = 100L;
    private Emoji emoji = new Emoji();

    @Data
    public static class Emoji {
        private String success = "\u2705";
        private String poor = "\uD83D\uDCB8";
    }
}
