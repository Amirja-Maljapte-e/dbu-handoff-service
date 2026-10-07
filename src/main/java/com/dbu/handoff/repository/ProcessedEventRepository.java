package com.dbu.handoff.repository;

import com.dbu.handoff.domain.ProcessedEvent;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    /**
     * Records a delivery, or does nothing if the key is already present.
     *
     * <p>Native, and deliberately so. Letting the insert fail on the primary key
     * and catching the exception does not work: a failed flush marks the
     * transaction rollback-only, so the commit throws even though the duplicate
     * was handled. {@code ON CONFLICT DO NOTHING} resolves the collision inside
     * the database, leaving the transaction clean.
     *
     * <p>It is also race-safe. Two instances claiming the same key concurrently
     * are ordered by the database: exactly one gets 1 back.
     *
     * @return 1 if this caller recorded the delivery, 0 if it was already there
     */
    @Modifying
    @Query(value = """
            INSERT INTO processed_event
                (event_key, chat_journey_id, source, status, attempts, processed_at)
            VALUES
                (:eventKey, :chatJourneyId, :source, 'PROCESSING', 1, :now)
            ON CONFLICT (event_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventKey") String eventKey,
                       @Param("chatJourneyId") String chatJourneyId,
                       @Param("source") String source,
                       @Param("now") Instant now);

    @Modifying
    @Query("delete from ProcessedEvent e where e.processedAt < :before")
    int deleteProcessedBefore(@Param("before") Instant before);
}
