package com.dbu.handoff.service;

import com.dbu.handoff.domain.HandoffState;

/**
 * Outcome of applying a trigger.
 *
 * <p>{@code applied == false} is the normal result for a duplicate delivery,
 * not an error. Callers acknowledge and move on.
 */
public record TransitionResult(
        boolean applied,
        String chatJourneyId,
        HandoffState from,
        HandoffState to) {

    public static TransitionResult ignored(String chatJourneyId, HandoffState current) {
        return new TransitionResult(false, chatJourneyId, current, current);
    }
}
