/*
 * ============================================================================
 * Name        : KolibriTopicsFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4954. Pick part of a channel: one level of its topic tree at
 *               a time, following controller/docs/CATALOG_BROWSE_TEMPLATE.md.
 * ============================================================================
 */
package org.iiab.controller.kolibri.presentation;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.StatFs;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
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

import org.iiab.controller.R;
import org.iiab.controller.kolibri.domain.Channel;
import org.iiab.controller.kolibri.domain.PickedSubtrees;
import org.iiab.controller.kolibri.domain.TopicNode;
import org.iiab.controller.util.ByteFormatter;

import java.util.ArrayList;
import java.util.List;

/**
 * Choose part of a channel instead of all of it.
 *
 * <p><b>Why this screen exists.</b> A Kolibri channel can be tens of gigabytes, and
 * a school usually wants two units of it. Kolibri's importer takes {@code node_ids}
 * and expands each one to its whole subtree, so what the user picks here are
 * <em>subtree roots</em>, not individual files.
 *
 * <p><b>What it does not have: sort toggles.</b> Studio reports children in the
 * order the channel's author arranged — unit 1, then unit 2. That order is content,
 * not presentation, and re-sorting it by size or alphabetically makes a curriculum
 * unreadable. The flat channel catalog has no authored order to protect, which is
 * why that screen does have them. Everything else follows the template: flat rows
 * with a leading checkbox and a right-aligned size, one hairline between them, the
 * selected row's teal highlight as the only rounded fill, the count on its own
 * line, storage and the forward action pinned at the bottom.
 *
 * <p>Two view models, two jobs: {@link KolibriTopicTreeViewModel} owns where in the
 * tree we are, {@link KolibriCatalogViewModel} owns what was chosen — it already
 * did for whole channels, and the choice has to survive this screen.
 */
public final class KolibriTopicsFragment extends Fragment {

    private static final String ARG_CHANNEL_ID = "channel_id";

    private KolibriCatalogViewModel catalogVm;
    private KolibriTopicTreeViewModel treeVm;

    private Channel channel;
    /** Working copy; written back to the catalog view model as it changes. */
    private PickedSubtrees picks = PickedSubtrees.empty();

    private TextView back;
    private TextView title;
    private TextView trail;
    private TextView count;
    private TextView status;
    private LinearLayout list;
    private TextView storageLabel;
    private ProgressBar storageBar;
    private Button done;

    private long freeMb = 0L;
    private long totalMb = 0L;

    /** @param channelId the channel to browse; the fragment resolves it from the catalog */
    public static KolibriTopicsFragment forChannel(String channelId) {
        KolibriTopicsFragment f = new KolibriTopicsFragment();
        Bundle b = new Bundle();
        b.putString(ARG_CHANNEL_ID, channelId);
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_k2go_kolibri_topics, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        back = v.findViewById(R.id.k2go_ktopics_back);
        title = v.findViewById(R.id.k2go_ktopics_title);
        trail = v.findViewById(R.id.k2go_ktopics_trail);
        count = v.findViewById(R.id.k2go_ktopics_count);
        status = v.findViewById(R.id.k2go_ktopics_status);
        list = v.findViewById(R.id.k2go_ktopics_list);
        storageLabel = v.findViewById(R.id.k2go_ktopics_storage_label);
        storageBar = v.findViewById(R.id.k2go_ktopics_storage_bar);
        done = v.findViewById(R.id.k2go_ktopics_done);

        readFreeSpace();

        ViewModelProvider provider = new ViewModelProvider(requireActivity(),
                new KolibriCatalogViewModelFactory(requireContext()));
        catalogVm = provider.get(KolibriCatalogViewModel.class);
        treeVm = provider.get(KolibriTopicTreeViewModel.class);

        channel = resolveChannel();
        if (channel == null) {
            // Nothing to browse — the catalog was not loaded, or the id is stale.
            // Leaving is the only honest response; an empty tree screen would lie.
            // Posted rather than immediate: popping the stack from inside
            // onViewCreated runs during the transaction that put us here.
            v.post(this::leave);
            return;
        }
        picks = catalogVm.subtreesFor(channel.id());

        back.setText(R.string.k2go_back);
        back.setOnClickListener(x -> goUpOrLeave());
        done.setOnClickListener(x -> commitAndLeave());

        treeVm.state().observe(getViewLifecycleOwner(), this::render);
        treeVm.open(channel);
        updateStorage();
    }

    /** The channel this screen was opened for, from the catalog already in memory. */
    private Channel resolveChannel() {
        String id = getArguments() == null ? null : getArguments().getString(ARG_CHANNEL_ID);
        if (id == null || id.isEmpty()) {
            return null;
        }
        KolibriCatalogUiState s = catalogVm.state().getValue();
        if (s != null) {
            for (Channel c : s.channels()) {
                if (id.equals(c.id())) {
                    return c;
                }
            }
        }
        // Filtered out of the current result but still chosen: look in the selection.
        for (Channel c : catalogVm.selection()) {
            if (id.equals(c.id())) {
                return c;
            }
        }
        return null;
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

    private void render(KolibriTopicsUiState s) {
        if (list == null || s == null) {
            return;
        }
        title.setText(s.title());

        List<String> crumbs = s.trail();
        if (crumbs.size() > 1) {
            trail.setVisibility(View.VISIBLE);
            trail.setText(joinTrail(crumbs));
        } else {
            trail.setVisibility(View.GONE);
        }

        list.removeAllViews();
        if (s.isLoading()) {
            showStatus(getString(R.string.k2go_zim_loading), false);
        } else if (s.isUnavailable()) {
            // The one state the user can act on, so this line is the retry.
            showStatus(getString(R.string.k2go_kolibri_tree_offline), true);
        } else if (s.isEmpty()) {
            showStatus(getString(R.string.k2go_kolibri_topic_empty), false);
        } else {
            status.setVisibility(View.GONE);
            List<TopicNode> rows = s.children();
            List<String> childAncestors = ancestorsOfChildren(s);
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) {
                    list.addView(hairline());
                }
                list.addView(nodeRow(rows.get(i), childAncestors));
            }
        }

        count.setText(getString(R.string.k2go_zc_count_items, s.children().size()));
        updateStorage();
    }

    /** A child's ancestry: everything above this level, plus the level itself. */
    private List<String> ancestorsOfChildren(KolibriTopicsUiState s) {
        List<String> out = new ArrayList<>(s.ancestorIds());
        if (s.node() != null) {
            out.add(s.node().id());
        }
        return out;
    }

    private String joinTrail(List<String> crumbs) {
        StringBuilder sb = new StringBuilder();
        for (String c : crumbs) {
            if (sb.length() > 0) {
                sb.append(" › ");
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private void showStatus(String text, boolean retry) {
        status.setVisibility(View.VISIBLE);
        status.setText(text);
        if (retry) {
            status.setOnClickListener(x -> treeVm.retry());
        } else {
            status.setOnClickListener(null);
            status.setClickable(false);
        }
    }

    /**
     * One flat row: checkbox, title, right-aligned size, and a chevron on a topic.
     *
     * <p>Whole-row tap toggles the pick; the chevron drills in — the same split the
     * channel list uses, so one gesture never means two things.
     */
    private View nodeRow(final TopicNode node, final List<String> ancestors) {
        // Covered but not picked: an ancestor is already bringing it. Showing it as
        // ticked is honest; letting it be un-ticked is not, because there is nothing
        // of its own to remove.
        final boolean picked = picks.contains(node.id());
        final boolean covered = !picked && picks.covers(node.id(), ancestors);

        final LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(px(12), px(10), px(12), px(10));
        if (picked) {
            content.setBackground(selectedHighlight());
        }

        LinearLayout top = new LinearLayout(requireContext());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setMinimumHeight(px(36));

        final CheckBox cb = new CheckBox(requireContext());
        cb.setChecked(picked || covered);
        cb.setEnabled(!covered);
        cb.setClickable(false);
        cb.setFocusable(false);
        top.addView(cb);

        final TextView name = new TextView(requireContext());
        name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        name.setText(node.title());
        name.setMaxLines(2);
        name.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        if (picked) {
            name.setTypeface(name.getTypeface(), Typeface.BOLD);
        }
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = px(8);
        top.addView(name, nlp);

        TextView size = new TextView(requireContext());
        size.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        size.setText(sizeLabel(node));
        size.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        size.setGravity(Gravity.END);
        top.addView(size);

        if (node.isTopic()) {
            ImageView chevron = new ImageView(requireContext());
            chevron.setImageResource(R.drawable.ic_chevron_right);
            chevron.setColorFilter(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
            chevron.setContentDescription(getString(R.string.k2go_kolibri_open_topic));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(px(40), px(40));
            clp.leftMargin = px(4);
            chevron.setPadding(px(8), px(8), px(8), px(8));
            chevron.setOnClickListener(x -> treeVm.enter(node));
            top.addView(chevron, clp);
        }
        content.addView(top);

        if (covered) {
            content.addView(note(getString(R.string.k2go_kolibri_included),
                    R.color.k2go_muted));
        } else if (!node.hasSubtreeSize()) {
            // Say it once, on the row, rather than letting a blank size imply zero.
            content.addView(note(getString(R.string.k2go_kolibri_size_unknown),
                    R.color.k2go_muted));
        }

        if (!covered) {
            content.setOnClickListener(x -> {
                picks = picks.contains(node.id())
                        ? picks.remove(node.id())
                        : picks.add(node.id(), ancestors,
                                node.subtreeBytes(), node.hasSubtreeSize());
                catalogVm.setSubtrees(channel, picks);
                // A pick can absorb or release sibling rows, so the level is redrawn
                // rather than patched: one source of truth for what a row shows.
                render(treeVm.state().getValue());
            });
        }
        return content;
    }

    /**
     * What the size column says. A topic Studio sized fully gets bytes; one it did
     * not gets its descendant count, which the nested-set bounds always provide —
     * some measure of how big a folder is beats none.
     */
    private String sizeLabel(TopicNode node) {
        if (node.hasSubtreeSize()) {
            return ByteFormatter.toHuman(node.subtreeBytes());
        }
        if (node.descendantCount() > 0) {
            return getString(R.string.k2go_kolibri_resources_fmt, node.descendantCount());
        }
        return "";
    }

    private View note(String text, int colorRes) {
        TextView t = new TextView(requireContext());
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        t.setText(text);
        t.setTextColor(ContextCompat.getColor(requireContext(), colorRes));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(2);
        lp.leftMargin = px(36);
        t.setLayoutParams(lp);
        return t;
    }

    private void updateStorage() {
        long sel = mb(catalogVm == null ? 0L : catalogVm.selectionBytes());
        long used = Math.max(0L, totalMb - freeMb);
        int pct = totalMb > 0
                ? (int) Math.min(100, Math.round((used + sel) * 100.0 / totalMb))
                : 0;
        storageBar.setProgress(pct);
        storageLabel.setText(getString(R.string.k2go_zim_storage_fmt,
                ByteFormatter.humanMb(used), ByteFormatter.humanMb(sel),
                ByteFormatter.humanMb(freeMb)));

        // The action carries the size of THIS channel's narrowing, not the whole
        // cart: it is the number this screen is responsible for.
        long bytes = picks.isEmpty() ? channelBytes() : picks.totalBytes();
        done.setText(getString(R.string.k2go_zim_add_setup_fmt, ByteFormatter.toHuman(bytes)));
    }

    private long channelBytes() {
        return channel == null ? 0L : channel.publishedSize();
    }

    /**
     * Confirms the narrowing. Leaving with nothing picked means "the whole channel",
     * and the button says as much by quoting the full size — so it selects the
     * channel rather than quietly queueing nothing.
     */
    private void commitAndLeave() {
        catalogVm.setSubtrees(channel, picks);
        if (picks.isEmpty()) {
            catalogVm.select(channel);
        }
        leave();
    }

    /** Back walks up the tree first, and only leaves the screen from the root. */
    private void goUpOrLeave() {
        if (!treeVm.up()) {
            leave();
        }
    }

    private void leave() {
        if (getActivity() != null) {
            getActivity().getSupportFragmentManager().popBackStack();
        }
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

    private static long mb(long bytes) {
        return bytes / (1024L * 1024L);
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
