package com.dbu.handoff.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Acknowledgement of an escalation. The work continues after the response. */
public record EscalationResponse(

        @JsonProperty("chat_journey_id")
        String chatJourneyId,

        @JsonProperty("handoff_id")
        String handoffId,

        @JsonProperty("state")
        String state) {
}
