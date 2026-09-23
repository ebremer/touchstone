package com.ebremer.touchstone.core.definitions;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An entry of {@code lws10/identities.yamlld} (EXECUTION.md section 5). Templates stay in
 * their JSON form: they are expanded when the credential is minted.
 *
 * @param kind  NoCredential, StorageAccessToken or SubjectCredential
 * @param basis the identity a fault identity starts from, or null
 * @param fault the one defect a fault identity adds, or null
 */
public record IdentityDefinition(
        String name,
        String label,
        String kind,
        String basis,
        String fault,
        List<String> requires,
        String suite,
        String tokenType,
        String algorithm,
        String webid,
        JsonNode credentialHeader,
        JsonNode credentialClaims,
        JsonNode identityDocument,
        JsonNode samlAssertion) {

    public static final String NO_CREDENTIAL = "NoCredential";
    public static final String STORAGE_ACCESS_TOKEN = "StorageAccessToken";
    public static final String SUBJECT_CREDENTIAL = "SubjectCredential";

    public boolean isFault() {
        return fault != null;
    }
}
