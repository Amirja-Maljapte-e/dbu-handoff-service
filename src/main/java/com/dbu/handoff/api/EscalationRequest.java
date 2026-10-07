package com.dbu.handoff.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of the escalation call, sent by a webhook tool on the ElevenLabs agent.
 *
 * <p>Belong authenticates the customer and passes their details into the
 * ElevenLabs conversation at launch; the agent forwards them here so the
 * Genesys agent sees a real contact rather than an anonymous chat.
 *
 * <p>Note what happens to those details: {@code residentName}, {@code mobile}
 * and {@code email} are passed through to Genesys and are never persisted or
 * logged by this service. Only the customer identifier is stored.
 */
public record EscalationRequest(

        @NotBlank
        @JsonProperty("elevenlabs_conversation_id")
        String elevenLabsConversationId,

        /** Present only if the agent already knows the journey. Usually absent. */
        @JsonProperty("chat_journey_id")
        String chatJourneyId,

        @NotBlank
        @JsonProperty("customer_id")
        String customerId,

        @NotBlank
        @JsonProperty("resident_name")
        String residentName,

        @NotBlank
        @JsonProperty("mobile")
        String mobile,

        @NotBlank
        @JsonProperty("email")
        String email,

        @NotBlank
        @Size(max = 512)
        @JsonProperty("escalation_reason")
        String escalationReason,

        @NotBlank
        @JsonProperty("conversation_summary")
        String conversationSummary,

        /**
         * Identifies this invocation so a retry does not start a second handoff.
         *
         * <p>ElevenLabs cannot supply a tool call ID on webhook tools, but can
         * supply the agent turn number, which increments within a conversation.
         * That distinguishes a retry, which carries the same turn, from a
         * genuine second escalation later in the same chat, which carries a
         * higher one.
         *
         * <p>Optional in the contract, but its absence has a consequence: the
         * key falls back to the conversation ID alone, which is identical for
         * every escalation in a chat. A repeat escalation would then be
         * suppressed as a duplicate and no agent would be assigned.
         */
        @JsonProperty("agent_turn")
        Integer agentTurn) {

    public String deliveryKey() {
        return agentTurn != null
                ? elevenLabsConversationId + ":turn-" + agentTurn
                : elevenLabsConversationId;
    }

    /** True when the fallback key is in use, which cannot survive a repeat escalation. */
    public boolean hasWeakDeliveryKey() {
        return agentTurn == null;
    }
}
