package com.dbu.handoff.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * What Genesys POSTs to the webhook when an agent replies or a channel event
 * occurs. Shape taken from the sample payloads in the Genesys team's Postman
 * collection.
 *
 * <p>Deliberately tolerant of unknown fields: the platform may add more over
 * time, and an unrecognised field must not fail a delivery.
 *
 * <p>Note what is <em>not</em> here: the Genesys conversation ID. The payload
 * identifies the recipient ({@code channel.to.id}, the Belong customer
 * reference) and the integration ({@code channel.id}), but not the
 * conversation. Resolution is therefore by customer reference against the
 * active handoff.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GenesysWebhookPayload(
        String id,
        Channel channel,
        String type,
        String text,
        String direction,
        String originatingEntity,
        List<Event> events) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Channel(
            String id,
            String platform,
            String messageId,
            String time,
            Party to,
            Party from) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Party(String id, String idType, String nickname) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(String eventType) {
    }

    /** The Belong customer reference this message is addressed to. */
    public String customerRef() {
        return channel != null && channel.to() != null ? channel.to().id() : null;
    }

    /**
     * A message typed by a human agent, bound for the customer's chat.
     *
     * <p>Bot messages are ignored: an Architect flow may send its own text, and
     * relaying that would duplicate what the AI already said.
     */
    public boolean isAgentMessage() {
        return "Text".equals(type)
                && "Outbound".equals(direction)
                && "Human".equals(originatingEntity)
                && text != null && !text.isBlank();
    }

    /** Stable across retries, so the idempotency ledger can suppress duplicates. */
    public String deliveryKey() {
        if (id != null && !id.isBlank()) {
            return id;
        }
        return channel != null ? channel.messageId() : null;
    }
}
