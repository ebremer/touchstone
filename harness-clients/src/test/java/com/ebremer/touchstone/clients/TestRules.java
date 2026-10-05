package com.ebremer.touchstone.clients;

import java.nio.file.Path;

import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.definitions.ClientRules;
import com.ebremer.touchstone.core.definitions.DefinitionLoader;

/** The repository's client rules, loaded and checked once for every test. */
final class TestRules {

    static final ClientRules RULES = DefinitionLoader.loadClientRules(Path.of("..", "definitions"),
            CatalogRepository.load(Path.of("..", "catalog")));

    private TestRules() {
    }
}
