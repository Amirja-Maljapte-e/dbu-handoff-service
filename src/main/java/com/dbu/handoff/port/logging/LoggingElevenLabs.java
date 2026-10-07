package com.dbu.handoff.port.logging;

import com.dbu.handoff.port.ElevenLabsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stands in for ElevenLabs until the real adapter exists.
 *
 * <p>Logs what would have been sent instead of sending it, so the whole
 * handoff flow can be exercised locally without the Enterprise entitlement.
 *
 * <p>Active while {@code handoff.vendors.mode} is {@code logging}, which is the
 * default. Set it to {@code live} once the real adapter exists.
 */
@Component
@ConditionalOnProperty(name = "handoff.vendors.mode", havingValue = "logging", matchIfMissing = true)
public class LoggingElevenLabs implements ElevenLabsPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingElevenLabs.class);

    @Override
    public void openMonitoring(String chatJourneyId, String elevenLabsConversationId) {
        log.info("[elevenlabs] open monitoring socket | journey={} conversation={}",
                chatJourneyId, elevenLabsConversationId);
    }

    @Override
    public void muteAi(String chatJourneyId) {
        log.info("[elevenlabs] enable_human_takeover | journey={}", chatJourneyId);
    }

    @Override
    public void unmuteAi(String chatJourneyId) {
        log.info("[elevenlabs] disable_human_takeover | journey={}", chatJourneyId);
    }

    @Override
    public void sendAgentMessage(String chatJourneyId, String text) {
        // Message text is logged here only because this is a development stand-in.
        // The real adapter must not log content.
        log.info("[elevenlabs] send_human_message | journey={} text=\"{}\"", chatJourneyId, text);
    }

    @Override
    public void sendContext(String chatJourneyId, String text) {
        log.info("[elevenlabs] contextual_update | journey={} text=\"{}\"", chatJourneyId, text);
    }

    @Override
    public void closeMonitoring(String chatJourneyId) {
        log.info("[elevenlabs] close monitoring socket | journey={}", chatJourneyId);
    }
}
