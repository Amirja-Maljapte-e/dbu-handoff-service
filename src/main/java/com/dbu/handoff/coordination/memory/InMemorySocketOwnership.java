package com.dbu.handoff.coordination.memory;

import com.dbu.handoff.coordination.CoordinationProperties;
import com.dbu.handoff.coordination.SocketOwnership;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Single-process ownership registry.
 *
 * <p>Mirrors the semantics the Redis implementation must provide: claims are
 * atomic, expire without refresh, and can only be refreshed or released by
 * their current owner. Tests written against this therefore still hold when
 * the Redis version arrives.
 */
public class InMemorySocketOwnership implements SocketOwnership {

    private record Claim(String instanceId, Instant expiresAt) {
    }

    private final Map<String, Claim> owners = new ConcurrentHashMap<>();
    private final Map<String, Instant> heartbeats = new ConcurrentHashMap<>();
    private final CoordinationProperties properties;
    private final Clock clock;

    public InMemorySocketOwnership(CoordinationProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public boolean claim(String chatJourneyId, String instanceId) {
        Instant now = clock.instant();
        Claim claimed = owners.compute(chatJourneyId, (key, existing) -> {
            if (existing != null && existing.expiresAt().isAfter(now)) {
                return existing;
            }
            return new Claim(instanceId, now.plus(properties.ownershipTtl()));
        });
        return claimed.instanceId().equals(instanceId);
    }

    @Override
    public boolean refresh(String chatJourneyId, String instanceId) {
        Instant now = clock.instant();
        Claim refreshed = owners.computeIfPresent(chatJourneyId, (key, existing) -> {
            boolean stillOurs = existing.instanceId().equals(instanceId)
                    && existing.expiresAt().isAfter(now);
            return stillOurs
                    ? new Claim(instanceId, now.plus(properties.ownershipTtl()))
                    : existing;
        });
        return refreshed != null
                && refreshed.instanceId().equals(instanceId)
                && refreshed.expiresAt().isAfter(now);
    }

    @Override
    public void release(String chatJourneyId, String instanceId) {
        owners.computeIfPresent(chatJourneyId, (key, existing) ->
                existing.instanceId().equals(instanceId) ? null : existing);
    }

    @Override
    public Optional<String> ownerOf(String chatJourneyId) {
        Claim claim = owners.get(chatJourneyId);
        if (claim == null || !claim.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(claim.instanceId());
    }

    @Override
    public Set<String> journeysOwnedBy(String instanceId) {
        Instant now = clock.instant();
        return owners.entrySet().stream()
                .filter(e -> e.getValue().instanceId().equals(instanceId))
                .filter(e -> e.getValue().expiresAt().isAfter(now))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void heartbeat(String instanceId) {
        heartbeats.put(instanceId, clock.instant().plus(properties.ownershipTtl()));
    }

    @Override
    public boolean isAlive(String instanceId) {
        Instant until = heartbeats.get(instanceId);
        return until != null && until.isAfter(clock.instant());
    }

    @Override
    public Set<String> deadInstancesHoldingJourneys() {
        Instant now = clock.instant();
        Set<String> dead = new HashSet<>();
        owners.values().stream()
                .filter(claim -> claim.expiresAt().isAfter(now))
                .map(Claim::instanceId)
                .distinct()
                .filter(instanceId -> !isAlive(instanceId))
                .forEach(dead::add);
        return Set.copyOf(dead);
    }
}
