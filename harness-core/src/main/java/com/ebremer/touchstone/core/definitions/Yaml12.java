package com.ebremer.touchstone.core.definitions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.AliasEvent;
import org.snakeyaml.engine.v2.events.CollectionStartEvent;
import org.snakeyaml.engine.v2.events.DocumentStartEvent;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.events.NodeEvent;
import org.snakeyaml.engine.v2.events.ScalarEvent;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.schema.CoreSchema;

/**
 * Reads one {@code .yamlld} file the way YAML-LD requires (EXECUTION.md section 2.1): YAML
 * 1.2 with the Core Schema, so {@code yes} stays a string and {@code 2026-09-21} is not a
 * date. The result is the JSON form the schema validates and the executor reads.
 *
 * <p>YAML features JSON has no counterpart for are refused rather than interpreted: a second
 * document, a duplicate key, an explicit tag, an anchor or an alias. Each would let the file
 * mean something its JSON-LD export could not say.
 */
final class Yaml12 {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final LoadSettings SETTINGS = LoadSettings.builder()
            .setSchema(new CoreSchema())
            .setAllowDuplicateKeys(false)
            .setAllowRecursiveKeys(false)
            .setMaxAliasesForCollections(0)
            .setDefaultMap(LinkedHashMap::new)
            .build();

    private Yaml12() {
    }

    static JsonNode read(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        return parse(text, file.toString());
    }

    static JsonNode parse(String text, String where) {
        if (text.indexOf('\t') >= 0) {
            // A tab is never indentation in YAML, and a definition has no reason to contain one.
            throw new InvalidDefinitionsException(where + ": contains a tab character");
        }
        int documents = 0;
        try {
            for (Event event : new Parse(SETTINGS).parseString(text)) {
                if (event instanceof DocumentStartEvent) {
                    documents++;
                } else if (event instanceof AliasEvent) {
                    throw new InvalidDefinitionsException(where + ": uses an alias " + line(event));
                }
                if (event instanceof NodeEvent node && node.getAnchor().isPresent()) {
                    throw new InvalidDefinitionsException(where + ": declares an anchor " + line(event));
                }
                boolean tagged = event instanceof ScalarEvent scalar ? scalar.getTag().isPresent()
                        : event instanceof CollectionStartEvent collection && collection.getTag().isPresent();
                if (tagged) {
                    throw new InvalidDefinitionsException(where + ": uses an explicit tag " + line(event));
                }
            }
        } catch (YamlEngineException e) {
            throw new InvalidDefinitionsException(where + ": not valid YAML 1.2: " + e.getMessage());
        }
        if (documents != 1) {
            throw new InvalidDefinitionsException(where + ": holds " + documents + " YAML documents, not one");
        }
        Object value;
        try {
            value = new Load(SETTINGS).loadFromString(text);
        } catch (YamlEngineException e) {
            throw new InvalidDefinitionsException(where + ": not valid YAML 1.2: " + e.getMessage());
        }
        checkKeys(value, where);
        return JSON.valueToTree(value);
    }

    /** Mapping keys must be strings: JSON has no other kind. */
    private static void checkKeys(Object value, String where) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String)) {
                    throw new InvalidDefinitionsException(where + ": mapping key " + e.getKey()
                            + " is not a string");
                }
                checkKeys(e.getValue(), where);
            }
        } else if (value instanceof List<?> list) {
            list.forEach(v -> checkKeys(v, where));
        }
    }

    private static String line(Event event) {
        return event.getStartMark().map(m -> "at line " + (m.getLine() + 1)).orElse("");
    }
}
