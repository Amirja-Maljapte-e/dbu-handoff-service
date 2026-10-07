# Handoff Service

Middleware that bridges an ElevenLabs chat conversation and a Genesys Cloud CX
human agent, so a customer can escalate to a person and be handed back to the
AI without leaving the chat.

Design: *Solution Design Document — ElevenLabs to Genesys Human Handoff v0.1*.
Prerequisite: *Integration Architecture Note v1.2*.

## Status

Increment 1 of the build order: domain core, persistence, state machine,
idempotency ledger, and the two vendor ports with test doubles.

## Run it

```bash
docker compose up -d      # PostgreSQL and Redis
mvn test                  # unit tests + Testcontainers integration tests
mvn spring-boot:run
```

Docker is needed for the integration tests. Nothing here requires an AZURE
account or vendor credentials.

## Structure

```
domain/       state machine, entities, lineage rules — no Spring
repository/   Spring Data JPA
service/      JourneyService (state + lineage), IdempotencyService (ledger)
port/         ElevenLabsPort, GenesysPort — what the domain needs, in its terms
```

Test doubles for both ports live under `src/test/.../port/`.

## Design decisions worth knowing before you change anything

**The AI is muted at `HANDOFF_REQUESTED`, not `AGENT_ACTIVE`.** `HandoffState`
carries `isAiMuted()`. Muting only when the agent first replies would leave the
AI answering throughout the queue wait.

**Save state first, then act.** `JourneyService` never calls a vendor. It
commits the transition; the caller performs the actions afterwards, and
reconciliation retries anything that failed. The reverse order can mute the AI
with no journey recorded as handed off.

**An illegal trigger is not an error.** Both vendors deliver at least once, so
a trigger with no transition from the current state is almost always a
duplicate. `TransitionResult.applied() == false` means acknowledge and move on.

**Lineage is append-only and one-to-many.** One journey, many handoff episodes.
A Genesys conversation ID is never overwritten — enforced in the entity and
again by a partial unique index.

**PostgreSQL holds no message content.** State, lineage, identifiers, summaries
and hashes only. The queue-wait buffer goes to Redis with a TTL. ElevenLabs and
Genesys remain the systems of record for conversation content.

**Ports are coarse on purpose.** `muteAi(journeyId)`, not
`sendCommand(conversationId, "enable_human_takeover")`. A surprise in the
payload format should change one adapter class, not the domain.

## Open decisions marked in the code

Search for `TODO(product)`. Each is a placeholder for a decision owned by
Product and Operations, currently filled with a reasonable default:

- Out-of-hours and no-agent behaviour
- Agent disconnect: re-queue, or fall back to the AI

## Next increments

| # | Work | 
|---|---|---|
| 2 | Redis: queue-wait buffer, ownership keys, relay pub/sub | 
| 3 | Relay service: routing by state, flush on agent accept | 
| 4 | Reconciliation job | Increment 2 |
| 5 | Inbound controllers, signature verification | 
| 6 | ElevenLabs adapter: socket client, reconnect, keepalive | 
| 7 | Genesys adapter: Open Messaging, OAuth | 


