"""Checks 5 of definitions/README.md, "Validating": the vocabulary.

Generates definitions/lws10/vocab.yamlld, the RDFS definition of every lwst: term, from the
table below, and asserts it covers exactly the lwst: terms of context.jsonld: no term
undefined, and no definition without a term. vocab.yamlld is generated, so edit the table,
not the file.

    python gen_vocab.py           regenerate vocab.yamlld
    python gen_vocab.py --check   fail if vocab.yamlld is not what the table generates
"""
import json
import sys
import textwrap

from _paths import LWS10

CTX = LWS10 / "context.jsonld"
OUT = LWS10 / "vocab.yamlld"

C, P, I = "rdfs:Class", "rdf:Property", "individual"

# (term, kind, class-of-individual, label, comment, domain, range)
V = [
    # ---- classes
    ("ValidationTest", C, None, "Validation test", "A test whose steps exercise behaviour the specification requires and assert the responses a conforming server gives.", None, None),
    ("NegativeTest", C, None, "Negative test", "A test whose point is a refusal: at least one step expects a 4xx response, and the test also checks that the refused request had no effect.", None, None),
    ("Step", C, None, "Step", "One HTTP exchange of a test: a request, the expectations on its response, and the variables it captures. Steps run in order; a test fails at its first failing step.", None, None),
    ("Request", C, None, "Request", "What the harness sends in a step.", None, None),
    ("ResponseExpectation", C, None, "Response expectation", "What the harness checks in the response to a step's request. Every expectation present must hold.", None, None),
    ("LinkExpectation", C, None, "Link expectation", "A condition on the Link header field (RFC 8288) of a response, or a Link value to send in a request.", None, None),
    ("HeaderExpectation", C, None, "Header expectation", "A condition on one header field of a response, or a header to send in a request.", None, None),
    ("ChallengeExpectation", C, None, "Challenge expectation", "A condition on the WWW-Authenticate header field, parsed into challenges per RFC 9110 section 11.6.1.", None, None),
    ("ParameterExpectation", C, None, "Parameter expectation", "A condition on one auth-param of a challenge. Parameter names compare case-insensitively.", None, None),
    ("JsonExpectation", C, None, "JSON expectation", "A condition on the value a JSON Pointer (RFC 6901) selects in a JSON response body, a JWT header or a JWT claims set.", None, None),
    ("JwtExpectation", C, None, "JWT expectation", "Conditions on a JWT captured earlier: its signature verifies against a JWKS and its header and claims satisfy JSON expectations.", None, None),
    ("LocationExpectation", C, None, "Location expectation", "The response carries a Location header; its value, resolved against the request URL, is captured.", None, None),
    ("Prerequisites", C, None, "Prerequisites", "The state a test needs before its first step: resources the engine creates, as alice, and the access it grants on them. A failure to establish it is setup, not a finding: the outcome is cantTell, or inapplicable for access the target cannot grant.", None, None),
    ("PrerequisiteResource", C, None, "Prerequisite resource", "A container or data resource the engine creates inside ${test.container}, or inside an earlier entry, before the test runs. The server chooses its URI; the entry's container or dataResource names the variable bound to it.", None, None),
    ("ResourceAuthorization", C, None, "Resource authorization", "Access the engine grants on a prerequisite resource through the storage's access grant service, by LWS action. alice, who creates the resource, needs no grant.", None, None),
    ("ConnegExpectation", C, None, "Content-negotiation expectation", "Refetching the same URL once per listed media type yields responses with byte-identical bodies whose Content-Type echoes the type requested.", None, None),
    ("IdentityRegistry", C, None, "Identity registry", "The document listing every identity tests may name.", None, None),
    ("Identity", C, None, "Identity", "An abstract agent and the credential the harness produces for it. Tests name identities, never credentials.", None, None),
    ("Level", C, None, "Requirement level", "The BCP 14 strength of what a test asserts. A test has exactly one level.", None, None),
    ("Trait", C, None, "Trait", "A facet of what a test exercises, used to select and group tests.", None, None),
    ("Capability", C, None, "Capability", "Something the harness or the target deployment must provide that the server under test cannot reveal about itself. A test requiring a capability the target lacks is inapplicable.", None, None),
    ("IdentityKind", C, None, "Identity kind", "How an identity's credential is used.", None, None),
    ("Fault", C, None, "Credential fault", "The single defect that distinguishes a fault identity from its basis identity.", None, None),

    # ---- levels
    ("MUST", I, "Level", "MUST", "Failing the test means the server does not conform.", None, None),
    ("SHOULD", I, "Level", "SHOULD", "Failing the test is reported as advisory; it does not decide conformance.", None, None),
    ("MAY", I, "Level", "MAY", "An optional behaviour; failing the test is reported as advisory.", None, None),

    # ---- traits
    ("Get", I, "Trait", "GET", "Uses the GET method.", None, None),
    ("Head", I, "Trait", "HEAD", "Uses the HEAD method.", None, None),
    ("Post", I, "Trait", "POST", "Uses the POST method.", None, None),
    ("Put", I, "Trait", "PUT", "Uses the PUT method.", None, None),
    ("Patch", I, "Trait", "PATCH", "Uses the PATCH method.", None, None),
    ("Delete", I, "Trait", "DELETE", "Uses the DELETE method.", None, None),
    ("Container", I, "Trait", "Container", "Exercises containers.", None, None),
    ("DataResource", I, "Trait", "Data resource", "Exercises data resources.", None, None),
    ("Linkset", I, "Trait", "Linkset", "Exercises linkset resources.", None, None),
    ("StorageDescription", I, "Trait", "Storage description", "Exercises the storage description resource.", None, None),
    ("Discovery", I, "Trait", "Discovery", "Exercises discovery through links, challenges or metadata.", None, None),
    ("Authn", I, "Trait", "Authentication", "Exercises authentication.", None, None),
    ("Authz", I, "Trait", "Authorization", "Exercises authorization decisions.", None, None),
    ("Public", I, "Trait", "Public", "Exercises resources readable without credentials.", None, None),
    ("Private", I, "Trait", "Private", "Exercises resources that require credentials.", None, None),
    ("Conditional", I, "Trait", "Conditional requests", "Exercises entity tags and preconditions.", None, None),
    ("Conneg", I, "Trait", "Content negotiation", "Exercises content negotiation.", None, None),
    ("Range", I, "Trait", "Range requests", "Exercises byte-range requests.", None, None),
    ("TokenExchange", I, "Trait", "Token exchange", "Exercises the authorization server's RFC 8693 token exchange.", None, None),
    ("AccessGrants", I, "Trait", "Access requests and grants", "Exercises access requests and grants.", None, None),
    ("Notifications", I, "Trait", "Notifications", "Exercises notifications.", None, None),

    # ---- capabilities
    ("Authentication", I, "Capability", "Authentication enforced", "The target storage enforces authentication on the run root: an anonymous request is challenged, and alice and bob are distinct authenticated agents.", None, None),
    ("HarnessIssuedTokens", I, "Capability", "Harness-issued access tokens", "The storage trusts an authorization server whose signing key the harness holds, so the harness can mint otherwise-valid access tokens with one chosen defect.", None, None),
    ("ReachableFixtures", I, "Capability", "Reachable fixtures", "The target can reach the harness fixture host at ${fixtures.baseUrl} (identity documents, OpenID Provider, JWKS).", None, None),
    ("SamlTrust", I, "Capability", "SAML trust", "The target's authorization server trusts the harness SAML identity provider's signing certificate.", None, None),

    # ---- identity kinds
    ("NoCredential", I, "IdentityKind", "No credential", "Requests carry no Authorization header.", None, None),
    ("StorageAccessToken", I, "IdentityKind", "Storage access token", "Requests made as this identity carry Authorization: Bearer with an access token for the storage.", None, None),
    ("SubjectCredential", I, "IdentityKind", "Subject credential", "The identity's credential is a subject_token presented at the token endpoint; it is never sent as a storage access token.", None, None),

    # ---- faults
    ("SignatureCorrupted", I, "Fault", "Signature corrupted", "The signature no longer verifies: the last byte of a JWS signature is flipped, or a signed SAML element is altered after signing. Header and claims are unchanged.", None, None),
    ("AlgNone", I, "Fault", "Unsigned (alg none)", "The JWS header alg is \"none\" and the signature part is empty. Claims are unchanged.", None, None),
    ("UnknownKeyId", I, "Fault", "Unknown key", "Signed by a key the verifier cannot find: a fresh key whose kid is \"touchstone-unknown-kid\", which appears in no published JWKS or controlled identifier document.", None, None),
    ("Expired", I, "Fault", "Expired", "iat is now minus 3900 s and exp is now minus 3600 s (SAML: NotOnOrAfter an hour past); validly signed.", None, None),
    ("NotYetValid", I, "Fault", "Not yet valid", "nbf is now plus 3600 s; validly signed.", None, None),
    ("IssuedInFuture", I, "Fault", "Issued in the future", "iat is now plus 3600 s and exp now plus 3900 s; validly signed.", None, None),
    ("WrongAudience", I, "Fault", "Wrong audience", "aud is [\"https://not-this-storage.invalid/\"]; validly signed.", None, None),
    ("MultipleAudiences", I, "Fault", "Multiple audiences", "aud is [the storage realm, \"https://another-storage.invalid/\"]; validly signed.", None, None),
    ("WrongIssuer", I, "Fault", "Wrong issuer", "iss is \"https://untrusted-issuer.invalid/\"; signed by the key the storage trusts.", None, None),
    ("AudienceExcludesAuthorizationServer", I, "Fault", "Audience excludes the authorization server", "aud is [\"https://not-the-authorization-server.invalid/\"]; validly signed.", None, None),
    ("ClientIdMismatch", I, "Fault", "client_id mismatch", "client_id is \"https://other-client.invalid/\" while sub and iss are unchanged; validly signed.", None, None),
    ("MissingExpiration", I, "Fault", "Missing exp", "The exp claim is removed; validly signed.", None, None),
    ("MissingIssuedAt", I, "Fault", "Missing iat", "The iat claim is removed; validly signed.", None, None),
    ("MissingAuthorizedParty", I, "Fault", "Missing azp", "The azp claim is removed; validly signed.", None, None),
    ("UntrustedIssuer", I, "Fault", "Untrusted OpenID Provider", "Issued and validly signed by a second harness OpenID Provider at ${fixtures.baseUrl}rogue-op, which publishes discovery and JWKS but is not named in the subject's controlled identifier document.", None, None),
    ("Unsigned", I, "Fault", "Unsigned assertion", "The SAML assertion carries no ds:Signature.", None, None),

    # ---- manifest and test properties
    ("specification", P, None, "specification", "A dated specification snapshot the manifest's tests were checked against.", "mf:Manifest", "rdfs:Resource"),
    ("identities", P, None, "identities", "An identity in the registry.", "lwst:IdentityRegistry", "lwst:Identity"),
    ("level", P, None, "level", "The test's requirement level.", "mf:ManifestEntry", "lwst:Level"),
    ("traits", P, None, "traits", "A facet the test exercises.", "mf:ManifestEntry", "lwst:Trait"),
    ("requires", P, None, "requires", "A capability the test (or identity) needs; absent on the target, the test is inapplicable.", None, "lwst:Capability"),
    ("as", P, None, "as", "Name of the identity whose credential authorizes the step's request (on a test, the default for its steps). Unset means alice.", None, "xsd:string"),
    ("steps", P, None, "steps", "The test's ordered steps.", "mf:ManifestEntry", "lwst:Step"),
    ("precondition", P, None, "precondition", "When true, a failing expectation in this step makes the test inapplicable instead of failed, and no later step runs.", "lwst:Step", "xsd:boolean"),
    ("request", P, None, "request", "The request a step sends. Directly on a test, with response, it is shorthand for a test of exactly one step.", None, "lwst:Request"),
    ("response", P, None, "response", "The expectations on the step's response. Directly on a test, with request, it is shorthand for a test of exactly one step.", None, "lwst:ResponseExpectation"),
    ("prereqs", P, None, "prereqs", "The state the test needs before its first step; the engine establishes it (EXECUTION.md section 4.3).", "mf:ManifestEntry", "lwst:Prerequisites"),
    ("hierarchy", P, None, "hierarchy", "The resources to create, in order; an entry is created before any entry inside it.", "lwst:Prerequisites", "lwst:PrerequisiteResource"),
    ("container", P, None, "container", "Creates a container and binds the variable this names to the URI the server assigns.", "lwst:PrerequisiteResource", "xsd:string"),
    ("dataResource", P, None, "dataResource", "Creates a data resource with the entry's content and binds the variable this names to the URI the server assigns.", "lwst:PrerequisiteResource", "xsd:string"),
    ("in", P, None, "in", "The variable of an earlier container entry to create this resource in. Unset means ${test.container}.", "lwst:PrerequisiteResource", "xsd:string"),
    ("authorization", P, None, "authorization", "Access to grant on the resource once it exists.", "lwst:PrerequisiteResource", "lwst:ResourceAuthorization"),
    ("read", P, None, "read", "An identity granted the read action (GET, HEAD). anonymous means the public: the assignee is foaf:Agent.", "lwst:ResourceAuthorization", "xsd:string"),
    ("modify", P, None, "modify", "An identity granted the modify action (PUT, PATCH). anonymous means the public.", "lwst:ResourceAuthorization", "xsd:string"),
    ("create", P, None, "create", "An identity granted the create action (POST into a container). anonymous means the public.", "lwst:ResourceAuthorization", "xsd:string"),
    ("delete", P, None, "delete", "An identity granted the delete action (DELETE). anonymous means the public.", "lwst:ResourceAuthorization", "xsd:string"),

    # ---- request properties
    ("method", P, None, "method", "The HTTP method, in upper case.", "lwst:Request", "xsd:string"),
    ("url", P, None, "url", "Template for the target URL; a relative reference is resolved against ${target.baseUrl}.", "lwst:Request", "xsd:string"),
    ("contentType", P, None, "contentType", "On a request, the Content-Type to send. On a response, the media type expected: type/subtype compared case-insensitively, parameters ignored.", None, "xsd:string"),
    ("accept", P, None, "accept", "The Accept header value to send.", "lwst:Request", "xsd:string"),
    ("ifMatch", P, None, "ifMatch", "If-Match value to send, a template; the literal current means HEAD the URL first, as the same identity, and send the ETag it returns (no If-Match if it returns none).", "lwst:Request", "xsd:string"),
    ("linkHeaders", P, None, "linkHeaders", "A Link value to send, or a condition on the response's Link values.", None, "lwst:LinkExpectation"),
    ("rel", P, None, "rel", "A link relation type: a registered name compared case-insensitively, or an extension relation URI compared exactly.", "lwst:LinkExpectation", "xsd:string"),
    ("href", P, None, "href", "Template for a link target, compared after resolving both sides against the request URL.", "lwst:LinkExpectation", "xsd:string"),
    ("mediaType", P, None, "mediaType", "The expected (or sent) type target attribute of a link.", "lwst:LinkExpectation", "xsd:string"),
    ("otherHeaders", P, None, "otherHeaders", "A header to send, or a condition on one response header.", None, "lwst:HeaderExpectation"),
    ("headerName", P, None, "headerName", "The header field name, compared case-insensitively.", "lwst:HeaderExpectation", "xsd:string"),
    ("headerValue", P, None, "headerValue", "Template for the value to send, or the exact expected value of some field line.", "lwst:HeaderExpectation", "xsd:string"),
    ("differsFrom", P, None, "differsFrom", "Template; the header must be present and no field line may equal this value.", "lwst:HeaderExpectation", "xsd:string"),
    ("body", P, None, "body", "Template for a body sent as UTF-8 text: a request's, or a prerequisite data resource's content.", None, "xsd:string"),
    ("bodyURL", P, None, "bodyURL", "A fixture file, relative to the definition document: the bytes to send, or the bytes the response body must equal exactly.", None, "rdfs:Resource"),
    ("bodyJSON", P, None, "bodyJSON", "A JSON value sent as a body (a request's, or a prerequisite data resource's content); string members are templates. Content-Type defaults to application/json.", None, "rdf:JSON"),
    ("bodyForm", P, None, "bodyForm", "A JSON object of string templates sent as application/x-www-form-urlencoded; members appear in document order.", "lwst:Request", "rdf:JSON"),

    # ---- response properties
    ("statusCode", P, None, "statusCode", "An acceptable status: an integer, or a class string such as \"4xx\". Several values mean any of them.", "lwst:ResponseExpectation", "rdfs:Literal"),
    ("location", P, None, "location", "The response must carry Location.", "lwst:ResponseExpectation", "lwst:LocationExpectation"),
    ("authenticationChallenge", P, None, "authenticationChallenge", "Some challenge in WWW-Authenticate must satisfy this expectation.", "lwst:ResponseExpectation", "lwst:ChallengeExpectation"),
    ("wwwAuthenticate", P, None, "wwwAuthenticate", "The challenge's auth-scheme, compared case-insensitively (lws-test-suite's term).", "lwst:ChallengeExpectation", "xsd:string"),
    ("asUri", P, None, "asUri", "The challenge's as_uri auth-param: a template it must equal exactly, or a parameter expectation (present, matches, capture).", "lwst:ChallengeExpectation", None),
    ("realm", P, None, "realm", "The challenge's realm auth-param: a template it must equal exactly, or a parameter expectation (present, matches, capture).", "lwst:ChallengeExpectation", None),
    ("params", P, None, "params", "A condition on one of the challenge's auth-params.", "lwst:ChallengeExpectation", "lwst:ParameterExpectation"),
    ("paramName", P, None, "paramName", "The auth-param name.", "lwst:ParameterExpectation", "xsd:string"),
    ("paramValue", P, None, "paramValue", "Template for the exact expected auth-param value, after unquoting.", "lwst:ParameterExpectation", "xsd:string"),
    ("bodyEmpty", P, None, "bodyEmpty", "When true, the response has no content.", "lwst:ResponseExpectation", "xsd:boolean"),
    ("bodyMatches", P, None, "bodyMatches", "A regular expression (portable dialect) that must find a match in the body decoded as UTF-8.", "lwst:ResponseExpectation", "xsd:string"),
    ("json", P, None, "json", "A condition on the body parsed as JSON.", "lwst:ResponseExpectation", "lwst:JsonExpectation"),
    ("pointer", P, None, "pointer", "An RFC 6901 JSON Pointer; the empty pointer selects the whole value.", "lwst:JsonExpectation", "xsd:string"),
    ("optional", P, None, "optional", "When true, the expectation holds vacuously if the pointer selects nothing.", "lwst:JsonExpectation", "xsd:boolean"),
    ("some", P, None, "some", "The selected value is an array with at least one element satisfying every nested expectation (pointers relative to the element).", "lwst:JsonExpectation", "lwst:JsonExpectation"),
    ("every", P, None, "every", "The selected value is an array whose every element satisfies every nested expectation.", "lwst:JsonExpectation", "lwst:JsonExpectation"),
    ("none", P, None, "none", "The selected value is an array with no element satisfying all of the nested expectations.", "lwst:JsonExpectation", "lwst:JsonExpectation"),
    ("jwt", P, None, "jwt", "Conditions on a compact-serialized JWT.", "lwst:ResponseExpectation", "lwst:JwtExpectation"),
    ("token", P, None, "token", "Template yielding the JWT to check, usually a variable captured in the same step.", "lwst:JwtExpectation", "xsd:string"),
    ("jwks", P, None, "jwks", "Template yielding a JWKS URL; the JWT's signature must verify with the key its kid names there.", "lwst:JwtExpectation", "xsd:string"),
    ("header", P, None, "header", "A condition on the decoded JWT header.", "lwst:JwtExpectation", "lwst:JsonExpectation"),
    ("claims", P, None, "claims", "A condition on the decoded JWT claims set.", "lwst:JwtExpectation", "lwst:JsonExpectation"),
    ("connegEquivalent", P, None, "connegEquivalent", "The step's URL serves one document under several media types.", "lwst:ResponseExpectation", "lwst:ConnegExpectation"),
    ("accepts", P, None, "accepts", "The media types to request, in order.", "lwst:ConnegExpectation", "xsd:string"),

    # ---- operators
    ("present", P, None, "present", "When true, the header or auth-param is present; when false, it is absent.", None, "xsd:boolean"),
    ("absent", P, None, "absent", "On a link expectation: no Link value satisfies its other conditions (a header's absence is present: false). On a prerequisite resource: the engine does not create it, and binds its variable to a fresh URI inside its parent that names nothing.", None, "xsd:boolean"),
    ("equals", P, None, "equals", "The selected JSON value equals this one (deep equality; strings are templates).", None, "rdf:JSON"),
    ("equalsIri", P, None, "equalsIri", "Template for an absolute IRI; the selected string, resolved against the request URL, equals it.", None, "xsd:string"),
    ("matches", P, None, "matches", "A regular expression in the portable dialect of EXECUTION.md (unanchored search; a leading (?i) makes it case-insensitive). On a header, some field line or the combined value matches; on JSON, a string value matches, any other value is matched as its compact JSON text.", None, "xsd:string"),
    ("hasValue", P, None, "hasValue", "The selected value equals this one, or is an array containing an element equal to it (strings are templates).", None, "rdf:JSON"),
    ("exists", P, None, "exists", "When true, the pointer selects a value; when false, it selects nothing.", None, "xsd:boolean"),
    ("count", P, None, "count", "The selected array has this many elements (an object, this many members).", None, "xsd:integer"),
    ("jsonType", P, None, "jsonType", "The selected value's JSON type: string, number, boolean, null, array or object.", None, "xsd:string"),
    ("capture", P, None, "capture", "Binds a variable, for later steps, to the value this expectation located: a Link target (absolute), a header value, an auth-param value, the Location (absolute), or a JSON value. (A prerequisite resource binds its variable through container or dataResource.)", None, "xsd:string"),
    ("cleanup", P, None, "cleanup", "When true, the captured Location is deleted, as the same identity, when the test ends.", "lwst:LocationExpectation", "xsd:boolean"),

    # ---- identity properties
    ("kind", P, None, "kind", "The identity's kind.", "lwst:Identity", "lwst:IdentityKind"),
    ("suite", P, None, "suite", "The authentication suite specification the credential follows.", "lwst:Identity", "rdfs:Resource"),
    ("tokenType", P, None, "tokenType", "The RFC 8693 token type URI the credential is presented with.", "lwst:Identity", "rdfs:Resource"),
    ("algorithm", P, None, "algorithm", "The signature algorithm the credential is signed with (a JOSE alg, or an XML-DSig algorithm URI for SAML).", "lwst:Identity", "xsd:string"),
    ("webid", P, None, "webid", "Template for the agent IRI of a harness-hosted identity; ${identity.<name>.webid} resolves to it.", "lwst:Identity", "xsd:string"),
    ("basis", P, None, "basis", "The identity a fault identity is derived from.", "lwst:Identity", "xsd:string"),
    ("fault", P, None, "fault", "The single defect of a fault identity.", "lwst:Identity", "lwst:Fault"),
    ("credentialHeader", P, None, "credentialHeader", "Template for the JOSE header of a JWT credential.", "lwst:Identity", "rdf:JSON"),
    ("credentialClaims", P, None, "credentialClaims", "Template for the claims set of a JWT credential.", "lwst:Identity", "rdf:JSON"),
    ("identityDocument", P, None, "identityDocument", "Template for the controlled identifier document the harness serves at the identity's webid.", "lwst:Identity", "rdf:JSON"),
    ("samlAssertion", P, None, "samlAssertion", "The fields of the SAML assertion the harness IdP issues for the identity.", "lwst:Identity", "rdf:JSON"),
]


def lwst_terms():
    ctx = json.load(open(CTX, encoding="utf-8"))["@context"]
    out = set()
    for k, v in ctx.items():
        iri = v if isinstance(v, str) else v.get("@id", "") if isinstance(v, dict) else ""
        if isinstance(iri, str) and iri.startswith("lwst:"):
            out.add(iri[5:])
    return out


def q(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


defined = {t[0] for t in V}
structural = {"Prerequisites", "PrerequisiteResource", "ResourceAuthorization", "Step", "Request", "ResponseExpectation", "LinkExpectation", "HeaderExpectation", "ChallengeExpectation",
              "ParameterExpectation", "JsonExpectation", "JwtExpectation", "LocationExpectation", "ConnegExpectation",
              "Level", "Trait", "Capability", "IdentityKind", "Fault"}
ctx_terms = lwst_terms()
missing = ctx_terms - defined
extra = defined - ctx_terms - structural
assert not missing, f"vocab lacks context terms: {sorted(missing)}"
assert not extra, f"vocab defines terms the context lacks: {sorted(extra)}"

L = []
L.append("# The LWS test vocabulary: every lwst: class, property and individual used by the")
L.append("# definitions, with its meaning. EXECUTION.md is the operational contract; this is the")
L.append("# RDF view of the same terms, and the document the lwst: namespace should serve.")
L.append('"@context":')
for pfx, iri in [("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#"), ("rdfs", "http://www.w3.org/2000/01/rdf-schema#"),
                 ("owl", "http://www.w3.org/2002/07/owl#"), ("xsd", "http://www.w3.org/2001/XMLSchema#"),
                 ("dcterms", "http://purl.org/dc/terms/"), ("mf", "http://www.w3.org/2001/sw/DataAccess/tests/test-manifest#"),
                 ("lwst", "https://www.w3.org/ns/lws-tests/v1#")]:
    L.append(f"  {pfx}: {iri}")
L.append('  id: "@id"')
L.append('  type: "@type"')
L.append("  label: rdfs:label")
L.append("  comment: rdfs:comment")
for t in ("subClassOf", "domain", "range", "isDefinedBy"):
    L.append(f'  {t}: {{"@id": rdfs:{t}, "@type": "@id"}}')
L.append('"@graph":')
L.append("  - id: https://www.w3.org/ns/lws-tests/v1")
L.append("    type: owl:Ontology")
L.append("    label: LWS test vocabulary")
L.append("    comment: >-")
L.append("      Terms for declarative conformance tests of the Linked Web Storage Protocol 1.0 and its")
L.append("      authentication suites. Test manifests use the W3C test-manifest vocabulary (mf:) for")
L.append("      manifests and entries and the RDF test vocabulary (rdft:) for approval status. The")
L.append("      namespace is the one lws-test-suite uses; it is not yet published by W3C.")
for term, kind, cls, label, comment, dom, rng in V:
    L.append(f"  - id: lwst:{term}")
    if kind == I:
        L.append(f"    type: lwst:{cls}")
    else:
        L.append(f"    type: {kind}")
    if term in ("ValidationTest",):
        L.append("    subClassOf: mf:ManifestEntry")
    if term == "NegativeTest":
        L.append("    subClassOf: mf:ManifestEntry")
    L.append(f"    label: {q(label)}")
    L.append("    comment: >-")
    for line in textwrap.wrap(comment, 84):
        L.append(f"      {line}")
    if dom:
        L.append(f"    domain: {dom}")
    if rng:
        L.append(f"    range: {rng}")
    L.append("    isDefinedBy: https://www.w3.org/ns/lws-tests/v1")
text = "\n".join(L) + "\n"
if "--check" in sys.argv:
    current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
    if current != text:
        print(f"vocab.yamlld is out of date: run python {__file__} and commit it")
        sys.exit(1)
    print("vocab.yamlld is up to date")
else:
    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
print(f"vocab: {len(V)} terms ({sum(1 for v in V if v[1] == C)} classes, {sum(1 for v in V if v[1] == P)} properties, "
      f"{sum(1 for v in V if v[1] == I)} individuals); context lwst terms covered: {len(ctx_terms)}")
