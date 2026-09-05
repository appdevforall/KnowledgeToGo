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
package org.appdevforall.k2go.kolibri.presentation;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.applang.data.ContentLanguage;
import org.appdevforall.k2go.kolibri.domain.Channel;
import org.appdevforall.k2go.redesign.K2GoFilterChip;
import org.appdevforall.k2go.redesign.SetupLibraryActivity;
import org.appdevforall.k2go.redesign.ZimLanguageDialog;
import org.appdevforall.k2go.util.ByteFormatter;

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

    /** Studio's code for a channel that is not in one language. Its own filter value. */
    private static final String MULTI = "mul";

    private KolibriCatalogViewModel vm;

    private TextView langLabel;
    private TextView langSub;
    private TextView status;
    private TextView count;
    private TextView updated;
    private TextView storageLabel;
    private ProgressBar storageBar;
    private LinearLayout chips;
    private com.google.android.material.chip.Chip sortSize;
    private com.google.android.material.chip.Chip sortName;
    private LinearLayout list;
    private Button review;

    private long freeMb = 0L;
    private long totalMb = 0L;
    /** The chip row's axis: {@code ""} is All. Only All exists until the groups are authored. */
    private String groupFilter = "";

    /** ADFA-5061: whether this screen banks rather than downloads, resolved on first use
     *  and dropped with the view — see {@link #isWizard()}. Null means "not asked yet". */
    private Boolean banksCache = null;

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
        updated = v.findViewById(R.id.k2go_kbrowse_updated);
        storageLabel = v.findViewById(R.id.k2go_kbrowse_storage_label);
        storageBar = v.findViewById(R.id.k2go_kbrowse_storage_bar);
        chips = v.findViewById(R.id.k2go_kbrowse_chips);
        sortSize = v.findViewById(R.id.k2go_kbrowse_sort_size);
        sortName = v.findViewById(R.id.k2go_kbrowse_sort_name);
        list = v.findViewById(R.id.k2go_kbrowse_list);
        review = v.findViewById(R.id.k2go_kbrowse_review);

        // Same screen, two doors: the wizard step goes Back, the Get More door names
        // where it returns to, exactly as the ZIM and Books landings do.
        TextView back = v.findViewById(R.id.k2go_kbrowse_back);
        back.setText(isWizard()
                ? getString(R.string.k2go_back)
                : "‹ " + getString(R.string.k2go_gm_hub_title));
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
                vm.search(s == null ? "" : s.toString().trim());
            }
        });

        // Tapping the active toggle reverses it; tapping the other switches axis and
        // starts at its natural direction (largest first / A–Z).
        sortSize.setOnClickListener(x -> vm.setSort(vm.sort().isSize()
                ? vm.sort().flipped() : KolibriCatalogViewModel.Sort.SIZE_DESC));
        sortName.setOnClickListener(x -> vm.setSort(vm.sort().isName()
                ? vm.sort().flipped() : KolibriCatalogViewModel.Sort.NAME_ASC));

        vm.state().observe(getViewLifecycleOwner(), this::render);

        // The catalog opens in the language the wizard already settled on — the same
        // preference the install path reads — not on "all languages". Coming back from
        // confirm keeps whatever is loaded, unless the language changed while away.
        KolibriCatalogUiState now = vm.state().getValue();
        if (now == null || now.isLoading()
                || !vm.query().langCodes().contains(lang())) {
            vm.filterLanguage(lang());
        }
        updateLangControl();
        updateSortControls();
    }

    /** The wizard's content language, shared with the ZIM catalog. Never null. */
    private String lang() {
        if (getActivity() instanceof SetupLibraryActivity) {
            return ((SetupLibraryActivity) getActivity()).getContentLang();
        }
        return ContentLanguage.systemDefault();
    }

    /**
     * A language code as a person reads it, matching the ZIM screens: the endonym,
     * capitalized. Studio's own {@code lang_name} is not used — it disagrees with
     * itself across region variants ("Português", "Português (Brasil)") that collapse
     * to one code, and {@code mul} has a name of its own in the app.
     */
    private String langDisplay(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        if (MULTI.equals(code)) {
            return getString(R.string.k2go_zim_lang_mul);
        }
        try {
            java.util.Locale l = new java.util.Locale(code);
            String n = l.getDisplayName(l);
            if (n == null || n.isEmpty()) {
                return code;
            }
            return n.substring(0, 1).toUpperCase(l) + n.substring(1);
        } catch (Exception e) {
            return code;
        }
    }

    private void readFreeSpace() {
        try {
            Long fb = org.appdevforall.k2go.storage.StorageProbe.freeBytes(requireContext());   // ADFA-5105: shared probe
            Long tb = org.appdevforall.k2go.storage.StorageProbe.totalBytes(requireContext());
            freeMb = (fb == null ? 0L : fb) / (1024L * 1024L);
            totalMb = (tb == null ? 0L : tb) / (1024L * 1024L);
        } catch (Exception e) {
            freeMb = 0L;
            totalMb = 0L;
        }
    }

    private void render(KolibriCatalogUiState s) {
        if (list == null || s == null) {
            return;
        }
        updateLangControl();
        updateSortControls();
        buildChips(s);

        list.removeAllViews();
        if (s.isLoading()) {
            showStatus(R.string.k2go_zim_loading);
        } else if (s.hasError()) {
            showStatus(R.string.k2go_kolibri_unavailable);
        } else if (s.isEmptyResult() && vm.query().hasKeyword()) {
            showStatus(R.string.k2go_kolibri_no_match);
        } else if (s.isEmptyResult()) {
            // Nothing in this language at all: 21 of Studio's languages have a public
            // channel, so a perfectly ordinary device language can land here. Say which
            // language emptied the list, and make the line change it.
            status.setVisibility(View.VISIBLE);
            status.setText(getString(R.string.k2go_kolibri_none_in_lang, langDisplay(lang())));
            status.setOnClickListener(x -> pickLanguage());
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

        // "N items" normally, live "N results" while searching. Read from the query, not
        // from a local flag, so a recreated view can't disagree with what is displayed.
        count.setText(getString(vm.query().hasKeyword()
                        ? R.string.k2go_zc_count_results : R.string.k2go_zc_count_items,
                s.channels().size()));

        // ADFA-5094: catalog freshness — the pulled overlay's date once refreshed, else the bundled one.
        String on = s.generatedOn();
        if (updated != null) {
            if (on == null || on.isEmpty()) {
                updated.setVisibility(View.GONE);
            } else {
                updated.setVisibility(View.VISIBLE);
                updated.setText(getString(R.string.k2go_kolibri_catalog_updated, on));
            }
        }
        updateStorage();
    }

    private void showStatus(int stringRes) {
        status.setVisibility(View.VISIBLE);
        status.setText(stringRes);
        status.setOnClickListener(null);
        status.setClickable(false);
    }

    /** Labels + selected state of the two sort toggles, arrows included. */
    private void updateSortControls() {
        KolibriCatalogViewModel.Sort s = vm.sort();
        sortSize.setText(getString(R.string.k2go_zc_sort_size)
                + (s == KolibriCatalogViewModel.Sort.SIZE_ASC ? " ▲" : " ▼"));
        boolean desc = s == KolibriCatalogViewModel.Sort.NAME_DESC;
        sortName.setText(getString(desc
                ? R.string.k2go_zc_sort_name_desc : R.string.k2go_zc_sort_name)
                + (desc ? " ▲" : " ▼"));
        pill(sortSize, s.isSize());
        pill(sortName, s.isName());
    }

    // K2GO-385 (PR3): the shared filter chip (8dp, 32dp, check when active) -- the sort toggles now match
    // the ZIM sort chips and the category/Books filters; the label still carries the sort direction.
    private void pill(com.google.android.material.chip.Chip t, boolean on) {
        K2GoFilterChip.style(t, on);
    }

    /**
     * One flat selectable row: leading checkbox, name, right-aligned size. No card —
     * the air comes from alignment and the hairline, and the only rounded fill on
     * the screen is a selected row's highlight.
     */
    private View channelRow(final Channel c) {
        // The fit question is about what will be downloaded, so a narrowed channel is
        // measured by its picked subtrees — a 60 GB channel narrowed to 2 GB fits.
        final long rowBytes = vm.bytesFor(c);
        final boolean fits = freeMb <= 0L || mb(rowBytes) <= freeMb;
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

        // Once narrowed, the row must quote what will actually come down, not the
        // channel's full published size — otherwise picking two units of a 60 GB
        // channel still reads as 60 GB.
        final boolean narrowed = vm.isNarrowed(c.id());
        TextView size = new TextView(requireContext());
        size.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        size.setText(vm.sizeUnknownFor(c)
                ? getString(R.string.k2go_kolibri_size_unknown)
                : ByteFormatter.toHuman(vm.bytesFor(c)));
        size.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        size.setGravity(Gravity.END);
        top.addView(size);

        // The chevron opens the tree; the row itself still toggles the whole channel.
        // One gesture, one meaning — the split the category index already uses.
        ImageView chevron = new ImageView(requireContext());
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setColorFilter(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
        chevron.setContentDescription(getString(R.string.k2go_kolibri_choose_topics));
        chevron.setPadding(px(8), px(8), px(8), px(8));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(px(40), px(40));
        clp.leftMargin = px(4);
        chevron.setOnClickListener(x -> openTopics(c));
        top.addView(chevron, clp);
        content.addView(top);

        if (narrowed) {
            TextView note = new TextView(requireContext());
            note.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            note.setText(getString(R.string.k2go_kolibri_narrowed_fmt,
                    vm.subtreesFor(c.id()).size()));
            note.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = px(2);
            tlp.leftMargin = px(36);
            content.addView(note, tlp);
        }

        if (!fits) {
            TextView warn = new TextView(requireContext());
            warn.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            warn.setText(getString(R.string.k2go_zc_nospace,
                    ByteFormatter.toHuman(rowBytes), ByteFormatter.humanMb(freeMb)));
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
                if (!now) {
                    // Un-ticking also dropped the topic picks, so the row's size and
                    // its "topics selected" note are stale — redraw rather than patch.
                    render(vm.state().getValue());
                    return;
                }
                updateStorage();
            });
        }
        return content;
    }

    /** Pre-install (wizard) versus the Get More door. Only the wording and the
     *  forward action differ; the catalog and the tree behave the same either way.
     *
     *  <p>ADFA-5061: read from the system rather than from a flag set when the picker was
     *  opened. "Pre-install" is not a mode the app remembers, it is the state of having no
     *  box to download against. An unrecognised host declares no replacement and the facts
     *  answer alone.
     *
     *  <p>Resolved once per view creation: the answer costs three filesystem reads, and it
     *  cannot change while this screen is up. Being a field is safe here because it is
     *  derived on creation — the flags this replaced were written by navigation, which is
     *  why they did not survive one. */
    private boolean isWizard() {
        if (banksCache == null) {
            banksCache = org.appdevforall.k2go.system.data.ContentDoor.banks(
                    requireContext(), org.appdevforall.k2go.system.domain.ContentType.COURSES,
                    SetupLibraryActivity.replacingSystem(this));
        }
        return banksCache;
    }

    /**
     * Opens the tree for one channel. The chevron is enabled whatever the row's
     * state: narrowing is precisely how an over-large channel becomes installable,
     * so a row that does not fit is the one that most needs this door.
     */
    private void openTopics(Channel c) {
        if (getActivity() instanceof SetupLibraryActivity) {
            ((SetupLibraryActivity) getActivity()).openKolibriTopics(c.id());
        }
    }

    /**
     * The group axis. Per the template the chip row carries <em>theme groups</em> —
     * an artificial five-or-so grouping we author ourselves so a long catalog can be
     * crossed in big jumps, exactly as {@code KiwixGroups} does for ZIM. It is not
     * taken from the catalog: Studio publishes {@code categories: []}, and the
     * grouping was never a projection of the source taxonomy anyway.
     *
     * <p>Those groups are not defined yet, so the row would hold nothing but
     * {@code All} and is {@code gone} in the layout — an unused second line of pills
     * next to the sort toggles is weight without a job. This method still runs, so
     * flipping that one attribute is all it takes to bring the row back. The language
     * is deliberately <em>not</em> here: it filters from the selector above, and
     * putting it in both places was the earlier mistake.
     */
    private void buildChips(KolibriCatalogUiState s) {
        chips.removeAllViews();
        if (s.isLoading()) {
            return;
        }
        chips.addView(chip(getString(R.string.k2go_zim_grp_all), ""));
    }

    private View chip(String label, final String group) {
        // K2GO-385 (PR3): category chips use the shared filter chip too.
        com.google.android.material.chip.Chip c = K2GoFilterChip.create(requireContext(), label,
                group.equals(groupFilter), x -> {
                    if (!group.equals(groupFilter)) {
                        groupFilter = group;
                        render(vm.state().getValue());
                    }
                });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px(8);
        c.setLayoutParams(lp);
        return c;
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

    /**
     * Two lines: the language in bold, then where the choice came from, Title-Case.
     * The same two strings the ZIM screens use — no "filters the catalog" clause,
     * which was dropped in ADFA-5033 as redundant with the control itself.
     */
    private void updateLangControl() {
        langLabel.setText(getString(R.string.k2go_zim_lang_fmt, langDisplay(lang())));
        boolean manual = (getActivity() instanceof SetupLibraryActivity)
                && ((SetupLibraryActivity) getActivity()).isContentLangManual();
        langSub.setText(manual
                ? R.string.k2go_zim_lang_state_manual
                : R.string.k2go_zim_lang_state_system);
    }

    /**
     * The searchable language picker, reusing the ZIM flow's dialog. Its name says
     * Zim but nothing inside it does; a second copy for one more caller would be
     * worse than the misleading name.
     *
     * <p>Choosing a language changes it for the whole wizard, exactly as the ZIM
     * screens do, because there is one content language and not one per catalog.
     * The reset option follows the system again rather than showing every language:
     * "all" is not a language, and a mixed list is what the selector exists to avoid.
     */
    private void pickLanguage() {
        KolibriCatalogUiState s = vm.state().getValue();
        if (s == null || s.languages().isEmpty()) {
            return;
        }
        ZimLanguageDialog.show(requireContext(),
                getString(R.string.k2go_zim_change),
                s.languages(), this::langDisplay, lang(),
                code -> {
                    if (getActivity() instanceof SetupLibraryActivity) {
                        ((SetupLibraryActivity) getActivity()).setContentLang(code);
                    }
                    vm.filterLanguage(lang());
                },
                getString(R.string.k2go_lang_follow_system),
                () -> {
                    if (getActivity() instanceof SetupLibraryActivity) {
                        ((SetupLibraryActivity) getActivity()).followSystemLang();
                    }
                    vm.filterLanguage(lang());
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
