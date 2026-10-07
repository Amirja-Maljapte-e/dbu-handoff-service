package com.dbu.handoff.coordination;

import java.util.Optional;
import java.util.Set;

/**
 * Which instance holds the monitoring socket for each journey.
 *
 * <p>A WebSocket belongs to the instance that opened it, so the service is
 * stateless in its data but not in socket ownership. Everything that needs to
 * reach a socket looks up its owner here first.
 *
 * <p>Ownership expires. An instance keeps its claim alive by refreshing; if it
 * dies, the claim lapses and reconciliation can take the journey over.
 */
public interface SocketOwnership {

    /**
     * Take ownership of a journey, if it is free.
     *
     * <p>Must be atomic: when two instances notice the same orphan at once,
     * exactly one wins and the other backs off.
     *
     * @return true if this instance now owns the journey
     */
    boolean claim(String chatJourneyId, String instanceId);

    /**
     * Extend an existing claim.
     *
     * @return false if the claim has lapsed and another instance has taken it.
     *         The caller must then close its socket without sending anything
     *         further, or two instances would be driving one conversation.
     */
    boolean refresh(String chatJourneyId, String instanceId);

    /** Give up a claim. Ignored if this instance is no longer the owner. */
    void release(String chatJourneyId, String instanceId);

    /** Who owns this journey, if anyone. Empty means it is orphaned. */
    Optional<String> ownerOf(String chatJourneyId);

    /** Journeys this instance currently owns. Lets reconciliation find orphans in one read. */
    Set<String> journeysOwnedBy(String instanceId);

    /** Record that this instance is alive. Called on a timer. */
    void heartbeat(String instanceId);

    /** Whether an instance is still heartbeating. False means its sockets can be reclaimed. */
    boolean isAlive(String instanceId);

    /** Instances that have stopped heartbeating but still hold claims. */
    Set<String> deadInstancesHoldingJourneys();
}
