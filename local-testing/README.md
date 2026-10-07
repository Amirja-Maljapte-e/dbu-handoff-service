# Local testing

Drives the handoff flow against a locally running service, with both vendors
replaced by the logging stand-ins (`handoff.vendors.mode=logging`). Nothing
leaves the machine: ElevenLabs and Genesys calls are written to the log instead
of being sent.

Run everything from this folder in PowerShell.

Turn the logging up first, or the stand-ins' output will be invisible. In
`src/main/resources/application.properties`:

    logging.level.com.dbu.handoff=DEBUG

---

## 1. Escalate

The call an ElevenLabs webhook tool makes when the customer asks for a human.

    curl.exe -X POST http://localhost:8080/v1/handoffs ^
      -H "Content-Type: application/json" ^
      -d "@escalate.json"

Expect `202 Accepted` and a body like:

    {"chat_journey_id":"CHAT-...","handoff_id":"HO-...","state":"QUEUED"}

In the log, in this order:

- `escalation requested for conversation EL-LOCAL-001`
- `handoff HO-... started for journey CHAT-... (sequence 1)`
- the stand-in muting the AI and opening a monitoring socket
- the stand-in sending "Connecting you to one of our team. One moment."
- the stand-in creating a Genesys interaction

Keep the `chat_journey_id`. You will want it for the SQL below.

### Repeat the call

Run it again unchanged. The delivery key is `EL-LOCAL-001:turn-3`, so the ledger
suppresses it:

    duplicate escalation for conversation EL-LOCAL-001 — ignoring

No second handoff, no second Genesys interaction. Change `agent_turn` to `4` in
`escalate.json` and it is treated as a genuine second escalation instead —
which is the whole argument for having that field.

---

## 2. Agent replies

What Genesys POSTs when a human agent types. Needs a real HMAC signature, hence
the script:

    .\post-webhook.ps1

Expect `200 OK`. In the log, the stand-in relaying the agent's text back into
the customer's chat.

### Things worth trying

**A duplicate delivery.** Run it a second time unchanged. The ledger suppresses
it on `id`:

    duplicate Genesys delivery gen-delivery-0001 — ignoring

**A tampered body.** Edit the `text` in `agent-message.json`, then post it with
the signature from the unmodified file:

    .\post-webhook.ps1 -Secret wrong-secret

`401`. The signature is checked before the body is parsed.

**An unknown customer.** Change `channel.to.id` to something with no active
handoff. `200 OK` — a webhook is acknowledged, never rejected, for something
the service simply cannot resolve — with a warning in the log:

    no active handoff for customer ... — ignoring agent message

**A bot message.** Change `originatingEntity` to `Bot`. Accepted and ignored:
an Architect flow's own text must not be relayed, or the customer sees it twice.

---

## 3. Check the database

    psql -U handoff -d handoff

    SELECT chat_journey_id, state, belong_customer_ref FROM chat_journey;

    SELECT handoff_id, sequence, customer_ref, genesys_conversation_id,
           outcome, started_at, completed_at
      FROM handoff;

    SELECT event_key, source, status, attempts FROM processed_event
     ORDER BY processed_at DESC LIMIT 10;

The third one is the ledger. Every delivery you posted should appear exactly
once, with `status = DONE`. A second row for the same key would mean duplicate
suppression is broken.

---

## What this cannot test

The parts that need the real vendors, and so are blocked on the three
environment dependencies:

- **Agent accepted, completed, disconnected.** These do not arrive on the
  webhook at all. They come from the Genesys Notifications API over a second
  WebSocket, which is not built yet. So the journey stays in `QUEUED` here and
  never reaches `AGENT_ACTIVE`.
- **Customer messages during a handoff.** They arrive on the ElevenLabs
  monitoring socket, which needs an Enterprise entitlement.
- **Queue-wait buffering and flush.** Depends on both of the above.

Those paths are covered by `HandoffOrchestratorTest` against the recording
fakes. This folder covers the two edges that are real today: the inbound
escalation, and the inbound Genesys webhook.
