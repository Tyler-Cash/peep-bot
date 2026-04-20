package dev.tylercash.event.rewind;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "dev.tylercash.rewind")
public class RewindConfiguration {
    private boolean enabled = true;
    private double clusterSimilarityThreshold = 0.78;
    private int backfillBatchSize = 20;
}
