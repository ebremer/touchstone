package com.ebremer.touchstone.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.catalog.Requirement;
import com.ebremer.touchstone.core.definitions.DefinitionLoader;
import com.ebremer.touchstone.core.definitions.Definitions;
import com.ebremer.touchstone.core.definitions.StepDefinition;
import com.ebremer.touchstone.core.definitions.TestDefinition;
import com.ebremer.touchstone.core.exec.Target;
import com.ebremer.touchstone.core.results.Outcome;
import com.ebremer.touchstone.core.results.StepResult;
import com.ebremer.touchstone.core.results.TestResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A step that reads a paged result whole (EXECUTION.md section 4.6): its json expectations judge
 * the items of every page it reaches through rel="next", run by the engine against a stub search
 * that pages as each test scripts.
 */
class PagedStepsTest {

    private static Definitions definitions;

    @BeforeAll
    static void load() {
        Set<String> catalog = CatalogRepository.load(Path.of("..", "catalog")).stream()
                .map(Requirement::iri).collect(Collectors.toSet());
        definitions = DefinitionLoader.load(Path.of("..", "definitions"), catalog);
    }

    /** {"some": a match for c, "none": a match for x}: c is on the last page only. */
    private static final String EXPECT = """
            {"statusCode": 200, "json": [
              {"pointer": "/items", "some": [{"pointer": "/id", "equalsIri": "${target.baseUrl}c"}]},
              {"pointer": "/items", "none": [{"pointer": "/id", "equalsIri": "${target.baseUrl}x"}]}]}""";

    @Test
    void theItemsOfEveryPageAreJudged() throws Exception {
        Map<String, String> accepts = new ConcurrentHashMap<>();
        Search search = new Search(Map.of(
                "/search", page(List.of("a", "b"), "/search?page=2"),
                "/search?page=2", page(List.of("c"), null)), accepts);
        TestResult result = run(search, step(false, 5, EXPECT));

        assertThat(result.outcome()).as(result.toString()).isEqualTo(Outcome.PASSED);
        assertThat(result.steps()).extracting(StepResult::name).contains("search (page 2)");
        // The next page is fetched with GET, with the step's Accept.
        assertThat(accepts).containsEntry("GET /search?page=2", "application/lws+json");
    }

    @Test
    void withoutPagesOnlyTheFirstPageIsJudged() throws Exception {
        Search search = new Search(Map.of(
                "/search", page(List.of("a", "b"), "/search?page=2"),
                "/search?page=2", page(List.of("c"), null)), new ConcurrentHashMap<>());
        assertThat(run(search, step(false, 0, EXPECT)).outcome()).isEqualTo(Outcome.FAILED);
    }

    @Test
    void anItemOnALaterPageCanFailANone() throws Exception {
        Search search = new Search(Map.of(
                "/search", page(List.of("a"), "/search?page=2"),
                "/search?page=2", page(List.of("c", "x"), null)), new ConcurrentHashMap<>());
        assertThat(run(search, step(false, 5, EXPECT)).outcome()).isEqualTo(Outcome.FAILED);
    }

    @Test
    void aNextPageThatIsNoPageFailsTheStep() throws Exception {
        Search search = new Search(Map.of("/search", page(List.of("a"), "/search?page=2")), new ConcurrentHashMap<>());
        TestResult result = run(search, step(false, 5, EXPECT));

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.steps().getLast().name()).isEqualTo("search (page 2)");
        assertThat(result.steps().getLast().assertions().getFirst().actual()).startsWith("404");
    }

    @Test
    void inAPreconditionItMakesTheTestInapplicable() throws Exception {
        Search search = new Search(Map.of("/search", page(List.of("a"), "/search?page=2")), new ConcurrentHashMap<>());
        TestResult result = run(search, step(true, 5, EXPECT));

        assertThat(result.outcome()).isEqualTo(Outcome.INAPPLICABLE);
        assertThat(result.reason()).contains("page 2");
    }

    @Test
    void aNextLinkBackToAPageAlreadyReadFails() throws Exception {
        Search search = new Search(Map.of(
                "/search", page(List.of("a"), "/search?page=2"),
                "/search?page=2", page(List.of("c"), "/search?page=2")), new ConcurrentHashMap<>());
        TestResult result = run(search, step(false, 5, EXPECT));

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.steps().getLast().assertions().getFirst().actual()).contains("already read");
    }

    @Test
    void aResultLongerThanTheStepReadsIsCantTell() throws Exception {
        Search search = new Search(Map.of(
                "/search", page(List.of("a"), "/search?page=2"),
                "/search?page=2", page(List.of("b"), "/search?page=3"),
                "/search?page=3", page(List.of("c"), null)), new ConcurrentHashMap<>());
        TestResult result = run(search, step(false, 2, EXPECT));

        assertThat(result.outcome()).isEqualTo(Outcome.CANT_TELL);
        assertThat(result.reason()).contains("more than 2 pages");
    }

    /** A one-page result is judged as it is. */
    @Test
    void aSinglePageIsAllThereIs() throws Exception {
        Search search = new Search(Map.of("/search", page(List.of("a", "c"), null)), new ConcurrentHashMap<>());
        TestResult result = run(search, step(false, 5, EXPECT));

        assertThat(result.outcome()).isEqualTo(Outcome.PASSED);
        assertThat(result.steps()).extracting(StepResult::name).noneMatch(n -> n.contains("(page"));
    }

    /** A ContainerPage of the named items, linking the next page when there is one. */
    private static String[] page(List<String> ids, String next) {
        String items = ids.stream().map(id -> "{\"id\": \"" + id + "\", \"type\": [\"DataResource\"]}")
                .collect(Collectors.joining(", "));
        return new String[] {"{\"type\": \"ContainerPage\", \"items\": [" + items + "]}", next};
    }

    /** Answers the QUERY to /search with page 1, and GETs of other pages by path and query. */
    private record Search(Map<String, String[]> pages, Map<String, String> accepts) implements StubTarget.Handler {
        @Override
        public boolean handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (!path.equals("/search")) {
                return false;
            }
            String query = exchange.getRequestURI().getRawQuery();
            String key = query == null ? path : path + "?" + query;
            String method = exchange.getRequestMethod();
            accepts.put(method + " " + key, String.valueOf(exchange.getRequestHeaders().getFirst("Accept")));
            String[] page = pages.get(key);
            if (page == null || (query == null) != method.equals("QUERY")) {
                StubTarget.answer(exchange, 404, Map.of(), null);
                return true;
            }
            Map<String, String> headers = page[1] == null ? Map.of("Content-Type", "application/lws+json")
                    : Map.of("Content-Type", "application/lws+json", "Link", "<" + page[1] + ">; rel=\"next\"");
            StubTarget.answer(exchange, 200, headers, page[0]);
            return true;
        }
    }

    private static StepDefinition step(boolean precondition, int pages, String expect) throws IOException {
        var request = Templates.JSON.readTree("""
                {"method": "QUERY", "url": "${target.baseUrl}search", "accept": "application/lws+json",
                 "contentType": "application/lws-query+json", "bodyJSON": {"type": ["urn:x:Alpha"]}}""");
        return new StepDefinition("search", null, precondition, request, Templates.JSON.readTree(expect), 0, 0, pages);
    }

    private static TestResult run(StubTarget.Handler handler, StepDefinition step) throws IOException {
        try (StubTarget storage = StubTarget.start(handler)) {
            Target target = new Target("stub", storage.base(), "env", Map.of(), Set.of());
            TestDefinition test = new TestDefinition("engine/paging#search", "engine/paging", "search",
                    Definitions.BASE + "engine/paging#search", "ValidationTest", "a paged search", "MUST",
                    "Proposed", List.of(), List.of("Discovery"), List.of(), null, List.of(), null, List.of(step),
                    Path.of("..", "definitions", "lws10"));
            return Engine.runOne(target, definitions, test);
        }
    }
}
