package com.ebremer.touchstone.core.catalog;

/**
 * One RFC 2119 conformance clause from the requirements catalog (DESIGN.md paragraph 5.1).
 * Tests declare the requirement IRIs they verify; EARL reports reuse the IRIs as
 * test criteria.
 */
public record Requirement(
        String iri,
        String level,
        String specModule,
        String section,
        String summary,
        String clauseText,
        String status) {
}
