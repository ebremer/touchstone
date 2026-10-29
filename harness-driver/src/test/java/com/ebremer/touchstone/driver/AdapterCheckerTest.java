package com.ebremer.touchstone.driver;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.ebremer.touchstone.driver.fakes.MisdeclaringAdapter;
import com.ebremer.touchstone.driver.fakes.OverdeclaringAdapter;
import com.ebremer.touchstone.fixtures.client.RefAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The checker of driver/PROTOCOL.md section 6 passes an adapter that keeps the protocol, and only that. */
class AdapterCheckerTest {

    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    @Test
    void theReferenceAdapterPassesEveryCheck(@TempDir Path tmp) {
        AdapterChecker.Result r = AdapterChecker.check(command(RefAdapter.class), null, Map.of(), tmp.resolve("ref.log"),
                Duration.ofSeconds(30));
        assertThat(r.checks()).filteredOn(c -> !c.passed()).isEmpty();
        assertThat(r.passed()).isTrue();
        assertThat(r.checks()).extracting(AdapterChecker.Check::name)
                .contains("hello", "configure", "listed operation read", "unlisted operation move",
                        "an operation the protocol does not define", "shutdown");
    }

    @Test
    void anOperationImplementedButNotListedFails(@TempDir Path tmp) {
        AdapterChecker.Result r = AdapterChecker.check(command(MisdeclaringAdapter.class), null, Map.of(),
                tmp.resolve("liar.log"), Duration.ofSeconds(30));
        assertThat(r.passed()).isFalse();
        assertThat(r.checks()).filteredOn(c -> !c.passed()).extracting(AdapterChecker.Check::name)
                .containsExactly("unlisted operation read");
    }

    @Test
    void anOperationListedButNotImplementedFails(@TempDir Path tmp) {
        AdapterChecker.Result r = AdapterChecker.check(command(OverdeclaringAdapter.class), null, Map.of(),
                tmp.resolve("boast.log"), Duration.ofSeconds(30));
        assertThat(r.checks()).filteredOn(c -> !c.passed()).extracting(AdapterChecker.Check::name)
                .containsExactly("listed operation move");
    }

    @Test
    void aCommandThatIsNotAnAdapterFailsItsHello(@TempDir Path tmp) {
        AdapterChecker.Result r = AdapterChecker.check(List.of(JAVA, "-version"), null, Map.of(), tmp.resolve("java.log"),
                Duration.ofSeconds(10));
        assertThat(r.hello()).isNull();
        assertThat(r.checks()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("hello");
            assertThat(c.passed()).isFalse();
        });
    }

    @Test
    void sampleArgumentsKeepTheSchema() {
        for (String op : com.ebremer.touchstone.core.definitions.DriverProtocol.operations()) {
            com.fasterxml.jackson.databind.node.ObjectNode request = new com.fasterxml.jackson.databind.ObjectMapper()
                    .createObjectNode().put("id", 1).put("op", op);
            request.set("args", AdapterChecker.sample(op));
            assertThat(com.ebremer.touchstone.driver.protocol.ProtocolSchema.instance().checkRequest(request)).as(op).isNull();
        }
    }

    private static List<String> command(Class<?> main) {
        return List.of(JAVA, "-cp", System.getProperty("java.class.path"), main.getName());
    }
}
