package com.ebremer.touchstone.core.definitions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.catalog.Requirement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefinitionLoaderTest {

    static final Path DEFINITIONS = Path.of("..", "definitions");
    static final Set<String> CATALOG = CatalogRepository.load(Path.of("..", "catalog")).stream()
            .map(Requirement::iri).collect(Collectors.toSet());

    @Test
    void loadsEveryDefinitionInTraversalOrder() {
        Definitions defs = DefinitionLoader.load(DEFINITIONS, CATALOG);
        assertThat(defs.tests()).hasSize(203);
        assertThat(defs.tests().getFirst().id()).isEqualTo("core/discovery#discovery-get-links-storageDescription");
        assertThat(defs.tests().stream().map(TestDefinition::level).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("MUST", "SHOULD", "MAY");
        assertThat(defs.identities()).containsKeys("anonymous", "alice", "bob", "didkey", "saml-unsigned");
    }

    @Test
    void theShortFormIsOneStepLabelledWithTheTest() {
        Definitions defs = DefinitionLoader.load(DEFINITIONS, CATALOG);
        TestDefinition t = defs.find("getContainer-private-unauthorized").orElseThrow();
        assertThat(t.steps()).hasSize(1);
        assertThat(t.steps().getFirst().label()).isEqualTo(t.label());
        assertThat(t.identity()).isEqualTo("anonymous");
        assertThat(t.iri()).isEqualTo(Definitions.BASE + "core/storage_authorization#getContainer-private-unauthorized");
    }

    @Test
    void selectorsNameModulesManifestsAndTests() {
        Definitions defs = DefinitionLoader.load(DEFINITIONS, CATALOG);
        assertThat(defs.select("all")).hasSize(203);
        assertThat(defs.select("auth")).hasSize(31);
        assertThat(defs.select("auth/oidc")).hasSize(6);
        assertThat(defs.select("core/containers")).hasSize(18);
        assertThat(defs.select("core/containers#getContainer")).hasSize(1);
        assertThat(defs.select("getContainer")).hasSize(1);
        assertThat(defs.select("cor")).isEmpty();
    }

    @Test
    void aRequirementTheCatalogLacksIsRefused() {
        assertThatThrownBy(() -> DefinitionLoader.load(DEFINITIONS, Set.of()))
                .isInstanceOf(InvalidDefinitionsException.class)
                .hasMessageContaining("is not in the catalog");
    }

    @Test
    void anUndefinedTermIsRefused(@TempDir Path tmp) throws IOException {
        Path copy = copy(tmp);
        Path file = copy.resolve("lws10/core/notifications.yamlld");
        Files.writeString(file, Files.readString(file).replace("    level: MUST", "    level: MUST\n    levle: MUST"));
        // The schema is strict, so it is the first to object; the JSON-LD safe mode behind it
        // is the second guard.
        assertThatThrownBy(() -> DefinitionLoader.load(copy, CATALOG))
                .isInstanceOf(InvalidDefinitionsException.class)
                .hasMessageContaining("notifications.yamlld")
                .hasMessageContaining("levle");
    }

    @Test
    void yaml11BooleansStayStrings() {
        assertThat(Yaml12.parse("a: yes\nb: 2026-09-21\nc: on\n", "t").toString())
                .isEqualTo("{\"a\":\"yes\",\"b\":\"2026-09-21\",\"c\":\"on\"}");
    }

    @Test
    void yamlFeaturesJsonCannotExpressAreRefused() {
        assertThatThrownBy(() -> Yaml12.parse("a: &x 1\nb: *x\n", "t")).hasMessageContaining("anchor");
        assertThatThrownBy(() -> Yaml12.parse("a: !!str 1\n", "t")).hasMessageContaining("tag");
        assertThatThrownBy(() -> Yaml12.parse("a: 1\na: 2\n", "t")).isInstanceOf(InvalidDefinitionsException.class);
        assertThatThrownBy(() -> Yaml12.parse("a: 1\n---\nb: 2\n", "t")).hasMessageContaining("2 YAML documents");
    }

    @Test
    void anExampleHostInAnExecutableValueIsRefused(@TempDir Path tmp) throws IOException {
        Path copy = copy(tmp);
        Path file = copy.resolve("lws10/core/discovery.yamlld");
        String s = Files.readString(file);
        Files.writeString(file, first(s, "url: \"${test.container}\"", "url: \"https://storage.example/alice/\""));
        assertThatThrownBy(() -> DefinitionLoader.load(copy, CATALOG))
                .hasMessageContaining("example host storage.example");
    }

    @Test
    void anUnboundVariableIsRefused(@TempDir Path tmp) throws IOException {
        Path copy = copy(tmp);
        Path file = copy.resolve("lws10/core/discovery.yamlld");
        String s = Files.readString(file);
        Files.writeString(file, first(s, "url: \"${test.container}\"", "url: \"${nowhere}\""));
        assertThatThrownBy(() -> DefinitionLoader.load(copy, CATALOG))
                .hasMessageContaining("${nowhere}");
    }

    @Test
    void aClientRuleInAServerManifestIsRefused(@TempDir Path tmp) throws IOException {
        Path copy = copy(tmp);
        Path file = copy.resolve("lws10/core/discovery.yamlld");
        Files.writeString(file, Files.readString(file).replace("\nentries:\n", """

                entries:
                  - id: "#client-put-conditional"
                    type: ObservationTest
                    name: client-put-conditional
                    label: The client makes a PUT that replaces a data resource conditional
                    status: Proposed
                    level: SHOULD
                    source:
                      - https://www.w3.org/TR/2026/WD-lws10-core-20260921/#update-resource
                    traits: [Put]
                    area: core
                    requirements:
                      - https://example.org/touchstone/req/lws10-core/put-clients-use-conditional-requests
                    observe:
                      role: dataResource
                      method: PUT
                    expect:
                      otherHeaders:
                        - headerName: If-Match
                          present: true
                    guidance: Send If-Match.
                """));
        // The schema admits client rules (format 0.8.0); only lws10/clients/ may hold them.
        assertThatThrownBy(() -> DefinitionLoader.load(copy, CATALOG))
                .isInstanceOf(InvalidDefinitionsException.class)
                .hasMessageContaining("client-put-conditional is a client rule");
        // A condition is as strict as the rest of the schema: a term it lacks is an error.
        Files.writeString(file, Files.readString(file).replace("      method: PUT\n", "      method: PUT\n      identity: alice\n"));
        assertThatThrownBy(() -> DefinitionLoader.load(copy, CATALOG))
                .isInstanceOf(InvalidDefinitionsException.class)
                .hasMessageContaining("identity");
    }

    @Test
    void theBundledSchemaIsTheDefinitionsSchema() throws IOException {
        try (var in = DefinitionLoader.class.getResourceAsStream(DefinitionLoader.SCHEMA_RESOURCE)) {
            assertThat(new String(in.readAllBytes()))
                    .isEqualTo(Files.readString(DEFINITIONS.resolve("schema/definitions.schema.json")));
        }
    }

    static String first(String s, String target, String replacement) {
        int i = s.indexOf(target);
        assertThat(i).as("fixture text " + target).isNotNegative();
        return s.substring(0, i) + replacement + s.substring(i + target.length());
    }

    static Path copy(Path tmp) throws IOException {
        Path target = tmp.resolve("definitions");
        try (Stream<Path> files = Files.walk(DEFINITIONS)) {
            for (Path p : files.toList()) {
                Path dest = target.resolve(DEFINITIONS.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(p, dest);
                }
            }
        }
        return target;
    }
}
