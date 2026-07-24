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

    /** Launch extra: skip Step 1 (system) and open Step 2 (content) directly, for when a
     *  system is already installed so adding content never overwrites it. */
    public static final String EXTRA_CONTENT_ONLY = "contentOnly";
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
            boolean contentOnly = getIntent().getBooleanExtra(EXTRA_CONTENT_ONLY, false);
            androidx.fragment.app.Fragment first;
            if (contentOnly) {
                selectedTier = readInstalledTier();   // size content against the installed tier
                first = new GetMoreHubFragment();     // ADFA-4848: Get More opens the content hub
            } else {
                first = new Step1SystemFragment();
            }
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.k2go_setup_host, first)
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

    private InstallationPlanner.Tier readInstalledTier() {
        String t = getSharedPreferences(getString(R.string.pref_file_internal), MODE_PRIVATE)
                .getString("installed_tier", InstallationPlanner.Tier.STANDARD.name());
        try {
            return InstallationPlanner.Tier.valueOf(t);
        } catch (Exception e) {
            return InstallationPlanner.Tier.STANDARD;
        }
    }

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

    /** ADFA-4848: open a content type's screen from the Get More hub. Maps is wired to its flow;
     *  the rest are navigable placeholders for now so the hub is reviewable. */
    public void openContentType(String key, String title) {
        androidx.fragment.app.Fragment f = "maps".equals(key)
                ? new MapsLandingFragment()
                : PlaceholderFragment.newInstance(title);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, f)
                .addToBackStack("getmore_" + key)
                .commit();
    }

    /** ADFA-4848: Maps landing -> "Choose layers & quality" (Option B). */
    public void openMapsChoose() {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, new MapsChooseFragment())
                .addToBackStack("maps_choose")
                .commit();
    }

    /** ADFA-4848: Choose -> Confirm (breakdown + total + time warning). */
    public void openMapsConfirm(String[] names, String[] opts, long[] mb) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, MapsConfirmFragment.newInstance(names, opts, mb))
                .addToBackStack("maps_confirm")
                .commit();
    }

    /** ADFA-4848: Confirm -> Preparing (boot animation + phase checklist; mock until the backend). */
    public void openMapsPreparing() {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, new MapsPreparingFragment())
                .addToBackStack("maps_preparing")
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
