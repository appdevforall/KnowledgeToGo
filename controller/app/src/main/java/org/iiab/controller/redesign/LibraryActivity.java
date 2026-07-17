package org.iiab.controller.redesign;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import org.iiab.controller.R;

/**
 * New content-first UI shell (ADFA-4725): bottom nav Library / Connect / Clone / Settings.
 * Phase 1 = shell only. Server wiring, content cards, wizard and Step-2 land in later phases.
 */
public class LibraryActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);

        BottomNavigationView nav = findViewById(R.id.k2go_bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            showTab(item.getItemId());
            return true;
        });

        if (savedInstanceState == null) {
            nav.setSelectedItemId(R.id.nav_library);
        }
    }

    private void showTab(int itemId) {
        final String title;
        if (itemId == R.id.nav_connect) {
            title = "Connect";
        } else if (itemId == R.id.nav_clone) {
            title = "Clone";
        } else if (itemId == R.id.nav_settings) {
            title = "Settings";
        } else {
            title = "Library";
        }
        Fragment f = PlaceholderFragment.newInstance(title);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_nav_host, f)
                .commit();
    }
}
