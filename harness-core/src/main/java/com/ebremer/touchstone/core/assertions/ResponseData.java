package com.ebremer.touchstone.core.assertions;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/** The observable parts of one HTTP response, as the assertion engine sees them. */
public record ResponseData(int status, HttpHeaders headers, byte[] body, URI uri, String contentType) {

    public static ResponseData of(HttpResponse<byte[]> response) {
        return new ResponseData(
                response.statusCode(),
                response.headers(),
                response.body() == null ? new byte[0] : response.body(),
                response.uri(),
                response.headers().firstValue("Content-Type").orElse(null));
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
