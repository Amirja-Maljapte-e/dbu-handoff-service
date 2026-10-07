package com.dbu.handoff.domain;

/** Destination for an inbound customer message. */
public enum MessageDestination {

    /** Let the AI handle it. The service is not in the message path. */
    ELEVENLABS_AI,

    /** Relay to the active Genesys interaction. */
    GENESYS_AGENT,

    /** Hold in Redis until an agent accepts, then flush in order. */
    BUFFER,

    /** Journey is closed. Log and drop. */
    DISCARD
}
