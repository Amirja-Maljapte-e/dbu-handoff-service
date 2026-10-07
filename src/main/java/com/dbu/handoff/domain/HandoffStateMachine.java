package com.dbu.handoff.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;

/**
 * Every legal transition for a chat journey, in one place.
 *
 * <p>Deliberately free of Spring and persistence concerns so it can be unit
 * tested without a container. Keeping the whole table here means the failure
 * paths are as visible as the happy path — those are the transitions the
 * Architecture Note leaves open.
 */
public final class HandoffStateMachine {

    private static final Map<HandoffState, Map<HandoffTrigger, HandoffState>> TRANSITIONS =
            new EnumMap<>(HandoffState.class);

    /**
     * Abandonment can arrive in any non-terminal state. Handled separately
     * rather than duplicated across every row of the table.
     */
    private static final EnumSet<HandoffState> ABANDONABLE =
            EnumSet.complementOf(EnumSet.of(HandoffState.CLOSED));

    static {
        // --- Happy path ------------------------------------------------
        put(HandoffState.AI_ACTIVE,
                HandoffTrigger.CUSTOMER_REQUESTS_HUMAN, HandoffState.HANDOFF_REQUESTED);

        put(HandoffState.HANDOFF_REQUESTED,
                HandoffTrigger.GENESYS_INTERACTION_CREATED, HandoffState.QUEUED);

        put(HandoffState.QUEUED,
                HandoffTrigger.AGENT_ACCEPTED, HandoffState.AGENT_ACTIVE);

        put(HandoffState.AGENT_ACTIVE,
                HandoffTrigger.AGENT_COMPLETED, HandoffState.RESUMING);

        put(HandoffState.RESUMING,
                HandoffTrigger.AI_CLOSED_CONVERSATION, HandoffState.CLOSED);

        // --- Re-escalation ---------------------------------------------
        // One journey may spawn several Genesys interactions over its life.
        // The lineage model must be one-to-many, not one-to-one.
        put(HandoffState.RESUMING,
                HandoffTrigger.CUSTOMER_REQUESTS_HUMAN, HandoffState.HANDOFF_REQUESTED);

        // --- Failure paths ---------------------------------------------
        // All currently land in RESUMING so the AI can explain and close
        // rather than the customer being left with a dead chat.
        //
        // TODO(product): confirm each with Product + Ops. The out-of-hours
        // case may warrant a different response (callback offer, or closing
        // the widget) rather than AI resumption.
        put(HandoffState.HANDOFF_REQUESTED,
                HandoffTrigger.GENESYS_CREATE_FAILED, HandoffState.RESUMING);

        put(HandoffState.QUEUED,
                HandoffTrigger.QUEUE_TIMEOUT, HandoffState.RESUMING);

        put(HandoffState.QUEUED,
                HandoffTrigger.NO_AGENTS_AVAILABLE, HandoffState.RESUMING);

        // TODO(product): re-queue instead? Returning to QUEUED would give the
        // customer another agent rather than bouncing them back to the AI,
        // but risks an unbounded loop if the queue itself is the problem.
        put(HandoffState.AGENT_ACTIVE,
                HandoffTrigger.AGENT_DISCONNECTED, HandoffState.RESUMING);
    }

    private HandoffStateMachine() {
    }

    private static void put(HandoffState from, HandoffTrigger trigger, HandoffState to) {
        TRANSITIONS.computeIfAbsent(from, k -> new EnumMap<>(HandoffTrigger.class))
                .put(trigger, to);
    }

    /**
     * Resolves the next state, or empty if the trigger is not legal here.
     *
     * <p>An illegal trigger is usually a duplicate webhook rather than a bug —
     * both vendors deliver at-least-once — so callers should treat an empty
     * result as "ignore and acknowledge", not as an error to retry.
     */
    public static Optional<HandoffState> next(HandoffState current, HandoffTrigger trigger) {
        if (trigger == HandoffTrigger.CUSTOMER_ABANDONED) {
            return ABANDONABLE.contains(current)
                    ? Optional.of(HandoffState.CLOSED)
                    : Optional.empty();
        }
        return Optional.ofNullable(TRANSITIONS.getOrDefault(current, Map.of()).get(trigger));
    }

    public static boolean isLegal(HandoffState current, HandoffTrigger trigger) {
        return next(current, trigger).isPresent();
    }

    /**
     * Where a customer message should go, given the current state.
     *
     * <p>{@link HandoffState#QUEUED} is the awkward one: the customer can type
     * but no agent is attached yet, so the message is held and flushed on
     * acceptance.
     */
    public static MessageDestination routeCustomerMessage(HandoffState current) {
        return switch (current) {
            case AI_ACTIVE, RESUMING -> MessageDestination.ELEVENLABS_AI;
            case HANDOFF_REQUESTED, QUEUED -> MessageDestination.BUFFER;
            case AGENT_ACTIVE -> MessageDestination.GENESYS_AGENT;
            case CLOSED -> MessageDestination.DISCARD;
        };
    }

    /** The outcome recorded on the handoff when a trigger ends the episode. */
    public static Optional<HandoffOutcome> outcomeFor(HandoffTrigger trigger) {
        return Optional.ofNullable(switch (trigger) {
            case AGENT_COMPLETED -> HandoffOutcome.COMPLETED;
            case QUEUE_TIMEOUT -> HandoffOutcome.TIMEOUT;
            case NO_AGENTS_AVAILABLE -> HandoffOutcome.NO_AGENTS;
            case AGENT_DISCONNECTED -> HandoffOutcome.DISCONNECTED;
            case CUSTOMER_ABANDONED -> HandoffOutcome.ABANDONED;
            case GENESYS_CREATE_FAILED -> HandoffOutcome.CREATE_FAILED;
            default -> null;
        });
    }
}
