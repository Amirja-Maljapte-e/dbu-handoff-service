package com.dbu.handoff.coordination;

import java.util.function.Consumer;

/**
 * Carries a message to the instance that owns a socket.
 *
 * <p>Fire and forget, like the Redis pub/sub it will be backed by: if the
 * target instance is not listening, the message is dropped. Callers check
 * liveness before publishing, and fall back to redelivery when the owner has
 * died — see the failure and recovery section of the design.
 */
public interface RelayChannel {

    /**
     * Send a message to one instance.
     *
     * @return true if a subscriber received it; false if it was dropped
     */
    boolean publish(String targetInstanceId, RelayMessage message);

    /** Listen for messages addressed to this instance. */
    void subscribe(String instanceId, Consumer<RelayMessage> handler);

    /** Stop listening. Called on shutdown. */
    void unsubscribe(String instanceId);
}
