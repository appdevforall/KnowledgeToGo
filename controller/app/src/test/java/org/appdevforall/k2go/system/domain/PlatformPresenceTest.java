package org.appdevforall.k2go.system.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.system.domain.PlatformPresence.Evidence;

import org.junit.Test;

/**
 * Unit tests for {@link PlatformPresence} — when an unreachable platform may be called absent.
 *
 * <p>Worth testing because the interesting cases cannot be produced on demand on a device: a
 * box mid-restart, or a platform too busy importing to answer within a second and a half.
 * Those are the ones that used to be answered wrongly, and wrongly in the expensive
 * direction — the dispatcher's "absent" is terminal, so the user's order was discarded
 * rather than queued.
 *
 * <p>Three cases, three tests, no duplicates. A first version had seven tests for five
 * distinct assertions, with two pairs that were byte-identical under different names; a
 * review called that padding and it was.
 */
public class PlatformPresenceTest {

    @Test
    public void onlyOutrightAbsenceMeansAbsent() {
        // A 404 is the box saying there is nothing there. The sole evidence strong enough to
        // refuse the user terminally.
        assertFalse(PlatformPresence.resolve(Evidence.ABSENT));
    }

    @Test
    public void presentMeansPresent() {
        assertTrue(PlatformPresence.resolve(Evidence.PRESENT));
    }

    @Test
    public void establishingNothingIsNotAbsence() {
        // The regression this class exists for, and it covers three device situations at
        // once: a timeout from a platform busy importing, a refused connection from a box
        // that is off, and the 502 nginx returns while the platform behind it restarts. All
        // three used to be reported as "this platform is not installed", and the down-box one
        // is what makes ENSURE_SERVER_THEN_RUN_LIVE reachable at all.
        assertTrue(PlatformPresence.resolve(Evidence.NONE));
    }

    @Test
    public void nothingAtAllIsNotAbsenceEither() {
        // The class is public and pure with no annotation on the parameter, and it fails to
        // the same side as everything else here.
        assertTrue(PlatformPresence.resolve(null));
    }
}
