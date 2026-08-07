/*
 * ============================================================================
 * Name        : KolibriBrowseFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4954. The Courses picker: a searchable, language-filtered
 *               list of Kolibri channels read from the bundled catalog. Replaces
 *               the PlaceholderFragment the wizard used to show.
 * ============================================================================
 */
package org.iiab.controller.kolibri.presentation;

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
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import org.iiab.controller.R;
import org.iiab.controller.kolibri.domain.Channel;
import org.iiab.controller.redesign.SetupLibraryActivity;
import org.iiab.controller.redesign.ZimLanguageDialog;
import org.iiab.controller.util.ByteFormatter;

/**
 * Browse and pick whole Kolibri channels.
 *
 * <p>A flat searchable list rather than the category grid the ZIM flow uses, for
 * a measured reason: Studio's public library leaves the `le_utils` subject
 * taxonomy empty, so there is nothing to group by. Language is the axis that
 * actually selects anything, and it is derived from the catalog — only 21 of the
 * 120-odd languages Studio lists as filterable are used by a real channel.
 *
 * <p>Reads nothing itself: {@link KolibriCatalogViewModel} owns the catalog and
 * the selection, and this observes it. The ViewModel is scoped to the activity so
 * the selection survives the trip to the confirm screen and back.
 *
 * <p>Sizes come from the catalog and free space from {@code StatFs}; no server is
 * involved, which is what lets this run in the wizard before anything exists.
 */
public final class KolibriBrowseFragment extends Fragment {

    private KolibriCatalogViewModel vm;

    private EditText search;
    private TextView langLabel;
    private TextView status;
    private TextView storageLabel;
    private ProgressBar storageBar;
    private LinearLayout list;
    private Button review;

    private long freeMb = 0L;
    private long totalMb = 0L;
    private String langFilter = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_k2go_kolibri_browse, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        search = v.findViewById(R.id.k2go_kbrowse_search);
        langLabel = v.findViewById(R.id.k2go_kbrowse_lang);
        status = v.findViewById(R.id.k2go_kbrowse_status);
        storageLabel = v.findViewById(R.id.k2go_kbrowse_storage_label);
        storageBar = v.findViewById(R.id.k2go_kbrowse_storage_bar);
        list = v.findViewById(R.id.k2go_kbrowse_list);
        review = v.findViewById(R.id.k2go_kbrowse_review);

        v.findViewById(R.id.k2go_kbrowse_back).setOnClickListener(x -> back());
        v.findViewById(R.id.k2go_kbrowse_change).setOnClickListener(x -> pickLanguage());
        review.setOnClickListener(x -> openConfirm());

        readFreeSpace();

        vm = new ViewModelProvider(requireActivity(),
                new KolibriCatalogViewModelFactory(requireContext()))
                .get(KolibriCatalogViewModel.class);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                vm.search(s == null ? "" : s.toString());
            }
        });

        vm.state().observe(getViewLifecycleOwner(), this::render);
        if (vm.state().getValue() == null || vm.state().getValue().isLoading()) {
            vm.load();
        }
        updateLangLabel();
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
        if (s.isLoading()) {
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.k2go_zim_loading);
            list.removeAllViews();
            updateStorage();
            return;
        }
        if (s.hasError()) {
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.k2go_kolibri_unavailable);
            list.removeAllViews();
            updateStorage();
            return;
        }
        if (s.isEmptyResult()) {
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.k2go_kolibri_no_match);
            list.removeAllViews();
            updateStorage();
            return;
        }

        status.setVisibility(View.GONE);
        list.removeAllViews();
        for (Channel c : s.channels()) {
            list.addView(channelRow(c));
        }
        updateStorage();
    }

    /**
     * One selectable channel. Rows are built by hand rather than through a
     * RecyclerView adapter to match the other content screens; the list is 142
     * rows at most, all of them cheap.
     */
    private View channelRow(final Channel c) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        row.setPadding(px(12), px(12), px(12), px(12));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = px(8);
        row.setLayoutParams(rlp);

        LinearLayout col = new LinearLayout(requireContext());
        col.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(requireContext());
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        title.setText(c.name());
        title.setMaxLines(2);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        col.addView(title);

        TextView sub = new TextView(requireContext());
        sub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        sub.setText(subtitle(c));
        sub.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        col.addView(sub);

        row.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final CheckBox box = new CheckBox(requireContext());
        box.setChecked(vm.isPicked(c.id()));

        // A channel bigger than the free space cannot be queued: it would fail
        // mid-download after occupying the device for however long it took to get
        // there. Refusing here is the honest moment.
        final boolean fits = freeMb <= 0L || mb(c.publishedSize()) <= freeMb;
        if (!fits) {
            box.setEnabled(false);
            sub.setText(getString(R.string.k2go_zc_nospace,
                    ByteFormatter.toHuman(c.publishedSize()),
                    ByteFormatter.humanMb(freeMb)));
            sub.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_amber_text));
        } else {
            box.setOnClickListener(x -> {
                vm.toggle(c);
                box.setChecked(vm.isPicked(c.id()));
                updateStorage();
            });
            row.setOnClickListener(x -> {
                box.setChecked(vm.toggle(c));
                updateStorage();
            });
        }
        row.addView(box);
        return row;
    }

    /** "Español · 1.2 GB · 340 resources", omitting whatever is unknown. */
    private String subtitle(Channel c) {
        StringBuilder sb = new StringBuilder();
        if (!c.langName().isEmpty()) {
            sb.append(c.langName());
        } else if (!c.langCode().isEmpty()) {
            sb.append(c.langCode());
        }
        if (c.hasKnownSize()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(ByteFormatter.toHuman(c.publishedSize()));
        }
        if (c.totalResources() > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(getString(R.string.k2go_kolibri_resources_fmt, c.totalResources()));
        }
        return sb.toString();
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

        int n = vm == null ? 0 : vm.selectionCount();
        review.setEnabled(n > 0);
        // Nested format so the count reads "3 items" in every language rather than a
        // bare number, reusing the ZIM strings whose text says nothing about ZIM.
        review.setText(getString(R.string.k2go_zim_review_fmt,
                getString(R.string.k2go_zim_items_fmt, n)));
    }

    private void updateLangLabel() {
        KolibriCatalogUiState s = vm == null ? null : vm.state().getValue();
        langLabel.setText(langFilter.isEmpty() || s == null
                ? getString(R.string.k2go_kolibri_lang_all)
                : s.languageName(langFilter));
    }

    /**
     * The language picker, reusing the ZIM flow's searchable dialog. Its name says
     * Zim but nothing in it does; duplicating it for one more caller would be
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
                    updateLangLabel();
                    vm.filterLanguage(langFilter);
                },
                getString(R.string.k2go_kolibri_lang_all),
                () -> {
                    langFilter = "";
                    updateLangLabel();
                    vm.filterLanguage("");
                });
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
