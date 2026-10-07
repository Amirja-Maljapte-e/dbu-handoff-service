package com.dbu.handoff.service;

import com.dbu.handoff.domain.EventSource;
import com.dbu.handoff.domain.ProcessedEvent;
import com.dbu.handoff.repository.ProcessedEventRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The delivery ledger: layer one of duplicate suppression.
 *
 * <p>Both vendors deliver at least once, and the monitoring socket replays
 * roughly its last hundred events on every reconnect, so duplicates are
 * normal traffic rather than an error condition.
 *
 * <p>Runs in its own transaction so that recording a delivery survives a
 * rollback of the work that follows it, and so a unique-key collision does
 * not poison the caller's transaction.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final ProcessedEventRepository events;
    private final Duration stalledAfter;
    private final Duration retention;

    public IdempotencyService(ProcessedEventRepository events,
                              @Value("${handoff.idempotency.stalled-after:30s}") Duration stalledAfter,
                              @Value("${handoff.idempotency.retention:7d}") Duration retention) {
        this.events = events;
        this.stalledAfter = stalledAfter;
        this.retention = retention;
    }

    /**
     * Claim a delivery for processing.
     *
     * @return true if the caller should process it; false if it is a duplicate
     *         that has already been handled, or is in flight elsewhere
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(String eventKey, String chatJourneyId, EventSource source) {
        // The collision is resolved by the database rather than by an exception.
        // Letting the insert fail and catching it leaves the transaction marked
        // rollback-only, so the commit throws even though the duplicate was
        // handled correctly — the caller sees UnexpectedRollbackException.
        int inserted = events.insertIfAbsent(
                eventKey, chatJourneyId, source.name(), Instant.now());

        return inserted == 1 || reclaimIfStalled(eventKey);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDone(String eventKey) {
        events.findById(eventKey).ifPresent(event -> {
            event.markDone();
            events.save(event);
        });
    }

    /**
     * Key for a delivery whose vendor supplies no stable identifier.
     *
     * <p>Hashes the text rather than storing it, so no message content reaches
     * the database. Weakness: the same message sent twice within one timestamp
     * resolution looks like a duplicate. Spike items S4 and S12 should tell us
     * whether this fallback is needed at all.
     */
    public static String fallbackKey(String conversationId, String eventType,
                                     Instant sourceTime, String text) {
        return sha256(conversationId + '|' + eventType + '|' + sourceTime + '|' + sha256(text));
    }

    /** Purges old ledger rows. State guards still suppress anything that slips through. */
    @Transactional
    public int purgeExpired() {
        int removed = events.deleteProcessedBefore(Instant.now().minus(retention));
        if (removed > 0) {
            log.info("purged {} processed-event rows older than {}", removed, retention);
        }
        return removed;
    }

    private boolean reclaimIfStalled(String eventKey) {
        Optional<ProcessedEvent> existing = events.findById(eventKey);
        if (existing.isEmpty()) {
            return false;
        }
        ProcessedEvent event = existing.get();
        if (event.isStalled(Instant.now(), stalledAfter)) {
            log.warn("delivery {} was left in PROCESSING for more than {} — reprocessing",
                    eventKey, stalledAfter);
            event.reattempt();
            events.save(event);
            return true;
        }
        log.debug("duplicate delivery {} suppressed", eventKey);
        return false;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
