package com.dbu.handoff.coordination.memory;

import com.dbu.handoff.coordination.BufferedMessage;
import com.dbu.handoff.coordination.CoordinationProperties;
import com.dbu.handoff.coordination.MessageBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Single-process message buffer.
 *
 * <p>Correct for one instance, which is what local development and the test
 * suite run. The Redis implementation replaces it when more than one instance
 * is involved; the interface does not change.
 */
public class InMemoryMessageBuffer implements MessageBuffer {

    private record Entry(BufferedMessage message, Instant expiresAt) {
    }

    private final Map<String, Deque<Entry>> buffers = new ConcurrentHashMap<>();
    private final CoordinationProperties properties;
    private final Clock clock;

    public InMemoryMessageBuffer(CoordinationProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void append(String chatJourneyId, BufferedMessage message) {
        Instant expiresAt = clock.instant().plus(properties.bufferTtl());
        buffers.computeIfAbsent(chatJourneyId, k -> new ConcurrentLinkedDeque<>())
                .addLast(new Entry(message, expiresAt));
    }

    @Override
    public List<BufferedMessage> drain(String chatJourneyId) {
        Deque<Entry> entries = buffers.remove(chatJourneyId);
        if (entries == null) {
            return List.of();
        }
        Instant now = clock.instant();
        List<BufferedMessage> live = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            if (entry.expiresAt().isAfter(now)) {
                live.add(entry.message());
            }
        }
        return List.copyOf(live);
    }

    @Override
    public void discard(String chatJourneyId) {
        buffers.remove(chatJourneyId);
    }

    @Override
    public int size(String chatJourneyId) {
        Deque<Entry> entries = buffers.get(chatJourneyId);
        if (entries == null) {
            return 0;
        }
        Instant now = clock.instant();
        return (int) entries.stream().filter(e -> e.expiresAt().isAfter(now)).count();
    }
}
