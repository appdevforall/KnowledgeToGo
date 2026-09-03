/*
 * ============================================================================
 * Name        : KolibriConfirmFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4954. Review the chosen channels, show the total, and decide
 *               whether it fits — in three states, not two.
 * ============================================================================
 */
package org.appdevforall.k2go.kolibri.presentation;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.kolibri.data.KolibriWishlist;
import org.appdevforall.k2go.kolibri.domain.Channel;
import org.appdevforall.k2go.kolibri.domain.ChannelSelection;
import org.appdevforall.k2go.kolibri.domain.InstalledChannel;
import org.appdevforall.k2go.kolibri.domain.SeedPlan;
import org.appdevforall.k2go.redesign.SetupLibraryActivity;
import org.appdevforall.k2go.redesign.SetupProgressActivity;
import org.appdevforall.k2go.util.AppExecutors;
import org.appdevforall.k2go.util.ByteFormatter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The consent step: what was chosen, how much it is, and whether it fits.
 *
 * <p><b>Three fit states, not two.</b> The ZIM equivalent disables its button
 * whenever the total exceeds free space, which is right when every size is exact.
 * Here it is not: a channel Studio publishes without a size contributes nothing
 * to the total, so the total can be a floor rather than a figure.
 * {@link SeedPlan#fitsIn} returns {@code null} for exactly that case, and
 * presenting "cannot tell" as "does not fit" would block downloads that would
 * have succeeded. So: fits, does not fit, and proceed-with-a-warning.
 *
 * <p>Banking only. Nothing downloads from here: the order goes to
 * {@link KolibriWishlist} and {@code KolibriProvisioner} drains it once the
 * system is installed and the server is up.
 */
public final class KolibriConfirmFragment extends Fragment {

    private KolibriCatalogViewModel vm;
    private KolibriReadinessViewModel readiness;

    /** The dispatcher's latest answer; never acted on until it exists. */
    private KolibriReadinessUiState ready = null;

    private LinearLayout breakdown;
    private TextView fits;
    private TextView liveNote;
    private Button confirm;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_k2go_kolibri_confirm, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        breakdown = v.findViewById(R.id.k2go_kconf_breakdown);
        fits = v.findViewById(R.id.k2go_kconf_fits);
        liveNote = v.findViewById(R.id.k2go_kconf_live_note);
        confirm = v.findViewById(R.id.k2go_kconf_confirm);
        v.findViewById(R.id.k2go_kconf_back).setOnClickListener(x -> back());

        ViewModelProvider provider = new ViewModelProvider(requireActivity(),
                new KolibriCatalogViewModelFactory(requireContext()));
        vm = provider.get(KolibriCatalogViewModel.class);
        readiness = provider.get(KolibriReadinessViewModel.class);

        // The screen renders from the dispatcher's answer, so it draws twice: once
        // while checking, with the action disabled, and once resolved. Guessing in
        // between is how a button ends up promising something the box cannot do.
        readiness.state().observe(getViewLifecycleOwner(), s -> {
            ready = s;
            render();
        });
        readiness.refresh(isWizard());
    }

    /**
     * Whether this screen was reached through the setup wizard, which is about to
     * install or replace the system.
     *
     * <p>Not something the device can be asked: during a reinstall the old system is
     * still installed, healthy and answering, so every readable fact says "run it
     * now" while the user is two taps from wiping it. Reading only those facts made
     * the picker download live onto the doomed system and leave the wizard, so the
     * reinstall never happened at all.
     *
     * <p>Fails closed: an unrecognised host cannot declare a replacement, and the
     * dispatcher then answers from the facts alone.
     *
     * <p><b>ADFA-5061.</b> This used to read {@code kolibriWizard}, a plain field set
     * when the picker was opened from the wizard — lost on every activity recreation,
     * which is how the reinstall hijack came back. {@code reinstallMode} is read from
     * the Intent on every {@code onCreate}, and Android keeps that Intent in the task
     * record, so it survives the recreations that lost the flag: a light/dark change
     * ({@code uiMode} is not in this activity's {@code configChanges}), "Don't keep
     * activities", and a process death with task restore. Rotation is not one of them
     * — {@code orientation} is declared, so the activity is never recreated for it.
     * A first install needs nothing here: with no system on disk the facts already
     * say defer.
     */
    private boolean isWizard() {
        return SetupLibraryActivity.replacingSystem(this);
    }

    private void render() {
        List<Channel> chosen = vm.selection();
        breakdown.removeAllViews();

        for (Channel c : chosen) {
            // A narrowed channel is listed by what it will actually bring, and says
            // so: "Whole course" versus "N topics" is the difference between a 60 GB
            // download and a 2 GB one, and the line must not hide which one it is.
            breakdown.addView(row(c.name(),
                    vm.sizeUnknownFor(c) ? getString(R.string.k2go_kolibri_size_unknown)
                            : ByteFormatter.toHuman(costOf(c)),
                    false));
            breakdown.addView(scopeNote(c));
            breakdown.addView(divider());
        }
        breakdown.addView(row(getString(R.string.k2go_zim_total),
                ByteFormatter.toHuman(totalCost(chosen)), true));

        applyFitState(chosen);
    }

    /**
     * Renders the fit decision. The plan is built through the domain so the rule
     * lives in one place: this screen only turns its answer into words.
     */
    private void applyFitState(List<Channel> chosen) {
        // The fit question is about what will be downloaded, so it is asked of the
        // plan that will actually be sent — without the channels already here.
        SeedPlan plan = planToSend(chosen);
        Long freeBytes = readFreeBytes();
        Boolean verdict = plan.fitsIn(freeBytes);
        final boolean fitAllows;

        if (Boolean.TRUE.equals(verdict)) {
            fits.setText(getString(R.string.k2go_zim_fits_fmt,
                    ByteFormatter.toHuman(plan.requiredBytes()),
                    freeBytes == null ? "" : ByteFormatter.toHuman(freeBytes)));
            paint(R.color.k2go_ok_ink, R.drawable.k2go_ok_bg);
            fitAllows = true;
        } else if (Boolean.FALSE.equals(verdict)) {
            fits.setText(getString(R.string.k2go_zim_nofit_fmt,
                    ByteFormatter.toHuman(plan.requiredBytes()),
                    freeBytes == null ? "" : ByteFormatter.toHuman(freeBytes)));
            paint(R.color.k2go_warn_ink, R.drawable.k2go_warn_bg);
            fitAllows = false;
        } else {
            // Cannot tell: a channel with no published size, or no readable free
            // space. Warn and let it through — refusing here would block a
            // download that has every chance of succeeding.
            fits.setText(R.string.k2go_kolibri_fit_unknown);
            paint(R.color.k2go_warn_ink, R.drawable.k2go_warn_bg);
            fitAllows = true;
        }

        // Per the template the forward action carries the SIZE, not the count.
        confirm.setText(getString(R.string.k2go_zim_add_setup_fmt,
                ByteFormatter.toHuman(plan.estimatedBytes())));

        applyDispatch(chosen, fitAllows && !chosen.isEmpty());
    }

    /**
     * Turns the dispatcher's answer into a button and a sentence.
     *
     * <p>This is where the model earns its keep. The question used to be "am I in
     * the wizard?", read from a boolean on the activity that a restore silently
     * reset. It is now "can this order run?", and there are four answers rather than
     * two — each of which the user can be told apart from the others, because
     * "not on offer" and "your box is off" stop the same button for very different
     * reasons.
     */
    private void applyDispatch(List<Channel> chosen, boolean allowedByFit) {
        if (ready == null || ready.isChecking()) {
            refuse(R.string.k2go_kolibri_checking);
            return;
        }

        switch (ready.dispatch()) {
            case DEFER:
                // The wizard: there is no box yet, so the order is written down and
                // KolibriProvisioner drains it once the system is up.
                liveNote.setVisibility(View.GONE);
                confirm.setEnabled(allowedByFit);
                confirm.setOnClickListener(x -> bankAndReturn(chosen));
                break;

            case RUN_LIVE:
                if (!chosen.isEmpty() && missingOnly(chosen).isEmpty()) {
                    // Everything picked is already here in full. Say so before the
                    // press rather than after it.
                    confirm.setEnabled(false);
                    confirm.setOnClickListener(null);
                    note(R.string.k2go_kolibri_nothing_to_add);
                    break;
                }
                liveNote.setVisibility(View.GONE);
                confirm.setEnabled(allowedByFit);
                confirm.setOnClickListener(x -> startLive(chosen));
                break;

            case ENSURE_SERVER_THEN_RUN_LIVE:
                // Everything is installed; the box is simply not answering. The
                // order is NOT banked here on purpose: nothing drains a wishlist
                // outside the post-install screen, so it would sit unnoticed. The
                // selection lives in the view model and survives the trip, so asking
                // the user to start the system loses nothing — and once it answers,
                // this screen resolves to RUN_LIVE by itself.
                confirm.setEnabled(false);
                note(R.string.k2go_kolibri_start_system_first);
                break;

            case UNAVAILABLE:
                // Courses is not on this system — a Basic tier, or the module never
                // installed. No future moment makes a queued order runnable, so it
                // is refused rather than banked.
                refuse(R.string.k2go_kolibri_not_installed);
                break;

            case BLOCKED_DAMAGED:
                refuse(R.string.k2go_kolibri_system_damaged);
                break;

            default:
                refuse(R.string.k2go_kolibri_checking);
                break;
        }
    }

    /** Shows the informational line without erasing the fit verdict above it. */
    private void note(int stringRes) {
        liveNote.setText(stringRes);
        liveNote.setVisibility(View.VISIBLE);
    }

    /**
     * Refuses the action and says why.
     *
     * <p>Clears the listener as well as disabling: leaving a stale one attached under
     * a disabled button is harmless only for as long as nobody enables the button
     * from somewhere else.
     */
    private void refuse(int stringRes) {
        confirm.setEnabled(false);
        confirm.setOnClickListener(null);
        note(stringRes);
    }

    /**
     * The scope line under a course: whole thing, how many topics, or that the
     * device already has it.
     *
     * <p>"Already here" wins over the scope, because it changes what the row means:
     * the size beside it is zero and pressing the button will not download it. The
     * user is entitled to know that before they press, not after.
     */
    private View scopeNote(Channel c) {
        TextView t = new TextView(requireContext());
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);

        InstalledChannel installed = ready == null ? null : ready.library().find(c.id());
        if (installed != null && installed.isComplete()) {
            t.setText(R.string.k2go_kolibri_already_here);
            t.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_leaf));
            return t;
        }
        if (installed != null && installed.isPartial()) {
            t.setText(R.string.k2go_kolibri_partly_here);
            t.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
            return t;
        }

        boolean narrowed = vm.isNarrowed(c.id());
        t.setText(narrowed
                ? getString(R.string.k2go_kolibri_narrowed_fmt, vm.subtreesFor(c.id()).size())
                : getString(R.string.k2go_kolibri_whole_course));
        t.setTextColor(ContextCompat.getColor(requireContext(),
                narrowed ? R.color.k2go_teal : R.color.k2go_muted));
        return t;
    }

    private SeedPlan planOf(List<Channel> chosen) {
        List<ChannelSelection> sels = new ArrayList<>();
        Map<String, Long> sizes = new HashMap<>();
        for (Channel c : chosen) {
            // Whole channel or picked subtrees — the view model answers, and the rule
            // that an empty narrowing means "everything" lives in PickedSubtrees, so
            // node_ids: [] (which Kolibri reads as zero nodes) can never be built.
            sels.add(vm.selectionFor(c));
            // Priced by what is missing, so the fit check answers the question the
            // user is actually asking: will the download fit, not would the whole
            // channel have fitted.
            sizes.put(c.id(), costOf(c));
        }
        return SeedPlan.of(sels, sizes);
    }

    /** The plan for what will actually be sent — the complete ones dropped. */
    private SeedPlan planToSend(List<Channel> chosen) {
        return planOf(missingOnly(chosen));
    }

    /** The whole order, priced against what the device already holds. */
    private long totalCost(List<Channel> chosen) {
        long total = 0L;
        for (Channel c : chosen) {
            total += costOf(c);
        }
        return total;
    }

    /** Free bytes on the data partition, or null when it cannot be read. */
    private Long readFreeBytes() {
        try {
            return org.appdevforall.k2go.storage.StorageProbe.freeBytes(requireContext());   // ADFA-5105: shared probe
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Writes the order and goes back to the hub. Nothing is downloaded: the wizard
     * has no server yet, so this is the "leave a food order" half of ADR-4853.
     */
    private void bankAndReturn(List<Channel> chosen) {
        // Same filter as the live door. In the wizard the library is unknown, so
        // nothing is dropped — but the two doors should not disagree about what an
        // order contains just because one of them happens to know more.
        bank(missingOnly(chosen));
        vm.clearSelection();
        if (getActivity() instanceof SetupLibraryActivity) {
            ((SetupLibraryActivity) getActivity()).kolibriWizardConfirm();
        }
    }

    /**
     * The live door: same order, written the same way, and then drained at once
     * instead of waiting for an install.
     *
     * <p>The wishlist is not bypassed. It is the record of what was asked for, and
     * keeping one path means the download that runs here is built from exactly the
     * same bytes as the one that runs after an install — including the {@code
     * node_ids} of a narrowed channel.
     *
     * <p>{@code drain} can legitimately refuse: a proot job or another content
     * stream may hold the line (ADR-4954 D8). The post-install screen ignores that
     * and calls again on the next pass, but a user who just pressed a button cannot
     * be left with silence, so the refusal is shown.
     */
    private void startLive(List<Channel> chosen) {
        // ADFA-5333: a live dashboard update restarts dash-node and would break the seed — defer.
        if (org.appdevforall.k2go.redesign.DashboardRebuild.blockedByUpdate(confirm)) return;
        final List<Channel> toDownload = missingOnly(chosen);
        if (toDownload.isEmpty()) {
            refuse(R.string.k2go_kolibri_nothing_to_add);
            return;
        }

        // The order is read off the view model here, on the main thread, and written
        // on the IO pool: SharedPreferences plus a foreground service start is small
        // but it is still disk at the moment of a tap.
        final List<Order> order = new ArrayList<>();
        for (Channel c : toDownload) {
            List<String> nodeIds = vm.subtreesFor(c.id()).nodeIds();
            order.add(new Order(c.id(), c.version(), c.name(), costOf(c),
                    nodeIds.isEmpty() ? null : nodeIds));
        }
        final Context app = requireContext().getApplicationContext();
        confirm.setEnabled(false);
        note(R.string.k2go_kolibri_checking);

        AppExecutors.get().io().execute(() -> {
            for (Order o : order) {
                KolibriWishlist.add(app, o.id, o.version, o.name, o.bytes, o.nodeIds);
            }
            // ADFA-5074: the order is written, then started if the line is free. It used to be
            // rolled back when another stream held the line, and the screen said "Another
            // download is running. Try again when it finishes." — an honest message for a door
            // with nowhere to show a queue, and a bad one now that there is somewhere. Nothing
            // here decides whether it runs; the wishlist IS the queue, and the index and the Home
            // both drain it as the line frees (ADR-4954 D8 fixes the order).
            //
            // The rollback existed because nothing outside the progress index drained a courses
            // wishlist, so a refused order would have sat unnoticed. That was fixed first:
            // LibraryHomeFragment now pumps courses like the other three. Landing on the index
            // makes the wait visible either way — a banked order draws its row as "Queued".
            KolibriProvisioner.drain(app);
            View v = getView();
            if (v != null) {
                v.post(this::finishStart);
            }
        });
    }

    /**
     * Back on the main thread once the order is written.
     *
     * <p>ADFA-5074: a started download lands on the progress index, the same place
     * Books goes ({@code SetupLibraryActivity.startBooksDownload}) and the same
     * place the notification and the Home header already point at. It used to open
     * the seeding fragment inside this activity instead, which is the identical
     * screen under a host with no chrome — so Back walked straight out of a live
     * download and the only way to end the session was one button the index also
     * has. One mechanism, one landing.
     *
     * <p>The index, not the courses detail. The detail is a deliberate zoom-in, reached
     * by tapping the row; the index is the only surface that can end the run, and these
     * downloads run for hours, so the user starts one and leaves. What they need on the
     * way out is the screen that will finish the job for them.
     */
    private void finishStart() {
        if (!isAdded()) {
            return;
        }
        vm.clearSelection();
        if (getActivity() == null) {
            return;
        }
        startActivity(new android.content.Intent(getActivity(), SetupProgressActivity.class));
    }

    /** One line of the order, read on the main thread so the write needs no view model. */
    private static final class Order {
        final String id;
        final int version;
        final String name;
        final long bytes;
        final List<String> nodeIds;

        Order(String id, int version, String name, long bytes, List<String> nodeIds) {
            this.id = id;
            this.version = version;
            this.name = name;
            this.bytes = bytes;
            this.nodeIds = nodeIds;
        }
    }

    /**
     * The chosen channels minus the ones already fully on the device.
     *
     * <p>A complete channel costs zero and downloads nothing, so sending it would
     * put a 0-byte row on the progress screen and tell the user a download had
     * started that cannot do anything. It is dropped from the order, and the row on
     * this screen already says why it is not coming.
     */
    private List<Channel> missingOnly(List<Channel> chosen) {
        List<Channel> out = new ArrayList<>();
        for (Channel c : chosen) {
            if (ready == null || !ready.library().isComplete(c.id())) {
                out.add(c);
            }
        }
        return out;
    }

    /** Writes the order. Identical on both doors — only the draining differs. */
    private void bank(List<Channel> chosen) {
        for (Channel c : chosen) {
            // nodeIds null/empty is written as "no nodeIds key at all", which the
            // provisioner reads as the whole channel — see KolibriWishlist.
            List<String> nodeIds = vm.subtreesFor(c.id()).nodeIds();
            KolibriWishlist.add(requireContext(), c.id(), c.version(), c.name(),
                    costOf(c), nodeIds.isEmpty() ? null : nodeIds);
        }
    }

    /**
     * What this channel will actually cost, given what the device already holds.
     *
     * <p>A channel imported 80 % of the way costs the missing fifth, not its
     * published size. Quoting the catalogue figure there is the difference between
     * "does not fit" and "fits easily", and it is the reason the installed listing
     * is read at all.
     */
    private long costOf(Channel c) {
        long declared = vm.bytesFor(c);
        return ready == null ? declared : ready.library().costOf(c.id(), declared);
    }

    private void paint(int textColor, int background) {
        fits.setTextColor(ContextCompat.getColor(requireContext(), textColor));
        fits.setBackgroundResource(background);
    }

    private View row(String left, String right, boolean bold) {
        LinearLayout r = new LinearLayout(requireContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(10), 0, px(10));

        TextView l = new TextView(requireContext());
        l.setTextAppearance(bold
                ? com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
                : com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        l.setText(left);
        l.setMaxLines(2);
        l.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        r.addView(l, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView rt = new TextView(requireContext());
        rt.setTextAppearance(bold
                ? com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
                : com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        rt.setText(right);
        rt.setTextColor(ContextCompat.getColor(requireContext(),
                bold ? R.color.k2go_ink : R.color.k2go_muted));
        r.addView(rt);
        return r;
    }

    private View divider() {
        View d = new View(requireContext());
        d.setBackgroundResource(R.color.k2go_hairline);
        d.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return d;
    }

    private void back() {
        if (getActivity() != null) {
            getActivity().getSupportFragmentManager().popBackStack();
        }
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
