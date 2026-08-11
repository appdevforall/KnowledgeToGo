package org.iiab.controller.redesign;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.iiab.controller.PortalActivity;
import org.iiab.controller.R;
import org.iiab.controller.ServerStateRepository;
import org.iiab.controller.config.BoxEndpoints;
import org.iiab.controller.kolibri.presentation.KolibriProvisioner;
import org.iiab.controller.system.domain.PlatformPresence;
import org.iiab.controller.util.AppExecutors;

/**
 * Content-first Library home (ADFA-4725, Phase 3): action cards with live 3-state
 * status dots (gray = not installed / amber = starting / green = ready). Tapping a
 * ready card opens its content in the portal WebView (Explore Wikipedia -> Kiwix).
 */
public class LibraryHomeFragment extends Fragment {

    private static final long POLL_MS = 3000L;
    private static final int GRAY = 0, AMBER = 1, GREEN = 2, RED = 3;
    // ADFA-4828: header (aggregate) states — distinct from the per-card states above.
    // ADFA-4837: H_FAILED = installed but the server isn't running and isn't starting.
    // ADFA-5074: H_INSTALLING = content is being added right now. Tapping opens the install
    // index — the only way in that does not start new work. Until this existed the index
    // could only be arrived at, never opened, so a user who left a running download had no
    // route back to it.
    private static final int H_NO_LIBRARY = 0, H_STARTING = 1, H_READY = 2, H_READY_EMPTY = 3,
            H_FAILED = 4, H_INSTALLING = 5;
    // ADFA-4837: how long a card stays amber (patient) once the box is up before it's called stuck.
    private static final long CARD_RED_GRACE_MS = 60000L;

    private static final class Card {
        final String endpoint; final String title; final boolean requires64; final int iconRes;
        View dot; TextView status; int state = GRAY;
        /**
         * ADFA-5061: what the last probe established, or null when nothing has been asked yet.
         *
         * <p>Null is the value {@code state} could not hold, and its absence was a bug: the field
         * seeded to GRAY, GRAY was read as "a 404 said it is not installed", and in the window
         * between building the cards and the first probe returning — up to a second and a half
         * per card, on a perfectly healthy box — the action sheet offered to install platforms
         * that were installed. Decision 8 names this exact flattening: "down" and "never asked"
         * are not the same answer, and a boolean cannot tell them apart.
         */
        PlatformPresence.Evidence evidence;
        Card(String e, String t, boolean r, int i) { endpoint = e; title = t; requires64 = r; iconRes = i; }
    }

    // ADFA-4837: monotonic mark of when the server first became alive; the card red-grace is measured
    // from here so services (kolibri/kiwix) get their full warm-up before anything turns red.
    private long serverAliveSinceMs = 0L;
    private int headerState = H_STARTING;

    private final List<Card> cards = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView homeStatus;
    private View homeStatusDot;
    private View homeStatusRow;
    private LinearLayout cardsHost;
    private View getMoreFooter;

    /** Material 3 breakpoint: >= 600dp wide → medium/expanded (3 columns + nav rail). */
    private static final int MEDIUM_MIN_DP = 600;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refreshStatuses();
            main.postDelayed(this, POLL_MS);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_library, container, false);
        homeStatus = root.findViewById(R.id.k2go_home_status);
        homeStatusDot = root.findViewById(R.id.k2go_home_status_dot);
        homeStatusRow = root.findViewById(R.id.k2go_home_status_row);
        // ADFA-4837: when the header reports the server couldn't start, tapping it retries — but only
        // when it's genuinely safe (LibraryActivity.canStartServer guards against stacking a 2nd proot).
        if (homeStatusRow != null) {
            homeStatusRow.setOnClickListener(v -> {
                // ADFA-5074: the way back into a running install. Opens the index without
                // starting anything — every other route to it begins new work.
                if (headerState == H_INSTALLING) {
                    startActivity(new android.content.Intent(
                            requireContext(), SetupProgressActivity.class));
                    return;
                }
                if (headerState != H_FAILED) return;
                if (getActivity() instanceof LibraryActivity) {
                    LibraryActivity act = (LibraryActivity) getActivity();
                    if (act.canStartServer()) {
                        act.startServer();
                        setHeader(H_STARTING);   // optimistic; the poll corrects it if the start fails
                    }
                }
            });
        }

        populateCards();

        cardsHost = root.findViewById(R.id.k2go_cards);
        getMoreFooter = root.findViewById(R.id.k2go_get_more_footer);
        root.findViewById(R.id.k2go_get_more).setOnClickListener(v -> openGetMore());
        relayout();
        return root;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        relayout();
    }

    /**
     * ADFA-4799: lay out by window width (not orientation). Compact (&lt; 600dp) = 2 columns +
     * pinned "Get more" footer; medium/expanded (&gt;= 600dp) = 3 columns with "Get more" as the
     * last grid cell. The card is fixed-height with an autosizing 2-line title, so the grid stays
     * balanced under any translation.
     */
    // ADFA-4958: build the experience list, dropping modules the user hid from Home (Restore lives
    // in Module management). Rebuilt on a Hide from the action sheet.
    private void populateCards() {
        cards.clear();
        cards.add(new Card("books",   getString(R.string.k2go_card_books),       false, R.drawable.ic_card_book));
        cards.add(new Card("code",    getString(R.string.k2go_card_code),    false, R.drawable.ic_card_code));
        cards.add(new Card("kiwix",   getString(R.string.k2go_card_wikipedia), true,  R.drawable.ic_card_wikipedia));
        cards.add(new Card("kolibri", getString(R.string.k2go_card_courses),      false, R.drawable.ic_card_courses));
        cards.add(new Card("maps",    getString(R.string.k2go_card_maps),     false, R.drawable.ic_card_maps));
        for (java.util.Iterator<Card> it = cards.iterator(); it.hasNext(); ) {
            Card card = it.next();
            ModuleCards.Card m = ModuleCards.byEndpoint(card.endpoint);
            if (m != null && HiddenModules.contains(requireContext(), m.key())) it.remove();
        }
    }

    // ADFA-4958: after the action sheet acts — a Hide drops the card (rebuild), a schedule/cancel
    // just refreshes its label.
    private void refreshAfterSheet(Card c) {
        ModuleCards.Card m = ModuleCards.byEndpoint(c.endpoint);
        if (m != null && HiddenModules.contains(requireContext(), m.key())) {
            populateCards();
            relayout();
        } else {
            applyState(c, c.state);
        }
    }

    private void relayout() {
        if (cardsHost == null || !isAdded()) return;
        int columns = getResources().getConfiguration().screenWidthDp >= MEDIUM_MIN_DP ? 3 : 2;
        boolean getMoreInGrid = columns >= 3;
        if (getMoreFooter != null) getMoreFooter.setVisibility(getMoreInGrid ? View.GONE : View.VISIBLE);
        buildCards(getLayoutInflater(), cardsHost, columns, getMoreInGrid);
        refreshStatuses();
    }

    private void buildCards(LayoutInflater inflater, LinearLayout host, int columns, boolean getMoreInGrid) {
        host.removeAllViews();
        final int cardH = getResources().getDimensionPixelSize(R.dimen.k2go_card_height);

        List<View> cells = new ArrayList<>();
        for (final Card c : cards) {
            View card = inflater.inflate(R.layout.view_k2go_card, host, false);
            ((ImageView) card.findViewById(R.id.k2go_card_icon)).setImageResource(c.iconRes);
            ((TextView) card.findViewById(R.id.k2go_card_title)).setText(c.title);
            c.dot = card.findViewById(R.id.k2go_card_dot);
            c.status = card.findViewById(R.id.k2go_card_status);
            card.setOnClickListener(v -> onCardClick(c));
            View ov = card.findViewById(R.id.k2go_card_overflow);   // ADFA-4958
            if (ov != null) {
                if (ModuleCards.byEndpoint(c.endpoint) != null) { ov.setOnClickListener(w -> openSheet(c)); }
                else ov.setVisibility(View.GONE);   // defensive: a Home card with no backing module (none today)
            }
            applyState(c, c.state);   // keep the live status across a relayout
            cells.add(card);
        }
        if (getMoreInGrid) cells.add(makeGetMoreCell(cardH));

        int i = 0, n = cells.size();
        while (i < n) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            host.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            int inRow = Math.min(columns, n - i);
            for (int k = 0; k < inRow; k++) {
                View cell = cells.get(i++);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cell.getLayoutParams();
                if (lp == null) lp = new LinearLayout.LayoutParams(0, cardH);
                lp.width = 0;
                lp.weight = (inRow == 1) ? columns : 1f;   // a lone last card spans the full row
                cell.setLayoutParams(lp);
                row.addView(cell);
            }
            if (inRow > 1 && inRow < columns) {   // pad a partial row so cards keep their column width
                View spacer = new View(requireContext());
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, 1);
                sp.weight = columns - inRow;
                row.addView(spacer, sp);
            }
        }
    }

    /** Card-shaped "Get more" cell used as the last grid cell in medium/expanded. */
    private View makeGetMoreCell(int cardH) {
        TextView t = new TextView(requireContext());
        t.setText(getString(R.string.k2go_get_more));
        t.setGravity(Gravity.CENTER);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        t.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
        t.setBackgroundResource(R.drawable.k2go_getmore_bg);
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(v -> openGetMore());
        int m = Math.round(getResources().getDisplayMetrics().density * 8);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, cardH);
        lp.setMargins(m, m, m, m);
        t.setLayoutParams(lp);
        return t;
    }

    private void openGetMore() {
        // If a system is already installed, skip the destructive system step and go straight
        // to content (Step 2). Otherwise run the full setup from Step 1.
        Intent i = new Intent(requireContext(), SetupLibraryActivity.class);
        if (org.iiab.controller.SystemStateEvaluator.isSystemInstalled(requireContext())) {
            i.putExtra(SetupLibraryActivity.EXTRA_CONTENT_ONLY, true);
        }
        startActivity(i);
    }

    private void onCardClick(Card c) {
        // ADFA-5061: a card whose content is downloading opens the platform, whatever the
        // last probe said. Without this the label and the tap disagree — the card reads
        // "Adding content" and a tap that lost the probe race opens the install sheet for a
        // platform that is demonstrably installed.
        if (c.state == GREEN || contentInFlight(c)) {
            Intent i = new Intent(requireContext(), PortalActivity.class);
            i.putExtra("TARGET_URL", BoxEndpoints.BASE + "/" + c.endpoint + "/");
            // ADFA-5043: Books (Calibre-Web) and Courses (Kolibri) auto-login as box admin in the WebView.
            String authService = authServiceFor(c.endpoint);
            if (authService != null) i.putExtra("AUTH_SERVICE", authService);
            startActivity(i);
        } else if (ModuleCards.byEndpoint(c.endpoint) != null) {   // ADFA-4958: module -> action sheet
            openSheet(c);
        } else {   // defensive fallback: non-Ready card with no backing module (none today) — re-probe
            applyState(c, AMBER);
            AppExecutors.get().io().execute(() -> {
                final int st = probe(c.endpoint);
                main.post(() -> { if (isAdded()) applyState(c, (st == GREEN || st == GRAY) ? st : AMBER); });
            });
            Toast.makeText(requireContext(), getString(R.string.k2go_retrying), Toast.LENGTH_SHORT).show();
        }
    }

    /** ADFA-5043: card endpoint → auto-login service name (server credential store), or null if the
     *  card has no admin login. */
    private static String authServiceFor(String endpoint) {
        if ("kolibri".equals(endpoint)) return "kolibri";
        if ("books".equals(endpoint)) return "calibre";
        return null;
    }

    /**
     * ADFA-4958: the module action sheet is the single contextual surface for a module card.
     *
     * <p>ADFA-5061: the state comes from the evidence, not from the dot. A first attempt read
     * {@code c.state == GRAY} and asserted that grey meant a 404 — it does not. Grey has three
     * other producers: an unsupported module, a box with no system, and the value the field
     * holds before anything has been asked. Reading the colour therefore offered to install
     * platforms that were installed, in the ordinary window before the first probe returns.
     *
     * <p>Content in flight is checked first and outranks everything, for the reason
     * {@code onCardClick} already uses it: a platform we are watching work cannot be missing.
     * That path bypasses this method, so without it the overflow menu could say "not
     * responding" about a card reading "Adding content" two centimetres away.
     */
    private void openSheet(Card c) {
        ModuleActionSheet.State s;
        if (c.state == GREEN || contentInFlight(c)) s = ModuleActionSheet.State.READY;
        else if (isScheduled(c)) s = ModuleActionSheet.State.SCHEDULED;
        else if (c.evidence != null && !PlatformPresence.resolve(c.evidence)) {
            s = ModuleActionSheet.State.NOT_INSTALLED;
        } else {
            s = ModuleActionSheet.State.UNKNOWN;
        }
        ModuleActionSheet.show(requireActivity(), c.endpoint, c.title, c.iconRes, s,
                () -> { if (isAdded()) refreshAfterSheet(c); });   // ADFA-4958: refresh label / drop if hidden
    }

    private boolean isScheduled(Card c) {
        ModuleCards.Card m = ModuleCards.byEndpoint(c.endpoint);
        if (m == null) return false;
        if (m.hasSelector) return MapsWishlist.has(requireContext());   // ADFA-4958: maps has its own store
        return ModuleWishlist.contains(requireContext(), m.key());
    }

    @Override public void onResume() { super.onResume(); main.post(poll); }
    @Override public void onPause() { super.onPause(); main.removeCallbacks(poll); }

    private boolean unsupported(Card c) {
        return c.requires64 && android.os.Build.SUPPORTED_64_BIT_ABIS.length == 0;
    }

    // ADFA-4853: guards a single in-flight readiness probe before the post-install drain.
    private volatile boolean provisionProbing = false;

    // ADFA-4874: the REST readiness probe now lives in RestReadiness (shared with
    // SetupProgressActivity) so there is a single definition of the drain gate.

    /**
     * ADFA-5061: one content reading for the whole pass.
     *
     * <p>{@code PendingContent} asks for exactly this — read once, decide from the snapshot,
     * because a screen that asks twice in one pass can get two answers and contradict itself.
     * The card labels and the header both need it, and a first version had each of them take
     * its own snapshot: four wishlist JSON parses per card per poll on the main thread, for a
     * question that reads three cheap fields, and a real chance of the header and a card
     * disagreeing about a stream that finished between the two reads.
     *
     * <p>Null between passes, and every reader tolerates that — {@code applyState} runs from
     * paths that are not part of a pass at all.
     */
    private org.iiab.controller.system.data.PendingContent.Snapshot passContent;

    private void refreshStatuses() {
        boolean installed = org.iiab.controller.SystemStateEvaluator.isSystemInstalled(requireContext());
        boolean alive = ServerStateRepository.get().current().alive;
        passContent = org.iiab.controller.system.data.PendingContent.read(requireContext());

        // ADFA-4837: track when the server first came up; the card red-grace is measured from here.
        if (alive) { if (serverAliveSinceMs == 0L) serverAliveSinceMs = android.os.SystemClock.elapsedRealtime(); }
        else serverAliveSinceMs = 0L;

        // ADFA-4828: nothing installed (or an install is running — the boot gate normally covers
        // that). The home isn't starting anything, so don't imply it: the cards read "Not installed"
        // and the header points to Get more (set in updateHeaderFromCards).
        if (!installed) {
            for (final Card c : cards) {
                // ADFA-5061: with no system at all, nothing is installed — and that is a fact,
                // not a failed probe. This is the one path where ABSENT is set without asking
                // anyone, and it is the path where offering an install is exactly right.
                c.evidence = unsupported(c) ? null : PlatformPresence.Evidence.ABSENT;
                applyState(c, GRAY);
                if (unsupported(c) && c.status != null) c.status.setText(getString(R.string.k2go_not_supported));
            }
            updateHeaderFromCards();
            return;
        }

        // ADFA-4853: system is installed and the server is up — drain any wizard content orders
        // (Books, ZIM) into the live download engines (one-shot; each wishlist is cleared once
        // handed off, and each service downloads one item at a time with per-item retry).
        // ADFA-4853: /home answering means nginx is up, but the dashboard REST engine (dash-node
        // on :4000) may still be warming up — POSTing then returns 502 Bad Gateway and the jobs
        // fail with no content installed. So gate the drain on the REST API actually answering;
        // the wishlist is untouched until then, and this poll (~3s) retries until it's ready.
        // ADFA-5074: courses were missing from this pump. Everything below is what makes a banked
        // order eventually run when the progress index is not on screen — so a courses order sat
        // in its wishlist until someone happened to open the index, which is also what made a
        // queue impossible: a door cannot honestly say "added to the queue" if nothing outside
        // that one screen ever drains it.
        if (alive && !provisionProbing
                && (BooksProvisioner.hasPending(requireContext()) || ZimProvisioner.hasPending(requireContext())
                    || MapsProvisioner.hasPending(requireContext())                       // ADFA-4900
                    || KolibriProvisioner.hasPending(requireContext()))) {                // ADFA-5074
            provisionProbing = true;
            AppExecutors.get().io().execute(() -> {
                final boolean ready = RestReadiness.apiReady();
                main.post(() -> {
                    provisionProbing = false;
                    if (!isAdded()) return;
                    if (!ready) { android.util.Log.d("K2Go-Provision", "REST API not ready yet (nginx 502); will retry"); return; }
                    // Fallback engine: if the visible Finishing-setup screen isn't up (e.g. the
                    // user backgrounded it), keep provisioning going from here. The install path
                    // opens the screen directly; this just makes sure the drain still happens.
                    android.util.Log.i("K2Go-Provision", "REST API ready -> draining wishlists (home fallback)");
                    if (BooksProvisioner.hasPending(requireContext())) BooksProvisioner.drain(requireContext());
                    if (ZimProvisioner.hasPending(requireContext())) ZimProvisioner.drain(requireContext());
                    // ADFA-5074: courses must go BEFORE maps, with the other REST streams.
                    //
                    // It was placed last, reasoning that courses is the longest stream and should
                    // not make the others wait. That reasoning is about the REST-vs-REST axis and
                    // misses the proot one: MapsProvisioner.drain clears MapsWishlist synchronously
                    // and only startForegroundService()s, so ModuleQueueRepository is still not
                    // running on the next line. Courses would then see no proot work pending, pass
                    // its own guard, and start a REST download on top of a maps runrole spinning up
                    // — the concurrency ADFA-4900 exists to prevent. Books and ZIM were only ever
                    // safe because they came first.
                    if (KolibriProvisioner.hasPending(requireContext())) KolibriProvisioner.drain(requireContext());
                    if (MapsProvisioner.hasPending(requireContext())) MapsProvisioner.drain(requireContext()); // ADFA-4900
                });
            });
        }

        for (final Card c : cards) {
            if (unsupported(c)) {
                // ADFA-5061: grey, but not "absent". A 64-bit module on a 32-bit device is not
                // missing — it is never going to be there, which is why the sheet must not offer
                // to install it. Leaving the evidence unset is what stops that: "nothing
                // established" withholds the offer, where ABSENT would have made it.
                c.evidence = null;
                applyState(c, GRAY);
                if (c.status != null) c.status.setText(getString(R.string.k2go_not_supported));
                continue;
            }
            // ADFA-4828: system is installed. Before the first probe resolves (or while the server
            // is still coming up) show "Connecting", never "Not installed" — the latter only appears
            // once a probe actually reports the content is absent (404 -> GRAY).
            if (!alive) { c.evidence = null; applyState(c, AMBER); continue; }
            AppExecutors.get().io().execute(() -> {
                final PlatformPresence.Evidence ev = probe(c.endpoint);
                main.post(() -> {
                    if (!isAdded()) return;
                    c.evidence = ev;
                    if (ev == PlatformPresence.Evidence.PRESENT) applyState(c, GREEN);
                    else if (ev == PlatformPresence.Evidence.ABSENT) applyState(c, GRAY);
                    else {
                        // ADFA-4837: stay amber (patient) while the box is warming up; only fall to red
                        // once the server has been alive past the grace and this service still won't answer.
                        long aliveMs = serverAliveSinceMs > 0L
                                ? android.os.SystemClock.elapsedRealtime() - serverAliveSinceMs : 0L;
                        applyState(c, aliveMs >= CARD_RED_GRACE_MS ? RED : AMBER);
                    }
                    updateHeaderFromCards();
                });
            });
        }
        updateHeaderFromCards();
    }

    /**
     * ADFA-4828: the header reflects the real aggregate, so it never contradicts the cards —
     * "no library" when nothing is installed, "starting" until the installed cards actually answer,
     * "ready" only when something is up, "ready but empty" when the server is up with no content.
     */
    private void updateHeaderFromCards() {
        if (homeStatus == null || !isAdded()) return;
        boolean installed = org.iiab.controller.SystemStateEvaluator.isSystemInstalled(requireContext());
        boolean alive = ServerStateRepository.get().current().alive;
        if (!installed) { setHeader(H_NO_LIBRARY); return; }
        // ADFA-5074: content being added outranks everything below. It is the only state
        // the user can act on from here, and reporting "ready" over a running install is
        // how the install became unreachable in the first place. Sessions only, not banked
        // orders: a banked one has no screen to open, and a blocked drain would leave this
        // claiming work that is not happening.
        // ADFA-5061: from the pass snapshot, so the header and the cards cannot answer this
        // from two different readings taken moments apart.
        boolean contentRunning = passContent != null
                ? passContent.anyRunning()
                : org.iiab.controller.system.data.PendingContent.anyRunning(requireContext());
        if (contentRunning
                || org.iiab.controller.install.presentation.ModuleQueueRepository.get().isRunning()) {
            setHeader(H_INSTALLING);
            return;
        }
        if (!alive) {
            // ADFA-4837: "Starting…" only while a start is really in progress; otherwise the server
            // isn't coming up on its own → "Couldn't start — tap to retry".
            boolean starting = (getActivity() instanceof LibraryActivity)
                    && ((LibraryActivity) getActivity()).isServerStarting();
            setHeader(starting ? H_STARTING : H_FAILED);
            return;
        }
        boolean anyChecking = false, anyReady = false;
        for (Card c : cards) {
            if (unsupported(c) || c.state == GRAY) continue;   // GRAY = content absent → doesn't gate
            if (c.state == AMBER) anyChecking = true;
            else if (c.state == GREEN) anyReady = true;
        }
        if (anyChecking) setHeader(H_STARTING);
        else if (anyReady) setHeader(H_READY);
        else setHeader(H_READY_EMPTY);   // server up, nothing installed to explore
    }

    private void setHeader(int h) {
        if (homeStatus == null) return;
        headerState = h;
        String text; int dotColor;
        switch (h) {
            case H_NO_LIBRARY:  text = getString(R.string.k2go_home_no_library);  dotColor = R.color.k2go_muted; break;
            case H_READY:       text = getString(R.string.k2go_home_ready);       dotColor = R.color.k2go_leaf;  break;
            case H_READY_EMPTY: text = getString(R.string.k2go_home_ready_empty); dotColor = R.color.k2go_leaf;  break;
            case H_FAILED:      text = getString(R.string.k2go_home_failed);      dotColor = R.color.k2go_clay;  break;
            // ADFA-5061: green, not amber. H_INSTALLING is set from PendingContent.anyRunning
            // — the same condition the cards test — so leaving this amber while a card
            // receiving content is green put two opposite colours on one screen for one event.
            // Under the convention the cards now follow, colour is the severity channel and
            // content arriving blocks nothing: the library works, and it is getting bigger.
            // The label carries the news, and the row stays tappable as the way into progress.
            case H_INSTALLING:  text = getString(R.string.k2go_home_installing);  dotColor = R.color.k2go_leaf;  break;
            default:            text = getString(R.string.k2go_starting_library); dotColor = R.color.k2go_amber; break;
        }
        homeStatus.setText(text);
        if (homeStatusDot != null) tint(homeStatusDot, dotColor);
        // ADFA-4837: only the failed state is tappable (retry); keep others inert.
        // ADFA-5074: and the installing state, which is the way back into a running install.
        if (homeStatusRow != null) homeStatusRow.setClickable(h == H_FAILED || h == H_INSTALLING);
    }

    private void applyState(Card c, int st) {
        c.state = st;
        if (c.dot == null || c.status == null) return;
        int dotColor, textColor;
        String label;
        switch (st) {
            case GREEN: dotColor = R.color.k2go_leaf; textColor = R.color.k2go_leaf; label = getString(R.string.k2go_card_ready); break;
            case AMBER: dotColor = R.color.k2go_amber; textColor = R.color.k2go_amber_text; label = getString(R.string.k2go_card_connecting); break;
            case RED:   dotColor = R.color.k2go_clay; textColor = R.color.k2go_clay; label = getString(R.string.k2go_card_unavailable); break;
            default:    dotColor = R.color.k2go_muted; textColor = R.color.k2go_muted; label = getString(R.string.k2go_card_not_installed); break;
        }
        tint(c.dot, dotColor);
        c.status.setText(label);
        c.status.setTextColor(ContextCompat.getColor(requireContext(), textColor));
        if (st != GREEN && isScheduled(c)) {   // ADFA-4958
            tint(c.dot, R.color.k2go_teal);
            c.status.setText(getString(R.string.k2go_state_scheduled));
            c.status.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
        }
        // ADFA-5061: content in flight for this card wins over whatever the probe said, and
        // it applies whether the probe answered or not.
        //
        // Observed on device: while courses were downloading, the card alternated between
        // "Ready" and "Unavailable" every few seconds. Kolibri is not going down and coming
        // back — it is a Django app importing a channel under proot on a phone, so it keeps
        // serving but sometimes takes longer than the 1500 ms this probe allows. Treating a
        // timeout as a verdict on health turned "slow" into "broken", twice a minute.
        //
        // Saying what is actually happening is both truthful and stable: no race to win, so
        // no flicker. The header three centimetres above already said "Adding content" while
        // the card said "Unavailable" — one screen, two answers, and the header's was right.
        // Green, not amber. The dot answers "can I use this?" and the label answers "what is
        // happening?" — two questions that do not have to share a channel. Importing a
        // channel database and serving content are different jobs inside Kolibri, and
        // browsing is read-only, so there is nothing to warn about and nothing to block.
        // Amber would say "degraded, wait"; that is a claim we have no evidence for, and it
        // was only chosen here by copying the header, where amber means something else
        // entirely ("this screen is not final yet").
        if (contentInFlight(c)) {
            tint(c.dot, R.color.k2go_leaf);
            c.status.setText(getString(R.string.k2go_card_adding));
            c.status.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_leaf));
        }
    }

    /**
     * ADFA-5061: whether this card's content type has a stream running right now.
     *
     * <p>Read from the pass snapshot, never with a fresh read of its own — see
     * {@link #passContent}. False when there is no pass in progress, which is the safe
     * direction: the label falls back to whatever the probe said.
     *
     * <p><b>Maps is always false here</b>, and deliberately so upstream: its progress belongs
     * to the module queue rather than to a content stream, so {@code Snapshot.isRunning} has
     * no case for it. The maps card therefore keeps showing the probe's verdict while a
     * runrole builds tiles — the same defect this fixed for the other three, still open for
     * one. Recorded rather than papered over, because the right answer is the module queue,
     * not a fourth reading here.
     *
     * <p>The card endpoints and the content keys disagree on one name — the Wikipedia card is
     * {@code kiwix} and its content type is {@code zim}. That belongs on {@code ContentType}
     * as an endpoint field; it is exactly what ADFA-5062 exists to retire, and this adds one
     * more instance of it.
     */
    private boolean contentInFlight(Card c) {
        if (c == null || c.endpoint == null || passContent == null) return false;
        String key = "kiwix".equals(c.endpoint) ? "zim" : c.endpoint;
        org.iiab.controller.system.domain.ContentType type =
                org.iiab.controller.system.domain.ContentType.byKey(key);
        return type != null && passContent.isRunning(type);
    }

    private void tint(View v, int colorRes) {
        v.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), colorRes)));
    }

    /**
     * ADFA-5061: what the endpoint said, in the model's words.
     *
     * <p>This used to return dot colours — GREEN, GRAY, AMBER — which is
     * {@code PRESENT}/{@code ABSENT}/{@code NONE} computed identically and named after paint.
     * The sheet then read the paint back out and re-derived the verdict, so one rule existed in
     * three places and the two a user could hit were the copies. It reports evidence now;
     * {@link PlatformPresence} owns what the evidence is worth, and the colour is chosen from
     * the answer rather than being the answer.
     */
    private static PlatformPresence.Evidence probe(String endpoint) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(BoxEndpoints.BASE + "/" + endpoint + "/");
            c = (HttpURLConnection) u.openConnection();
            c.setUseCaches(false);
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            if (code >= 200 && code < 400) return PlatformPresence.Evidence.PRESENT;
            return code == 404 ? PlatformPresence.Evidence.ABSENT
                    : PlatformPresence.Evidence.NONE;
        } catch (Exception e) {
            return PlatformPresence.Evidence.NONE;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}

// CI test (ADFA-4812): a push to a non-main branch is built and distributed to Firebase — validates ADFA-4810. Throwaway; do not merge.
