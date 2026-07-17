package com.ebremer.touchstone.fixtures.lws;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.ebremer.touchstone.core.exec.ProvisioningAdapters;
import com.ebremer.touchstone.core.exec.RunContext;
import com.ebremer.touchstone.core.exec.Target;
import com.ebremer.touchstone.core.junit.ManifestDynamicTests;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The conformance self-test loop (DESIGN.md paragraph 8): the real manifest suite,
 * loaded by the real JUnit @TestFactory loader, executed by the real engine against
 * the reference server. One DynamicTest per manifest.
 */
class CoreConformanceSuiteTest {

    private static RefLwsServer server;
    private static RunContext ctx;

    @BeforeAll
    static void provision() {
        server = RefLwsServer.start(0);
        Target target = new Target("ref", server.baseUri(), "env", Map.of(), Set.of());
        ctx = ProvisioningAdapters.forTarget(target)
                .provision(target, UUID.randomUUID().toString().substring(0, 8));
    }

    @AfterAll
    static void teardown() {
        if (ctx != null) {
            ctx.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @TestFactory
    Stream<DynamicTest> coreConformanceSuite() {
        return ManifestDynamicTests.forDirectory(Path.of("..", "manifests"), "core", ctx);
    }
}
