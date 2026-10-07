package com.dbu.handoff.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbu.handoff.coordination.MessageBuffer;
import com.dbu.handoff.domain.ChatJourney;
import com.dbu.handoff.domain.HandoffState;
import com.dbu.handoff.port.RecordingElevenLabs;
import com.dbu.handoff.port.RecordingGenesys;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * The orchestrator against both vendor fakes.
 *
 * <p>Covers the paths that matter most and are easiest to get wrong: the AI
 * being muted before an agent is involved, messages held during the queue wait
 * and flushed on acceptance, and a duplicate escalation not opening a second
 * Genesys interaction.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("Handoff orchestration")
class HandoffOrchestratorTest {

    /**
     * Only what the main context does not already provide.
     *
     * <p>CoordinationConfig supplies the clock, the message buffer and socket
     * ownership; CoordinationProperties comes from @EnableConfigurationProperties
     * with defaults built into the record; InstanceIdentity is a @Component.
     * Redefining any of them here fails the context, because Spring Boot does
     * not allow one bean definition to override another of the same name.
     */
    @TestConfiguration
    static class Fakes {

        @Bean
        RecordingElevenLabs elevenLabs() {
            return new RecordingElevenLabs();
        }

        @Bean
        RecordingGenesys genesys() {
            return new RecordingGenesys();
        }

        /**
         * A fixed clock, so buffered-message timestamps are deterministic.
         *
         * <p>Note the method name: it must differ from CoordinationConfig's
         * {@code clock}, or this is an override rather than an alternative.
         * Two beans of the same type then coexist and @Primary decides which
         * one is injected.
         */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired HandoffOrchestrator orchestrator;
    @Autowired JourneyService journeys;
    @Autowired RecordingElevenLabs elevenLabs;
    @Autowired RecordingGenesys genesys;
    @Autowired MessageBuffer buffer;

    private String conversationId;

    @BeforeEach
    void setUp() {
        elevenLabs.reset();
        // The fakes are singletons in a cached Spring context, so without this
        // the recorded calls accumulate and interactionCount() counts every
        // escalation the whole class has made, not this test's.
        genesys.reset();
        conversationId = "EL-" + java.util.UUID.randomUUID();
    }

    /**
     * A delivery key unique to this test and this run.
     *
     * <p>The ledger writes in its own transaction, so its rows survive the
     * test's rollback and outlive the whole run. A literal key such as
     * "accept-1" is therefore claimed once, by whichever test runs first, and
     * every later use of it — in another test, or tomorrow's run — is correctly
     * suppressed as a duplicate, leaving those tests doing nothing at all.
     *
     * <p>Real delivery keys are unique per delivery. These should be too.
     */
    private String key(String suffix) {
        return conversationId + ":" + suffix;
    }

    @Test
    void mutesTheAiBeforeAnyAgentIsInvolved() {
        ChatJourney journey = escalate().orElseThrow();

        assertThat(journey.getState())
                .isIn(HandoffState.HANDOFF_REQUESTED, HandoffState.QUEUED);
        assertThat(elevenLabs.isMuted(journey.getChatJourneyId())).isTrue();
        assertThat(elevenLabs.hasOpenSocket(journey.getChatJourneyId())).isTrue();
    }

    @Test
    void tellsTheCustomerTheyAreBeingConnected() {
        ChatJourney journey = escalate().orElseThrow();

        assertThat(elevenLabs.calls())
                .filteredOn(call -> call.type().equals("sendAgentMessage"))
                .anySatisfy(call -> assertThat(call.text()).contains("Connecting you"));
        assertThat(journey.getChatJourneyId()).isNotBlank();
    }

    @Test
    void createsOneGenesysInteraction() {
        escalate();
        assertThat(genesys.interactionCount()).isEqualTo(1);
    }

    @Test
    void aRetriedEscalationDoesNotCreateASecondInteraction() {
        escalate();
        escalate();

        assertThat(genesys.interactionCount()).isEqualTo(1);
    }

    @Test
    void holdsCustomerMessagesWhileNobodyHasAccepted() {
        ChatJourney journey = escalate().orElseThrow();
        String journeyId = journey.getChatJourneyId();

        orchestrator.handleCustomerMessage(journeyId, "m1", "are you there?");
        orchestrator.handleCustomerMessage(journeyId, "m2", "hello?");

        assertThat(buffer.size(journeyId)).isEqualTo(2);
        assertThat(genesys.sent()).isEmpty();
    }

    @Test
    void flushesHeldMessagesInOrderWhenAnAgentAccepts() {
        ChatJourney journey = escalate().orElseThrow();
        String journeyId = journey.getChatJourneyId();
        String conversation = conversationIdOf(journeyId);

        orchestrator.handleCustomerMessage(journeyId, "m1", "first");
        orchestrator.handleCustomerMessage(journeyId, "m2", "second");

        orchestrator.handleAgentAccepted(conversation, key("accept"));

        assertThat(genesys.sent())
                .extracting(RecordingGenesys.Sent::text)
                .containsExactly("first", "second");
        assertThat(buffer.size(journeyId)).isZero();
    }

    @Test
    void relaysMessagesBothWaysWhileTheAgentIsActive() {
        ChatJourney journey = escalate().orElseThrow();
        String journeyId = journey.getChatJourneyId();
        String conversation = conversationIdOf(journeyId);

        orchestrator.handleAgentAccepted(conversation, key("accept"));
        orchestrator.handleAgentMessage(conversation, "How can I help?", "a1", key("msg-1"));
        orchestrator.handleCustomerMessage(journeyId, "m3", "my bill is wrong");

        assertThat(elevenLabs.calls())
                .filteredOn(call -> call.type().equals("sendAgentMessage"))
                .anySatisfy(call -> assertThat(call.text()).isEqualTo("How can I help?"));
        assertThat(genesys.sent())
                .extracting(RecordingGenesys.Sent::text)
                .contains("my bill is wrong");
    }

    @Test
    void handsBackToTheAiWhenTheAgentCompletes() {
        ChatJourney journey = escalate().orElseThrow();
        String journeyId = journey.getChatJourneyId();
        String conversation = conversationIdOf(journeyId);

        orchestrator.handleAgentAccepted(conversation, key("accept"));
        orchestrator.handleAgentCompleted(conversation, "Refund raised, SR-4471", key("complete"));

        assertThat(journeys.byId(journeyId).orElseThrow().getState())
                .isEqualTo(HandoffState.RESUMING);
        assertThat(elevenLabs.isMuted(journeyId)).isFalse();
        assertThat(elevenLabs.hasOpenSocket(journeyId)).isFalse();
        assertThat(elevenLabs.calls())
                .filteredOn(call -> call.type().equals("sendContext"))
                .anySatisfy(call -> assertThat(call.text()).contains("SR-4471"));
    }

    @Test
    void aDuplicateAgentMessageIsDeliveredOnlyOnce() {
        ChatJourney journey = escalate().orElseThrow();
        String conversation = conversationIdOf(journey.getChatJourneyId());

        orchestrator.handleAgentAccepted(conversation, key("accept"));
        // Deliberately the same key twice: that is what a redelivery looks like.
        orchestrator.handleAgentMessage(conversation, "Hello", "a1", key("msg-dup"));
        orchestrator.handleAgentMessage(conversation, "Hello", "a1", key("msg-dup"));

        assertThat(elevenLabs.calls())
                .filteredOn(call -> call.type().equals("sendAgentMessage")
                        && "Hello".equals(call.text()))
                .hasSize(1);
    }

    @Test
    void closesEverythingWhenTheCustomerAbandons() {
        ChatJourney journey = escalate().orElseThrow();
        String journeyId = journey.getChatJourneyId();

        orchestrator.handleCustomerMessage(journeyId, "m1", "still here?");
        orchestrator.handleCustomerAbandoned(journeyId);

        assertThat(journeys.byId(journeyId).orElseThrow().getState())
                .isEqualTo(HandoffState.CLOSED);
        assertThat(buffer.size(journeyId)).isZero();
        assertThat(elevenLabs.hasOpenSocket(journeyId)).isFalse();
        assertThat(genesys.disconnected()).isNotEmpty();
    }

    /**
     * The contact details are fictional on purpose: the service passes them to
     * Genesys and never stores them, and a fixture is where a real customer's
     * number would otherwise end up in the repository.
     */
    private java.util.Optional<ChatJourney> escalate() {
        return orchestrator.requestHandoff(new EscalationCommand(
                conversationId,
                "BLG-CUST-8812",
                "Asha Menon",
                "+971500000000",
                "asha.menon@example.com",
                "Customer asked for a person",
                "Customer queried the March service charge",
                conversationId + ":turn-3"));
    }

    private String conversationIdOf(String journeyId) {
        return journeys.byId(journeyId).orElseThrow()
                .activeHandoff().orElseThrow()
                .getGenesysConversationId();
    }
}
