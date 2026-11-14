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

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.contentexchange.data.ShareHandshake;
import org.appdevforall.k2go.redesign.CodeText;
import org.appdevforall.k2go.util.SnackbarDuration;

public class ContentReceiveStartFragment extends Fragment {

    private TextView showPaste;
    private View pasteBlock;
    private EditText paste;
    private boolean pasteExpanded;

    /** The invite-QR scanner (same ZXing contract Clone uses). */
    private ActivityResultLauncher<ScanOptions> inviteScan;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_content_receive_start, container, false);
        inviteScan = registerForActivityResult(new ScanContract(), r -> onInvite(r.getContents()));

        root.findViewById(R.id.k2go_cxst_back).setOnClickListener(
                v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
        ((TextView) root.findViewById(R.id.k2go_cxst_badge_join)).setText("1");
        ((TextView) root.findViewById(R.id.k2go_cxst_badge_receive)).setText("2");
        root.findViewById(R.id.k2go_cxst_wifi).setOnClickListener(v -> openWifiSettings());
        root.findViewById(R.id.k2go_cxst_scan).setOnClickListener(v -> launchScanner());

        showPaste = root.findViewById(R.id.k2go_code_showpaste);
        pasteBlock = root.findViewById(R.id.k2go_code_pasteblock);
        paste = root.findViewById(R.id.k2go_code_paste);
        showPaste.setOnClickListener(v -> {
            pasteExpanded = !pasteExpanded;
            renderPaste();
        });
        root.findViewById(R.id.k2go_code_use).setOnClickListener(v -> onPasteUsed());
        if (s != null) {
            pasteExpanded = s.getBoolean("pasteExpanded");
        }
        renderPaste();
        return root;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("pasteExpanded", pasteExpanded);
    }

    private void renderPaste() {
        showPaste.setVisibility(View.VISIBLE);
        CodeText.setExpandChevron(showPaste, pasteExpanded, R.color.k2go_teal);
        pasteBlock.setVisibility(pasteExpanded ? View.VISIBLE : View.GONE);
    }

    /** Android Wi-Fi settings: join the sender's hotspot there (its QR, or the name and password). */
    private void openWifiSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (Exception e) {
            showMessage(getString(R.string.k2go_clone_toast_open_wifi));
        }
    }

    private void launchScanner() {
        ScanOptions o = new ScanOptions();
        o.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        o.setPrompt(getString(R.string.k2go_cx_receive_scan_prompt));
        o.setBeepEnabled(false);
        o.setBarcodeImageEnabled(false);
        o.setOrientationLocked(false);
        inviteScan.launch(o);
    }

    /** "Use this code": the pasted invite takes the same path as a scanned one. */
    private void onPasteUsed() {
        String text = paste.getText().toString().trim();
        if (text.isEmpty()) {
            showMessage(getString(R.string.k2go_clone_toast_paste_first));
            return;
        }
        onInvite(text);
    }

    /** A scanned or pasted invite. Null means the scan was cancelled; an invalid code is rejected. */
    private void onInvite(@Nullable String contents) {
        if (!isAdded() || contents == null) {
            return;
        }
        ShareHandshake.ShareInvite invite = ShareHandshake.decode(contents);
        if (invite == null) {
            showMessage(getString(R.string.k2go_cx_receive_invalid));
            return;
        }
        requireActivity().getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, ContentReceiveFragment.newInstance(invite))
                .addToBackStack(null)
                .commit();
    }

    private void showMessage(String msg) {
        Snackbar.make(requireView(), msg, SnackbarDuration.millisForText(msg)).show();
    }
}
