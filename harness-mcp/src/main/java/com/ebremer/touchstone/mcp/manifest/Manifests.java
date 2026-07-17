package com.ebremer.touchstone.mcp.manifest;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import com.ebremer.touchstone.core.manifest.Manifest;
import com.ebremer.touchstone.core.manifest.ManifestLoader;
import com.ebremer.touchstone.mcp.config.TouchstoneProperties;

/** Access to the test manifests, loaded once at startup (they are static repo content). */
public final class Manifests {

    private final List<Manifest> manifests;

    public Manifests(TouchstoneProperties props) {
        this.manifests = Files.isDirectory(props.manifests())
                ? ManifestLoader.loadDirectory(props.manifests())
                : List.of();
    }

    public List<Manifest> all() {
        return manifests;
    }

    public List<Manifest> module(String module) {
        return manifests.stream().filter(m -> m.module().equals(module)).toList();
    }

    public Optional<Manifest> find(String testId) {
        return manifests.stream().filter(m -> m.id().equals(testId)).findFirst();
    }
}
