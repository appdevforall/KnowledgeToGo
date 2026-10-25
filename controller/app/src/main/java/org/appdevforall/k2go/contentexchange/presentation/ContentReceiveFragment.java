/*
 * ============================================================================
 * Name        : ContentReceiveFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-376. The receive step of wireless Share: after the user joins the sender hotspot
 *               and scans the invite QR, this fetches the sender's manifest over the hotspot, runs the
 *               receiver-side free-space preflight, and starts the network import (then the shared
 *               import progress screen). The sender already scoped what is shared, so there is no
 *               per-item selection here: the receiver pulls the scoped set. The manifest fetch is a
 *               short, idempotent GET on a worker; a flaky hotspot shows an error with a retry.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.presentation;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.contentexchange.data.NetworkImportCarrier;
import org.appdevforall.k2go.contentexchange.data.ShareHandshake;
import org.appdevforall.k2go.contentexchange.data.ShareManifestReader;
import org.appdevforall.k2go.contentexchange.domain.ImportEntry;
import org.appdevforall.k2go.contentexchange.domain.ImportPreflight;
import org.appdevforall.k2go.contentexchange.domain.ItemRef;
import org.appdevforall.k2go.storage.StorageProbe;
import org.appdevforall.k2go.util.ByteFormatter;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ContentReceiveFragment extends Fragment {

    private static final String ARG_HOST = "host";
    private static final String ARG_PORT = "port";
    private static final String ARG_TOKEN = "token";
    private static final String ARG_MANIFEST = "manifestUrl";

    /** Create the receive screen for a validated invite the manager just scanned. */
    public static ContentReceiveFragment newInstance(ShareHandshake.ShareInvite invite) {
        ContentReceiveFragment f = new ContentReceiveFragment();
        Bundle b = new Bundle();
        b.putString(ARG_HOST, invite.host);
        b.putInt(ARG_PORT, invite.port);
        b.putString(ARG_TOKEN, invite.token);
        b.putString(ARG_MANIFEST, invite.manifestUrl);
        f.setArguments(b);
        return f;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView status;
    private TextView freeLabel;
    private TextView warning;
    private Button receiveButton;
    private Button retryButton;

    private Context appContext;
    private String host;
    private int port;
    private String token;
    private String manifestUrl;

    /** The parsed, scoped items once the manifest is read; the Receive button hands them to the service. */
    private List<ImportEntry> entries;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_content_receive, container, false);
        status = root.findViewById(R.id.k2go_cxrv_status);
        freeLabel = root.findViewById(R.id.k2go_cxrv_free_label);
        warning = root.findViewById(R.id.k2go_cxrv_warning);
        receiveButton = root.findViewById(R.id.k2go_cxrv_receive);
        retryButton = root.findViewById(R.id.k2go_cxrv_retry);

        TextView back = root.findViewById(R.id.k2go_cxrv_back);
        back.setText(getString(R.string.k2go_back_link));
        back.setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
        receiveButton.setOnClickListener(v -> onReceiveClicked());
        retryButton.setOnClickListener(v -> fetchManifest());

        appContext = requireContext().getApplicationContext();
        host = requireArguments().getString(ARG_HOST);
        port = requireArguments().getInt(ARG_PORT);
        token = requireArguments().getString(ARG_TOKEN);
        manifestUrl = requireArguments().getString(ARG_MANIFEST);

        fetchManifest();   // idempotent GET; safe to re-run on a recreate or a Try-again tap
        return root;
    }

    /** Fetch and parse the sender's manifest on a worker, then render the summary or an error. */
    private void fetchManifest() {
        entries = null;
        showConnecting();
        worker.execute(() -> {
            List<ImportEntry> parsed;
            try {
                NetworkImportCarrier carrier = new NetworkImportCarrier(
                        appContext, host, port, token, manifestUrl, () -> false);
                parsed = ShareManifestReader.parse(carrier.readManifest());
            } catch (Exception e) {
                post(() -> showError());
                return;
            }
            post(() -> showManifest(parsed));
        });
    }

    private void showConnecting() {
        status.setText(getString(R.string.k2go_cx_receive_connecting));
        freeLabel.setVisibility(View.GONE);
        warning.setVisibility(View.GONE);
        receiveButton.setVisibility(View.GONE);
        retryButton.setVisibility(View.GONE);
    }

    private void showError() {
        status.setText(getString(R.string.k2go_cx_receive_error));
        freeLabel.setVisibility(View.GONE);
        warning.setVisibility(View.GONE);
        receiveButton.setVisibility(View.GONE);
        retryButton.setVisibility(View.VISIBLE);
    }

    private void showManifest(List<ImportEntry> parsed) {
        this.entries = parsed;
        retryButton.setVisibility(View.GONE);
        if (parsed.isEmpty()) {
            status.setText(getString(R.string.k2go_cx_receive_nothing));
            freeLabel.setVisibility(View.GONE);
            warning.setVisibility(View.GONE);
            receiveButton.setVisibility(View.GONE);
            return;
        }
        long total = 0L;
        for (ImportEntry e : parsed) {
            total += Math.max(0L, e.bytes());
        }
        status.setText(getString(R.string.k2go_cx_receive_ready_fmt,
                parsed.size(), ByteFormatter.humanBytes(total)));

        Long free = StorageProbe.freeBytes(appContext);
        freeLabel.setText(free != null
                ? getString(R.string.k2go_cx_free_fmt, ByteFormatter.humanBytes(free))
                : getString(R.string.k2go_cx_free_unavailable));
        freeLabel.setVisibility(View.VISIBLE);

        // The whole scoped set may not fit; warn, but still allow it (partial is first-class and the
        // receiver cannot deselect items the sender scoped). The service stops cleanly on ENOSPC.
        boolean fits = ImportPreflight.fits(total, free);
        warning.setVisibility(fits ? View.GONE : View.VISIBLE);
        if (!fits) {
            warning.setText(getString(R.string.k2go_cx_receive_wont_fit));
        }
        receiveButton.setVisibility(View.VISIBLE);
    }

    private void onReceiveClicked() {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        int n = entries.size();
        ItemRef[] refs = new ItemRef[n];
        String[] titles = new String[n];
        String[] entryJson = new String[n];
        String[] payloads = new String[n];
        long[] bytes = new long[n];
        for (int i = 0; i < n; i++) {
            ImportEntry e = entries.get(i);
            refs[i] = e.ref();
            titles[i] = e.title() != null && !e.title().isEmpty() ? e.title() : e.localRef();
            entryJson[i] = e.entryJson();
            payloads[i] = e.payloadName();
            bytes[i] = e.bytes();
        }
        ContentImportService.startNetwork(requireContext(), host, port, token, manifestUrl,
                refs, titles, entryJson, payloads, bytes);
        showProgress();
    }

    /** Swap to the shared import progress screen. Not added to the back stack, so Finish there pops
     *  straight back to the manager (this transient connect screen is not re-shown). */
    private void showProgress() {
        requireActivity().getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, new ImportProgressFragment())
                .commit();
    }

    private void post(Runnable r) {
        main.post(() -> {
            if (isAdded() && getView() != null) {
                r.run();
            }
        });
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
