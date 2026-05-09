package dev.tylercash.event.test;

import java.util.concurrent.atomic.AtomicLong;

/**
 * JVM-wide monotonic ID generator for tests that share a database. Use a fresh ID per test
 * method (snowflake / guild id / event id) so concurrently-running tests in the same JVM —
 * and tests within a single class — never collide on hard-coded values.
 *
 * <p>Counter starts well above the range any production fixture or hard-coded test value uses
 * (10^12). A long is plenty even at 1M IDs/sec for years.
 */
public final class TestIds {

    private static final AtomicLong COUNTER = new AtomicLong(1_000_000_000_000L);

    private TestIds() {}

    public static long nextLong() {
        return COUNTER.incrementAndGet();
    }

    public static String nextSnowflake() {
        return Long.toString(nextLong());
    }
}
