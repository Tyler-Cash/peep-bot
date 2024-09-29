package dev.tylercash.event;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

public class GlobalTestConfiguration {
    public static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1515236400000L), ZoneId.systemDefault());

}
