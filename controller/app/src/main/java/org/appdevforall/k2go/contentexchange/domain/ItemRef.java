package org.appdevforall.k2go.contentexchange.domain;

/**
 * The address of one content item on the box: the adapter key plus the app's own local
 * handle. This is what every action slice (export / import / delete) names an item by, and
 * it matches the core contract {@code {app, localRef}}.
 *
 * <p>Immutable value object with value equality, so it is a safe map key (the export flow
 * keys the manifest payload names and the captured plan entries by it). Pure domain type.
 */
public final class ItemRef {

    private final String app;
    private final String localRef;

    public ItemRef(String app, String localRef) {
        this.app = app;
        this.localRef = localRef;
    }

    public String app() {
        return app;
    }

    public String localRef() {
        return localRef;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ItemRef)) {
            return false;
        }
        ItemRef other = (ItemRef) o;
        return eq(app, other.app) && eq(localRef, other.localRef);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    public int hashCode() {
        return 31 * (app == null ? 0 : app.hashCode()) + (localRef == null ? 0 : localRef.hashCode());
    }

    @Override
    public String toString() {
        return app + "/" + localRef;
    }
}
