package org.booklore.service.enrichment.steps;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.settings.SmartEnrichmentSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Caps how often the agent may be invoked.
 * <p>
 * Not a throughput knob: an agent call costs quota and money, and a library-wide run would otherwise
 * spend both as fast as the machine allows. A sliding window rather than a fixed one, so the limit
 * cannot be doubled by starting a run just before the hour turns over.
 * <p>
 * Reaching the limit defers a book instead of failing it. The queue will offer it again, and by then
 * the window has moved.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRateLimiter {

    /**
     * Applied when the settings row carries no value — which is every row written before the field
     * existed. Deliberately conservative: the failure mode of guessing too low is a slower run, and
     * of guessing "unlimited" is a spent quota nobody asked to spend.
     */
    public static final int DEFAULT_LIMIT = 20;

    private static final Duration WINDOW = Duration.ofHours(1);

    private final AppSettingService appSettingService;
    private final Deque<Instant> recentCalls = new ArrayDeque<>();

    public synchronized boolean tryAcquire() {
        int limit = limit();
        if (limit < 0) {
            return true;
        }
        Instant cutoff = Instant.now().minus(WINDOW);
        while (!recentCalls.isEmpty() && recentCalls.peekFirst().isBefore(cutoff)) {
            recentCalls.pollFirst();
        }
        if (recentCalls.size() >= limit) {
            return false;
        }
        recentCalls.addLast(Instant.now());
        return true;
    }

    public synchronized int remaining() {
        int limit = limit();
        if (limit < 0) {
            return Integer.MAX_VALUE;
        }
        Instant cutoff = Instant.now().minus(WINDOW);
        long used = recentCalls.stream().filter(call -> !call.isBefore(cutoff)).count();
        return (int) Math.max(0, limit - used);
    }

    private int limit() {
        SmartEnrichmentSettings settings = appSettingService.getAppSettings().getSmartEnrichmentSettings();
        if (settings == null || settings.getMaxAgentCallsPerHour() == 0) {
            return DEFAULT_LIMIT;
        }
        return settings.getMaxAgentCallsPerHour();
    }
}
