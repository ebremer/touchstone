package com.ebremer.touchstone.mcp.config;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import com.ebremer.touchstone.core.catalog.CatalogRepository;
import com.ebremer.touchstone.core.catalog.Requirement;

/** The requirements catalog, loaded once at startup and shared by the read-only tools. */
public final class Catalog {

    private final List<Requirement> requirements;

    public Catalog(TouchstoneProperties props) {
        this.requirements = Files.isDirectory(props.catalog())
                ? CatalogRepository.load(props.catalog())
                : List.of();
    }

    public List<Requirement> all() {
        return requirements;
    }

    public Optional<Requirement> find(String iri) {
        return requirements.stream().filter(r -> r.iri().equals(iri)).findFirst();
    }
}
