package com.dbu.handoff.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dbu.handoff.domain.ChatJourney;
import com.dbu.handoff.service.EscalationCommand;
import com.dbu.handoff.service.HandoffOrchestrator;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The escalation endpoint in isolation. The orchestrator is mocked, so this
 * covers the HTTP contract only: validation, status codes and field mapping.
 *
 * <p>The contact details in these fixtures are deliberately fictional. The
 * service passes them to Genesys and never stores them, and a test fixture is
 * exactly where a real customer's number would otherwise leak into the repo.
 */
@WebMvcTest(EscalationController.class)
@DisplayName("Escalation endpoint")
class EscalationControllerTest {

    @Autowired MockMvc mvc;

    @MockBean HandoffOrchestrator orchestrator;

    private static final String VALID = """
            {
              "elevenlabs_conversation_id": "EL-001",
              "customer_id": "BLG-CUST-8812",
              "resident_name": "Asha Menon",
              "mobile": "+971500000000",
              "email": "asha.menon@example.com",
              "escalation_reason": "Customer asked for a person",
              "conversation_summary": "Queried the March service charge",
              "agent_turn": 3
            }
            """;

    @Test
    void acceptsAValidEscalation() throws Exception {
        ChatJourney journey = ChatJourney.startedBy("EL-001");
        when(orchestrator.requestHandoff(any())).thenReturn(Optional.of(journey));

        mvc.perform(post("/v1/handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.chat_journey_id").value(journey.getChatJourneyId()))
                .andExpect(jsonPath("$.state").value("AI_ACTIVE"));
    }

    @Test
    void passesTheRequestThroughAsACommand() throws Exception {
        when(orchestrator.requestHandoff(any()))
                .thenReturn(Optional.of(ChatJourney.startedBy("EL-001")));

        mvc.perform(post("/v1/handoffs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID));

        ArgumentCaptor<EscalationCommand> captor =
                ArgumentCaptor.forClass(EscalationCommand.class);
        verify(orchestrator).requestHandoff(captor.capture());

        EscalationCommand command = captor.getValue();
        assertThat(command.elevenLabsConversationId()).isEqualTo("EL-001");
        assertThat(command.customerId()).isEqualTo("BLG-CUST-8812");
        assertThat(command.residentName()).isEqualTo("Asha Menon");
        assertThat(command.mobile()).isEqualTo("+971500000000");
        assertThat(command.email()).isEqualTo("asha.menon@example.com");
        assertThat(command.deliveryKey()).isEqualTo("EL-001:turn-3");
    }

    @Test
    void rejectsARequestWithNoConversationId() throws Exception {
        mvc.perform(post("/v1/handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customer_id": "BLG-CUST-8812",
                                  "resident_name": "Asha Menon",
                                  "mobile": "+971500000000",
                                  "email": "asha.menon@example.com",
                                  "escalation_reason": "Customer asked for a person",
                                  "conversation_summary": "Queried the March service charge"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.elevenLabsConversationId").exists());
    }

    @Test
    void rejectsARequestWithNoEscalationReason() throws Exception {
        mvc.perform(post("/v1/handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "elevenlabs_conversation_id": "EL-001",
                                  "customer_id": "BLG-CUST-8812",
                                  "resident_name": "Asha Menon",
                                  "mobile": "+971500000000",
                                  "email": "asha.menon@example.com",
                                  "conversation_summary": "Queried the March service charge"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.escalationReason").exists());
    }

    @Test
    void rejectsARequestMissingTheContactDetails() throws Exception {
        // Belong passes these in at conversation launch, so their absence means
        // the agent was not given them — the Genesys agent would open a blank
        // screen. Better a 400 the ElevenLabs team can see than a silent gap.
        mvc.perform(post("/v1/handoffs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "elevenlabs_conversation_id": "EL-001",
                                  "customer_id": "BLG-CUST-8812",
                                  "escalation_reason": "Customer asked for a person",
                                  "conversation_summary": "Queried the March service charge"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.residentName").exists())
                .andExpect(jsonPath("$.fields.mobile").exists())
                .andExpect(jsonPath("$.fields.email").exists());
    }

    @Test
    void theAgentTurnDistinguishesARetryFromASecondEscalation() {
        EscalationRequest first = request(3);
        EscalationRequest retryOfFirst = request(3);
        EscalationRequest later = request(7);

        assertThat(first.deliveryKey()).isEqualTo(retryOfFirst.deliveryKey());
        assertThat(first.deliveryKey()).isNotEqualTo(later.deliveryKey());
        assertThat(first.hasWeakDeliveryKey()).isFalse();
    }

    @Test
    void fallsBackToTheConversationIdWhenNoAgentTurnIsSupplied() {
        // The fallback is identical for every escalation in a chat, so a second
        // escalation would be suppressed as a duplicate. The controller logs a
        // warning when this is the case; hasWeakDeliveryKey is what it checks.
        EscalationRequest request = request(null);

        assertThat(request.deliveryKey()).isEqualTo("EL-001");
        assertThat(request.hasWeakDeliveryKey()).isTrue();
    }

    private static EscalationRequest request(Integer agentTurn) {
        return new EscalationRequest(
                "EL-001",
                null,
                "BLG-CUST-8812",
                "Asha Menon",
                "+971500000000",
                "asha.menon@example.com",
                "Customer asked for a person",
                "Queried the March service charge",
                agentTurn);
    }
}
