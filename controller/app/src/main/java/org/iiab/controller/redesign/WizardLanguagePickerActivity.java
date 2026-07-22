package org.iiab.controller.redesign;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;
import org.iiab.controller.R;
import org.iiab.controller.applang.domain.AppLanguage;
import org.iiab.controller.applang.domain.SupportedAppLanguages;

/**
 * ADFA-4797: "Choose language" sub-screen for the setup wizard. A search box over a
 * single-choice list of every supported UI language (endonyms), "follow system" pinned
 * on top. Returns the chosen BCP-47 tag ("" = follow system) via {@link #EXTRA_TAG}.
 * No hardcoded language list — driven by {@link SupportedAppLanguages}.
 */
public class WizardLanguagePickerActivity extends AppCompatActivity {

    public static final String EXTRA_TAG = "lang_tag";

    private String current = "";
    private List<AppLanguage> all;
    private LinearLayout listView;

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_k2go_lang_picker);

        current = getIntent().getStringExtra(EXTRA_TAG);
        if (current == null) current = "";
        all = SupportedAppLanguages.forPicker(getString(R.string.k2go_lang_follow_system));
        listView = findViewById(R.id.lang_list);

        findViewById(R.id.lang_picker_back).setOnClickListener(v -> finish());
        ((EditText) findViewById(R.id.lang_search)).addTextChangedListener(new TextWatcher() {
            @Override public void afterTextChanged(Editable s) { render(s.toString()); }
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
        });
        render("");
    }

    private void render(String term) {
        String t = term == null ? "" : term.trim().toLowerCase();
        listView.removeAllViews();
        AppLanguage sys = all.get(0);
        if (t.isEmpty() || matches(sys, t)) listView.addView(row(sys));
        List<View> rows = new ArrayList<>();
        for (int i = 1; i < all.size(); i++) {
            AppLanguage l = all.get(i);
            if (t.isEmpty() || matches(l, t)) rows.add(row(l));
        }
        if (!rows.isEmpty()) {
            listView.addView(header(getString(R.string.k2go_lang_all)));
            for (View v : rows) listView.addView(v);
        }
    }

    private boolean matches(AppLanguage l, String term) {
        return l.toString().toLowerCase().contains(term) || l.searchName().toLowerCase().contains(term);
    }

    private TextView header(String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextColor(ContextCompat.getColor(this, R.color.k2go_muted));
        h.setTextSize(12);
        h.setLetterSpacing(0.08f);
        h.setPadding(dp(18), dp(12), dp(18), dp(6));
        return h;
    }

    private View row(AppLanguage l) {
        boolean sel = l.tag().equals(current);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(14), dp(16), dp(14));
        if (sel) r.setBackgroundResource(R.drawable.k2go_search_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(4), dp(2), dp(4), dp(2));
        r.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(l.toString());
        t.setTextColor(ContextCompat.getColor(this, R.color.k2go_ink));
        t.setTextSize(18);
        if (sel) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));

        if (sel) {
            TextView c = new TextView(this);
            c.setText("✓");
            c.setTextColor(ContextCompat.getColor(this, R.color.k2go_teal));
            c.setTextSize(20);
            r.addView(c);
        }

        r.setClickable(true);
        r.setFocusable(true);
        r.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_TAG, l.tag()));
            finish();
        });
        return r;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
