-- Lineage and state for the DBU handoff service.
--
-- PostgreSQL holds state and lineage only. Message bodies and transcripts are
-- never stored here: ElevenLabs and Genesys remain the systems of record for
-- conversation content. Transient content (the queue-wait buffer) lives in
-- Redis with a TTL.

CREATE TABLE chat_journey (
    chat_journey_id            VARCHAR(64)  PRIMARY KEY,
    elevenlabs_conversation_id VARCHAR(128) NOT NULL UNIQUE,
    state                      VARCHAR(32)  NOT NULL,
    belong_customer_ref        VARCHAR(128),
    belong_property_ref        VARCHAR(128),
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                    BIGINT       NOT NULL DEFAULT 0
);

-- The escalation webhook arrives with the ElevenLabs conversation ID and
-- nothing else, so this is the hot lookup path.
CREATE INDEX idx_chat_journey_el_conversation
    ON chat_journey (elevenlabs_conversation_id);

-- Reconciliation scans for journeys that should hold a live socket.
CREATE INDEX idx_chat_journey_live
    ON chat_journey (state)
    WHERE state <> 'CLOSED';

CREATE TABLE handoff (
    handoff_id              VARCHAR(64)  PRIMARY KEY,
    chat_journey_id         VARCHAR(64)  NOT NULL REFERENCES chat_journey (chat_journey_id),
    sequence                INTEGER      NOT NULL,
    genesys_conversation_id VARCHAR(128),
    escalation_reason       VARCHAR(512),
    resolution_summary      VARCHAR(2048),
    outcome                 VARCHAR(32),
    started_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    accepted_at             TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    CONSTRAINT uq_handoff_sequence UNIQUE (chat_journey_id, sequence)
);

-- Genesys webhooks identify the interaction, not the journey, so we resolve
-- in this direction constantly.
CREATE UNIQUE INDEX idx_handoff_genesys_conversation
    ON handoff (genesys_conversation_id)
    WHERE genesys_conversation_id IS NOT NULL;

-- At most one episode in flight per journey. Enforced here as well as in the
-- domain, so the rule holds even if a code path is wrong.
CREATE UNIQUE INDEX idx_handoff_single_active
    ON handoff (chat_journey_id)
    WHERE completed_at IS NULL;

-- Idempotency ledger. Keys and hashes only; no message content.
CREATE TABLE processed_event (
    event_key       VARCHAR(256) PRIMARY KEY,
    chat_journey_id VARCHAR(64),
    source          VARCHAR(32)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 1,
    processed_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_processed_event_journey ON processed_event (chat_journey_id);
CREATE INDEX idx_processed_event_purge ON processed_event (processed_at);
