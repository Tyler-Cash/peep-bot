package dev.tylercash.event.contract;

import org.springframework.stereotype.Service;

@Service
public class LmsrService {

    /**
     * b = seedAmount / ln(numOutcomes). Creator's max loss is capped at seedAmount.
     */
    public static double computeB(long seedAmount, int numOutcomes) {
        return seedAmount / Math.log(numOutcomes);
    }

    /**
     * LMSR cost function: C(q) = b * ln(sum(exp(q_i / b)))
     * Uses log-sum-exp trick to avoid overflow when q_i/b is large.
     */
    private double cost(double[] q, double b) {
        double max = 0;
        for (double qi : q) max = Math.max(max, qi);
        double sum = 0;
        for (double qi : q) sum += Math.exp((qi - max) / b);
        return max + b * Math.log(sum);
    }

    /**
     * Cost in coins to buy `shares` of outcome `outcomeIndex`.
     */
    public long costToBuy(double[] q, int outcomeIndex, double shares, double b) {
        double[] qAfter = q.clone();
        qAfter[outcomeIndex] += shares;
        return Math.round(cost(qAfter, b) - cost(q, b));
    }

    /**
     * Probability of outcome i: exp(q_i / b) / sum(exp(q_j / b))
     * Uses log-sum-exp trick to avoid overflow when q_i/b is large.
     */
    public double probability(double[] q, int outcomeIndex, double b) {
        double max = 0;
        for (double qi : q) max = Math.max(max, qi);
        double denom = 0;
        for (double qi : q) denom += Math.exp((qi - max) / b);
        return Math.exp((q[outcomeIndex] - max) / b) / denom;
    }

    /**
     * Binary search: find shares such that costToBuy ≈ targetCost.
     * Upper bound is expanded until it brackets the answer.
     */
    public double sharesToBuyForCost(double[] q, int outcomeIndex, long targetCost, double b) {
        double lo = 0, hi = Math.max(targetCost * 2.0, 1.0);
        while (costToBuy(q, outcomeIndex, hi, b) < targetCost) hi *= 2;
        for (int i = 0; i < 64; i++) {
            double mid = (lo + hi) / 2;
            long c = costToBuy(q, outcomeIndex, mid, b);
            if (c < targetCost) lo = mid;
            else hi = mid;
        }
        return (lo + hi) / 2;
    }
}
