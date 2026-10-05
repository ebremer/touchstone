package com.ebremer.touchstone.fixtures.lws;

import java.time.Duration;

/**
 * Behaviour of a {@link RefLwsServer} that the drafts allow but a client cannot rely on not
 * meeting (CLIENT-TESTING.md section 6.1). A conformant client handles each without noticing;
 * a client that assumes what the drafts leave open trips over it. All are off in the server
 * self-test's compliant deployment, and all are on in its trapped one, which every server
 * definition must pass as well: that is what shows each trap is legal.
 *
 * @param opaquePageUrls container pages are random URLs under {@code _t/p/}, not {@code ?page=N}
 * @param flatResourceUris resources the server names get URIs under {@code _r/} that do not nest
 *     under their container's path (lws10-core: "clients SHOULD NOT assume URI structure reflects
 *     containment")
 * @param opaqueLinksetUrls a linkset is a random URL under {@code _t/l/}, found only through
 *     {@code rel="linkset"}
 * @param putOnlyForText PUT is supported, and advertised in {@code Allow}, only on data resources
 *     with a textual media type; a binary one answers PUT with 405
 * @param decoy the storage root lists a resource that answers every request with a 401 whose
 *     {@code realm} does not contain it, so a client that checks the realm never sends it a token
 * @param indexLag how long a write takes to reach the type index and search (lws10-index: "MAY
 *     be eventually consistent"); zero for read-your-writes
 */
public record Traps(boolean opaquePageUrls, boolean flatResourceUris, boolean opaqueLinksetUrls,
                    boolean putOnlyForText, boolean decoy, Duration indexLag) {

    /** None: the reference behaviour the self-test's compliant deployment has always had. */
    public static final Traps NONE = new Traps(false, false, false, false, false, Duration.ZERO);

    public Traps {
        indexLag = indexLag == null ? Duration.ZERO : indexLag;
    }

    /** Every trap, with {@code indexLag} for the index. */
    public static Traps all(Duration indexLag) {
        return new Traps(true, true, true, true, true, indexLag);
    }
}
