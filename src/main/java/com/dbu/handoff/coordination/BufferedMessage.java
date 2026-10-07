package com.dbu.handoff.coordination;

import java.time.Instant;

/**
 * A customer message typed while nobody was listening yet.
 *
 * <p>Held only between the customer asking for a human and an agent accepting.
 * Transient by design: losing it degrades one conversation, and the agent still
 * has the escalation summary.
 *
 * @param messageId stable across retries, so a flush that is retried does not
 *                  deliver the same message twice
 * @param text      the message itself — never persisted to the database
 */
public record BufferedMessage(String messageId, String text, Instant receivedAt) {
}
