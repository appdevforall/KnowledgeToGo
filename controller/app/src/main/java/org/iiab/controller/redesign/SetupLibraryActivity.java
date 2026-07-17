package org.iiab.controller.redesign;

import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import org.iiab.controller.InstallationPlanner;
import org.iiab.controller.R;

/**
 * "Set up your library" host (ADFA-4725): Step 1 System -> Step 2 Content (A/B). Holds the
 * shared tier + content picks so the two Step-2 layouts (A expandable+bar, B 5-step+gauge)
 * carry selections across the hidden tap-5x flip.
 */
public class SetupLibraryActivity extends AppCompatActivity {

    private InstallationPlanner.Tier selectedTier = InstallationPlanner.Tier.STANDARD;
    private boolean contentEverything = false; // legacy (kept for compat; unused by the picker)
    private boolean contentPictures = true;    // legacy
    private boolean optionB = false;           // A is the default
    // Shared Wikipedia selection so picks survive the A/B flip.
    private final java.util.LinkedHashSet<String> wikiVariants = new java.util.LinkedHashSet<>();
    private boolean wikiIncluded = true;
    private String wikiView = "list"; // "list" | "grouped"

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

    public boolean isEverything() { return contentEverything; }
    public void setEverything(boolean b) { contentEverything = b; }
    public boolean isPictures() { return contentPictures; }
    public void setPictures(boolean b) { contentPictures = b; }

    public java.util.LinkedHashSet<String> getWikiVariants() { return wikiVariants; }
    public boolean isWikiIncluded() { return wikiIncluded; }
    public void setWikiIncluded(boolean b) { wikiIncluded = b; }
    public String getWikiView() { return wikiView; }
    public void setWikiView(String v) { wikiView = v; }

    private Fragment step2Fragment() {
        return optionB ? new Step2OptionBFragment() : new Step2OptionAFragment();
    }

    /** Called by Step 1 "Next". */
    public void goToStep2() {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, step2Fragment())
                .addToBackStack("step2")
                .commit();
    }

    /** Hidden A/B-test switch: flip the Step-2 layout in place; picks carry over. */
    public void flipAbTest() {
        optionB = !optionB;
        Log.d("K2Go-ABtest", "Set-up-content layout -> " + (optionB ? "B (5-step + gauge)" : "A (expandable + bar)"));
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, step2Fragment())
                .commit();
    }
}
