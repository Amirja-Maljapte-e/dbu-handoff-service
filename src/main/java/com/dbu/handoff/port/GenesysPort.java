package com.dbu.handoff.port;

import java.util.Optional;

/**
 * What the service needs from Genesys Cloud CX.
 *
 * <p>Coarse by intent, as with {@link ElevenLabsPort}. The domain asks for an
 * interaction to be created; it does not know about Open Messaging payload
 * shapes or which endpoint carries them.
 */
public interface GenesysPort {

    /**
     * Create and route an interaction, carrying the escalation context.
     *
     * <p>Implementations must be idempotent on {@code idempotencyKey}: a
     * retried call must not create a second interaction.
     *
     * @return the Genesys conversation ID. The create call is made with
     *         {@code ?prefetchConversationId=true}, so it is returned on the
     *         response; empty means the platform did not supply one.
     */
    Optional<String> createInteraction(EscalationContext context, String idempotencyKey);

    /**
     * Relay a customer message to an active interaction.
     *
     * <p>Addressed by customer identifier rather than conversation: Open
     * Messaging attaches the message to the live conversation for that sender.
     *
     * @param messageId stable across retries, so a resend is not a duplicate
     */
    void sendCustomerMessage(String customerId, String text, String messageId);

    /**
     * End the interaction because the customer has gone.
     *
     * <p>The one call that does need the conversation ID.
     */
    void disconnect(String genesysConversationId);

    /**
     * Everything the agent needs on pickup, so they never open a blank screen.
     *
     * <p>Sent as {@code channel.metadata.customAttributes} alongside the first
     * message, and read by the Architect flow with Get Participant Data.
     *
     * <p>{@code residentName}, {@code mobile} and {@code email} are in flight
     * only. They reach Genesys, which is the system of record for the agent's
     * view of the customer, and are not persisted here.
     */
    record EscalationContext(
            String chatJourneyId,
            String handoffId,
            int sequence,
            String elevenLabsConversationId,
            String customerId,
            String residentName,
            String mobile,
            String email,
            String escalationReason,
            String conversationSummary,
            String previousResolutionSummary) {
    }
}
