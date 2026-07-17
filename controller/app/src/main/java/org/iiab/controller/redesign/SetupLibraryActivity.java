package org.iiab.controller.redesign;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import org.iiab.controller.InstallationPlanner;
import org.iiab.controller.R;

/**
 * "Set up your library" host (ADFA-4725): Step 1 System -> Step 2 Content. Reached from the
 * Library "Get more" action (and, later, the first-run wizard). Own Material 3 theme.
 */
public class SetupLibraryActivity extends AppCompatActivity {

    private InstallationPlanner.Tier selectedTier = InstallationPlanner.Tier.STANDARD;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_k2go_setup);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.k2go_setup_host, new Step1SystemFragment())
                    .commit();
        }
    }

    public void setSelectedTier(InstallationPlanner.Tier tier) { this.selectedTier = tier; }
    public InstallationPlanner.Tier getSelectedTier() { return selectedTier; }

    /** Called by Step 1 "Next". Step 2 (A/B) lands in the next increment. */
    public void goToStep2() {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host,
                        PlaceholderFragment.newInstance("Step 2 · Content — coming next"))
                .addToBackStack("step2")
                .commit();
    }
}
