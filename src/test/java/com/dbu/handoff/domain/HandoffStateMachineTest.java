package com.dbu.handoff.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class HandoffStateMachineTest {

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        void walksFromAiActiveThroughToClosed() {
            HandoffState state = HandoffState.AI_ACTIVE;

            state = advance(state, HandoffTrigger.CUSTOMER_REQUESTS_HUMAN);
            assertThat(state).isEqualTo(HandoffState.HANDOFF_REQUESTED);

            state = advance(state, HandoffTrigger.GENESYS_INTERACTION_CREATED);
            assertThat(state).isEqualTo(HandoffState.QUEUED);

            state = advance(state, HandoffTrigger.AGENT_ACCEPTED);
            assertThat(state).isEqualTo(HandoffState.AGENT_ACTIVE);

            state = advance(state, HandoffTrigger.AGENT_COMPLETED);
            assertThat(state).isEqualTo(HandoffState.RESUMING);

            state = advance(state, HandoffTrigger.AI_CLOSED_CONVERSATION);
            assertThat(state).isEqualTo(HandoffState.CLOSED);
        }
    }

    @Nested
    @DisplayName("AI muting")
    class AiMuting {

        @Test
        void aiIsMutedFromTheMomentTheCustomerAsksForAHuman() {
            // The distinction that matters: muting at HANDOFF_REQUESTED rather
            // than AGENT_ACTIVE means the AI cannot answer during the queue wait.
            assertThat(HandoffState.HANDOFF_REQUESTED.isAiMuted()).isTrue();
            assertThat(HandoffState.QUEUED.isAiMuted()).isTrue();
            assertThat(HandoffState.AGENT_ACTIVE.isAiMuted()).isTrue();
        }

        @Test
        void aiIsLiveBeforeEscalationAndAfterResolution() {
            assertThat(HandoffState.AI_ACTIVE.isAiMuted()).isFalse();
            assertThat(HandoffState.RESUMING.isAiMuted()).isFalse();
        }

        @Test
        void aSocketIsNeededExactlyWhileTheAiIsMuted() {
            for (HandoffState state : HandoffState.values()) {
                assertThat(state.needsSocket()).isEqualTo(state.isAiMuted());
            }
        }
    }

    @Nested
    @DisplayName("Failure paths")
    class FailurePaths {

        @Test
        void queueTimeoutReturnsControlToTheAi() {
            assertThat(advance(HandoffState.QUEUED, HandoffTrigger.QUEUE_TIMEOUT))
                    .isEqualTo(HandoffState.RESUMING);
        }

        @Test
        void noAgentsAvailableReturnsControlToTheAi() {
            assertThat(advance(HandoffState.QUEUED, HandoffTrigger.NO_AGENTS_AVAILABLE))
                    .isEqualTo(HandoffState.RESUMING);
        }

        @Test
        void agentDisconnectReturnsControlToTheAi() {
            assertThat(advance(HandoffState.AGENT_ACTIVE, HandoffTrigger.AGENT_DISCONNECTED))
                    .isEqualTo(HandoffState.RESUMING);
        }

        @Test
        void failureToCreateTheGenesysInteractionFallsBackToTheAi() {
            assertThat(advance(HandoffState.HANDOFF_REQUESTED, HandoffTrigger.GENESYS_CREATE_FAILED))
                    .isEqualTo(HandoffState.RESUMING);
        }

        @ParameterizedTest
        @EnumSource(value = HandoffState.class, mode = EnumSource.Mode.EXCLUDE, names = "CLOSED")
        void abandonmentClosesTheJourneyFromAnyLiveState(HandoffState from) {
            assertThat(advance(from, HandoffTrigger.CUSTOMER_ABANDONED))
                    .isEqualTo(HandoffState.CLOSED);
        }

        @Test
        void everyEndingTriggerHasARecordedOutcome() {
            assertThat(HandoffStateMachine.outcomeFor(HandoffTrigger.AGENT_COMPLETED))
                    .contains(HandoffOutcome.COMPLETED);
            assertThat(HandoffStateMachine.outcomeFor(HandoffTrigger.QUEUE_TIMEOUT))
                    .contains(HandoffOutcome.TIMEOUT);
            assertThat(HandoffStateMachine.outcomeFor(HandoffTrigger.CUSTOMER_ABANDONED))
                    .contains(HandoffOutcome.ABANDONED);
            assertThat(HandoffStateMachine.outcomeFor(HandoffTrigger.AGENT_ACCEPTED))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Re-escalation")
    class ReEscalation {

        @Test
        void customerCanAskForAHumanAgainAfterResolution() {
            assertThat(advance(HandoffState.RESUMING, HandoffTrigger.CUSTOMER_REQUESTS_HUMAN))
                    .isEqualTo(HandoffState.HANDOFF_REQUESTED);
        }

        @Test
        void aJourneyAccumulatesHandoffsRatherThanReplacingThem() {
            ChatJourney journey = new ChatJourney("CHAT-001", "EL-001");

            Handoff first = journey.startHandoff("billing query");
            first.attachGenesysConversation("GEN-001");
            first.complete(HandoffOutcome.COMPLETED, "refund raised, SR-4471");

            Handoff second = journey.startHandoff("follow-up on refund");
            second.attachGenesysConversation("GEN-002");

            assertThat(journey.getHandoffs()).hasSize(2);
            assertThat(first.getGenesysConversationId()).isEqualTo("GEN-001");
            assertThat(second.isActive()).isTrue();
            assertThat(journey.activeHandoff()).contains(second);
        }

        @Test
        void aCompletedGenesysInteractionIdIsNeverOverwritten() {
            ChatJourney journey = new ChatJourney("CHAT-002", "EL-002");
            Handoff handoff = journey.startHandoff("billing query");
            handoff.attachGenesysConversation("GEN-001");

            assertThatThrownBy(() -> handoff.attachGenesysConversation("GEN-002"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(handoff.getGenesysConversationId()).isEqualTo("GEN-001");
        }

        @Test
        void onlyOneEpisodeCanBeInFlightAtATime() {
            ChatJourney journey = new ChatJourney("CHAT-003", "EL-003");
            journey.startHandoff("first");

            assertThatThrownBy(() -> journey.startHandoff("second"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Duplicate and out-of-order delivery")
    class DuplicateDelivery {

        @Test
        void aRepeatedTriggerIsRejectedRatherThanReapplied() {
            // Both vendors deliver at-least-once. A second AGENT_ACCEPTED after
            // we have already moved on must be ignored, not retried.
            assertThat(HandoffStateMachine.next(
                    HandoffState.AGENT_ACTIVE, HandoffTrigger.AGENT_ACCEPTED))
                    .isEmpty();
        }

        @Test
        void nothingEscapesTheClosedState() {
            for (HandoffTrigger trigger : HandoffTrigger.values()) {
                assertThat(HandoffStateMachine.next(HandoffState.CLOSED, trigger))
                        .as("trigger %s from CLOSED", trigger)
                        .isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("Message routing")
    class MessageRouting {

        @Test
        void messagesTypedDuringTheQueueWaitAreHeld() {
            assertThat(HandoffStateMachine.routeCustomerMessage(HandoffState.HANDOFF_REQUESTED))
                    .isEqualTo(MessageDestination.BUFFER);
            assertThat(HandoffStateMachine.routeCustomerMessage(HandoffState.QUEUED))
                    .isEqualTo(MessageDestination.BUFFER);
        }

        @Test
        void messagesGoToTheAgentOnlyWhileTheAgentIsActive() {
            assertThat(HandoffStateMachine.routeCustomerMessage(HandoffState.AGENT_ACTIVE))
                    .isEqualTo(MessageDestination.GENESYS_AGENT);
        }

        @Test
        void messagesGoToTheAiBeforeEscalationAndAfterResolution() {
            assertThat(HandoffStateMachine.routeCustomerMessage(HandoffState.AI_ACTIVE))
                    .isEqualTo(MessageDestination.ELEVENLABS_AI);
            assertThat(HandoffStateMachine.routeCustomerMessage(HandoffState.RESUMING))
                    .isEqualTo(MessageDestination.ELEVENLABS_AI);
        }

        @ParameterizedTest
        @EnumSource(HandoffState.class)
        void everyStateHasADefinedDestination(HandoffState state) {
            assertThat(HandoffStateMachine.routeCustomerMessage(state)).isNotNull();
        }
    }

    private static HandoffState advance(HandoffState from, HandoffTrigger trigger) {
        return HandoffStateMachine.next(from, trigger)
                .orElseThrow(() -> new AssertionError(
                        "no transition from " + from + " on " + trigger));
    }
}
