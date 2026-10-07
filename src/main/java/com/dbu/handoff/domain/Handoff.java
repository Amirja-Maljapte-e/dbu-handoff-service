package com.dbu.handoff.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One human-assistance episode within a {@link ChatJourney}.
 *
 * <p>Records are appended, never overwritten. A repeat request for a human
 * creates a new episode with a new Genesys interaction; the previous agent is
 * not reconnected. Continuity comes from lineage and summaries, not from
 * agent identity.
 */
@Entity
@Table(name = "handoff")
public class Handoff {

    @Id
    @Column(name = "handoff_id", length = 64)
    private String handoffId = "HO-" + UUID.randomUUID();

    @ManyToOne
    @JoinColumn(name = "chat_journey_id", nullable = false)
    private ChatJourney chatJourney;

    /** 1-based position within the journey. */
    @Column(name = "sequence", nullable = false)
    private int sequence;

    /**
     * Set once the interaction exists in Genesys. Null while the create call
     * is in flight — which is why the idempotency key lives on the journey
     * rather than here.
     */
    @Column(name = "genesys_conversation_id", length = 128)
    private String genesysConversationId;

    /**
     * Denormalised from the parent journey. Genesys agent-message webhooks
     * identify the recipient but not the conversation, so this is the only
     * thing available to resolve an inbound message to a handoff.
     */
    @Column(name = "customer_ref", length = 128)
    private String customerRef;

    @Column(name = "escalation_reason", length = 512)
    private String escalationReason;

    /** Summary handed back to the AI before it resumes. Not a transcript. */
    @Column(name = "resolution_summary", length = 2048)
    private String resolutionSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 32)
    private HandoffOutcome outcome;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    /** Supports queue-wait metrics. */
    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Handoff() {
    }

    Handoff(ChatJourney chatJourney, int sequence, String escalationReason, String customerRef) {
        this.chatJourney = chatJourney;
        this.sequence = sequence;
        this.escalationReason = escalationReason;
        this.customerRef = customerRef;
    }

    public boolean isActive() {
        return completedAt == null;
    }

    public void markAccepted() {
        if (acceptedAt == null) {
            this.acceptedAt = Instant.now();
        }
    }

    public void complete(HandoffOutcome outcome, String resolutionSummary) {
        this.outcome = outcome;
        this.resolutionSummary = resolutionSummary;
        this.completedAt = Instant.now();
    }

    public void attachGenesysConversation(String genesysConversationId) {
        if (this.genesysConversationId != null) {
            throw new IllegalStateException(
                    "Genesys conversation already set for handoff " + handoffId
                            + " — a completed interaction ID must never be overwritten");
        }
        this.genesysConversationId = genesysConversationId;
    }

    public String getHandoffId() {
        return handoffId;
    }

    public ChatJourney getChatJourney() {
        return chatJourney;
    }

    public int getSequence() {
        return sequence;
    }

    public String getGenesysConversationId() {
        return genesysConversationId;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public String getEscalationReason() {
        return escalationReason;
    }

    public String getResolutionSummary() {
        return resolutionSummary;
    }

    public HandoffOutcome getOutcome() {
        return outcome;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
