package com.dbu.handoff.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One customer chat journey — the vendor-independent parent record.
 *
 * <p>Deliberately not keyed on either vendor's conversation ID, so the journey
 * survives changes to either platform's session model. A journey holds zero,
 * one or several {@link Handoff} episodes over its lifetime.
 */
@Entity
@Table(name = "chat_journey")
public class ChatJourney {

    @Id
    @Column(name = "chat_journey_id", length = 64)
    private String chatJourneyId;

    /** The active customer-facing ElevenLabs conversation. */
    @Column(name = "elevenlabs_conversation_id", nullable = false, length = 128, unique = true)
    private String elevenLabsConversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private HandoffState state = HandoffState.AI_ACTIVE;

    /** Context lineage only — never used for live orchestration. */
    @Column(name = "belong_customer_ref", length = 128)
    private String belongCustomerRef;

    @Column(name = "belong_property_ref", length = 128)
    private String belongPropertyRef;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** Optimistic locking — webhooks and socket events can race. */
    @Version
    @Column(name = "version")
    private Long version;

    /** Append only. A completed handoff is never overwritten. */
    @OneToMany(mappedBy = "chatJourney", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("sequence ASC")
    private List<Handoff> handoffs = new ArrayList<>();

    protected ChatJourney() {
    }

    public ChatJourney(String chatJourneyId, String elevenLabsConversationId) {
        this.chatJourneyId = chatJourneyId;
        this.elevenLabsConversationId = elevenLabsConversationId;
    }

    public static ChatJourney startedBy(String elevenLabsConversationId) {
        return new ChatJourney("CHAT-" + UUID.randomUUID(), elevenLabsConversationId);
    }

    /**
     * Applies a trigger if it is legal in the current state.
     *
     * @return the new state, or empty if the trigger was not legal — which
     *         normally means a duplicate delivery and should be acknowledged
     *         rather than retried.
     */
    public Optional<HandoffState> apply(HandoffTrigger trigger) {
        Optional<HandoffState> next = HandoffStateMachine.next(state, trigger);
        next.ifPresent(s -> {
            this.state = s;
            this.updatedAt = Instant.now();
        });
        return next;
    }

    /** The handoff episode currently in progress, if any. */
    public Optional<Handoff> activeHandoff() {
        return handoffs.stream().filter(Handoff::isActive).findFirst();
    }

    public Handoff startHandoff(String escalationReason) {
        if (activeHandoff().isPresent()) {
            throw new IllegalStateException(
                    "journey " + chatJourneyId + " already has an active handoff");
        }
        // The customer reference is denormalised onto the episode so an inbound
        // Genesys agent message, which carries no conversation ID, can still be
        // resolved. setBelongCustomerRef must therefore run before this.
        Handoff handoff = new Handoff(
                this, handoffs.size() + 1, escalationReason, belongCustomerRef);
        handoffs.add(handoff);
        return handoff;
    }

    public MessageDestination routeCustomerMessage() {
        return HandoffStateMachine.routeCustomerMessage(state);
    }

    public String getChatJourneyId() {
        return chatJourneyId;
    }

    public String getElevenLabsConversationId() {
        return elevenLabsConversationId;
    }

    public HandoffState getState() {
        return state;
    }

    public List<Handoff> getHandoffs() {
        return List.copyOf(handoffs);
    }

    public String getBelongCustomerRef() {
        return belongCustomerRef;
    }

    public void setBelongCustomerRef(String belongCustomerRef) {
        this.belongCustomerRef = belongCustomerRef;
    }

    public String getBelongPropertyRef() {
        return belongPropertyRef;
    }

    public void setBelongPropertyRef(String belongPropertyRef) {
        this.belongPropertyRef = belongPropertyRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
