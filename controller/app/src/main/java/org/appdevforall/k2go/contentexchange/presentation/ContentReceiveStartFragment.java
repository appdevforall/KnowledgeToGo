/*
 * ============================================================================
 * Name        : ContentReceiveStartFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Receive over Wi-Fi, start screen (modelled on Clone's Receive): join the sender's
 *               Wi-Fi, then scan the invite or enter it as text. A valid invite opens
 *               ContentReceiveFragment.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.presentation;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.contentexchange.data.ShareHandshake;
import org.appdevforall.k2go.redesign.CodeText;
import org.appdevforall.k2go.redesign.QrSection;
import org.appdevforall.k2go.util.Snackbars;

public class ContentReceiveStartFragment extends Fragment {

    private static final String STATE_PASTE_OPEN = "pasteOpen";

    private CodeText.PasteBlock pasteCode;
    private ActivityResultLauncher<ScanOptions> inviteScan;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_content_receive_start, container, false);
        inviteScan = registerForActivityResult(new ScanContract(), r -> onInvite(r.getContents()));

        QrSection.setStep(root.findViewById(R.id.k2go_cxst_join_hdr), "1", getString(R.string.k2go_cx_share_step_join));
        QrSection.setStep(root.findViewById(R.id.k2go_cxst_receive_hdr), "2",
                getString(R.string.k2go_cx_share_step_receive));
        root.findViewById(R.id.k2go_cxst_wifi).setOnClickListener(v -> {
            if (!CodeText.openWifiSettings(requireContext())) {
                Snackbars.make(v, R.string.k2go_clone_toast_open_wifi).show();
            }
        });
        root.findViewById(R.id.k2go_cxst_scan).setOnClickListener(
                v -> inviteScan.launch(CodeText.scanOptions(getString(R.string.k2go_cx_receive_scan_prompt))));

        pasteCode = new CodeText.PasteBlock(root, this::onInvite);
        pasteCode.setExpanded(s != null && s.getBoolean(STATE_PASTE_OPEN));
        pasteCode.show(true);
        return root;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        if (pasteCode != null) {
            out.putBoolean(STATE_PASTE_OPEN, pasteCode.isExpanded());
        }
    }

    /** A scanned or pasted invite. Null means the scan was cancelled; an invalid code is rejected. */
    private void onInvite(@Nullable String contents) {
        if (!isAdded() || contents == null) {
            return;
        }
        ShareHandshake.ShareInvite invite = ShareHandshake.decode(contents);
        if (invite == null) {
            Snackbars.make(requireView(), R.string.k2go_cx_receive_invalid).show();
            return;
        }
        requireActivity().getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, ContentReceiveFragment.newInstance(invite))
                .addToBackStack(null)
                .commit();
    }
}
