package com.ebremer.touchstone.clients;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The recorder's bounds (CLIENT-TESTING.md section 8.2): the log and the ledger stay bounded whatever a client sends. */
class RecorderTest {

    @Test
    void theLogKeepsItsSizeAsWellAsItsCount() {
        Recorder recorder = new Recorder(url -> true, 100);
        String big = "x".repeat(64 << 10);
        for (int i = 0; i < 50; i++) {
            recorder.append(seq -> exchange(seq, big));
        }
        long kept = recorder.after(0, 500).size();
        // 100 exchanges' worth of text holds about twelve of 65 KiB each.
        assertThat(kept).isBetween(10L, 13L);
        assertThat(recorder.recorded()).isEqualTo(50);
        assertThat(recorder.dropped()).isEqualTo(50 - kept);
        // The latest exchange stays, however large.
        recorder.append(seq -> exchange(seq, "y".repeat(2 << 20)));
        assertThat(recorder.after(0, 500)).extracting(Exchange::seq).containsExactly(51L);
    }

    @Test
    void theLedgerForgetsUrlsBeyondItsBound() {
        Recorder recorder = new Recorder(url -> true, 100);
        String base = "https://lab.example/s/x/storage/";
        assertThat(recorder.repeats(base + "kept", "GET")).isFalse();
        for (int i = 1; i < Recorder.MAX_URLS; i++) {
            recorder.repeats(base + "made-up-" + i, "GET");
        }
        // Full: a URL seen before is still remembered, a new one is not.
        assertThat(recorder.repeats(base + "kept", "GET")).isTrue();
        assertThat(recorder.repeats(base + "new", "GET")).isFalse();
        assertThat(recorder.repeats(base + "new", "GET")).isFalse();
    }

    private static Exchange exchange(long seq, String text) {
        return new Exchange(seq, "2026-10-05T00:00:00Z", 1, "PUT", "https://lab.example/s/x/storage/r", Map.of(),
                new Exchange.Body(text.length(), text, false), 204, Map.<String, List<String>>of(), Exchange.Body.NONE, null,
                List.of());
    }
}
