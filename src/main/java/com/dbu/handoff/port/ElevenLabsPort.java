package com.dbu.handoff.port;

/**
 * What the service needs from ElevenLabs, stated in its own terms.
 *
 * <p>Deliberately coarse. The domain asks to mute the AI; it does not know
 * that this is a JSON command on a WebSocket, or what the command is called.
 * When the spike confirms the real event names and envelopes, only the
 * adapter changes.
 */
public interface ElevenLabsPort {

    /** Open the monitoring channel for a conversation. Idempotent. */
    void openMonitoring(String chatJourneyId, String elevenLabsConversationId);

    /** Stop the AI answering. Sent on entering the handoff, not on agent reply. */
    void muteAi(String chatJourneyId);

    /** Let the AI answer again. */
    void unmuteAi(String chatJourneyId);

    /** Put a human agent's message into the customer's chat. */
    void sendAgentMessage(String chatJourneyId, String text);

    /**
     * Give the AI context it should know before resuming — typically what the
     * human agent resolved, so the AI does not contradict them.
     */
    void sendContext(String chatJourneyId, String text);

    /** Close the monitoring channel and release any resources. Idempotent. */
    void closeMonitoring(String chatJourneyId);
}
