package org.appdevforall.k2go.contentexchange.domain;

/**
 * The receiver-side ruling on one bundle item, from {@code POST /v1/import/validate}: whether an app
 * exists for it, and how what the box already has compares to the incoming item.
 *
 * <p>Verdict meaning is relative to the INCOMING item: {@code ABSENT} the box lacks it (a new import),
 * {@code SAME_VERSION} the box has the same (skip), {@code OLDER_HERE} the box has an older one so the
 * incoming is NEWER (an update), {@code NEWER_HERE} the box has a newer one so the incoming is OLDER
 * (skip by default). A {@code SOFT} idKind (Calibre) makes the verdict advisory, not a hard fact.
 *
 * <p>Pure value object, unit-testable: the default-selection rule lives here so the UI and a test
 * agree on it.
 */
public final class ImportVerdict {

    // Wire values from the core (status + verdict + idKind).
    public static final String STATUS_IMPORTABLE = "importable";
    public static final String STATUS_NO_APP = "NO_APP";

    public static final String ABSENT = "ABSENT";
    public static final String SAME_VERSION = "SAME_VERSION";
    public static final String OLDER_HERE = "OLDER_HERE";
    public static final String NEWER_HERE = "NEWER_HERE";

    public static final String ID_SOFT = "SOFT";

    private final String status;
    private final String verdict;
    private final String idKind;

    public ImportVerdict(String status, String verdict, String idKind) {
        this.status = status != null ? status : STATUS_NO_APP;
        this.verdict = verdict;
        this.idKind = idKind;
    }

    public String status() {
        return status;
    }

    public String verdict() {
        return verdict;
    }

    public String idKind() {
        return idKind;
    }

    public boolean hasApp() {
        return STATUS_IMPORTABLE.equals(status);
    }

    /** Advisory (Calibre soft key): the verdict is a hint, not a hard fact. */
    public boolean isAdvisory() {
        return ID_SOFT.equalsIgnoreCase(idKind);
    }

    public boolean isNew() {
        return hasApp() && ABSENT.equals(verdict);
    }

    /** The incoming item is newer than what the box has (an update). */
    public boolean isUpdate() {
        return hasApp() && OLDER_HERE.equals(verdict);
    }

    public boolean isSame() {
        return hasApp() && SAME_VERSION.equals(verdict);
    }

    /** The incoming item is older than what the box has. */
    public boolean isOlderIncoming() {
        return hasApp() && NEWER_HERE.equals(verdict);
    }

    /** May this item be imported at all (an app exists and it is not identical)? */
    public boolean isImportable() {
        return hasApp() && !isSame();
    }

    /**
     * Whether this item is selected by default: a genuinely new item, or an update to an older local
     * copy. An item the box already has (same), an older incoming, or one with no app starts off.
     */
    public boolean defaultSelected() {
        return isNew() || isUpdate();
    }
}
