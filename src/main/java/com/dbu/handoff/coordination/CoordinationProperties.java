package com.dbu.handoff.coordination;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the coordination layer. Externalised so they can be changed
 * without a code change once real behaviour has been measured.
 *
 * @param ownershipTtl      how long a claim survives without a refresh
 * @param refreshInterval   how often an owner refreshes its claims
 * @param bufferTtl         how long queue-wait messages are held
 */
@ConfigurationProperties(prefix = "handoff.coordination")
public record CoordinationProperties(
        Duration ownershipTtl,
        Duration refreshInterval,
        Duration bufferTtl) {

    public CoordinationProperties {
        ownershipTtl = ownershipTtl != null ? ownershipTtl : Duration.ofSeconds(30);
        refreshInterval = refreshInterval != null ? refreshInterval : Duration.ofSeconds(10);
        bufferTtl = bufferTtl != null ? bufferTtl : Duration.ofMinutes(30);
    }

    /**
     * Three missed refreshes before a claim lapses. Too tight and a garbage
     * collection pause makes a healthy instance look dead; too loose and a real
     * crash leaves the customer waiting.
     */
    public boolean isSane() {
        return ownershipTtl.compareTo(refreshInterval.multipliedBy(2)) >= 0;
    }
}
