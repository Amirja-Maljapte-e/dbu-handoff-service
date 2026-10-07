package com.dbu.handoff.coordination;

/**
 * A message forwarded from the instance that received it to the instance that
 * owns the socket.
 *
 * <p>Carries correlation so a log line on the receiving side can be tied to the
 * journey. The text is in transit only and is never persisted.
 */
public record RelayMessage(
        String chatJourneyId,
        String handoffId,
        String traceId,
        String originInstance,
        Type type,
        String messageId,
        String text) {

    public enum Type {
        /** An agent's reply, bound for the customer's chat. */
        AGENT_MESSAGE,

        /** Context for the AI before it resumes. */
        CONTEXT_UPDATE,

        /** Stop the AI answering. */
        MUTE_AI,

        /** Let the AI answer again. */
        UNMUTE_AI
    }
}
