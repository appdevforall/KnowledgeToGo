package org.iiab.controller.ui.dialog;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import org.iiab.controller.R;

/**
 * Branded dialog (ADFA-4638). Single presentation-layer component that gives every app dialog
 * the card look of the Share tutorial: rounded card, brand surface, a full-width filled primary
 * button (blue, or red for destructive), and plain-text secondary buttons.
 *
 * <p>Fluent API shaped like {@code AlertDialog.Builder} so migrating call sites is mechanical.
 * Supports title, message or a custom content view, up to three buttons with roles, cancelable
 * and dismiss-on-positive control, and a {@link Handle} for sites that update the dialog after
 * showing (e.g. OTA progress). No domain/data dependencies -- pure UI.
 */
public final class BrandDialog {

    public enum Role { PRIMARY, DESTRUCTIVE }

    public interface OnClick { void onClick(); }

    public static final class Handle {
        private final AlertDialog dialog;
        private final Button positive;
        private final FrameLayout content;

        Handle(AlertDialog dialog, Button positive, FrameLayout content) {
            this.dialog = dialog;
            this.positive = positive;
            this.content = content;
        }

        public AlertDialog getDialog() { return dialog; }
        public Button getPositiveButton() { return positive; }
        public void dismiss() { dialog.dismiss(); }

        public void setContent(@NonNull View view) {
            content.removeAllViews();
            content.addView(view);
            content.setVisibility(View.VISIBLE);
        }
    }

    private final Context context;
    private CharSequence title;
    private CharSequence message;
    private View contentView;
    private CharSequence positiveText;
    private Role positiveRole = Role.PRIMARY;
    private OnClick positiveClick;
    private CharSequence negativeText;
    private OnClick negativeClick;
    private CharSequence neutralText;
    private OnClick neutralClick;
    private boolean cancelable = true;
    private boolean dismissOnPositive = true;

    public BrandDialog(@NonNull Context context) {
        this.context = context;
    }

    public BrandDialog setTitle(@Nullable CharSequence title) {
        this.title = title;
        return this;
    }

    public BrandDialog setTitle(@StringRes int title) {
        this.title = context.getString(title);
        return this;
    }

    public BrandDialog setMessage(@Nullable CharSequence message) {
        this.message = message;
        return this;
    }

    public BrandDialog setMessage(@StringRes int message) {
        this.message = context.getString(message);
        return this;
    }

    public BrandDialog setContentView(@Nullable View view) {
        this.contentView = view;
        return this;
    }

    public BrandDialog setPositive(@NonNull CharSequence text, @NonNull Role role, @Nullable OnClick click) {
        this.positiveText = text;
        this.positiveRole = role;
        this.positiveClick = click;
        return this;
    }

    public BrandDialog setPositive(@StringRes int text, @NonNull Role role, @Nullable OnClick click) {
        return setPositive(context.getString(text), role, click);
    }

    public BrandDialog setNegative(@NonNull CharSequence text, @Nullable OnClick click) {
        this.negativeText = text;
        this.negativeClick = click;
        return this;
    }

    public BrandDialog setNegative(@StringRes int text, @Nullable OnClick click) {
        return setNegative(context.getString(text), click);
    }

    public BrandDialog setNeutral(@NonNull CharSequence text, @Nullable OnClick click) {
        this.neutralText = text;
        this.neutralClick = click;
        return this;
    }

    public BrandDialog setNeutral(@StringRes int text, @Nullable OnClick click) {
        return setNeutral(context.getString(text), click);
    }

    public BrandDialog setCancelable(boolean cancelable) {
        this.cancelable = cancelable;
        return this;
    }

    public BrandDialog setDismissOnPositive(boolean dismissOnPositive) {
        this.dismissOnPositive = dismissOnPositive;
        return this;
    }

    public Handle show() {
        View root = LayoutInflater.from(context).inflate(R.layout.dialog_brand, null, false);
        TextView titleView = root.findViewById(R.id.brand_dialog_title);
        TextView messageView = root.findViewById(R.id.brand_dialog_message);
        FrameLayout contentHost = root.findViewById(R.id.brand_dialog_content);
        Button positive = root.findViewById(R.id.brand_dialog_positive);
        Button neutral = root.findViewById(R.id.brand_dialog_neutral);
        Button negative = root.findViewById(R.id.brand_dialog_negative);

        if (title != null) {
            titleView.setText(title);
            titleView.setVisibility(View.VISIBLE);
        }
        if (message != null) {
            messageView.setText(message);
            messageView.setVisibility(View.VISIBLE);
        }
        if (contentView != null) {
            contentHost.removeAllViews();
            contentHost.addView(contentView);
            contentHost.setVisibility(View.VISIBLE);
        }

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(root)
                .setCancelable(cancelable)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        if (positiveText != null) {
            int tintColor = (positiveRole == Role.DESTRUCTIVE)
                    ? ContextCompat.getColor(context, R.color.btn_danger)
                    : ContextCompat.getColor(context, R.color.dialog_accent);
            positive.setText(positiveText);
            positive.setBackgroundTintList(ColorStateList.valueOf(tintColor));
            positive.setVisibility(View.VISIBLE);
            positive.setOnClickListener(v -> {
                if (dismissOnPositive) {
                    dialog.dismiss();
                }
                if (positiveClick != null) {
                    positiveClick.onClick();
                }
            });
        }
        if (neutralText != null) {
            neutral.setText(neutralText);
            neutral.setVisibility(View.VISIBLE);
            neutral.setOnClickListener(v -> {
                dialog.dismiss();
                if (neutralClick != null) {
                    neutralClick.onClick();
                }
            });
        }
        if (negativeText != null) {
            negative.setText(negativeText);
            negative.setVisibility(View.VISIBLE);
            negative.setOnClickListener(v -> {
                dialog.dismiss();
                if (negativeClick != null) {
                    negativeClick.onClick();
                }
            });
        }

        dialog.show();
        return new Handle(dialog, positive, contentHost);
    }
}
