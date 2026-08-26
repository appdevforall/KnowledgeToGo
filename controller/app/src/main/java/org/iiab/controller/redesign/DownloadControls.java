/*
 * ============================================================================
 * Name        : DownloadControls.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4893. Shared status-screen control row for the content download streams
 *               (ZIM, Books, ...): one primary button that morphs Pause -> Resume -> Retry, plus a
 *               Cancel that is always present. Both ZimPreparingFragment and BooksDownloadsFragment
 *               used to carry an identical copy of this wiring; this is the single source. The poll
 *               (the stream's service) is the source of truth via the Controller getters — the row
 *               only reads state and dispatches the right action per tap.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import org.iiab.controller.R;

public final class DownloadControls {

    /** Abstracts a stream's service (its static state + actions) so one row drives ZIM or Books. */
    public interface Controller {
        boolean isRunning();
        boolean isPaused();
        boolean hasFailed();
        boolean pauseSupported();   // box dash-node >= 1.2.4 exposes pause/resume
        void pause();
        void resume();
        void retryFailed();
        void cancelRunning();       // ACTION_CANCEL on the running session
        void dismiss();             // clear a terminal/failed session (finishSession)
    }

    private final LinearLayout row;
    private final Button primary, cancel;
    private final Controller c;

    public DownloadControls(LinearLayout row, Button primary, Button cancel, Controller c) {
        this.row = row; this.primary = primary; this.cancel = cancel; this.c = c;
        // The action is decided at tap time from the live state, so we never remove/re-add buttons.
        primary.setOnClickListener(v -> {
            if (!c.isRunning() && c.hasFailed()) c.retryFailed();
            else if (c.isPaused()) c.resume();
            else c.pause();
        });
        // Cancel is always available: cancels a running session, or dismisses a failed/terminal one.
        cancel.setOnClickListener(v -> {
            if (c.isRunning()) c.cancelRunning();
            else c.dismiss();
        });
    }

    /** Reflect the live state: visible while running (needs pause support) or whenever an item failed
     *  (Retry works on any dash-node version); primary text morphs Pause -> Resume -> Retry. */
    public void render() {
        boolean show = (c.isRunning() && c.pauseSupported()) || c.hasFailed();
        row.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            if (!c.isRunning() && c.hasFailed()) primary.setText(R.string.k2go_dl_retry);
            else if (c.isPaused()) primary.setText(R.string.k2go_dl_resume);
            else primary.setText(R.string.k2go_dl_pause);
        }
    }
}
