package com.dbu.handoff.domain;

/** Which channel delivered an inbound event. Used by the idempotency ledger. */
public enum EventSource {

    /** Channel 1: the ElevenLabs webhook tool that requests a handoff. */
    ELEVENLABS_TRIGGER,

    /** Channel 2: an event on the ElevenLabs monitoring socket. */
    ELEVENLABS_SOCKET,

    /** Channel 4: a Genesys outbound webhook delivery. */
    GENESYS_WEBHOOK
}
