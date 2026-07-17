package com.ebremer.touchstone.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jetty.JettyWebServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TouchstoneMcpApplicationTest {

    @Autowired
    private WebServerApplicationContext context;

    @Test
    void bootsOnJettyNeverTomcat() {
        assertThat(context.getWebServer()).isInstanceOf(JettyWebServer.class);
    }
}
