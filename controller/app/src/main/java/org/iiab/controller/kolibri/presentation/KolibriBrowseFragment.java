/*
 * ============================================================================
 * Name        : KolibriBrowseFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4954. The Courses picker, following the conventions in
 *               controller/docs/CATALOG_BROWSE_TEMPLATE.md. Replaces the
 *               PlaceholderFragment the wizard used to show.
 * ============================================================================
 */
package org.iiab.controller.kolibri.presentation;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.StatFs;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import org.iiab.controller.R;
import org.iiab.controller.kolibri.domain.Channel;
import org.iiab.controller.redesign.SetupLibraryActivity;
import org.iiab.controller.redesign.ZimLanguageDialog;
import org.iiab.controller.util.ByteFormatter;

import java.util.List;

/**
 * Browse and pick whole Kolibri channels.
 *
 * <p><b>How this instantiates the catalog-browse template.</b> The template's shape
 * is a category index followed by an item list. There is no index here yet: the
 * chip row's theme groups are an artificial grouping we author ourselves — the ZIM
 * flow groups Kiwix's categories, this one would group the channels themselves —
 * and those groups are still to be defined, so the row holds only {@code All} and
 * this screen is the item list. Language filters from the selector above; it is not
 * a chip.
 *
 * <p>Everything else follows the agreements: the language selector is one whole-row
 * control with no inner button, rows are flat with hairlines between them and a
 * leading checkbox and a right-aligned size, the only rounded fill is the selected
 * row's highlight, the count lives on its own line, and there is one fixed bottom
 * bar. See {@code controller/docs/CATALOG_BROWSE_TEMPLATE.md}.
 *
 * <p>Reads nothing itself: {@link KolibriCatalogViewModel} owns the catalog and the
 * selection. It is scoped to the activity so the selection survives the trip to
 * confirm and back. Sizes come from the bundled catalog and free space from
 * {@code StatFs} — no server, which is what lets this run in the wizard.
 */
public final class KolibriBrowseFragment extends Fragment {

    private KolibriCatalogViewModel vm;

    private TextView langLabel;
    private TextView langSub;
    private TextView status;
    private TextView count;
    private TextView storageLabel;
    private ProgressBar storageBar;
    private LinearLayout chips;
    private LinearLayout list;
    private Button review;

    private long freeMb = 0L;
    private long totalMb = 0L;
    /** Language comes from the selector above the list, never from the chip row. */
    private String langFilter = "";
    /** The chip row's axis: {@code ""} is All. Only All exists until the groups are authored. */
    private String groupFilter = "";
    private boolean searching = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_k2go_kolibri_browse, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        langLabel = v.findViewById(R.id.k2go_kbrowse_lang);
        langSub = v.findViewById(R.id.k2go_kbrowse_lang_sub);
        status = v.findViewById(R.id.k2go_kbrowse_status);
        count = v.findViewById(R.id.k2go_kbrowse_count);
        storageLabel = v.findViewById(R.id.k2go_kbrowse_storage_label);
        storageBar = v.findViewById(R.id.k2go_kbrowse_storage_bar);
        chips = v.findViewById(R.id.k2go_kbrowse_chips);
        list = v.findViewById(R.id.k2go_kbrowse_list);
        review = v.findViewById(R.id.k2go_kbrowse_review);

        TextView back = v.findViewById(R.id.k2go_kbrowse_back);
        back.setText(R.string.k2go_back);
        back.setOnClickListener(x -> back());

        // The whole box opens the picker — not an inner "Change" button.
        v.findViewById(R.id.k2go_kbrowse_lang_box).setOnClickListener(x -> pickLanguage());
        review.setOnClickListener(x -> openConfirm());

        readFreeSpace();

        vm = new ViewModelProvider(requireActivity(),
                new KolibriCatalogViewModelFactory(requireContext()))
                .get(KolibriCatalogViewModel.class);

        EditText search = v.findViewById(R.id.k2go_kbrowse_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String q = s == null ? "" : s.toString().trim();
                searching = !q.isEmpty();
                vm.search(q);
            }
        });

        vm.state().observe(getViewLifecycleOwner(), this::render);
        if (vm.state().getValue() == null || vm.state().getValue().isLoading()) {
            vm.load();
        }
        updateLangControl(null);
    }

    private void readFreeSpace() {
        try {
            StatFs st = new StatFs(requireContext().getFilesDir().getPath());
            freeMb = st.getAvailableBytes() / (1024L * 1024L);
            totalMb = st.getTotalBytes() / (1024L * 1024L);
        } catch (Exception e) {
            freeMb = 0L;
            totalMb = 0L;
        }
    }

    private void render(KolibriCatalogUiState s) {
        if (list == null || s == null) {
            return;
        }
        updateLangControl(s);
        buildChips(s);

        list.removeAllViews();
        if (s.isLoading()) {
            showStatus(R.string.k2go_zim_loading);
        } else if (s.hasError()) {
            showStatus(R.string.k2go_kolibri_unavailable);
        } else if (s.isEmptyResult()) {
            showStatus(R.string.k2go_kolibri_no_match);
        } else {
            status.setVisibility(View.GONE);
            List<Channel> rows = s.channels();
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) {
                    list.addView(hairline());
                }
                list.addView(channelRow(rows.get(i)));
            }
        }

        count.setText(getString(
                searching ? R.string.k2go_zc_count_results : R.string.k2go_zc_count_items,
                s.channels().size()));
        updateStorage();
    }

    private void showStatus(int stringRes) {
        status.setVisibility(View.VISIBLE);
        status.setText(stringRes);
    }

    /**
     * One flat selectable row: leading checkbox, name, right-aligned size. No card —
     * the air comes from alignment and the hairline, and the only rounded fill on
     * the screen is a selected row's highlight.
     */
    private View channelRow(final Channel c) {
        final boolean fits = freeMb <= 0L || mb(c.publishedSize()) <= freeMb;
        final boolean selected = vm.isPicked(c.id()) && fits;

        final LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(px(12), px(10), px(12), px(10));
        if (selected) {
            content.setBackground(selectedHighlight());
        }

        LinearLayout top = new LinearLayout(requireContext());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setMinimumHeight(px(36));

        final CheckBox cb = new CheckBox(requireContext());
        cb.setChecked(selected);
        cb.setEnabled(fits);
        cb.setClickable(false);
        cb.setFocusable(false);
        top.addView(cb);

        final TextView name = new TextView(requireContext());
        name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        name.setText(c.name());
        name.setMaxLines(2);
        name.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        if (selected) {
            name.setTypeface(name.getTypeface(), Typeface.BOLD);
        }
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = px(8);
        top.addView(name, nlp);

        TextView size = new TextView(requireContext());
        size.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        size.setText(c.hasKnownSize() ? ByteFormatter.toHuman(c.publishedSize())
                : getString(R.string.k2go_kolibri_size_unknown));
        size.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        size.setGravity(Gravity.END);
        top.addView(size);
        content.addView(top);

        if (!fits) {
            TextView warn = new TextView(requireContext());
            warn.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            warn.setText(getString(R.string.k2go_zc_nospace,
                    ByteFormatter.toHuman(c.publishedSize()), ByteFormatter.humanMb(freeMb)));
            warn.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_amber_text));
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            wlp.topMargin = px(2);
            wlp.leftMargin = px(36);
            content.addView(warn, wlp);
        } else {
            content.setOnClickListener(x -> {
                boolean now = vm.toggle(c);
                cb.setChecked(now);
                content.setBackground(now ? selectedHighlight() : null);
                name.setTypeface(null, now ? Typeface.BOLD : Typeface.NORMAL);
                updateStorage();
            });
        }
        return content;
    }

    /**
     * The group axis. Per the template the chip row carries <em>theme groups</em> —
     * an artificial five-or-so grouping we author ourselves so a long catalog can be
     * crossed in big jumps, exactly as {@code KiwixGroups} does for ZIM. It is not
     * taken from the catalog: Studio publishes {@code categories: []}, and the
     * grouping was never a projection of the source taxonomy anyway.
     *
     * <p>Those groups are not defined yet, so for now the row holds only
     * {@code All}. The language is deliberately <em>not</em> here — it filters from
     * the selector above, and putting it in both places was the earlier mistake.
     * When the groups land, add them beside {@code All} and filter on
     * {@link #groupFilter}; the rest of the row needs no change.
     */
    private void buildChips(KolibriCatalogUiState s) {
        chips.removeAllViews();
        if (s.isLoading()) {
            return;
        }
        chips.addView(chip(getString(R.string.k2go_zim_grp_all), ""));
    }

    private View chip(String label, final String group) {
        boolean selected = group.equals(groupFilter);
        int teal = ContextCompat.getColor(requireContext(), R.color.k2go_teal);

        TextView t = new TextView(requireContext());
        t.setText(label);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(px(48));   // tap target, even though the pill looks smaller
        t.setPadding(px(14), px(6), px(14), px(6));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(px(24));
        if (selected) {
            bg.setColor(teal);
            t.setTextColor(Color.WHITE);
        } else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(px(1), ContextCompat.getColor(requireContext(), R.color.k2go_hairline));
            t.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        }
        t.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(x -> {
            if (!group.equals(groupFilter)) {
                groupFilter = group;
                render(vm.state().getValue());
            }
        });
        return t;
    }

    private void updateStorage() {
        long sel = mb(vm == null ? 0L : vm.selectionBytes());
        long used = Math.max(0L, totalMb - freeMb);
        int pct = totalMb > 0
                ? (int) Math.min(100, Math.round((used + sel) * 100.0 / totalMb))
                : 0;
        storageBar.setProgress(pct);
        storageLabel.setText(getString(R.string.k2go_zim_storage_fmt,
                ByteFormatter.humanMb(used), ByteFormatter.humanMb(sel),
                ByteFormatter.humanMb(freeMb)));

        // The bar carries the SIZE, per the template, not the count: it is the
        // number the next screen has to justify.
        long bytes = vm == null ? 0L : vm.selectionBytes();
        review.setEnabled(vm != null && vm.selectionCount() > 0);
        review.setText(getString(R.string.k2go_zim_review_fmt, ByteFormatter.toHuman(bytes)));
    }

    /** Two lines: the value in bold, then where the choice came from, Title-Case. */
    private void updateLangControl(KolibriCatalogUiState s) {
        String value = langFilter.isEmpty()
                ? getString(R.string.k2go_kolibri_lang_all)
                : (s == null ? langFilter : s.languageName(langFilter));
        langLabel.setText(getString(R.string.k2go_zim_lang_fmt, value));
        langSub.setText(langFilter.isEmpty()
                ? R.string.k2go_zim_lang_sub
                : R.string.k2go_zim_lang_sub_manual);
    }

    /**
     * The searchable language picker, reusing the ZIM flow's dialog. Its name says
     * Zim but nothing inside it does; a second copy for one more caller would be
     * worse than the misleading name.
     */
    private void pickLanguage() {
        KolibriCatalogUiState s = vm.state().getValue();
        if (s == null || s.languages().isEmpty()) {
            return;
        }
        ZimLanguageDialog.show(requireContext(),
                getString(R.string.k2go_kolibri_lang_pick),
                s.languages(), s::languageName, langFilter,
                code -> {
                    langFilter = code == null ? "" : code;
                    vm.filterLanguage(langFilter);
                },
                getString(R.string.k2go_kolibri_lang_all),
                () -> {
                    langFilter = "";
                    vm.filterLanguage("");
                });
    }

    private GradientDrawable selectedHighlight() {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(10));
        g.setColor(ColorUtils.setAlphaComponent(
                ContextCompat.getColor(requireContext(), R.color.k2go_teal), 0x33));
        return g;
    }

    private View hairline() {
        View v = new View(requireContext());
        v.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.k2go_hairline));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(1)));
        return v;
    }

    private void openConfirm() {
        if (getActivity() instanceof SetupLibraryActivity) {
            ((SetupLibraryActivity) getActivity()).openKolibriConfirm();
        }
    }

    private void back() {
        if (getActivity() != null) {
            getActivity().getSupportFragmentManager().popBackStack();
        }
    }

    private static long mb(long bytes) {
        return bytes / (1024L * 1024L);
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
