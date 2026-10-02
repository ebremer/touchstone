package com.ebremer.touchstone.fixtures.definitions;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.definitions.DefinitionLoader;
import com.ebremer.touchstone.core.definitions.Definitions;
import com.ebremer.touchstone.core.definitions.TestDefinition;
import com.ebremer.touchstone.core.engine.Engine;
import com.ebremer.touchstone.core.exec.Target;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.Results;
import com.ebremer.touchstone.core.results.RunResult;
import com.ebremer.touchstone.core.results.TestResult;
import com.ebremer.touchstone.fixtures.ReferenceScenario;
import com.ebremer.touchstone.fixtures.ReferenceScenario.Kind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The self-test loop (DESIGN.md section 8) for the YAML-LD definitions: every definition runs
 * through the engine against the reference deployment, and against its broken twins.
 *
 * <p>Against the compliant deployment every test passes, except the one that checks a
 * notification service's advertisement, which the reference does not provide (D-0041). Against
 * each broken twin the tests that exist to catch its defect fail, and nothing else does: that
 * is what shows they can fail, which a test that only ever passes never shows (D-0017).
 */
class DefinitionsSelfTest {

    /**
     * The reference splits the five-member container into pages, so the test for a server that
     * presents it as one page does not apply to it; every other definition does.
     */
    private static final String SINGLE_PAGE = "core/pagination#pagination-single-page";
    private static Definitions definitions;

    @BeforeAll
    static void load() {
        Set<String> catalog = CatalogRepository.load(Path.of("..", "catalog")).stream()
                .map(Requirement::iri).collect(Collectors.toSet());
        definitions = DefinitionLoader.load(Path.of("..", "definitions"), catalog);
    }

    @Test
    void everyDefinitionPassesAgainstTheReferenceDeployment() {
        try (ReferenceScenario scenario = ReferenceScenario.start(Kind.SECURED)) {
            RunResult run = run(scenario);

            assertThat(run.results()).hasSize(definitions.tests().size());
            assertThat(notPassed(run)).as(details(run)).containsExactly(Map.entry(SINGLE_PAGE, Outcome.INAPPLICABLE));
            assertThat(run.conformant()).isTrue();
            // Every test container, grant and access request is gone, and the run root with them.
            assertThat(scenario.storage().residue()).as("left on the storage").isEmpty();
        }
    }

    @Test
    void anOpenStorageMakesEveryAuthenticationTestInapplicable() {
        try (ReferenceScenario scenario = ReferenceScenario.start(Kind.OPEN)) {
            RunResult run = run(scenario);

            // It declares no capability: neither Authentication nor a fixture host it can reach.
            Set<String> needAuthentication = definitions.tests().stream()
                    .filter(t -> t.requires().contains("Authentication") || t.requires().contains("ReachableFixtures"))
                    .map(TestDefinition::id).collect(Collectors.toCollection(TreeSet::new));
            Set<String> expected = new TreeSet<>(needAuthentication);
            expected.add(SINGLE_PAGE);
            assertThat(notPassed(run).keySet()).as(details(run)).isEqualTo(expected);
            assertThat(notPassed(run).values()).containsOnly(Outcome.INAPPLICABLE);
            assertThat(scenario.storage().residue()).isEmpty();
        }
    }

    /** A token endpoint that exchanges anything: every credential defect goes unnoticed. */
    @Test
    void theAuthenticationNegativeTestsFailAgainstABrokenAuthorizationServer() {
        try (ReferenceScenario scenario = ReferenceScenario.start(Kind.BROKEN_AUTHORIZATION_SERVER)) {
            RunResult run = run(scenario);

            Set<String> expected = definitions.tests().stream()
                    .filter(t -> t.module().equals("auth") && t.type().equals("NegativeTest"))
                    .map(TestDefinition::id).collect(Collectors.toCollection(TreeSet::new));
            expected.add("core/authorization_server#authz-token-exchange-invalid-resource");
            assertThat(failed(run)).as(details(run)).isEqualTo(expected);
            assertThat(expected).hasSize(23);
            assertThat(notPassed(run)).doesNotContainValue(Outcome.CANT_TELL);
        }
    }

    /** A storage that never challenges and never forbids: every access-control test notices. */
    @Test
    void theAccessControlTestsFailAgainstABrokenStorage() {
        try (ReferenceScenario scenario = ReferenceScenario.start(Kind.BROKEN_STORAGE)) {
            RunResult run = run(scenario);

            assertThat(failed(run)).as(details(run)).containsExactlyInAnyOrder(
                    "core/discovery#discovery-unauthorized-response-headers",
                    "core/storage_authorization#getContainer-private-unauthorized",
                    "core/storage_authorization#createDataResource-unauthorized",
                    "core/storage_authorization#updateDataResource-unauthorized",
                    "core/storage_authorization#deleteDataResource-unauthorized",
                    "core/storage_authorization#authz-non-owner-read-rejected",
                    "core/storage_authorization#authz-non-owner-write-rejected",
                    "core/storage_authorization#authz-non-owner-delete-rejected",
                    "core/storage_authorization#authz-expired-token-rejected",
                    "core/storage_authorization#authz-token-bad-signature-rejected",
                    "core/storage_authorization#authz-token-alg-none-rejected",
                    "core/storage_authorization#authz-token-unknown-key-rejected",
                    "core/access_grants#access-grant-revoke",
                    "core/access_grants#access-grant-authenticated-agent",
                    "core/access_grants#access-grant-incomplete-refused",
                    "core/access_grants#access-grant-constraints-all-satisfied",
                    "core/access_grants#access-grant-constraint-format",
                    "core/access_grants#access-grant-constraint-type",
                    "core/access_grants#access-grant-constraint-client",
                    "core/notifications#subscription-unreadable-topic-refused",
                    "index/manifest#type-index-omits-unreadable-type",
                    "index/manifest#type-search-authorization-filtered");
            // It reveals no authorization server, so everything that needs one is inapplicable,
            // not failed: the harness cannot tell what it cannot reach.
            assertThat(notPassed(run)).doesNotContainValue(Outcome.CANT_TELL);
        }
    }

    /**
     * A storage that delivers notifications to subscribers who may not read the resource, and
     * signs them with a key it does not publish: the delivery-time authorization tests and the
     * signature verification test notice, and nothing else does.
     */
    @Test
    void theDeliveryAuthorizationTestsFailAgainstALeakingStorage() {
        try (ReferenceScenario scenario = ReferenceScenario.start(Kind.BROKEN_NOTIFICATIONS)) {
            RunResult run = run(scenario);

            assertThat(failed(run)).as(details(run)).containsExactlyInAnyOrder(
                    "core/notifications#notification-not-delivered-for-unreadable-resource",
                    "core/notifications#notification-stops-after-revocation",
                    "notifications/webhook/manifest#webhook-signature-verifies");
            assertThat(notPassed(run)).doesNotContainValue(Outcome.CANT_TELL);
        }
    }

    private static RunResult run(ReferenceScenario scenario) {
        Target target = new Target("reference", scenario.storageBaseUri(), "env", scenario.properties(),
                new LinkedHashSet<>(scenario.capabilities()));
        return Engine.run(target, definitions, definitions.tests(), Engine.ProgressListener.NONE);
    }

    private static Map<String, Outcome> notPassed(RunResult run) {
        Map<String, Outcome> out = new LinkedHashMap<>();
        run.results().stream().filter(r -> r.outcome() != Outcome.PASSED)
                .forEach(r -> out.put(r.testId(), r.outcome()));
        return out;
    }

    private static Set<String> failed(RunResult run) {
        return run.results().stream().filter(r -> r.outcome() == Outcome.FAILED)
                .map(TestResult::testId).collect(Collectors.toCollection(TreeSet::new));
    }

    private static String details(RunResult run) {
        List<String> lines = run.results().stream().filter(r -> r.outcome() != Outcome.PASSED)
                .map(Results::describe).toList();
        return String.join("\n\n", lines);
    }
}
