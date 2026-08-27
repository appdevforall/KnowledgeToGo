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
import org.iiab.controller.system.data.PlatformEvidence;
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
            H_FAILED = 4, H_INSTALLING = 5,
            // ADFA-5143: a clone in flight, told apart by side. The receiver is the only one with a
            // percentage, so it is the one whose loss matters; the donor has a QR and a Stop.
            H_CLONE_RECEIVING = 6, H_CLONE_SHARING = 7,
            // ADFA-5147: a system is on disk but was left unbootable (a killed install or clone). Split
            // out of H_NO_LIBRARY, which offered to install over it — this offers to repair it instead.
            H_DAMAGED = 8,
            // ADFA-5312: a system op is in progress with the server deliberately DOWN (a proot module,
            // a rootfs install, or a deep op). That is "wait", so it is rendered amber like the clone
            // states — NOT the green "Adding content" of H_INSTALLING, which is a content download over
            // a live, navigable box. Same "See progress" action, different colour + label.
            H_INSTALLING_SYSTEM = 9;
    // ADFA-4837: how long a card stays amber (patient) once the box is up before it's called stuck.
    private static final long CARD_RED_GRACE_MS = 60000L;

    private static final class Card {
        final String endpoint; final String title; final boolean requires64; final int iconRes;
        View dot; TextView status; int state = GRAY;
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
    /** ADFA-5061: the action, when the state has one. Never the status text. */
    private com.google.android.material.button.MaterialButton homeStatusAction;
    /** Shown instead of a button while the app is doing something the user cannot press. */
    private View homeStatusSpinner;
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
        homeStatusAction = root.findViewById(R.id.k2go_home_status_action);
        homeStatusSpinner = root.findViewById(R.id.k2go_home_status_spinner);
        // ADFA-5061: the action moved off the status text and onto a button. The row itself is
        // no longer clickable — a line of prose that silently doubles as a control is the thing
        // this change is undoing, and leaving the old target in place would keep half of it.
        if (homeStatusAction != null) {
            homeStatusAction.setOnClickListener(v -> {
                // ADFA-5074: the way back into a running install. Opens the index without
                // starting anything — every other route to it begins new work.
                if (headerState == H_INSTALLING || headerState == H_INSTALLING_SYSTEM) {
                    startActivity(new android.content.Intent(
                            requireContext(), SetupProgressActivity.class));
                    return;
                }
                // ADFA-5137: with no system, the way forward is choosing one — the tier step, the same
                // place the wizard sends people. Named, on the header, instead of a sentence pointing
                // at a control at the bottom of the screen called "Get more".
                if (headerState == H_NO_LIBRARY) {
                    openGetMore();
                    return;
                }
                // ADFA-5147: a damaged system is repaired, not installed over. Exactly what the launch
                // recovery dialog does on "Recover" — the recovery screen offers restore-a-backup or
                // reinstall, both of which work without a healthy rootfs. No finish() here, unlike the
                // dialog: the dialog closes so the user cannot fall back onto a held boot gate, but Home
                // is now an honest surface — back out of recovery and it still says "needs repair".
                if (headerState == H_DAMAGED) {
                    SetupLibraryActivity.recover(requireContext());   // ADFA-5150: the shared route
                    return;
                }
                // ADFA-5143: both clone states go to the Clone tab. For the receiver that is the only
                // place the percentage exists — the donor has no progress to lose, only a QR — which
                // is why this is the half of the ticket that carries the priority.
                if (headerState == H_CLONE_RECEIVING || headerState == H_CLONE_SHARING) {
                    if (getActivity() instanceof LibraryActivity) {
                        ((LibraryActivity) getActivity()).openCloneTab();
                    }
                    return;
                }
                // ADFA-4837: retry only when it is genuinely safe — canStartServer guards
                // against stacking a second proot over a live one.
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
        // ADFA-5137 (review): refuse while a deep operation owns the environment, and refuse HERE so
        // both entrances are covered — the footer control and the header button this ticket added.
        //
        // The hole is not theoretical and the header made it one tap wide. isSystemInstalled() is false
        // for the whole time an install marker is set, and a clone-receive holds both the marker and
        // the lock — so during a live receive the header reads "no library" and this method would take
        // the Step-1 branch. That branch starts an install with reinstall=false, InstallService's
        // non-destructive guard sees the half-received rootfs directory, skips the extract and reports
        // success, and its teardown clears the marker that a killed receive needs for recovery. That is
        // exactly the "boot the wreck" failure InstallService's own cleanup comment warns about,
        // reached from a button labelled as a way out.
        //
        // ownerHeld, not isHeld: a live content download holds no owner marker and must not block this
        // (ADFA-4957 draws the same line for the server toggle).
        if (org.iiab.controller.env.EnvironmentLock.ownerHeld(requireContext())) {
            if (getView() != null) {
                org.iiab.controller.util.Snackbars.make(getView(), R.string.k2go_install_busy).show();
            }
            return;
        }
        // ADFA-5312: the content-only vs full-setup decision (and the busy guard) come from the shared
        // verdict. A running proot module holds the install marker but NOT the env-lock owner, so the
        // ownerHeld guard above misses it — without this, "Get more" during a module install would
        // launch the destructive full setup over a system that is present and mid-install.
        org.iiab.controller.system.domain.SystemVerdict.State verdict =
                org.iiab.controller.system.data.SystemFactsReader.verdict(requireContext());
        if (verdict == org.iiab.controller.system.domain.SystemVerdict.State.INSTALLING) {
            if (getView() != null) {
                org.iiab.controller.util.Snackbars.make(getView(), R.string.k2go_install_busy).show();
            }
            return;
        }
        // A ready system skips the destructive system step and goes straight to content (Step 2);
        // otherwise (no system / damaged) run the full setup from Step 1.
        Intent i = new Intent(requireContext(), SetupLibraryActivity.class);
        if (verdict == org.iiab.controller.system.domain.SystemVerdict.State.READY) {
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
                final PlatformPresence.Evidence ev = probe(c.endpoint);
                // Recorded off the main thread and before any view gate: what an endpoint said
                // is a fact about the box, and throwing it away because the fragment went is
                // exactly the case the store exists for.
                PlatformEvidence.record(c.endpoint, ev);
                main.post(() -> {
                    if (!isAdded()) return;
                    if (ev == PlatformPresence.Evidence.PRESENT) applyState(c, GREEN);
                    else if (ev == PlatformPresence.Evidence.ABSENT) applyState(c, GRAY);
                    else applyState(c, AMBER);
                });
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
     * other producers: an unsupported module, a box with no system, and the colour a card wears
     * before anything has been asked. Reading the colour therefore offered to install platforms
     * that were installed, in the ordinary window before the first probe returns.
     *
     * <p>The evidence is read from {@link PlatformEvidence}, not from this fragment. It began as
     * a field on the card and that life was too short: the cards are rebuilt in {@code
     * onCreateView}, so a trip to Settings — where the server is stopped — forgot the one answer
     * the next sheet needed.
     *
     * <p>Content in flight is checked first and outranks everything, for the reason
     * {@code onCardClick} already uses it: a platform we are watching work cannot be missing.
     * That path bypasses this method, so without it the overflow menu could say "not
     * responding" about a card reading "Adding content" two centimetres away.
     */
    private void openSheet(Card c) {
        ModuleActionSheet.State s;
        final PlatformPresence.Evidence ev = PlatformEvidence.last(c.endpoint);
        // ADFA-5312: the "no usable system" decision comes from the shared verdict, so a card's sheet
        // can't collapse to Recover during an install (marker held) the way bare !systemInstalled did.
        org.iiab.controller.system.domain.SystemVerdict.State verdict =
                org.iiab.controller.system.data.SystemFactsReader.verdict(requireContext());
        if (c.state == GREEN || contentInFlight(c)) s = ModuleActionSheet.State.READY;
        else if (isScheduled(c)) s = ModuleActionSheet.State.SCHEDULED;
        else if (verdict == org.iiab.controller.system.domain.SystemVerdict.State.NO_SYSTEM
                || verdict == org.iiab.controller.system.domain.SystemVerdict.State.DAMAGED) {
            // ADFA-5150: no system to install a module into. This used to be NOT_INSTALLED — About /
            // Install now / Schedule — which banked an install order with nothing to drain it. The
            // sheet now collapses to Recover. (A first-run phone never reaches this surface; the wizard
            // runs before the tabs, so a systemless card here always means a system was lost.)
            // ADFA-5312: gated on the verdict (NO_SYSTEM/DAMAGED), not bare !systemInstalled, so a
            // mid-install marker no longer routes a card to Recover.
            s = ModuleActionSheet.State.NO_SYSTEM;
        } else if (ev != null && !PlatformPresence.resolve(ev)) {
            // Known absent, and still known absent with the box off — that is the state where
            // installing is exactly the right offer.
            s = ModuleActionSheet.State.NOT_INSTALLED;
        } else if (!org.iiab.controller.system.data.SystemFactsReader.serverAnswering()) {
            // We know why this one is silent, so we say so rather than shrugging.
            s = ModuleActionSheet.State.STOPPED;
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

    /**
     * ADFA-5061: whether a system is installed, read once per pass alongside {@link #passContent}
     * and for the same reason.
     *
     * <p>It was read twice per pass off the disk — once here and once in the header — which
     * {@code SystemFactsReader} asks callers not to do ("once per decision rather than once per
     * row"), and it is now also read by {@code applyState} and {@code openSheet}, which would
     * have made four. Seeded true so the paths that run before the first pass behave as they did.
     */
    private boolean systemInstalled = true;

    private void refreshStatuses() {
        boolean installed = org.iiab.controller.SystemStateEvaluator.isSystemInstalled(requireContext());
        systemInstalled = installed;
        boolean alive = ServerStateRepository.get().current().alive;
        passContent = org.iiab.controller.system.data.PendingContent.read(requireContext());

        // ADFA-4837: track when the server first came up; the card red-grace is measured from here.
        if (alive) { if (serverAliveSinceMs == 0L) serverAliveSinceMs = android.os.SystemClock.elapsedRealtime(); }
        else serverAliveSinceMs = 0L;

        // ADFA-4828: nothing installed (or an install is running — the boot gate normally covers
        // that). The home isn't starting anything, so don't imply it: the cards read "Not installed"
        // and the header points to Get more (set in updateHeaderFromCards).
        // ADFA-5312: only paint every card "Not installed" when there is genuinely no usable system —
        // nothing on disk (NO_SYSTEM) or a killed install left it damaged (DAMAGED). During an install,
        // clone or deep op the system is present/arriving, so fall through and keep last-known state.
        org.iiab.controller.system.domain.SystemVerdict.State verdict =
                org.iiab.controller.system.data.SystemFactsReader.verdict(requireContext());
        if (verdict == org.iiab.controller.system.domain.SystemVerdict.State.NO_SYSTEM
                || verdict == org.iiab.controller.system.domain.SystemVerdict.State.DAMAGED) {
            for (final Card c : cards) {
                // ADFA-5061: this used to record ABSENT for all five, and the write was right at
                // the instant it happened and wrong forever after. "No system is installed" is a
                // fact SystemStateEvaluator owns; copying it into the probe store made a second
                // holder that nothing updated, so once the user installed the system the store
                // still said ABSENT — through the whole boot, since no probe runs before the box
                // answers — and every sheet offered to install a platform that was there. The
                // fact is asked for where it is needed (applyState, openSheet) instead.
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
                // to install it. Nothing is recorded, so "nothing established" withholds the
                // offer where ABSENT would have made it.
                applyState(c, GRAY);
                if (c.status != null) c.status.setText(getString(R.string.k2go_not_supported));
                continue;
            }
            // ADFA-4828: system is installed. Before the first probe resolves (or while the server
            // is still coming up) show "Connecting", never "Not installed" — the latter only appears
            // once a probe actually reports the content is absent (404 -> GRAY).
            // ADFA-5061: the box being down does not unlearn what a probe already established.
            // This line cleared the evidence and then painted everything amber, and both halves
            // were wrong on the device. Clearing it made a platform that had answered 404 go
            // back to "unknown" the moment the server stopped, so its sheet stopped offering the
            // install it should still offer. Painting it amber said "Connecting" about a platform
            // that is not there and is not going to be there when the box returns — a card
            // claiming to connect two centimetres from a sheet correctly reading "Not installed".
            // Turning the box off installs nothing and uninstalls nothing: the verdict stands,
            // and only a fresh probe replaces it.
            if (!alive) {
                applyState(c, PlatformEvidence.last(c.endpoint) == PlatformPresence.Evidence.ABSENT
                        ? GRAY : AMBER);
                continue;
            }
            AppExecutors.get().io().execute(() -> {
                final PlatformPresence.Evidence ev = probe(c.endpoint);
                // Before the view gate, deliberately — see the retry path above.
                PlatformEvidence.record(c.endpoint, ev);
                main.post(() -> {
                    if (!isAdded()) return;
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
        boolean alive = ServerStateRepository.get().current().alive;
        // ADFA-5312: the system-state axis comes from the ONE shared verdict (SystemFactsReader), so
        // Home cannot disagree with Modules/Connect/Clone about whether an install-in-progress is
        // "installing" vs "no system / damaged". Clone, any system op (install/module/deep-op) and
        // damaged all outrank the content + server sub-states below, which only apply once the system
        // itself is READY. (This centralizes what used to be re-derived here from isSystemInstalled()
        // plus a grab-bag of running flags — the ADFA-5312 bug class.)
        switch (org.iiab.controller.system.data.SystemFactsReader.verdict(requireContext())) {
            case CLONE_RECEIVING: setHeader(H_CLONE_RECEIVING); return;
            case CLONE_SHARING:   setHeader(H_CLONE_SHARING);   return;
            case INSTALLING:      setHeader(H_INSTALLING_SYSTEM); return;
            case NO_SYSTEM:       setHeader(H_NO_LIBRARY);      return;
            case DAMAGED:         setHeader(H_DAMAGED);         return;
            case READY:           break;   // fall through to the content + server sub-state below
        }
        // ADFA-5074: content being added outranks everything below. It is the only state
        // the user can act on from here, and reporting "ready" over a running install is
        // how the install became unreachable in the first place. Sessions only, not banked
        // orders: a banked one has no screen to open, and a blocked drain would leave this
        // claiming work that is not happening.
        // ADFA-5061: from the pass snapshot, so the header and the cards cannot answer this
        // from two different readings taken moments apart.
        // ADFA-5074: content being added (REST download) still shows "installing" — but that is the
        // content axis on a READY system, not a system state, so it lives here in the READY branch
        // (the system op case is already handled by the verdict above).
        boolean contentRunning = passContent != null
                ? passContent.anyRunning()
                : org.iiab.controller.system.data.PendingContent.anyRunning(requireContext());
        if (contentRunning) {
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
            // ADFA-5312: a system op with the server stopped — amber "wait", not the green content look.
            case H_INSTALLING_SYSTEM: text = getString(R.string.k2go_home_installing_system); dotColor = R.color.k2go_amber; break;
            // ADFA-5143: amber, not green and not red. The server is deliberately down and the box is
            // busy with something the user started — that is "wait", which is what amber says here.
            case H_CLONE_RECEIVING: text = getString(R.string.k2go_home_clone_receiving); dotColor = R.color.k2go_amber; break;
            case H_CLONE_SHARING:   text = getString(R.string.k2go_home_clone_sharing);   dotColor = R.color.k2go_amber; break;
            // ADFA-5147: clay, like H_FAILED — both are "the system won't run and needs you". The
            // difference is the remedy: FAILED can retry a healthy system, DAMAGED must repair a broken
            // one, so it carries the Recover action below rather than Retry.
            case H_DAMAGED:     text = getString(R.string.k2go_home_damaged);     dotColor = R.color.k2go_clay;  break;
            default:            text = getString(R.string.k2go_starting_library); dotColor = R.color.k2go_amber; break;
        }
        homeStatus.setText(text);
        if (homeStatusDot != null) tint(homeStatusDot, dotColor);

        // ADFA-5061: the trailing slot. Two states offer an action and get a button; one is the
        // app working and gets a spinner; the rest are statements and get nothing. The status
        // colour lives on the dot only — the button wears the brand colour, because it is a
        // control rather than a severity.
        // ADFA-5137: H_NO_LIBRARY gets one too. It was the only state here that offered nothing, and
        // that is finding 5 of state-spine.svg: the header said "tap Get more to install" while being
        // plain text, pointing at a control at the far bottom of the screen whose name says content
        // rather than system. Meanwhile both cards on the way there offer Install and Schedule, and
        // both refuse.
        //
        // ADFA-5137 also closes the way INTO this state, so in principle nobody arrives here any more.
        // The button stays anyway, because "in principle" is what the last four dead ends had in
        // common: a state with no exit is a bug whoever reaches it, including by a route that does not
        // exist yet. One line in a switch that already hands out two other buttons.
        int action = h == H_FAILED ? R.string.k2go_home_retry
                : (h == H_INSTALLING || h == H_INSTALLING_SYSTEM) ? R.string.k2go_home_see_progress
                : h == H_NO_LIBRARY ? R.string.k2go_home_install_system
                // ADFA-5143: a transfer is not offered a restart — restarting the server is not a
                // meaningful thing to do to a transfer. Each side is offered the place that matters:
                // the receiver its progress, the donor its QR.
                : h == H_CLONE_RECEIVING ? R.string.k2go_home_see_progress
                : h == H_CLONE_SHARING ? R.string.k2go_home_clone_view
                // ADFA-5147: repair, not install. Routes to the recovery screen the launch dialog uses.
                : h == H_DAMAGED ? R.string.k2go_home_recover : 0;
        if (homeStatusAction != null) {
            homeStatusAction.setVisibility(action != 0 ? View.VISIBLE : View.GONE);
            if (action != 0) homeStatusAction.setText(action);
        }
        if (homeStatusSpinner != null) {
            homeStatusSpinner.setVisibility(h == H_STARTING ? View.VISIBLE : View.GONE);
        }
        // The row reports; it does not act. Kept explicit so a future edit has to mean it.
        if (homeStatusRow != null) {
            homeStatusRow.setClickable(false);
            homeStatusRow.setFocusable(false);
        }
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
        // ADFA-5061: stopped, not connecting. When the box is not answering, every card sat in
        // amber saying "Connecting" — a claim about an attempt that is not happening, and the
        // header two centimetres above was already saying the system could not start.
        //
        // This is not the unknown case and must not borrow its words. `alive` comes from a ping
        // to /home, which is nginx itself: if the front door is dark, nothing behind it can
        // answer, and all five platforms are behind it. That is a fact we hold, not an inference
        // — so the card says what is true rather than hedging. "Status unknown" belongs to the
        // other silence, a box that answers while one platform does not.
        //
        // Last of the overrides on purpose: content in flight cannot be real over a box that is
        // not answering, so if the two ever disagree, this one is right.
        //
        // Except over a platform we know is absent. With no system installed the box does not
        // answer either, and "Stopped" would be the wrong half of the truth — there is nothing
        // to start, and "Not installed" is what the user needs to read. Knowing beats knowing why.
        // And except when there is no system at all: the box cannot be "stopped" when there is
        // nothing to run. That case used to be carried by a recorded ABSENT; it is asked for now.
        if (systemInstalled
                && PlatformEvidence.last(c.endpoint) != PlatformPresence.Evidence.ABSENT
                && !org.iiab.controller.system.data.SystemFactsReader.serverAnswering()) {
            tint(c.dot, R.color.k2go_amber);
            c.status.setText(getString(R.string.k2go_card_stopped));
            c.status.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_amber_text));
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
     * {@code kiwix} and its content type is {@code zim}. That alias now lives on {@code ContentType}
     * as an {@code endpoint} field (ADFA-5062), so the lookup is {@code byEndpoint} rather than a
     * hand-rolled remap here.
     */
    private boolean contentInFlight(Card c) {
        if (c == null || c.endpoint == null || passContent == null) return false;
        org.iiab.controller.system.domain.ContentType type =
                org.iiab.controller.system.domain.ContentType.byEndpoint(c.endpoint);
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
