package com.dbu.handoff.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * One inbound delivery, recorded before processing so a repeat is suppressed.
 *
 * <p>Holds keys and hashes only. No message content reaches this table.
 *
 * <p>Implements {@link Persistable} deliberately. The key is assigned rather
 * than generated, so Spring Data would otherwise treat every instance as an
 * existing row and call {@code merge}, which overwrites a duplicate instead of
 * failing. The ledger depends on the second insert of a key raising a
 * constraint violation, so the entity has to say when it is new.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent implements Persistable<String> {

    public enum Status { PROCESSING, DONE }

    @Id
    @Column(name = "event_key", length = 256)
    private String eventKey;

    @Column(name = "chat_journey_id", length = 64)
    private String chatJourneyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 32)
    private EventSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PROCESSING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 1;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();

    /** True only for an instance this process has just constructed. */
    @Transient
    private boolean isNew = true;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(String eventKey, String chatJourneyId, EventSource source) {
        this.eventKey = eventKey;
        this.chatJourneyId = chatJourneyId;
        this.source = source;
    }

    @Override
    public String getId() {
        return eventKey;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    /** Once the row exists or has been loaded, a save must be an update. */
    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public void markDone() {
        this.status = Status.DONE;
        this.processedAt = Instant.now();
    }

    /** A delivery left in PROCESSING for too long: the previous attempt died. */
    public boolean isStalled(Instant now, java.time.Duration stalledAfter) {
        return status == Status.PROCESSING && processedAt.isBefore(now.minus(stalledAfter));
    }

    public void reattempt() {
        this.attempts++;
        this.processedAt = Instant.now();
    }

    public String getEventKey() {
        return eventKey;
    }

    public String getChatJourneyId() {
        return chatJourneyId;
    }

    public EventSource getSource() {
        return source;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
