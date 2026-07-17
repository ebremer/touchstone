package com.ebremer.touchstone.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Project identity, kept in one place so a rename stays trivial (DESIGN.md notes a
 * known name collision with an HL7/FHIR conformance platform before public release).
 */
public final class Touchstone {

    public static final String NAME = "Touchstone";

    /**
     * Base IRI for the requirements catalog and vocabulary. Placeholder namespace
     * until a permanent one is chosen (DECISIONS.md D-0001); relocatable by design.
     */
    public static final String VOCAB_NS = "https://example.org/touchstone/vocab#";

    /** Base IRI minted for test cases in EARL reports (same placeholder namespace). */
    public static final String TEST_NS = "https://example.org/touchstone/test/";

    /** IRI identifying the harness itself as the EARL assertor. */
    public static final String HARNESS_IRI = "https://example.org/touchstone/harness";

    private static final String VERSION = loadVersion();

    private Touchstone() {
    }

    public static String version() {
        return VERSION;
    }

    private static String loadVersion() {
        Properties props = new Properties();
        try (InputStream in = Touchstone.class.getResourceAsStream("/touchstone.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read touchstone.properties", e);
        }
        return props.getProperty("touchstone.version", "unknown");
    }
}
