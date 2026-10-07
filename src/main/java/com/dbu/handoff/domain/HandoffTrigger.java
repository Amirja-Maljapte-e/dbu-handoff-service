package com.dbu.handoff.domain;

/**
 * Events that can move a chat journey between states.
 *
 * <p>Each trigger names its origin so it is obvious which external system
 * has to be working for the transition to ever fire.
 */
public enum HandoffTrigger {

    /** ElevenLabs webhook tool fires when the customer asks for a human. */
    CUSTOMER_REQUESTS_HUMAN,

    /** We successfully created and routed the Genesys interaction. */
    GENESYS_INTERACTION_CREATED,

    /** Creating the Genesys interaction failed. Fall back to the AI. */
    GENESYS_CREATE_FAILED,

    /** Genesys event: an agent picked up the interaction. */
    AGENT_ACCEPTED,

    /** Genesys event: agent wrapped up after the customer confirmed. */
    AGENT_COMPLETED,

    /** Genesys event: agent connection lost mid-conversation. */
    AGENT_DISCONNECTED,

    /** No agent accepted within the configured window. */
    QUEUE_TIMEOUT,

    /** Queue is closed or has no eligible agents. */
    NO_AGENTS_AVAILABLE,

    /** Customer closed the chat or went away. Can arrive in any state. */
    CUSTOMER_ABANDONED,

    /** AI finished the survey and closed the conversation. */
    AI_CLOSED_CONVERSATION
}
