package com.dbu.handoff.domain;

/**
 * Lifecycle of a single customer chat journey.
 *
 * <p>Exactly one state is active at any time. The state decides where an
 * incoming customer message is routed and whether the ElevenLabs AI is
 * permitted to respond.
 */
public enum HandoffState {

    /** AI is answering. No Genesys interaction exists. */
    AI_ACTIVE(false),

    /**
     * Customer has asked for a human. The AI is muted immediately — before
     * any agent is involved — so it cannot answer while the customer waits.
     */
    HANDOFF_REQUESTED(true),

    /** Genesys interaction created and routed. No agent has accepted yet. */
    QUEUED(true),

    /** An agent has accepted. Messages relay in both directions. */
    AGENT_ACTIVE(true),

    /**
     * Human segment finished. Resolution context has been pushed back to the
     * AI, which handles the closing acknowledgement and survey.
     */
    RESUMING(false),

    /** Terminal. No further messages are relayed. */
    CLOSED(false);

    private final boolean aiMuted;

    HandoffState(boolean aiMuted) {
        this.aiMuted = aiMuted;
    }

    /**
     * Whether ElevenLabs human takeover should be enabled in this state.
     *
     * <p>True from {@link #HANDOFF_REQUESTED} onward, not from
     * {@link #AGENT_ACTIVE}. Muting only when the agent first replies would
     * leave the AI answering throughout the queue wait.
     */
    public boolean isAiMuted() {
        return aiMuted;
    }

    public boolean isTerminal() {
        return this == CLOSED;
    }

    /** True while a monitoring socket should be open for this journey. */
    public boolean needsSocket() {
        return aiMuted;
    }
}
