package org.appdevforall.k2go.contentexchange.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The receiver-verdict interpretation and the default-selection rule. */
public class ImportVerdictTest {

    private static ImportVerdict v(String status, String verdict, String idKind) {
        return new ImportVerdict(status, verdict, idKind);
    }

    @Test
    public void absentIsNewAndSelectedByDefault() {
        ImportVerdict r = v(ImportVerdict.STATUS_IMPORTABLE, ImportVerdict.ABSENT, "AUTHORITATIVE");
        assertTrue(r.isNew());
        assertTrue(r.isImportable());
        assertTrue(r.defaultSelected());
    }

    @Test
    public void olderHereMeansIncomingIsAnUpdate() {
        ImportVerdict r = v(ImportVerdict.STATUS_IMPORTABLE, ImportVerdict.OLDER_HERE, "AUTHORITATIVE");
        assertTrue(r.isUpdate());
        assertTrue(r.isImportable());
        assertTrue(r.defaultSelected());
    }

    @Test
    public void sameVersionIsNotImportable() {
        ImportVerdict r = v(ImportVerdict.STATUS_IMPORTABLE, ImportVerdict.SAME_VERSION, "AUTHORITATIVE");
        assertTrue(r.isSame());
        assertFalse(r.isImportable());
        assertFalse(r.defaultSelected());
    }

    @Test
    public void newerHereIsImportableButOffByDefault() {
        ImportVerdict r = v(ImportVerdict.STATUS_IMPORTABLE, ImportVerdict.NEWER_HERE, "AUTHORITATIVE");
        assertTrue(r.isOlderIncoming());
        assertTrue(r.isImportable());       // allowed (the user may keep both), just not pre-picked
        assertFalse(r.defaultSelected());
    }

    @Test
    public void noAppCannotImport() {
        ImportVerdict r = v(ImportVerdict.STATUS_NO_APP, null, null);
        assertFalse(r.hasApp());
        assertFalse(r.isImportable());
        assertFalse(r.defaultSelected());
    }

    @Test
    public void softIdKindIsAdvisory() {
        assertTrue(v(ImportVerdict.STATUS_IMPORTABLE, ImportVerdict.ABSENT, ImportVerdict.ID_SOFT).isAdvisory());
        assertFalse(v(ImportVerdict.STATUS_IMPORTABLE, ImportVerdict.ABSENT, "AUTHORITATIVE").isAdvisory());
    }
}
