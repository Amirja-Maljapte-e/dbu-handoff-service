package com.dbu.handoff.port;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stand-in for ElevenLabs.
 *
 * <p>Lets the whole service be built and tested before the monitoring socket
 * contract is confirmed. Replace nothing when the real adapter arrives — this
 * stays as the test double.
 */
public class RecordingElevenLabs implements ElevenLabsPort {

    public record Call(String type, String chatJourneyId, String text) {
    }

    private final List<Call> calls = new ArrayList<>();
    private final Set<String> openSockets = ConcurrentHashMap.newKeySet();
    private final Set<String> muted = ConcurrentHashMap.newKeySet();

    @Override
    public void openMonitoring(String chatJourneyId, String elevenLabsConversationId) {
        openSockets.add(chatJourneyId);
        calls.add(new Call("openMonitoring", chatJourneyId, elevenLabsConversationId));
    }

    @Override
    public void muteAi(String chatJourneyId) {
        muted.add(chatJourneyId);
        calls.add(new Call("muteAi", chatJourneyId, null));
    }

    @Override
    public void unmuteAi(String chatJourneyId) {
        muted.remove(chatJourneyId);
        calls.add(new Call("unmuteAi", chatJourneyId, null));
    }

    @Override
    public void sendAgentMessage(String chatJourneyId, String text) {
        calls.add(new Call("sendAgentMessage", chatJourneyId, text));
    }

    @Override
    public void sendContext(String chatJourneyId, String text) {
        calls.add(new Call("sendContext", chatJourneyId, text));
    }

    @Override
    public void closeMonitoring(String chatJourneyId) {
        openSockets.remove(chatJourneyId);
        calls.add(new Call("closeMonitoring", chatJourneyId, null));
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public boolean isMuted(String chatJourneyId) {
        return muted.contains(chatJourneyId);
    }

    public boolean hasOpenSocket(String chatJourneyId) {
        return openSockets.contains(chatJourneyId);
    }

    public void reset() {
        calls.clear();
        openSockets.clear();
        muted.clear();
    }
}
