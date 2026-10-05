package com.ebremer.touchstone.core.catalog;

import java.util.List;
import java.util.Set;

/**
 * One RFC 2119 conformance clause from the requirements catalog (DESIGN.md paragraph 5.1).
 * Tests declare the requirement IRIs they verify; EARL reports reuse the IRIs as
 * test criteria.
 *
 * @param appliesTo the roles the clause binds ({@code touchstone:appliesTo}, D-0076), by local
 *     name: {@code Server}, {@code AuthorizationServer}, {@code Client}, {@code IdentityProvider},
 *     {@code Receiver} or {@code Specification}; empty when the catalog does not say
 */
public record Requirement(
        String iri,
        String level,
        String specModule,
        String section,
        String summary,
        String clauseText,
        String status,
        List<String> appliesTo) {

    /** The roles a server run answers for: the storage and its authorization server. */
    public static final Set<String> SERVER_SIDE = Set.of("Server", "AuthorizationServer");

    public Requirement {
        appliesTo = appliesTo == null ? List.of() : List.copyOf(appliesTo);
    }

    /**
     * Whether a server run answers for this clause: it binds a server or an authorization server,
     * or the catalog names no role for it (a catalog older than D-0076). The others bind only
     * clients, receivers, identity providers or other specifications, which a server run cannot
     * break (CLIENT-TESTING.md).
     */
    public boolean bindsServerSide() {
        return appliesTo.isEmpty() || appliesTo.stream().anyMatch(SERVER_SIDE::contains);
    }
}
