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
import org.iiab.controller.util.AppExecutors;

/**
 * Content-first Library home (ADFA-4725, Phase 3): action cards with live 3-state
 * status dots (gray = not installed / amber = starting / green = ready). Tapping a
 * ready card opens its content in the portal WebView (Explore Wikipedia -> Kiwix).
 */
public class LibraryHomeFragment extends Fragment {

    private static final long POLL_MS = 3000L;
    private static final int GRAY = 0, AMBER = 1, GREEN = 2, RED = 3;

    private static final class Card {
        final String endpoint; final String title; final boolean requires64; final int iconRes;
        View dot; TextView status; int state = GRAY; int amberStreak = 0;
        Card(String e, String t, boolean r, int i) { endpoint = e; title = t; requires64 = r; iconRes = i; }
    }

    private final List<Card> cards = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView homeStatus;
    private View homeStatusDot;
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

        cards.clear();
        cards.add(new Card("books",   getString(R.string.k2go_card_books),       false, R.drawable.ic_card_book));
        cards.add(new Card("code",    getString(R.string.k2go_card_code),    false, R.drawable.ic_card_code));
        cards.add(new Card("kiwix",   getString(R.string.k2go_card_wikipedia), true,  R.drawable.ic_card_wikipedia));
        cards.add(new Card("kolibri", getString(R.string.k2go_card_courses),      false, R.drawable.ic_card_courses));
        cards.add(new Card("maps",    getString(R.string.k2go_card_maps),     false, R.drawable.ic_card_maps));

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
        if (c.state == GREEN) {
            Intent i = new Intent(requireContext(), PortalActivity.class);
            i.putExtra("TARGET_URL", BoxEndpoints.BASE + "/" + c.endpoint + "/");
            startActivity(i);
        } else {
            c.amberStreak = 0;
            applyState(c, AMBER);
            AppExecutors.get().io().execute(() -> {
                final int st = probe(c.endpoint);
                main.post(() -> { if (isAdded()) applyState(c, (st == GREEN || st == GRAY) ? st : AMBER); });
            });
            Toast.makeText(requireContext(), getString(R.string.k2go_retrying), Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onResume() { super.onResume(); main.post(poll); }
    @Override public void onPause() { super.onPause(); main.removeCallbacks(poll); }

    private void refreshStatuses() {
        boolean alive = ServerStateRepository.get().current().alive;
        if (homeStatus != null) homeStatus.setText(alive ? getString(R.string.k2go_home_ready) : getString(R.string.k2go_starting_library));
        if (homeStatusDot != null) tint(homeStatusDot, alive ? R.color.k2go_leaf : R.color.k2go_amber);
        for (final Card c : cards) {
            if (c.requires64 && android.os.Build.SUPPORTED_64_BIT_ABIS.length == 0) {
                applyState(c, GRAY);
                if (c.status != null) c.status.setText(getString(R.string.k2go_not_supported));
                continue;
            }
            if (!alive) { applyState(c, GRAY); continue; }
            AppExecutors.get().io().execute(() -> {
                final int st = probe(c.endpoint);
                main.post(() -> {
                    if (!isAdded()) return;
                    if (st == GREEN || st == GRAY) { c.amberStreak = 0; applyState(c, st); }
                    else { c.amberStreak++; applyState(c, c.amberStreak >= 4 ? RED : AMBER); }
                });
            });
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
    }

    private void tint(View v, int colorRes) {
        v.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), colorRes)));
    }

    private static int probe(String endpoint) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(BoxEndpoints.BASE + "/" + endpoint + "/");
            c = (HttpURLConnection) u.openConnection();
            c.setUseCaches(false);
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            if (code >= 200 && code < 400) return GREEN;
            if (code == 404) return GRAY;
            return AMBER;
        } catch (Exception e) {
            return AMBER;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
