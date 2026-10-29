package com.ebremer.touchstone.driver.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * Everything an adapter writes is untrusted (CLIENT-TESTING.md section 14.3): the connection takes
 * a hello and responses that keep the protocol, and calls any other adapter broken, which ends its
 * drive and is never a finding about the client.
 */
class AdapterConnectionTest {

    private static final String HELLO = """
            {"hello": {"protocol": "touchstone-driver/1", "client": {"name": "a client"}, "auth": ["bearer"],
             "operations": ["read", "a_future_operation"]}}
            """.replace("\n", " ").strip() + "\n";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration SHORT = Duration.ofSeconds(2);

    @Test
    void aHelloNamesTheClientAndItsOperations() {
        AdapterConnection c = connect(HELLO, new ByteArrayOutputStream());
        assertThat(c.hello().client().name()).isEqualTo("a client");
        assertThat(c.hello().implementsOperation("read")).isTrue();
        assertThat(c.hello().implementsOperation("configure")).isTrue();
        assertThat(c.hello().implementsOperation("search")).isFalse();
        assertThat(c.hello().operations()).contains("a_future_operation");
    }

    @Test
    void theHelloOfLwsDriver1IsNotOne() {
        String old = "{\"hello\": {\"protocol\": \"lws-driver/1\", \"language\": \"go\", \"library\": \"x\", \"operations\": []}}\n";
        assertThatThrownBy(() -> connect(old, new ByteArrayOutputStream()))
                .isInstanceOf(BrokenAdapterException.class).hasMessageContaining("its hello");
    }

    @Test
    void anAdapterThatSaysNothingIsBroken() {
        assertThatThrownBy(() -> connect("", new ByteArrayOutputStream()))
                .isInstanceOf(BrokenAdapterException.class).hasMessageContaining("before its hello");
    }

    @Test
    void aLineThatIsNotJsonBreaksTheAdapter() {
        assertThatThrownBy(() -> connect("Starting the adapter...\n" + HELLO, new ByteArrayOutputStream()))
                .isInstanceOf(BrokenAdapterException.class).hasMessageContaining("not JSON");
    }

    @Test
    void aResponseIsReadAndAnErrorIsAnOutcome() throws Exception {
        try (Scripted adapter = new Scripted()) {
            adapter.say(HELLO);
            AdapterConnection c = adapter.connection();
            adapter.say("{\"id\": 1, \"ok\": false, \"error\": {\"kind\": \"http\", \"status\": 404, \"message\": \"gone\"}}\n");
            Reply r = c.call("read", args("url", "https://s.test/a"), SHORT);
            assertThat(r.ok()).isFalse();
            assertThat(r.error().status()).isEqualTo(404);
            assertThat(r.error().describe()).isEqualTo("http 404: gone");
            assertThat(adapter.sent()).contains("\"op\":\"read\"").contains("\"id\":1");
        }
    }

    @Test
    void aResponseToAnotherRequestBreaksTheAdapter() throws Exception {
        try (Scripted adapter = new Scripted()) {
            adapter.say(HELLO);
            AdapterConnection c = adapter.connection();
            adapter.say("{\"id\": 7, \"ok\": true, \"result\": {}}\n");
            assertThatThrownBy(() -> c.call("read", args("url", "https://s.test/a"), SHORT))
                    .isInstanceOf(BrokenAdapterException.class).hasMessageContaining("outstanding");
            assertThat(c.isBroken()).isTrue();
            assertThatThrownBy(() -> c.call("read", args("url", "https://s.test/a"), SHORT))
                    .isInstanceOf(BrokenAdapterException.class);
        }
    }

    @Test
    void aResultThatBreaksTheSchemaBreaksTheAdapter() throws Exception {
        try (Scripted adapter = new Scripted()) {
            adapter.say(HELLO);
            AdapterConnection c = adapter.connection();
            adapter.say("{\"id\": 1, \"ok\": true, \"result\": {\"notModified\": false}}\n");
            assertThatThrownBy(() -> c.call("read", args("url", "https://s.test/a"), SHORT))
                    .isInstanceOf(BrokenAdapterException.class).hasMessageContaining("breaks the schema");
        }
    }

    @Test
    void anAdapterThatDoesNotAnswerInTimeIsBroken() throws Exception {
        try (Scripted adapter = new Scripted()) {
            adapter.say(HELLO);
            AdapterConnection c = adapter.connection();
            assertThatThrownBy(() -> c.call("read", args("url", "https://s.test/a"), Duration.ofMillis(200)))
                    .isInstanceOf(BrokenAdapterException.class).hasMessageContaining("within");
        }
    }

    @Test
    void aRequestOutsideTheProtocolIsTheDriversDefectNotTheAdapters() throws Exception {
        try (Scripted adapter = new Scripted()) {
            adapter.say(HELLO);
            AdapterConnection c = adapter.connection();
            assertThatThrownBy(() -> c.call("read", args("url", "/relative"), SHORT))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(c.isBroken()).isFalse();
        }
    }

    private static ObjectNode args(String name, String value) {
        return JSON.createObjectNode().put(name, value);
    }

    private static AdapterConnection connect(String output, ByteArrayOutputStream input) {
        InputStream in = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
        return new AdapterConnection(in, input, ProtocolSchema.instance(), SHORT);
    }

    /** An adapter whose output the test writes line by line, and whose input it reads afterwards. */
    private static final class Scripted implements AutoCloseable {
        private final PipedOutputStream out = new PipedOutputStream();
        private final PipedInputStream in;
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();

        Scripted() throws IOException {
            in = new PipedInputStream(out, 1 << 16);
        }

        void say(String line) throws IOException {
            out.write(line.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        AdapterConnection connection() {
            return new AdapterConnection(in, received, ProtocolSchema.instance(), SHORT);
        }

        String sent() {
            return received.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }
}
