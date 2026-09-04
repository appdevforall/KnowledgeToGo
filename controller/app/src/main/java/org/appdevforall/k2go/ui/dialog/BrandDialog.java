package org.appdevforall.k2go.ui.dialog;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.checkbox.MaterialCheckBox;

import org.appdevforall.k2go.R;

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

    /** Positive callback for a dialog carrying a checkbox — {@code checked} is its final state. */
    public interface OnConfirm { void onConfirm(boolean checked); }

    public static final class Handle {
        private final AlertDialog dialog;
        private final Button positive;
        private final Button negative;
        private final FrameLayout content;

        Handle(AlertDialog dialog, Button positive, Button negative, FrameLayout content) {
            this.dialog = dialog;
            this.positive = positive;
            this.negative = negative;
            this.content = content;
        }

        public AlertDialog getDialog() { return dialog; }
        public Button getPositiveButton() { return positive; }
        public Button getNegativeButton() { return negative; }
        public void dismiss() { dialog.dismiss(); }
        public void show() { dialog.show(); }

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
    private OnConfirm positiveConfirm;
    private CharSequence checkboxLabel;
    private boolean checkboxChecked;
    private boolean checkboxRequired;
    private CharSequence checkboxRequiredMessage;

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

    /**
     * Add an optional checkbox under the message. Its final state is delivered to the positive
     * {@link OnConfirm}. It sits in the same inset column as the title/message, so call sites no
     * longer hand-build a padded holder to line it up.
     */
    public BrandDialog setCheckbox(@NonNull CharSequence label, boolean checked) {
        this.checkboxLabel = label;
        this.checkboxChecked = checked;
        return this;
    }

    public BrandDialog setCheckbox(@StringRes int label, boolean checked) {
        return setCheckbox(context.getString(label), checked);
    }

    /**
     * Make the checkbox a gate: the positive action is blocked until it is ticked, with
     * {@code messageWhenUnchecked} shown inline under the checkbox.
     */
    public BrandDialog requireCheckbox(@NonNull CharSequence messageWhenUnchecked) {
        this.checkboxRequired = true;
        this.checkboxRequiredMessage = messageWhenUnchecked;
        return this;
    }

    public BrandDialog requireCheckbox(@StringRes int messageWhenUnchecked) {
        return requireCheckbox(context.getString(messageWhenUnchecked));
    }

    /** Positive button that receives the checkbox's final state. */
    public BrandDialog setPositive(@NonNull CharSequence text, @NonNull Role role, @Nullable OnConfirm confirm) {
        this.positiveText = text;
        this.positiveRole = role;
        this.positiveConfirm = confirm;
        return this;
    }

    public BrandDialog setPositive(@StringRes int text, @NonNull Role role, @Nullable OnConfirm confirm) {
        return setPositive(context.getString(text), role, confirm);
    }

    public BrandDialog setDestructive(@NonNull CharSequence text, @Nullable OnConfirm confirm) {
        return setPositive(text, Role.DESTRUCTIVE, confirm);
    }

    public BrandDialog setDestructive(@StringRes int text, @Nullable OnConfirm confirm) {
        return setPositive(context.getString(text), Role.DESTRUCTIVE, confirm);
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

    public BrandDialog setPositive(@NonNull CharSequence text, @Nullable OnClick click) {
        return setPositive(text, Role.PRIMARY, click);
    }

    public BrandDialog setPositive(@StringRes int text, @Nullable OnClick click) {
        return setPositive(context.getString(text), Role.PRIMARY, click);
    }

    public BrandDialog setDestructive(@NonNull CharSequence text, @Nullable OnClick click) {
        return setPositive(text, Role.DESTRUCTIVE, click);
    }

    public BrandDialog setDestructive(@StringRes int text, @Nullable OnClick click) {
        return setPositive(context.getString(text), Role.DESTRUCTIVE, click);
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

    public Handle create() {
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
        final MaterialCheckBox checkbox;
        final TextView checkboxError;
        if (contentView != null || checkboxLabel != null) {
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            if (contentView != null) {
                column.addView(contentView);
            }
            MaterialCheckBox cb = null;
            TextView err = null;
            if (checkboxLabel != null) {
                cb = new MaterialCheckBox(context);
                cb.setText(checkboxLabel);
                cb.setChecked(checkboxChecked);
                // brand_dialog_content already sits at the card's content inset, so clearing the box's
                // own start padding lines it up with the title/message above — no per-call holder.
                cb.setPaddingRelative(0, cb.getPaddingTop(), cb.getPaddingEnd(), cb.getPaddingBottom());
                column.addView(cb);
                if (checkboxRequired && checkboxRequiredMessage != null) {
                    err = new TextView(context);
                    err.setText(checkboxRequiredMessage);
                    err.setTextColor(ContextCompat.getColor(context, R.color.btn_danger));
                    err.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                    err.setPadding(0, Math.round(4 * context.getResources().getDisplayMetrics().density), 0, 0);
                    err.setVisibility(View.GONE);
                    column.addView(err);
                    final TextView errRef = err;
                    cb.setOnCheckedChangeListener((b, isChecked) -> {
                        if (isChecked) {
                            errRef.setVisibility(View.GONE);
                        }
                    });
                }
            }
            contentHost.removeAllViews();
            contentHost.addView(column);
            contentHost.setVisibility(View.VISIBLE);
            checkbox = cb;
            checkboxError = err;
        } else {
            checkbox = null;
            checkboxError = null;
        }

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(root)
                .setCancelable(cancelable)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        if (positiveText != null) {
            positive.setText(positiveText);
            // Filled teal by default from the Widget.K2Go.Button style; a destructive action re-tints
            // the same filled button red.
            if (positiveRole == Role.DESTRUCTIVE) {
                positive.setBackgroundTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.btn_danger)));
            }
            positive.setVisibility(View.VISIBLE);
            positive.setOnClickListener(v -> {
                if (checkbox != null && checkboxRequired && !checkbox.isChecked()) {
                    if (checkboxError != null) {
                        checkboxError.setVisibility(View.VISIBLE);
                    }
                    return;   // gate: keep the dialog open, fire nothing, until the box is ticked
                }
                if (dismissOnPositive) {
                    dialog.dismiss();
                }
                if (positiveConfirm != null) {
                    positiveConfirm.onConfirm(checkbox != null && checkbox.isChecked());
                } else if (positiveClick != null) {
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

        return new Handle(dialog, positive, negative, contentHost);
    }

    public Handle show() {
        Handle h = create();
        h.show();
        return h;
    }
}
