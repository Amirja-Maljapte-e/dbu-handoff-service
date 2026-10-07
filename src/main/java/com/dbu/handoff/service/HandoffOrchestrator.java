package com.dbu.handoff.service;

import com.dbu.handoff.coordination.BufferedMessage;
import com.dbu.handoff.coordination.InstanceIdentity;
import com.dbu.handoff.coordination.MessageBuffer;
import com.dbu.handoff.coordination.SocketOwnership;
import com.dbu.handoff.domain.ChatJourney;
import com.dbu.handoff.domain.EventSource;
import com.dbu.handoff.domain.Handoff;
import com.dbu.handoff.domain.HandoffTrigger;
import com.dbu.handoff.domain.MessageDestination;
import com.dbu.handoff.port.ElevenLabsPort;
import com.dbu.handoff.port.GenesysPort;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Ties the state machine to the two vendors.
 *
 * <p>Follows the rule from the design: the state change is saved first, then
 * the actions are performed. {@link JourneyService} owns the transaction and
 * never calls a vendor; this class performs the vendor calls afterwards. If one
 * fails, the saved state still records where the journey should be, and
 * reconciliation can retry.
 */
@Service
public class HandoffOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(HandoffOrchestrator.class);

    private static final String CONNECTING_MESSAGE =
            "Connecting you to one of our team. One moment.";

    private final JourneyService journeys;
    private final IdempotencyService idempotency;
    private final ElevenLabsPort elevenLabs;
    private final GenesysPort genesys;
    private final MessageBuffer buffer;
    private final SocketOwnership ownership;
    private final InstanceIdentity instance;
    private final Clock clock;

    public HandoffOrchestrator(JourneyService journeys,
                               IdempotencyService idempotency,
                               ElevenLabsPort elevenLabs,
                               GenesysPort genesys,
                               MessageBuffer buffer,
                               SocketOwnership ownership,
                               InstanceIdentity instance,
                               Clock clock) {
        this.journeys = journeys;
        this.idempotency = idempotency;
        this.elevenLabs = elevenLabs;
        this.genesys = genesys;
        this.buffer = buffer;
        this.ownership = ownership;
        this.instance = instance;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Escalation
    // ------------------------------------------------------------------

    /**
     * The customer has asked for a human.
     *
     * <p>Mutes the AI before anything else, so it cannot answer while the
     * customer waits in the queue.
     *
     * @return the journey, whether newly escalated or already in progress
     */
    public Optional<ChatJourney> requestHandoff(EscalationCommand command) {
        String key = "el-trigger:" + command.deliveryKey();

        if (!idempotency.claim(key, null, EventSource.ELEVENLABS_TRIGGER)) {
            log.debug("duplicate escalation for conversation {} — ignoring",
                    command.elevenLabsConversationId());
            return journeys.byElevenLabsConversation(command.elevenLabsConversationId());
        }

        Optional<Handoff> started = journeys.requestHandoff(
                command.elevenLabsConversationId(),
                command.customerId(),
                command.escalationReason());

        if (started.isEmpty()) {
            idempotency.markDone(key);
            return journeys.byElevenLabsConversation(command.elevenLabsConversationId());
        }

        ChatJourney journey = journeys
                .byElevenLabsConversation(command.elevenLabsConversationId())
                .orElseThrow();

        muteAiAndOpenSocket(journey);
        createGenesysInteraction(journey, started.get(), command, key);

        idempotency.markDone(key);
        return Optional.of(journey);
    }

    private void muteAiAndOpenSocket(ChatJourney journey) {
        String journeyId = journey.getChatJourneyId();

        if (!ownership.claim(journeyId, instance.id())) {
            log.warn("another instance already owns journey {} — not opening a second socket",
                    journeyId);
            return;
        }

        elevenLabs.openMonitoring(journeyId, journey.getElevenLabsConversationId());
        elevenLabs.muteAi(journeyId);
        elevenLabs.sendAgentMessage(journeyId, CONNECTING_MESSAGE);
    }

    private void createGenesysInteraction(ChatJourney journey,
                                          Handoff handoff,
                                          EscalationCommand command,
                                          String idempotencyKey) {
        String journeyId = journey.getChatJourneyId();
        try {
            GenesysPort.EscalationContext context = new GenesysPort.EscalationContext(
                    journeyId,
                    handoff.getHandoffId(),
                    handoff.getSequence(),
                    journey.getElevenLabsConversationId(),
                    command.customerId(),
                    command.residentName(),
                    command.mobile(),
                    command.email(),
                    command.escalationReason(),
                    command.conversationSummary(),
                    previousResolution(journey));

            Optional<String> conversationId = genesys.createInteraction(context, idempotencyKey);

            if (conversationId.isPresent()) {
                journeys.attachGenesysConversation(journeyId, conversationId.get());
                journeys.apply(journeyId, HandoffTrigger.GENESYS_INTERACTION_CREATED);
            } else {
                // The platform may return the ID later on a webhook instead.
                // Spike item S9 decides which of the two applies.
                log.info("journey {} created without a conversation ID — awaiting webhook",
                        journeyId);
            }
        } catch (RuntimeException e) {
            log.error("could not create Genesys interaction for journey {} — returning to AI",
                    journeyId, e);
            journeys.apply(journeyId, HandoffTrigger.GENESYS_CREATE_FAILED);
            returnToAi(journeyId, "The connection to our team failed. Please continue here.");
        }
    }

    /** The previous episode's outcome, so a repeat agent is not starting cold. */
    private String previousResolution(ChatJourney journey) {
        List<Handoff> all = journey.getHandoffs();
        if (all.size() < 2) {
            return null;
        }
        return all.get(all.size() - 2).getResolutionSummary();
    }

    // ------------------------------------------------------------------
    // Messages from the customer
    // ------------------------------------------------------------------

    /**
     * A customer message arrived on the monitoring socket.
     *
     * <p>Where it goes depends entirely on the journey's state. During the
     * queue wait it is held, because there is no agent to send it to yet.
     */
    public void handleCustomerMessage(String chatJourneyId, String messageId, String text) {
        ChatJourney journey = journeys.byId(chatJourneyId).orElse(null);
        if (journey == null) {
            log.warn("customer message for unknown journey {} — dropping", chatJourneyId);
            return;
        }

        MessageDestination destination = journey.routeCustomerMessage();
        switch (destination) {
            case BUFFER -> {
                buffer.append(chatJourneyId,
                        new BufferedMessage(messageId, text, clock.instant()));
                log.debug("journey {} is waiting for an agent — message held", chatJourneyId);
            }
            case GENESYS_AGENT -> {
                // Open Messaging addresses the customer, not the conversation:
                // it attaches the message to whichever conversation is live for
                // that sender. See GenesysPort.sendCustomerMessage.
                String customerRef = journey.getBelongCustomerRef();
                if (customerRef == null) {
                    log.warn("journey {} is agent-active with no customer reference",
                            chatJourneyId);
                } else {
                    genesys.sendCustomerMessage(customerRef, text, messageId);
                }
            }
            case ELEVENLABS_AI ->
                    log.debug("journey {} is AI-handled — no relay needed", chatJourneyId);
            case DISCARD ->
                    log.debug("journey {} is closed — message discarded", chatJourneyId);
        }
    }

    // ------------------------------------------------------------------
    // Events from Genesys
    // ------------------------------------------------------------------

    /** An agent picked up. Everything held during the wait goes across now. */
    public void handleAgentAccepted(String genesysConversationId, String deliveryKey) {
        withJourney(genesysConversationId, deliveryKey, journey -> {
            String journeyId = journey.getChatJourneyId();
            if (!journeys.apply(journeyId, HandoffTrigger.AGENT_ACCEPTED).applied()) {
                return;
            }
            flushBuffer(journeyId, journey.getBelongCustomerRef());
        });
    }

    /** The agent replied. Put it in the customer's chat. */
    public void handleAgentMessage(String genesysConversationId, String text,
                                   String messageId, String deliveryKey) {
        withJourney(genesysConversationId, deliveryKey, journey ->
                elevenLabs.sendAgentMessage(journey.getChatJourneyId(), text));
    }

    /** The agent finished. Hand the customer back to the AI. */
    public void handleAgentCompleted(String genesysConversationId, String resolutionSummary,
                                     String deliveryKey) {
        withJourney(genesysConversationId, deliveryKey, journey -> {
            String journeyId = journey.getChatJourneyId();
            if (!journeys.apply(journeyId, HandoffTrigger.AGENT_COMPLETED).applied()) {
                return;
            }
            if (resolutionSummary != null) {
                journeys.recordResolution(journeyId, resolutionSummary);
            }
            returnToAi(journeyId, resolutionSummary);
        });
    }

    /** The agent's connection dropped. Same destination, different reason. */
    public void handleAgentDisconnected(String genesysConversationId, String deliveryKey) {
        withJourney(genesysConversationId, deliveryKey, journey -> {
            String journeyId = journey.getChatJourneyId();
            if (!journeys.apply(journeyId, HandoffTrigger.AGENT_DISCONNECTED).applied()) {
                return;
            }
            returnToAi(journeyId, "The agent was disconnected. Continuing here.");
        });
    }

    /** The customer left. Close everything down. */
    public void handleCustomerAbandoned(String chatJourneyId) {
        // Read the conversation ID before the transition, not after. Applying
        // CUSTOMER_ABANDONED completes the episode, so activeHandoff() is empty
        // by the time the trigger returns and there would be nothing left to
        // disconnect — the agent would sit waiting on a customer who has gone.
        String genesysConversationId = journeys.byId(chatJourneyId)
                .flatMap(ChatJourney::activeHandoff)
                .map(Handoff::getGenesysConversationId)
                .orElse(null);

        if (!journeys.apply(chatJourneyId, HandoffTrigger.CUSTOMER_ABANDONED).applied()) {
            return;
        }

        if (genesysConversationId != null) {
            genesys.disconnect(genesysConversationId);
        }

        buffer.discard(chatJourneyId);
        closeSocket(chatJourneyId);
    }

    // ------------------------------------------------------------------
    // Shared steps
    // ------------------------------------------------------------------

    private void flushBuffer(String chatJourneyId, String customerRef) {
        List<BufferedMessage> held = buffer.drain(chatJourneyId);
        if (customerRef == null && !held.isEmpty()) {
            log.warn("journey {} has {} held messages but no customer reference — not flushed",
                    chatJourneyId, held.size());
            return;
        }
        for (BufferedMessage message : held) {
            genesys.sendCustomerMessage(customerRef, message.text(), message.messageId());
        }
        if (!held.isEmpty()) {
            log.info("flushed {} queue-wait messages for journey {}", held.size(), chatJourneyId);
        }
    }

    private void returnToAi(String chatJourneyId, String context) {
        if (context != null && !context.isBlank()) {
            elevenLabs.sendContext(chatJourneyId, context);
        }
        elevenLabs.unmuteAi(chatJourneyId);
        closeSocket(chatJourneyId);
    }

    private void closeSocket(String chatJourneyId) {
        elevenLabs.closeMonitoring(chatJourneyId);
        ownership.release(chatJourneyId, instance.id());
    }

    /**
     * Resolves a Genesys event to its journey, suppressing duplicates.
     *
     * <p>An unknown conversation or a repeat delivery is logged and ignored
     * rather than treated as an error — both vendors deliver at least once.
     */
    private void withJourney(String genesysConversationId, String deliveryKey,
                             java.util.function.Consumer<ChatJourney> action) {
        Optional<ChatJourney> found = journeys.byGenesysConversation(genesysConversationId);
        if (found.isEmpty()) {
            log.warn("no journey for Genesys conversation {} — ignoring", genesysConversationId);
            return;
        }
        ChatJourney journey = found.get();

        String key = "gen:" + deliveryKey;
        if (!idempotency.claim(key, journey.getChatJourneyId(), EventSource.GENESYS_WEBHOOK)) {
            log.debug("duplicate Genesys delivery {} — ignoring", deliveryKey);
            return;
        }

        action.accept(journey);
        idempotency.markDone(key);
    }

    /**
     * An agent replied. Resolved by customer reference, because the Genesys
     * webhook does not carry the conversation ID.
     */
    public void handleAgentMessageForCustomer(String customerRef, String text, String deliveryKey) {
        Optional<ChatJourney> found = journeys.byActiveCustomerRef(customerRef);
        if (found.isEmpty()) {
            log.warn("no active handoff for customer {} — ignoring agent message", customerRef);
            return;
        }
        ChatJourney journey = found.get();

        String key = "gen:" + deliveryKey;
        if (!idempotency.claim(key, journey.getChatJourneyId(), EventSource.GENESYS_WEBHOOK)) {
            log.debug("duplicate Genesys delivery {} — ignoring", deliveryKey);
            return;
        }

        elevenLabs.sendAgentMessage(journey.getChatJourneyId(), text);
        idempotency.markDone(key);
    }
}
