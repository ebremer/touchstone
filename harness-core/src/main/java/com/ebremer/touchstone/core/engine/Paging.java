package com.ebremer.touchstone.core.engine;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ebremer.touchstone.core.engine.Http.Req;
import com.ebremer.touchstone.core.engine.Http.Resp;
import com.ebremer.touchstone.core.results.AssertionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A step's paged result, read whole (EXECUTION.md section 4.6, since format 0.13.0). Starting
 * from the step's own response, the engine follows {@code rel="next"} with GET, as the LWS
 * pagination model conveys page URIs, and gathers every page's {@code items}, so that a step's
 * {@code json} expectations on {@code /items} judge the whole result whatever the server's page
 * size. A page that is not a page is a finding; a result longer than the step reads is not one the
 * step can judge.
 */
final class Paging {

    private Paging() {
    }

    /** A page after the first: the GET that fetched it and the answer. */
    record Page(Req req, Resp resp) {
    }

    /**
     * What reading came to: the pages after the first, in order; the first page's JSON object with
     * {@code items} replaced by every page's items, or null when the first response is no page to
     * extend; and, when reading failed, the failed check or the reason the engine cannot tell.
     */
    record Read(List<Page> pages, JsonNode merged, AssertionResult failure, String cantTell) {
    }

    /**
     * @param first     the step's request, whose URL the first page's links resolve against
     * @param firstResp its response, page 1
     * @param max       the most pages to read, page 1 included
     */
    static Read read(RunSession run, Map<String, String> authorization, String accept, Req first, Resp firstResp,
                     int max) throws IOException {
        JsonNode root = firstResp.status() == 200 ? object(firstResp) : null;
        if (root == null || !root.path("items").isArray()) {
            // Not a page at all: the step's own expectations say what is wrong with it.
            return new Read(List.of(), null, null, null);
        }
        ArrayNode items = ((ArrayNode) root.get("items")).deepCopy();
        List<Page> pages = new ArrayList<>();
        Set<String> read = new LinkedHashSet<>();
        read.add(first.uri().toString());
        URI base = first.uri();
        Resp last = firstResp;
        while (true) {
            String next = next(last, base);
            if (next == null) {
                break;
            }
            int number = read.size() + 1;
            if (read.size() >= max) {
                return new Read(pages, null, null, "the result has more than " + max + " pages: page " + max
                        + " links a next page, so the step cannot judge the whole result");
            }
            if (!read.add(next)) {
                return new Read(pages, null, AssertionResult.failed("page " + number,
                        "a rel=next link to a page not read yet", "rel=next names " + next + ", already read"), null);
            }
            List<Map.Entry<String, String>> headers = new ArrayList<>();
            if (accept != null) {
                headers.add(Http.header("Accept", accept));
            }
            authorization.forEach((k, v) -> headers.add(Http.header(k, v)));
            Req get = new Req("GET", URI.create(next), List.copyOf(headers), null, null);
            Resp resp = run.send(get);
            pages.add(new Page(get, resp));
            if (RateLimits.isRefusal(resp.status())) {
                return new Read(pages, null, null, "page " + number + " (" + next + ") answered " + resp.status()
                        + " even after waiting as asked (EXECUTION.md section 4.5)");
            }
            JsonNode page = resp.status() == 200 ? object(resp) : null;
            if (page == null || !page.path("items").isArray()) {
                return new Read(pages, null, AssertionResult.failed("page " + number,
                        "200 with a JSON object whose items is an array",
                        resp.status() + (page == null ? " without a JSON object" : " without an items array")), null);
            }
            for (JsonNode item : page.get("items")) {
                items.add(resolved(item, get.uri()));
            }
            base = get.uri();
            last = resp;
        }
        ObjectNode merged = ((ObjectNode) root).deepCopy();
        merged.set("items", items);
        return new Read(List.copyOf(pages), merged, null, null);
    }

    /**
     * An item of a later page, its {@code id} resolved against that page's URL: the expectations
     * resolve what they read against the step's request URL, which is the first page's, and a
     * relative id means what it says on the page that carries it.
     */
    private static JsonNode resolved(JsonNode item, URI page) {
        if (!item.isObject() || !item.path("id").isTextual()) {
            return item;
        }
        try {
            ObjectNode copy = ((ObjectNode) item).deepCopy();
            copy.put("id", page.resolve(item.get("id").asText()).toString());
            return copy;
        } catch (IllegalArgumentException e) {
            return item;
        }
    }

    /** The target of the response's first {@code rel="next"} link, resolved against its URL, or null. */
    private static String next(Resp resp, URI base) {
        for (LinkValues.Link link : LinkValues.parse(resp.header("Link"), base)) {
            if (link.hasRel("next")) {
                return link.target();
            }
        }
        return null;
    }

    private static JsonNode object(Resp resp) {
        try {
            JsonNode node = Templates.JSON.readTree(resp.body());
            return node != null && node.isObject() ? node : null;
        } catch (IOException e) {
            return null;
        }
    }
}
