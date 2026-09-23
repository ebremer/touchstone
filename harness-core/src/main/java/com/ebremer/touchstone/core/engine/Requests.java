package com.ebremer.touchstone.core.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ebremer.touchstone.core.engine.Http.Req;
import com.ebremer.touchstone.core.engine.Http.Resp;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Builds the request a step describes (EXECUTION.md section 6). Only the headers a definition
 * names are sent, plus Authorization per the identity and the User-Agent; the JDK client adds
 * Host and Content-Length.
 */
final class Requests {

    private Requests() {
    }

    /**
     * @param identity       the step's identity
     * @param acceptOverride the media type a {@code connegEquivalent} refetch asks for, or null
     */
    static Req build(JsonNode request, String identity, Scope scope, Path directory, String acceptOverride) {
        String method = request.path("method").asText();
        URI url = url(Templates.expand(request.path("url").asText(), scope), scope);
        List<Map.Entry<String, String>> headers = new ArrayList<>();

        String accept = acceptOverride != null ? acceptOverride : text(request, "accept");
        if (accept != null) {
            headers.add(Http.header("Accept", accept));
        }

        byte[] body = null;
        String bodyText = null;
        String contentType = text(request, "contentType");
        if (request.has("body")) {
            bodyText = Templates.expand(request.get("body").asText(), scope);
            body = bodyText.getBytes(StandardCharsets.UTF_8);
        } else if (request.has("bodyURL")) {
            body = fixture(directory, request.get("bodyURL").asText());
            bodyText = new String(body, StandardCharsets.UTF_8);
        } else if (request.has("bodyJSON")) {
            bodyText = Templates.expandJson(request.get("bodyJSON"), scope).toString();
            body = bodyText.getBytes(StandardCharsets.UTF_8);
            contentType = contentType != null ? contentType : "application/json";
        } else if (request.has("bodyForm")) {
            Map<String, String> fields = new LinkedHashMap<>();
            request.get("bodyForm").properties().forEach(f ->
                    fields.put(f.getKey(), Templates.expand(f.getValue().asText(), scope)));
            bodyText = Credentials.form(fields);
            body = bodyText.getBytes(StandardCharsets.UTF_8);
            contentType = contentType != null ? contentType : "application/x-www-form-urlencoded";
        }
        if (contentType != null) {
            headers.add(Http.header("Content-Type", contentType));
        }

        if (request.has("ifMatch")) {
            String ifMatch = request.get("ifMatch").asText();
            if (ifMatch.equals("current")) {
                String etag = currentEtag(url, identity, scope);
                if (etag != null) {
                    headers.add(Http.header("If-Match", etag));
                }
            } else {
                headers.add(Http.header("If-Match", Templates.expand(ifMatch, scope)));
            }
        }

        if (request.has("linkHeaders")) {
            List<String[]> links = new ArrayList<>();
            for (JsonNode l : request.get("linkHeaders")) {
                links.add(new String[] {Templates.expand(l.path("href").asText(), scope), l.path("rel").asText(),
                        text(l, "mediaType")});
            }
            headers.add(Http.header("Link", LinkValues.serialize(links)));
        }

        boolean ownAuthorization = false;
        for (JsonNode h : request.path("otherHeaders")) {
            String name = h.path("headerName").asText();
            ownAuthorization |= name.equalsIgnoreCase("Authorization");
            headers.add(Http.header(name, Templates.expand(h.path("headerValue").asText(), scope)));
        }
        if (!ownAuthorization) {
            scope.authorization(identity).forEach((k, v) -> headers.add(Http.header(k, v)));
        }
        return new Req(method, url, List.copyOf(headers), body, bodyText);
    }

    /** A relative reference resolves against the target's base URL (section 6.1). */
    static URI url(String expanded, Scope scope) {
        URI uri;
        try {
            uri = URI.create(expanded);
        } catch (IllegalArgumentException e) {
            throw Unresolvable.cantTell("not a URL: " + expanded);
        }
        return uri.isAbsolute() ? uri : scope.run().target().baseUrl().resolve(uri);
    }

    /** {@code ifMatch: current}: HEAD the URL as the same identity and send the ETag it returns, if any. */
    private static String currentEtag(URI url, String identity, Scope scope) {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        scope.authorization(identity).forEach((k, v) -> headers.add(Http.header(k, v)));
        try {
            Resp head = scope.run().send(new Req("HEAD", url, List.copyOf(headers), null, null));
            return head.first("ETag");
        } catch (IOException e) {
            throw Unresolvable.cantTell("ifMatch: current needs a HEAD of " + url + ", which failed: " + e);
        }
    }

    static byte[] fixture(Path directory, String relative) {
        try {
            return Files.readAllBytes(directory.resolve(relative).normalize());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read fixture " + relative, e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
