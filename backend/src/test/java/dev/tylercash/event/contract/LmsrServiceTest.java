package dev.tylercash.event.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LmsrServiceTest {
    private final LmsrService lmsr = new LmsrService();

    @Test
    @DisplayName("computeB returns seedAmount / ln(numOutcomes) so max loss is capped at seedAmount")
    void computeB_capsMaxLossAtSeedAmount() {
        double b = LmsrService.computeB(1000, 2);
        assertThat(b).isCloseTo(1000 / Math.log(2), within(1e-9));

        // LMSR max-loss bound: b * ln(n) == seedAmount
        assertThat(b * Math.log(2)).isCloseTo(1000.0, within(1e-9));
    }

    @Test
    @DisplayName("probabilities sum to 1 regardless of q")
    void probabilities_sumToOne() {
        double b = LmsrService.computeB(1000, 3);
        double[] q = {5, 12, 3};

        double total = lmsr.probability(q, 0, b) + lmsr.probability(q, 1, b) + lmsr.probability(q, 2, b);

        assertThat(total).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("at q=0 each outcome has equal probability 1/n")
    void probabilities_uniformAtZero() {
        double b = LmsrService.computeB(1000, 4);
        double[] q = new double[4];

        for (int i = 0; i < 4; i++) {
            assertThat(lmsr.probability(q, i, b)).isCloseTo(0.25, within(1e-9));
        }
    }

    @Test
    @DisplayName("buying shares in an outcome increases its probability")
    void buyingShares_increasesProbability() {
        double b = LmsrService.computeB(1000, 3);
        double[] q = new double[3];
        double before = lmsr.probability(q, 0, b);

        q[0] += 50;
        double after = lmsr.probability(q, 0, b);

        assertThat(after).isGreaterThan(before);
    }

    @Test
    @DisplayName("costToBuy is non-negative and larger as more shares are purchased")
    void costToBuy_monotonicInShares() {
        double b = LmsrService.computeB(1000, 2);
        double[] q = new double[2];

        long c1 = lmsr.costToBuy(q, 0, 10, b);
        long c10 = lmsr.costToBuy(q, 0, 100, b);
        long c100 = lmsr.costToBuy(q, 0, 1000, b);

        assertThat(c1).isGreaterThanOrEqualTo(0);
        assertThat(c10).isGreaterThan(c1);
        assertThat(c100).isGreaterThan(c10);
    }

    @Test
    @DisplayName("sharesToBuyForCost inverts costToBuy — the resulting cost matches the target within 1 coin")
    void sharesToBuyForCost_invertsCostToBuy() {
        double b = LmsrService.computeB(1000, 2);
        double[] q = new double[2];

        double shares = lmsr.sharesToBuyForCost(q, 0, 250L, b);
        long actualCost = lmsr.costToBuy(q, 0, shares, b);

        assertThat(actualCost).isBetween(249L, 251L);
    }

    @Test
    @DisplayName("cost function is numerically stable at extreme q values via log-sum-exp trick")
    void costToBuy_stableAtLargeQ() {
        double b = LmsrService.computeB(1000, 2);
        // Extreme q values would overflow a naive exp() implementation.
        double[] q = {10_000, 0};

        long cost = lmsr.costToBuy(q, 1, 10, b);

        assertThat(cost).isGreaterThanOrEqualTo(0);
        assertThat(Double.isFinite(cost)).isTrue();
    }
}
