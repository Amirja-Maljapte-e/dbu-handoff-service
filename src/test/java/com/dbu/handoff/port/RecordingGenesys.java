package com.dbu.handoff.port;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stand-in for Genesys Open Messaging.
 *
 * <p>Deduplicates on the idempotency key so tests can prove a retried
 * escalation does not create a second interaction.
 */
public class RecordingGenesys implements GenesysPort {

    /**
     * Note the first field: Open Messaging addresses the customer, not the
     * conversation, so this records who the message was sent to rather than
     * which conversation it landed in.
     */
    public record Sent(String customerId, String text, String messageId) {
    }

    private final Map<String, String> interactionsByKey = new ConcurrentHashMap<>();
    private final List<Sent> sent = new ArrayList<>();
    private final List<String> disconnected = new ArrayList<>();
    private boolean returnIdOnCreate = true;

    @Override
    public Optional<String> createInteraction(EscalationContext context, String idempotencyKey) {
        String id = interactionsByKey.computeIfAbsent(
                idempotencyKey, key -> "GEN-" + UUID.randomUUID());
        return returnIdOnCreate ? Optional.of(id) : Optional.empty();
    }

    @Override
    public void sendCustomerMessage(String customerId, String text, String messageId) {
        boolean duplicate = sent.stream().anyMatch(s -> s.messageId().equals(messageId));
        if (!duplicate) {
            sent.add(new Sent(customerId, text, messageId));
        }
    }

    @Override
    public void disconnect(String genesysConversationId) {
        disconnected.add(genesysConversationId);
    }

    /** Simulates the case where the conversation ID arrives later on a webhook. */
    public void returnIdOnCreate(boolean value) {
        this.returnIdOnCreate = value;
    }

    /** Clears recorded state between tests. The bean is a context-wide singleton. */
    public void reset() {
        interactionsByKey.clear();
        sent.clear();
        disconnected.clear();
        returnIdOnCreate = true;
    }

    public int interactionCount() {
        return interactionsByKey.size();
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    public List<String> disconnected() {
        return List.copyOf(disconnected);
    }
}
