package com.dbu.handoff.service;

/**
 * Everything needed to start a handoff, as the domain sees it.
 *
 * <p>Separate from the HTTP request so the orchestrator does not depend on the
 * wire format. When the ElevenLabs contract changes, the controller changes and
 * this does not.
 *
 * <p>{@code residentName}, {@code mobile} and {@code email} travel with the
 * command so they can be passed to Genesys, and go no further: they are never
 * written to the database and never logged. Only {@code customerId} is stored.
 *
 * @param deliveryKey identifies this invocation for the idempotency ledger
 */
public record EscalationCommand(
        String elevenLabsConversationId,
        String customerId,
        String residentName,
        String mobile,
        String email,
        String escalationReason,
        String conversationSummary,
        String deliveryKey) {
}
