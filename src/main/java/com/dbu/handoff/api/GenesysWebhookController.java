package com.dbu.handoff.api;

import com.dbu.handoff.service.HandoffOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Channel 4: agent messages and channel events from Genesys.
 *
 * <p>The body is taken as raw bytes so the signature can be checked against
 * exactly what was received. Letting Spring parse it into an object first and
 * then re-serialising would change whitespace and key order, and no signature
 * would ever match.
 *
 * <p>Acknowledges quickly. Genesys retries on a slow or failed response, so the
 * work must not hold the request open.
 *
 * <p>This endpoint does <em>not</em> carry interaction lifecycle events — agent
 * accepted, completed and disconnected arrive on a separate Notifications API
 * channel. See the design document, section 6.5.
 */
@RestController
@RequestMapping("/genesys/webhook")
public class GenesysWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GenesysWebhookController.class);

    private final GenesysSignatureVerifier verifier;
    private final HandoffOrchestrator orchestrator;
    private final ObjectMapper mapper;

    public GenesysWebhookController(GenesysSignatureVerifier verifier,
                                    HandoffOrchestrator orchestrator,
                                    ObjectMapper mapper) {
        this.verifier = verifier;
        this.orchestrator = orchestrator;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {

        // Verified before parsing, before the ledger, before any database
        // access. An unsigned request must not reach any of them.
        if (!verifier.isValid(signature, body)) {
            log.warn("rejected Genesys webhook: invalid signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        GenesysWebhookPayload payload;
        try {
            payload = mapper.readValue(body, GenesysWebhookPayload.class);
        } catch (Exception e) {
            log.warn("rejected Genesys webhook: body could not be parsed");
            return ResponseEntity.badRequest().build();
        }

        String customerRef = payload.customerRef();
        String deliveryKey = payload.deliveryKey();

        if (customerRef == null || deliveryKey == null) {
            log.warn("ignoring Genesys webhook with no recipient or delivery key");
            return ResponseEntity.ok().build();
        }

        if (payload.isAgentMessage()) {
            orchestrator.handleAgentMessageForCustomer(
                    customerRef, payload.text(), deliveryKey);
        } else {
            // Typing indicators and other channel events. Nothing to relay yet:
            // whether the ElevenLabs takeover feed can show a typing indicator
            // is still open.
            log.debug("Genesys webhook type={} for customer {} — no action",
                    payload.type(), customerRef);
        }

        return ResponseEntity.ok().build();
    }
}
