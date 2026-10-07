package com.dbu.handoff.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbu.handoff.domain.ChatJourney;
import com.dbu.handoff.domain.EventSource;
import com.dbu.handoff.domain.HandoffOutcome;
import com.dbu.handoff.domain.HandoffState;
import com.dbu.handoff.domain.HandoffTrigger;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exercises the journey lifecycle against a real PostgreSQL, so the Flyway
 * migration and the JPA mapping are verified together.
 *
 * <p>Runs against a local PostgreSQL on the test profile. Each test is rolled
 * back, so the database is left clean.
 *
 * <p>One exception: IdempotencyService writes in its own transaction, so its
 * rows survive the rollback. That test uses a key unique to the run.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JourneyServiceIT {

    @Autowired
    JourneyService journeys;

    @Autowired
    IdempotencyService idempotency;

    @Test
    void createsAJourneyAndAHandoffOnFirstEscalation() {
        var handoff = journeys.requestHandoff("EL-100", "CUST-1", "billing query");

        assertThat(handoff).isPresent();
        assertThat(handoff.get().getSequence()).isEqualTo(1);

        ChatJourney journey = journeys.byElevenLabsConversation("EL-100").orElseThrow();
        assertThat(journey.getState()).isEqualTo(HandoffState.HANDOFF_REQUESTED);
        assertThat(journey.getBelongCustomerRef()).isEqualTo("CUST-1");
    }

    @Test
    void aRepeatedEscalationRequestDoesNotCreateASecondHandoff() {
        journeys.requestHandoff("EL-101", "CUST-2", "billing query");
        journeys.requestHandoff("EL-101", "CUST-2", "billing query");

        ChatJourney journey = journeys.byElevenLabsConversation("EL-101").orElseThrow();
        assertThat(journey.getHandoffs()).hasSize(1);
    }

    @Test
    void walksTheFullLifecycleAndRecordsTheOutcome() {
        var handoff = journeys.requestHandoff("EL-102", "CUST-3", "meter reading")
                .orElseThrow();
        String journeyId = journeys.byElevenLabsConversation("EL-102").orElseThrow()
                .getChatJourneyId();

        journeys.attachGenesysConversation(journeyId, "GEN-102");
        assertThat(journeys.apply(journeyId, HandoffTrigger.GENESYS_INTERACTION_CREATED).applied())
                .isTrue();
        assertThat(journeys.apply(journeyId, HandoffTrigger.AGENT_ACCEPTED).applied()).isTrue();
        assertThat(journeys.apply(journeyId, HandoffTrigger.AGENT_COMPLETED).applied()).isTrue();

        ChatJourney journey = journeys.byId(journeyId).orElseThrow();
        assertThat(journey.getState()).isEqualTo(HandoffState.RESUMING);
        assertThat(journey.getHandoffs().get(0).getOutcome()).isEqualTo(HandoffOutcome.COMPLETED);
        assertThat(journey.getHandoffs().get(0).getAcceptedAt()).isNotNull();
        assertThat(journey.getHandoffs().get(0).getGenesysConversationId()).isEqualTo("GEN-102");
        assertThat(handoff.getHandoffId()).isNotBlank();
    }

    @Test
    void anIllegalTriggerIsIgnoredRatherThanFailing() {
        journeys.requestHandoff("EL-103", "CUST-4", "query");
        String journeyId = journeys.byElevenLabsConversation("EL-103").orElseThrow()
                .getChatJourneyId();

        TransitionResult result = journeys.apply(journeyId, HandoffTrigger.AGENT_COMPLETED);

        assertThat(result.applied()).isFalse();
        assertThat(result.from()).isEqualTo(HandoffState.HANDOFF_REQUESTED);
    }

    @Test
    void aRepeatEscalationCreatesASecondEpisodeUnderTheSameJourney() {
        journeys.requestHandoff("EL-104", "CUST-5", "first issue");
        String journeyId = journeys.byElevenLabsConversation("EL-104").orElseThrow()
                .getChatJourneyId();

        journeys.apply(journeyId, HandoffTrigger.GENESYS_INTERACTION_CREATED);
        journeys.apply(journeyId, HandoffTrigger.AGENT_ACCEPTED);
        journeys.apply(journeyId, HandoffTrigger.AGENT_COMPLETED);

        var second = journeys.requestHandoff("EL-104", "CUST-5", "same issue again");

        assertThat(second).isPresent();
        assertThat(second.get().getSequence()).isEqualTo(2);
        assertThat(journeys.byId(journeyId).orElseThrow().getHandoffs()).hasSize(2);
    }

    @Test
    void theLatestCustomerReferenceWins() {
        // Belong could correct the identifier mid-chat. The journey carries
        // whatever ElevenLabs sent most recently, so the webhook lookup in
        // GenesysWebhookController keeps resolving.
        journeys.requestHandoff("EL-105", "CUST-6", "first issue");
        String journeyId = journeys.byElevenLabsConversation("EL-105").orElseThrow()
                .getChatJourneyId();

        journeys.apply(journeyId, HandoffTrigger.GENESYS_INTERACTION_CREATED);
        journeys.apply(journeyId, HandoffTrigger.AGENT_ACCEPTED);
        journeys.apply(journeyId, HandoffTrigger.AGENT_COMPLETED);

        journeys.requestHandoff("EL-105", "CUST-6-CORRECTED", "same issue again");

        assertThat(journeys.byId(journeyId).orElseThrow().getBelongCustomerRef())
                .isEqualTo("CUST-6-CORRECTED");
    }

    @Test
    void theLedgerSuppressesARepeatedDelivery() {
        // The ledger writes in its own transaction, so these rows outlive the
        // rollback. A key unique to the run keeps the test repeatable.
        String key = "gen:msg-" + java.util.UUID.randomUUID();

        assertThat(idempotency.claim(key, null, EventSource.GENESYS_WEBHOOK)).isTrue();
        idempotency.markDone(key);

        assertThat(idempotency.claim(key, null, EventSource.GENESYS_WEBHOOK)).isFalse();
    }

    @Test
    void fallbackKeysAreStableAndCarryNoMessageContent() {
        var now = java.time.Instant.parse("2026-09-21T10:15:30Z");
        String a = IdempotencyService.fallbackKey("EL-1", "user_message", now, "hello");
        String b = IdempotencyService.fallbackKey("EL-1", "user_message", now, "hello");

        assertThat(a).isEqualTo(b).doesNotContain("hello");
        assertThat(Optional.of(a)).isPresent();
    }
}