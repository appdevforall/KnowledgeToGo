package org.iiab.controller.redesign;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private static final int GRAY = 0, AMBER = 1, GREEN = 2;

    private static final class Card {
        final String endpoint; final String title; final boolean requires64; final int iconRes;
        View dot; TextView status; int state = GRAY;
        Card(String e, String t, boolean r, int i) { endpoint = e; title = t; requires64 = r; iconRes = i; }
    }

    private final List<Card> cards = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView homeStatus;
    private View homeStatusDot;

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
        cards.add(new Card("books",   "Read a book",       false, R.drawable.ic_card_book));
        cards.add(new Card("code",    "Code on the Go",    false, R.drawable.ic_card_code));
        cards.add(new Card("kiwix",   "Explore Wikipedia", true,  R.drawable.ic_card_wikipedia));
        cards.add(new Card("kolibri", "Take courses",      false, R.drawable.ic_card_courses));
        cards.add(new Card("maps",    "Navigate maps",     false, R.drawable.ic_card_maps));

        buildCards(inflater, root.findViewById(R.id.k2go_cards));

        root.findViewById(R.id.k2go_get_more).setOnClickListener(v ->
                startActivity(new android.content.Intent(requireContext(), SetupLibraryActivity.class)));
        return root;
    }

    private void buildCards(LayoutInflater inflater, LinearLayout host) {
        LinearLayout row = null;
        for (int i = 0; i < cards.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                host.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            final Card c = cards.get(i);
            View card = inflater.inflate(R.layout.view_k2go_card, row, false);
            ((ImageView) card.findViewById(R.id.k2go_card_icon)).setImageResource(c.iconRes);
            ((TextView) card.findViewById(R.id.k2go_card_title)).setText(c.title);
            c.dot = card.findViewById(R.id.k2go_card_dot);
            c.status = card.findViewById(R.id.k2go_card_status);
            card.setOnClickListener(v -> onCardClick(c));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
            lp.width = 0; lp.weight = 1f;
            row.addView(card, lp);
            applyState(c, GRAY);
        }
        if (cards.size() % 2 == 1 && row != null) {
            View spacer = new View(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1);
            lp.weight = 1f;
            row.addView(spacer, lp);
        }
    }

    private void onCardClick(Card c) {
        if (c.state == GREEN) {
            Intent i = new Intent(requireContext(), PortalActivity.class);
            i.putExtra("TARGET_URL", BoxEndpoints.BASE + "/" + c.endpoint + "/");
            startActivity(i);
        } else {
            Toast.makeText(requireContext(), "Still starting — one moment", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onResume() { super.onResume(); main.post(poll); }
    @Override public void onPause() { super.onPause(); main.removeCallbacks(poll); }

    private void refreshStatuses() {
        boolean alive = ServerStateRepository.get().current().alive;
        if (homeStatus != null) homeStatus.setText(alive ? "Ready to explore" : "Starting your library…");
        if (homeStatusDot != null) tint(homeStatusDot, alive ? R.color.k2go_leaf : R.color.k2go_amber);
        for (final Card c : cards) {
            if (c.requires64 && android.os.Build.SUPPORTED_64_BIT_ABIS.length == 0) {
                applyState(c, GRAY);
                if (c.status != null) c.status.setText("Not supported");
                continue;
            }
            if (!alive) { applyState(c, GRAY); continue; }
            AppExecutors.get().io().execute(() -> {
                final int st = probe(c.endpoint);
                main.post(() -> { if (isAdded()) applyState(c, st); });
            });
        }
    }

    private void applyState(Card c, int st) {
        c.state = st;
        if (c.dot == null || c.status == null) return;
        int dotColor, textColor;
        String label;
        switch (st) {
            case GREEN: dotColor = R.color.k2go_leaf; textColor = R.color.k2go_leaf; label = "Ready"; break;
            case AMBER: dotColor = R.color.k2go_amber; textColor = R.color.k2go_amber_text; label = "Connecting"; break;
            default:    dotColor = R.color.k2go_muted; textColor = R.color.k2go_muted; label = "Not installed"; break;
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
