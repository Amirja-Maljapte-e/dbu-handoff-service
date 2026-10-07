package com.dbu.handoff.port.logging;

import com.dbu.handoff.port.GenesysPort;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stands in for Genesys until the real adapter exists.
 *
 * <p>Invents a conversation ID so the journey can move past HANDOFF_REQUESTED
 * and the rest of the flow can be exercised. Deduplicates on the idempotency
 * key, so a retried escalation behaves as the real platform should.
 */
@Component
@ConditionalOnProperty(name = "handoff.vendors.mode", havingValue = "logging", matchIfMissing = true)
public class LoggingGenesys implements GenesysPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingGenesys.class);

    private final Map<String, String> conversationsByKey = new ConcurrentHashMap<>();

    @Override
    public Optional<String> createInteraction(EscalationContext context, String idempotencyKey) {
        String conversationId = conversationsByKey.computeIfAbsent(
                idempotencyKey, key -> "GEN-" + UUID.randomUUID());

        log.info("[genesys] create interaction | journey={} handoff={} sequence={} "
                        + "customer={} conversation={}",
                context.chatJourneyId(), context.handoffId(), context.sequence(),
                context.customerId(), conversationId);

        if (context.previousResolutionSummary() != null) {
            log.info("[genesys] previous resolution passed to agent | journey={}",
                    context.chatJourneyId());
        }
        return Optional.of(conversationId);
    }

    @Override
    public void sendCustomerMessage(String genesysConversationId, String text, String messageId) {
        log.info("[genesys] customer message | conversation={} messageId={} text=\"{}\"",
                genesysConversationId, messageId, text);
    }

    @Override
    public void disconnect(String genesysConversationId) {
        log.info("[genesys] disconnect | conversation={}", genesysConversationId);
    }
}
