package com.ebremer.touchstone.core.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Both legal framings of a multi-link response, and the relation-matching rules of RFC 8288. */
class LinkHeadersTest {

    private static final URI BASE = URI.create("https://example.org/alice/notes/report");

    private static HttpHeaders headers(String... linkValues) {
        return HttpHeaders.of(Map.of("Link", List.of(linkValues)), (k, v) -> true);
    }

    @Test
    void findsTheRelationAcrossSeparateHeaderFields() {
        HttpHeaders h = headers(
                "<https://example.org/.description>; rel=\"https://www.w3.org/ns/lws#storageDescription\"",
                "<https://example.org/alice/notes/report.meta>; rel=\"linkset\"; type=\"application/linkset+json\"");
        assertThat(LinkHeaders.target(h, "linkset", BASE))
                .isEqualTo("https://example.org/alice/notes/report.meta");
    }

    @Test
    void findsTheRelationInOneCommaSeparatedField() {
        HttpHeaders h = headers("<https://example.org/a>; rel=\"up\", "
                + "<https://example.org/b.meta>; rel=\"linkset\"");
        assertThat(LinkHeaders.target(h, "linkset", BASE)).isEqualTo("https://example.org/b.meta");
    }

    @Test
    void aCommaInsideAQuotedParameterDoesNotSplitTheValue() {
        HttpHeaders h = headers("<https://example.org/x.meta>; rel=\"linkset\"; title=\"a, b\"");
        assertThat(LinkHeaders.target(h, "linkset", BASE)).isEqualTo("https://example.org/x.meta");
    }

    @Test
    void matchesOneTokenOfARelationList() {
        HttpHeaders h = headers("<https://example.org/y.meta>; rel=\"alternate linkset\"");
        assertThat(LinkHeaders.target(h, "linkset", BASE)).isEqualTo("https://example.org/y.meta");
    }

    @Test
    void matchesCaseInsensitivelyAndResolvesARelativeTarget() {
        HttpHeaders h = headers("<report.meta>; REL=LinkSet");
        assertThat(LinkHeaders.target(h, "linkset", BASE))
                .isEqualTo("https://example.org/alice/notes/report.meta");
    }

    @Test
    void returnsNullWhenTheRelationIsAbsent() {
        HttpHeaders h = headers("<https://example.org/z>; rel=\"up\"");
        assertThat(LinkHeaders.target(h, "linkset", BASE)).isNull();
        assertThat(LinkHeaders.target(HttpHeaders.of(Map.of(), (k, v) -> true), "linkset", BASE)).isNull();
    }
}
