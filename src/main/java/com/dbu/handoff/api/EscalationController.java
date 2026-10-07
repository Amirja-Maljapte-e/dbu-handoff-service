package com.dbu.handoff.api;

import com.dbu.handoff.domain.ChatJourney;
import com.dbu.handoff.domain.Handoff;
import com.dbu.handoff.domain.HandoffState;
import com.dbu.handoff.service.EscalationCommand;
import com.dbu.handoff.service.HandoffOrchestrator;
import jakarta.validation.Valid;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Channel 1: the customer has asked for a human.
 *
 * <p>Returns as soon as the handoff is recorded. Opening the socket and
 * creating the Genesys interaction happen within the call but are not allowed
 * to fail it — the AI's tool call should not block on queue creation.
 */
@RestController
@RequestMapping("/v1/handoffs")
public class EscalationController {

    private static final Logger log = LoggerFactory.getLogger(EscalationController.class);

    private final HandoffOrchestrator orchestrator;

    public EscalationController(HandoffOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping
    public ResponseEntity<EscalationResponse> escalate(@Valid @RequestBody EscalationRequest request) {
        log.info("escalation requested for conversation {}", request.elevenLabsConversationId());

        if (request.hasWeakDeliveryKey()) {
            log.warn("escalation for conversation {} carries no agent_turn — a repeat "
                            + "escalation in this chat will be suppressed as a duplicate",
                    request.elevenLabsConversationId());
        }

        Optional<ChatJourney> journey = orchestrator.requestHandoff(new EscalationCommand(
                request.elevenLabsConversationId(),
                request.customerId(),
                request.residentName(),
                request.mobile(),
                request.email(),
                request.escalationReason(),
                request.conversationSummary(),
                request.deliveryKey()));

        if (journey.isEmpty()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).build();
        }

        ChatJourney found = journey.get();
        if (found.getState() == HandoffState.CLOSED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        String handoffId = found.activeHandoff().map(Handoff::getHandoffId).orElse(null);

        return ResponseEntity.accepted().body(new EscalationResponse(
                found.getChatJourneyId(), handoffId, found.getState().name()));
    }
}
