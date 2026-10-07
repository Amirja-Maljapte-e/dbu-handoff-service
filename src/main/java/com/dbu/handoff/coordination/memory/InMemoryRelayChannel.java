package com.dbu.handoff.coordination.memory;

import com.dbu.handoff.coordination.RelayChannel;
import com.dbu.handoff.coordination.RelayMessage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-process relay.
 *
 * <p>Drops messages addressed to an instance with no subscriber, deliberately:
 * Redis pub/sub does the same, and code written against a fake that retained
 * them would fail in production.
 */
public class InMemoryRelayChannel implements RelayChannel {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRelayChannel.class);

    private final Map<String, Consumer<RelayMessage>> subscribers = new ConcurrentHashMap<>();

    @Override
    public boolean publish(String targetInstanceId, RelayMessage message) {
        Consumer<RelayMessage> handler = subscribers.get(targetInstanceId);
        if (handler == null) {
            log.warn("no subscriber on instance {} — relay message for journey {} dropped",
                    targetInstanceId, message.chatJourneyId());
            return false;
        }
        handler.accept(message);
        return true;
    }

    @Override
    public void subscribe(String instanceId, Consumer<RelayMessage> handler) {
        subscribers.put(instanceId, handler);
    }

    @Override
    public void unsubscribe(String instanceId) {
        subscribers.remove(instanceId);
    }
}
