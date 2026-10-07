package com.dbu.handoff.service;

import com.dbu.handoff.domain.ChatJourney;
import com.dbu.handoff.domain.Handoff;
import com.dbu.handoff.domain.HandoffOutcome;
import com.dbu.handoff.domain.HandoffState;
import com.dbu.handoff.domain.HandoffStateMachine;
import com.dbu.handoff.domain.HandoffTrigger;
import com.dbu.handoff.repository.ChatJourneyRepository;
import java.util.List;
import java.util.Optional;

import com.dbu.handoff.repository.HandoffRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the journey lifecycle: state transitions and lineage.
 *
 * <p>This class only saves state. It never calls a vendor. That separation is
 * what makes "save first, then act" possible: the caller performs the actions
 * after this method returns and its transaction has committed, and the
 * reconciliation job retries anything that failed.
 */
@Service
public class JourneyService {

    private static final Logger log = LoggerFactory.getLogger(JourneyService.class);

    /** States in which a monitoring socket should exist. */
    private static final List<HandoffState> SOCKET_STATES = List.of(
            HandoffState.HANDOFF_REQUESTED, HandoffState.QUEUED, HandoffState.AGENT_ACTIVE);

    private final ChatJourneyRepository journeys;
    private final HandoffRepository handoffs;


    public JourneyService(ChatJourneyRepository journeys, HandoffRepository handoffs) {
        this.journeys = journeys;
        this.handoffs = handoffs;
    }

    /**
     * Start a handoff, creating the journey if this is the first escalation.
     *
     * <p>Safe to call twice for the same conversation: the second call finds
     * the journey already past AI_ACTIVE, the trigger is not legal, and the
     * existing handoff is returned instead of a new one being created.
     */
    @Transactional
    public Optional<Handoff> requestHandoff(String elevenLabsConversationId,
                                            String customerId,
                                            String escalationReason) {

        ChatJourney journey = journeys.findByElevenLabsConversationId(elevenLabsConversationId)
                .orElseGet(() -> journeys.save(ChatJourney.startedBy(elevenLabsConversationId)));

        journey.setBelongCustomerRef(customerId);

        Optional<HandoffState> moved = journey.apply(HandoffTrigger.CUSTOMER_REQUESTS_HUMAN);
        if (moved.isEmpty()) {
            log.debug("handoff request not legal in state {} for journey {} — treating as duplicate",
                    journey.getState(), journey.getChatJourneyId());
            return journey.activeHandoff();
        }

        Handoff handoff = journey.startHandoff(escalationReason);
        journeys.save(journey);

        log.info("handoff {} started for journey {} (sequence {})",
                handoff.getHandoffId(), journey.getChatJourneyId(), handoff.getSequence());
        return Optional.of(handoff);
    }

    /**
     * Apply a trigger to a journey and record any lineage consequence.
     *
     * <p>Returns an ignored result rather than throwing when the trigger is
     * not legal, because that is what a duplicate webhook looks like.
     */
    @Transactional
    public TransitionResult apply(String chatJourneyId, HandoffTrigger trigger) {
        ChatJourney journey = journeys.findById(chatJourneyId)
                .orElseThrow(() -> new IllegalArgumentException("unknown journey " + chatJourneyId));

        HandoffState from = journey.getState();
        Optional<HandoffState> to = journey.apply(trigger);

        if (to.isEmpty()) {
            log.debug("trigger {} not legal in state {} for journey {} — ignoring",
                    trigger, from, chatJourneyId);
            return TransitionResult.ignored(chatJourneyId, from);
        }

        applyLineage(journey, trigger);
        journeys.save(journey);

        log.info("journey {} moved {} -> {} on {}", chatJourneyId, from, to.get(), trigger);
        return new TransitionResult(true, chatJourneyId, from, to.get());
    }

    /** Records the Genesys interaction ID against the episode that is in flight. */
    @Transactional
    public void attachGenesysConversation(String chatJourneyId, String genesysConversationId) {
        ChatJourney journey = journeys.findById(chatJourneyId)
                .orElseThrow(() -> new IllegalArgumentException("unknown journey " + chatJourneyId));

        journey.activeHandoff().ifPresentOrElse(
                handoff -> {
                    if (handoff.getGenesysConversationId() == null) {
                        handoff.attachGenesysConversation(genesysConversationId);
                        journeys.save(journey);
                    } else {
                        log.debug("journey {} already has interaction {} — ignoring duplicate {}",
                                chatJourneyId, handoff.getGenesysConversationId(), genesysConversationId);
                    }
                },
                () -> log.warn("no active handoff on journey {} to attach interaction {}",
                        chatJourneyId, genesysConversationId));
    }

    @Transactional(readOnly = true)
    public Optional<ChatJourney> byElevenLabsConversation(String elevenLabsConversationId) {
        return journeys.findByElevenLabsConversationId(elevenLabsConversationId);
    }

    @Transactional(readOnly = true)
    public Optional<ChatJourney> byId(String chatJourneyId) {
        return journeys.findById(chatJourneyId);
    }

    /**
     * Genesys webhooks identify the interaction, not the journey, so every
     * inbound event from that side resolves through this lookup.
     */
    @Transactional(readOnly = true)
    public Optional<ChatJourney> byGenesysConversation(String genesysConversationId) {
        return handoffs.findByGenesysConversationId(genesysConversationId)
                .map(Handoff::getChatJourney);
    }

    /** Journeys that should currently hold a monitoring socket. */
    @Transactional(readOnly = true)
    public List<ChatJourney> journeysNeedingSocket() {
        return journeys.findByStateIn(SOCKET_STATES);
    }

    private void applyLineage(ChatJourney journey, HandoffTrigger trigger) {
        Optional<Handoff> active = journey.activeHandoff();
        if (active.isEmpty()) {
            return;
        }
        Handoff handoff = active.get();

        if (trigger == HandoffTrigger.AGENT_ACCEPTED) {
            handoff.markAccepted();
            return;
        }

        // The resolution summary is filled in by the caller once it has the
        // agent's wrap-up; the outcome is known here from the trigger alone.
        HandoffStateMachine.outcomeFor(trigger)
                .ifPresent(outcome -> handoff.complete(outcome, handoff.getResolutionSummary()));
    }

    /** Records what the human segment resolved, for the AI and the next agent. */
    @Transactional
    public void recordResolution(String chatJourneyId, String summary) {
        journeys.findById(chatJourneyId).ifPresent(journey -> journey.getHandoffs().stream()
                .reduce((first, second) -> second)
                .filter(h -> h.getOutcome() == HandoffOutcome.COMPLETED)
                .ifPresent(h -> {
                    h.complete(h.getOutcome(), summary);
                    journeys.save(journey);
                }));
    }

    @Transactional(readOnly = true)
    public Optional<ChatJourney> byActiveCustomerRef(String customerRef) {
        return handoffs.findByCustomerRefAndCompletedAtIsNull(customerRef)
                .map(Handoff::getChatJourney);
    }
}
