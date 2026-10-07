package com.dbu.handoff.repository;

import com.dbu.handoff.domain.Handoff;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HandoffRepository extends JpaRepository<Handoff, String> {

    /** Genesys webhooks identify the interaction, not the journey. */
    @EntityGraph(attributePaths = {"chatJourney", "chatJourney.handoffs"})
    Optional<Handoff> findByGenesysConversationId(String genesysConversationId);

    /**
     * Genesys agent-message webhooks carry the customer reference, not the
     * conversation ID, so this is how an inbound message finds its handoff.
     *
     * <p>The schema permits at most one active episode per customer reference,
     * so this returns at most one row.
     */
    @EntityGraph(attributePaths = {"chatJourney", "chatJourney.handoffs"})
    Optional<Handoff> findByCustomerRefAndCompletedAtIsNull(String customerRef);
}
