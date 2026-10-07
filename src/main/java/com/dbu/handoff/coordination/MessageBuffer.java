package com.dbu.handoff.coordination;

import java.util.List;

/**
 * Holds customer messages during the queue wait.
 *
 * <p>The awkward window in the design: the customer can type, the AI is muted,
 * and no agent is attached yet. Messages are held here and flushed in arrival
 * order the moment an agent accepts.
 *
 * <p>Implementations expire entries after a TTL, so a customer who gives up
 * before an agent answers leaves nothing behind.
 */
public interface MessageBuffer {

    /** Hold a message. Order of arrival is preserved. */
    void append(String chatJourneyId, BufferedMessage message);

    /**
     * Take everything held for a journey and clear it, in one operation.
     *
     * <p>Atomic, so a concurrent flush cannot deliver the same message twice.
     */
    List<BufferedMessage> drain(String chatJourneyId);

    /** Throw away the buffer — the handoff ended without an agent accepting. */
    void discard(String chatJourneyId);

    /** How many messages are waiting. For metrics and tests. */
    int size(String chatJourneyId);
}
