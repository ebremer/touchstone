package com.ebremer.touchstone.clients;

/** A token bucket: {@code burst} requests at once, refilled at {@code perSecond}. */
final class TokenBucket {

    private final double capacity;
    private final double perNano;
    private double tokens;
    private long last;

    TokenBucket(int burst, double perSecond) {
        this.capacity = burst;
        this.perNano = perSecond / 1_000_000_000d;
        this.tokens = burst;
        this.last = System.nanoTime();
    }

    /** Takes one token if there is one. */
    synchronized boolean tryTake() {
        long now = System.nanoTime();
        tokens = Math.min(capacity, tokens + (now - last) * perNano);
        last = now;
        if (tokens < 1) {
            return false;
        }
        tokens -= 1;
        return true;
    }
}
