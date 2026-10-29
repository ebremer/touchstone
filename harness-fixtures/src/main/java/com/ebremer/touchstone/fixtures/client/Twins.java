package com.ebremer.touchstone.fixtures.client;

import static java.util.Map.entry;

import java.util.Map;
import java.util.Set;

import com.ebremer.touchstone.fixtures.client.RefLwsClient.Flaw;

/**
 * The client rules each broken twin of the reference client exists to break (CLIENT-TESTING.md
 * section 9). The client rules' self-test and the driver's (section 14.5) both hold every twin to
 * exactly these: a twin that fails another rule, or misses one of these, is a defect in the twin, the
 * rule, or the drive that should reach it.
 */
public final class Twins {

    /** The rules each twin exists to break. */
    public static final Map<Flaw, Set<String>> AIMED = Map.ofEntries(
            entry(Flaw.TOKEN_ALSO_IN_OWN_HEADER, Set.of("client-token-in-authorization-header")),
            entry(Flaw.LINKSET_PUT_UNADVERTISED, Set.of("client-linkset-put-only-when-advertised")),
            entry(Flaw.LINKSET_PATCH_FORMAT_UNADVERTISED, Set.of("client-linkset-patch-format-advertised")),
            entry(Flaw.PUT_UNCONDITIONAL, Set.of("client-put-conditional")),
            entry(Flaw.LINKSET_WRITE_UNCONDITIONAL, Set.of("client-linkset-write-conditional")),
            entry(Flaw.BUILDS_PAGE_URL, Set.of("client-page-urls-issued")),
            entry(Flaw.BUILDS_MEMBER_URL, Set.of("client-member-urls-issued")),
            entry(Flaw.ACCESS_CONTEXT_NOT_ARRAY, Set.of("client-access-document-context")),
            entry(Flaw.ACCESS_REQUEST_WRONG_TYPE, Set.of("client-access-request-type")),
            entry(Flaw.ACCESS_GRANT_WITHOUT_TYPE, Set.of("client-access-grant-type")),
            entry(Flaw.ACCESS_WITHOUT_STORAGE, Set.of("client-access-document-storage")),
            entry(Flaw.ACCESS_NOT_ARRAY, Set.of("client-access-document-access")),
            entry(Flaw.POLICY_WITHOUT_TYPE, Set.of("client-access-policy-type")),
            entry(Flaw.POLICY_UNKNOWN_ACTION, Set.of("client-access-policy-action")),
            entry(Flaw.POLICY_ASSIGNEE_NOT_URI, Set.of("client-access-policy-assignee")),
            entry(Flaw.POLICY_TARGET_NOT_OBJECT, Set.of("client-access-policy-target")),
            entry(Flaw.CONSTRAINT_WITHOUT_OPERATOR, Set.of("client-access-policy-constraint")),
            entry(Flaw.ACCESS_INBOX_NOT_URI, Set.of("client-access-document-inbox")),
            entry(Flaw.DELETE_UNCONDITIONAL, Set.of("client-delete-conditional")),
            entry(Flaw.SUBSCRIPTION_AS_PLAIN_JSON, Set.of("client-subscription-media-type")),
            entry(Flaw.SUBSCRIPTION_UNADVERTISED_TYPE, Set.of("client-subscription-type")),
            entry(Flaw.SUBSCRIPTION_TOPIC_NOT_ARRAY, Set.of("client-subscription-topic")),
            entry(Flaw.SUBSCRIPTION_WITHOUT_INBOX, Set.of("client-subscription-inbox")),
            entry(Flaw.QUERY_WITHOUT_CONTENT_TYPE, Set.of("client-query-content-type")),
            entry(Flaw.QUERY_KEEPS_REFUSED_FORMAT, Set.of("client-query-baseline-after-415")),
            entry(Flaw.CREATES_CONTAINER_WITHOUT_TYPE_LINK, Set.of("client-create-container-type-link")),
            entry(Flaw.DELETES_CONTAINER_WITHOUT_DEPTH, Set.of("client-delete-container-depth")),
            // Resending a PUT the 405 told it is not supported also assumes PUT is supported.
            entry(Flaw.REPEATS_REFUSED_PUT, Set.of("client-no-repeat-after-405-415",
                    "client-linkset-put-only-when-advertised")),
            entry(Flaw.RETRIES_LOST_CREATE_BLINDLY, Set.of("client-no-blind-retry-of-create")),
            entry(Flaw.DOES_NOT_RESTART_SEARCH, Set.of("client-restart-after-refused-page")),
            entry(Flaw.CID_UNSIGNED, Set.of("client-cid-credential-signed")),
            entry(Flaw.CID_WITHOUT_SUB, Set.of("client-cid-subject-claim")),
            entry(Flaw.CID_WITHOUT_ISS, Set.of("client-cid-issuer-claim")),
            entry(Flaw.CID_WITHOUT_CLIENT_ID, Set.of("client-cid-client-id-claim")),
            entry(Flaw.CID_CLIENT_ID_DIFFERS, Set.of("client-cid-identifiers-agree")),
            entry(Flaw.CID_AUDIENCE_WITHOUT_AS, Set.of("client-cid-audience-includes-as")),
            entry(Flaw.CID_WITHOUT_AUDIENCE, Set.of("client-cid-audience-restricted")),
            entry(Flaw.CID_WITHOUT_EXP, Set.of("client-cid-expiry-claim")),
            entry(Flaw.CID_WITHOUT_IAT, Set.of("client-cid-issued-at-claim")),
            entry(Flaw.CID_TYPED_AS_ID_TOKEN, Set.of("client-cid-token-type-jwt")),
            entry(Flaw.ID_TOKEN_TYPED_AS_JWT, Set.of("client-oidc-token-type-id-token")),
            entry(Flaw.TOKEN_REQUEST_WITHOUT_RESOURCE, Set.of("client-token-exchange-resource")),
            entry(Flaw.TOKEN_REQUEST_WITHOUT_SUBJECT_TOKEN, Set.of("client-token-exchange-subject-token")),
            entry(Flaw.SKIPS_DISCOVERY_CHECKS, Set.of("client-token-after-discovery-checks")),
            entry(Flaw.INBOX_SKIPS_SIGNATURE_CHECK, Set.of("client-inbox-refuses-unpublished-key")),
            entry(Flaw.INBOX_SKIPS_DIGEST_CHECK, Set.of("client-inbox-refuses-altered-body")),
            entry(Flaw.INBOX_ACCEPTS_KEYID_WITHOUT_FRAGMENT, Set.of("client-inbox-refuses-keyid-without-fragment")),
            entry(Flaw.INBOX_SKIPS_STORAGE_ID_CHECK, Set.of("client-inbox-refuses-foreign-key-document")),
            // The twin of C5's acceptance criterion: an inbox that accepts everything.
            entry(Flaw.INBOX_ACCEPTS_EVERYTHING, Set.of("client-inbox-refuses-unpublished-key",
                    "client-inbox-refuses-altered-body", "client-inbox-refuses-keyid-without-fragment",
                    "client-inbox-refuses-foreign-key-document")),
            entry(Flaw.INBOX_REFUSES_EVERYTHING, Set.of("client-inbox-acknowledges-genuine-delivery")),
            entry(Flaw.SHARES_INBOX, Set.of("client-subscription-own-inbox")),
            entry(Flaw.CREATES_WITH_LINKSET_LINK, Set.of("client-create-no-server-managed-links")),
            entry(Flaw.UPDATES_LINKS_WITHOUT_PREFER, Set.of("client-combined-update-prefer-set-linkset")),
            entry(Flaw.SAML_TYPED_AS_JWT, Set.of("client-saml-token-type-saml2")));

    private Twins() {
    }
}
