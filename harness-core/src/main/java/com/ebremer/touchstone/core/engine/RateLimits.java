package com.ebremer.touchstone.core.engine;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.ebremer.touchstone.core.engine.Http.Req;
import com.ebremer.touchstone.core.engine.Http.Resp;
import com.ebremer.touchstone.core.exec.Target;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 429 Too Many Requests and 503 Service Unavailable (EXECUTION.md section 4.5). A server that
 * limits how often it is asked, or is briefly unavailable, is not wrong, and says when to come
 * back with {@code Retry-After} (RFC 6585 section 4, RFC 9110 sections 15.6.4 and 10.2.3). The
 * engine waits as asked, up to a cap, and sends the request again a bounded number of times. A
 * request still refused that way is one the engine could not get answered: the step that sent it
 * cannot tell, unless its own expectation accepts the refusal.
 *
 * <p>A step whose {@code statusCode} names 429 or 503 itself, as an integer, is judged on that
 * status as it came: the refusal is what it tests. A class such as {@code "4xx"} does not name it.
 *
 * <p>Target properties: {@code retryAfter.maxWait}, the longest {@code Retry-After} the engine
 * waits for, in seconds (default 30); {@code retryAfter.retries}, how many times it sends a request
 * again (default 3).
 */
final class RateLimits {

    static final int DEFAULT_MAX_WAIT_SECONDS = 30;
    static final int DEFAULT_RETRIES = 3;

    /** The obsolete RFC 850 form a recipient must still accept: {@code Sunday, 06-Nov-94 08:49:37 GMT}. */
    private static final DateTimeFormatter RFC_850 = new DateTimeFormatterBuilder()
            .appendPattern("EEEE, dd-MMM-")
            .appendValueReduced(ChronoField.YEAR, 2, 2, 1970)
            .appendPattern(" HH:mm:ss 'GMT'")
            .toFormatter(Locale.US).withZone(java.time.ZoneOffset.UTC);
    /** ANSI C's asctime() form: {@code Sun Nov  6 08:49:37 1994}. */
    private static final DateTimeFormatter ASCTIME = DateTimeFormatter
            .ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.US).withZone(java.time.ZoneOffset.UTC);

    /** Sends one request: the engine's HTTP, or a test's stand-in. */
    @FunctionalInterface
    interface Sender {
        Resp send(Req req) throws IOException;
    }

    /** Waits a number of seconds: the thread's sleep, or a test's stand-in. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long seconds) throws InterruptedException;
    }

    /** An attempt refused with 429 or 503 and sent again, and what the engine made of it. */
    record Refusal(Req req, Resp resp, String note) {
    }

    /**
     * What sending came to: the last response, the refused attempts sent again before it, and,
     * when the last response is still a refusal the engine did not overcome, why it stopped.
     */
    record Result(Resp response, List<Refusal> retried, String unresolved) {

        /** The last response is a 429 or 503 that was not resolved by waiting. */
        boolean limited() {
            return unresolved != null;
        }
    }

    private final int maxWait;
    private final int retries;
    private final Sleeper sleeper;
    private final Clock clock;

    RateLimits(int maxWait, int retries, Sleeper sleeper, Clock clock) {
        this.maxWait = maxWait;
        this.retries = retries;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    /** The target's limits, or the defaults; the thread sleeps for real. */
    static RateLimits forTarget(Target target) {
        return new RateLimits(property(target, "retryAfter.maxWait", DEFAULT_MAX_WAIT_SECONDS),
                property(target, "retryAfter.retries", DEFAULT_RETRIES),
                seconds -> Thread.sleep(seconds * 1000L), Clock.systemUTC());
    }

    private static int property(Target target, String name, int fallback) {
        String value = target.properties().get(name);
        try {
            return value == null ? fallback : Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 429 Too Many Requests or 503 Service Unavailable. */
    static boolean isRefusal(int status) {
        return status == 429 || status == 503;
    }

    /** Whether a {@code statusCode} expectation names {@code status} itself, as an integer. */
    static boolean named(JsonNode statusCode, int status) {
        if (statusCode == null || statusCode.isMissingNode()) {
            return false;
        }
        for (JsonNode s : statusCode.isArray() ? statusCode : List.of(statusCode)) {
            if (s.isInt() && s.asInt() == status) {
                return true;
            }
        }
        return false;
    }

    /** Sends a request that no expectation judges as it comes: every refusal is waited out if it can be. */
    Result send(Req req, Sender sender) throws IOException {
        return send(req, sender, null);
    }

    /**
     * Sends {@code req}, and sends it again after each 429 or 503 that {@code statusCode} does not
     * name, waiting the {@code Retry-After} it gives, as long as that is at most the cap and the
     * retries last.
     */
    Result send(Req req, Sender sender, JsonNode statusCode) throws IOException {
        List<Refusal> retried = new ArrayList<>();
        Resp resp = sender.send(req);
        int attempt = 0;
        while (isRefusal(resp.status()) && !named(statusCode, resp.status())) {
            String what = "answered " + resp.status() + (resp.status() == 429 ? " Too Many Requests" : " Service Unavailable");
            String header = resp.first("Retry-After");
            Long wait = retryAfter(header, clock.instant());
            if (wait == null) {
                return new Result(resp, List.copyOf(retried), what + (header == null ? " without a Retry-After"
                        : " with a Retry-After that is neither seconds nor a date (" + header + ")")
                        + ", so the engine cannot tell when to ask again");
            }
            if (wait > maxWait) {
                return new Result(resp, List.copyOf(retried), what + " with Retry-After " + header + " (" + wait
                        + " s), longer than the " + maxWait + " s the engine waits");
            }
            if (attempt >= retries) {
                return new Result(resp, List.copyOf(retried), what + " again, after " + retries
                        + (retries == 1 ? " retry" : " retries") + " that each waited as asked");
            }
            retried.add(new Refusal(req, resp, what + " with Retry-After " + header + ": sent again after " + wait + " s"));
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("interrupted while waiting out a Retry-After");
            }
            attempt++;
            resp = sender.send(req);
        }
        return new Result(resp, List.copyOf(retried), null);
    }

    /**
     * The seconds a {@code Retry-After} value asks for (RFC 9110 section 10.2.3): delta-seconds, or
     * an HTTP-date in any of the three forms a recipient must accept, counted from {@code now}
     * and rounded up; zero for a date already past. Null when absent or neither.
     */
    static Long retryAfter(String value, Instant now) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (!v.isEmpty() && v.chars().allMatch(c -> c >= '0' && c <= '9')) {
            // A value too long for a long is a wait no one will make.
            return v.length() > 12 ? Long.MAX_VALUE : Long.parseLong(v);
        }
        Instant date = httpDate(v);
        if (date == null) {
            return null;
        }
        long millis = Duration.between(now, date).toMillis();
        return millis <= 0 ? 0L : (millis + 999) / 1000;
    }

    private static Instant httpDate(String v) {
        for (DateTimeFormatter f : List.of(DateTimeFormatter.RFC_1123_DATE_TIME, RFC_850, ASCTIME)) {
            try {
                return ZonedDateTime.parse(v, f).toInstant();
            } catch (DateTimeParseException e) {
                // try the next form
            }
        }
        return null;
    }
}
