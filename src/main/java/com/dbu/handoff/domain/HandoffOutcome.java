package com.dbu.handoff.domain;

/** How a human-assistance episode ended. Drives operational reporting. */
public enum HandoffOutcome {

    /** Agent completed after the customer confirmed no further help was needed. */
    COMPLETED,

    /** No agent accepted within the configured window. */
    TIMEOUT,

    /** Queue closed or no eligible agents. */
    NO_AGENTS,

    /** Agent connection lost mid-conversation. */
    DISCONNECTED,

    /** Customer left before the episode finished. */
    ABANDONED,

    /** The Genesys interaction could not be created. */
    CREATE_FAILED
}
