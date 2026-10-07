package com.dbu.handoff.repository;

import com.dbu.handoff.domain.ChatJourney;
import com.dbu.handoff.domain.HandoffState;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatJourneyRepository extends JpaRepository<ChatJourney, String> {

    /**
     * Handoffs are fetched with the journey because callers use the entity
     * after the transaction has closed. Without this the first call to
     * getHandoffs() throws LazyInitializationException.
     */
    @EntityGraph(attributePaths = "handoffs")
    Optional<ChatJourney> findByElevenLabsConversationId(String elevenLabsConversationId);

    @EntityGraph(attributePaths = "handoffs")
    @Override
    Optional<ChatJourney> findById(String chatJourneyId);

    @EntityGraph(attributePaths = "handoffs")
    @Query("select j from ChatJourney j where j.state in :states")
    List<ChatJourney> findByStateIn(@Param("states") List<HandoffState> states);
}