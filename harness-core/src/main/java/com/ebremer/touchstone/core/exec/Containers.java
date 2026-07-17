package com.ebremer.touchstone.core.exec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Spec-standard container creation (POST + Link rel="type" lws#Container + Slug). */
final class Containers {

    static final String CONTAINER_TYPE_LINK = "<https://www.w3.org/ns/lws#Container>; rel=\"type\"";

    private Containers() {
    }

    /** Creates a container under {@code parent}, sending {@code headers} (e.g. the provisioner's credentials). */
    static URI create(HttpClient http, URI parent, String slug, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(parent)
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Link", CONTAINER_TYPE_LINK)
                .header("Slug", slug)
                .timeout(Duration.ofSeconds(15));
        headers.forEach(builder::header);
        HttpResponse<Void> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new ProvisioningException("cannot create container '" + slug + "' under " + parent, e);
        }
        if (response.statusCode() != 201) {
            throw new ProvisioningException("container creation under " + parent + " returned "
                    + response.statusCode() + " (expected 201)");
        }
        String location = response.headers().firstValue("Location").orElseThrow(
                () -> new ProvisioningException("container creation under " + parent + " returned no Location"));
        return parent.resolve(location);
    }
}
