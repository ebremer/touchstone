package com.ebremer.touchstone.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The proxy targets' registry (CLIENT-TESTING.md section 10): only front-door targets, checked as they load. */
class ProxyTargetsTest {

    private static final URI BASE = URI.create("https://lab.example/touchstone/clients");

    @TempDir
    Path dir;

    @Test
    void aFrontDoorTargetLoads() throws Exception {
        ProxyTargets targets = load("""
                targets:
                  local:
                    baseUrl: https://lab.example/touchstone/clients/p/local/storage/
                    adapter: env
                    properties:
                      proxy.backend: http://127.0.0.1:8090/
                      proxy.issuer: https://lab.example/touchstone/clients/p/local/as
                """);
        ProxyTargets.ProxyTarget t = targets.find("local").orElseThrow();
        assertThat(t.prefix()).isEqualTo("https://lab.example/touchstone/clients/p/local/");
        assertThat(t.issuer()).endsWith("/p/local/as");
        ProxySession proxy = new ProxySession(t, "/.well-known/lws-configuration/touchstone/clients/p/local",
                new Recorder(u -> true, 10));
        assertThat(proxy.backendFor("/touchstone/clients/p/local/storage/x", "a=1"))
                .isEqualTo(URI.create("http://127.0.0.1:8090/storage/x?a=1"));
        assertThat(proxy.backendFor("/.well-known/lws-configuration/touchstone/clients/p/local/as", null))
                .isEqualTo(URI.create("http://127.0.0.1:8090/.well-known/lws-configuration/touchstone/clients/p/local/as"));
    }

    @Test
    void aTargetThatIsNotAFrontDoorIsRefused() {
        assertThatThrownBy(() -> load("""
                targets:
                  elsewhere:
                    baseUrl: https://real.example/lws/
                    adapter: env
                    properties:
                      proxy.backend: https://real.example/lws/
                """)).hasMessageContaining("must start with https://lab.example/touchstone/clients/p/elsewhere/");
        assertThatThrownBy(() -> load("""
                targets:
                  nobackend:
                    baseUrl: https://lab.example/touchstone/clients/p/nobackend/
                    adapter: env
                """)).hasMessageContaining("needs proxy.backend");
        assertThatThrownBy(() -> load("""
                targets:
                  "../up":
                    baseUrl: https://lab.example/touchstone/clients/p/x/
                    adapter: env
                """)).hasMessageContaining("not a simple name");
    }

    private ProxyTargets load(String yaml) throws Exception {
        Path file = dir.resolve("targets.yaml");
        Files.writeString(file, yaml);
        return ProxyTargets.load(file, BASE);
    }
}
