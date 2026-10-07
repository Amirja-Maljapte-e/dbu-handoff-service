-- Genesys agent-message webhooks do not carry the Genesys conversation ID.
-- The sample payload supplied by the Genesys team identifies the recipient only
-- (channel.to.id = the Belong customer reference), so the service must resolve
-- an inbound agent message to a handoff by customer reference instead.
--
-- The reference is denormalised onto the handoff row so that lookup is a single
-- indexed read, and so the uniqueness rule below can be enforced by the
-- database rather than by application code.

ALTER TABLE handoff ADD COLUMN customer_ref VARCHAR(128);

-- Backfill from the parent journey for any rows created before this migration.
UPDATE handoff h
   SET customer_ref = j.belong_customer_ref
  FROM chat_journey j
 WHERE h.chat_journey_id = j.chat_journey_id
   AND h.customer_ref IS NULL;

-- The hot path for every inbound agent message.
CREATE INDEX idx_handoff_customer_ref ON handoff (customer_ref);

-- Close stale episodes before the uniqueness rule is imposed.
--
-- Rows created before this migration were never subject to the one-active-
-- episode rule, so a customer can legitimately have several open handoffs in an
-- existing database. The newest is kept; the rest are closed as ABANDONED,
-- which is what they are — nothing is listening to them, because the service
-- could not have resolved an inbound message to them in the first place.
--
-- Without this the CREATE UNIQUE INDEX below fails on any database that has
-- ever run an escalation, and the whole migration rolls back.
UPDATE handoff
   SET completed_at = NOW(),
       outcome = COALESCE(outcome, 'ABANDONED')
 WHERE completed_at IS NULL
   AND customer_ref IS NOT NULL
   AND handoff_id NOT IN (
       SELECT DISTINCT ON (customer_ref) handoff_id
         FROM handoff
        WHERE completed_at IS NULL
          AND customer_ref IS NOT NULL
        ORDER BY customer_ref, started_at DESC, handoff_id DESC
   );

-- At most one episode in flight per customer reference.
--
-- Without this, two concurrent journeys for the same customer would make an
-- inbound agent message ambiguous: there would be no way to tell which
-- conversation it belonged to. Enforcing it here means the ambiguity cannot
-- arise, rather than being handled after the fact.
CREATE UNIQUE INDEX idx_handoff_single_active_customer
    ON handoff (customer_ref)
    WHERE completed_at IS NULL AND customer_ref IS NOT NULL;

-- Also useful for the abandonment path: find live journeys for a customer.
CREATE INDEX idx_chat_journey_customer_ref
    ON chat_journey (belong_customer_ref)
    WHERE state <> 'CLOSED';
