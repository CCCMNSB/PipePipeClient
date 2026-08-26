package com.pipepipe.translator;

/**
 * A tiny global token-rate limiter that enforces a minimum interval between
 * requests regardless of how many worker threads call it. Use it to stay well
 * under free-tier quota (e.g. Google gtx endpoint) and to avoid hammering an LLM.
 */
public class RateLimiter {

    private final long minIntervalMillis;
    private final Object lock = new Object();
    private long nextAllowed = 0;

    public RateLimiter(final long minIntervalMillis) {
        this.minIntervalMillis = Math.max(0, minIntervalMillis);
    }

    /**
     * Blocks until a request is allowed by the interval budget.
     */
    public void acquire() {
        final long now = System.currentTimeMillis();
        final long wait;
        synchronized (lock) {
            wait = Math.max(0, nextAllowed - now);
            nextAllowed = Math.max(nextAllowed, now) + minIntervalMillis;
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
