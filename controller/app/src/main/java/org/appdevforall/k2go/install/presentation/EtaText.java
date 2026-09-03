package org.appdevforall.k2go.install.presentation;

import android.content.Context;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.install.domain.Eta;

/**
 * ADFA-5228: turns an ETA (seconds) into a short, localized, calm label for the progress caption.
 * The rounding/shape decision is the pure {@link Eta}; this only maps it to strings, so both the
 * module detail and the maps detail render the ETA the same way (no duplicated wording).
 */
public final class EtaText {

    private EtaText() {}

    /** "under a min" / "~N min" / "" (unknown). */
    public static String of(Context ctx, long etaSeconds) {
        Eta e = Eta.of(etaSeconds);
        switch (e.kind) {
            case UNDER_MINUTE: return ctx.getString(R.string.k2go_eta_under_min);
            case MINUTES:      return ctx.getString(R.string.k2go_eta_minutes_fmt, e.minutes);
            default:           return "";   // UNKNOWN -> blank slot
        }
    }
}
