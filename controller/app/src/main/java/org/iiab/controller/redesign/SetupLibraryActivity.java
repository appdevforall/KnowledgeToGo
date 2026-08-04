package org.iiab.controller.redesign;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import org.iiab.controller.InstallationPlanner;
import org.iiab.controller.R;
import org.iiab.controller.SystemStateEvaluator;
import org.iiab.controller.install.presentation.InstallService;

/**
 * "Set up your library" host (ADFA-4725): Step 1 System -> Step 2 Content (A/B). Holds the
 * shared tier + content picks so the two Step-2 layouts (A expandable+bar, B 5-step+gauge)
 * carry selections across the hidden tap-5x flip.
 */
public class SetupLibraryActivity extends AppCompatActivity implements org.iiab.controller.ServerController.Host {

    private InstallationPlanner.Tier selectedTier = InstallationPlanner.Tier.STANDARD;

    // ADFA-4952: this host owns a ServerController so backup/restore can stop the environment before the
    // job (a static rootfs) and boot it after (startEnvironment). The server proot is process-scoped, so
    // it survives back to LibraryActivity, which only monitors it.
    private org.iiab.controller.ServerController serverController;
    private Boolean targetServerState = null;   // ServerController.Host state

    /** Launch extra: skip Step 1 (system) and open Step 2 (content) directly, for when a
     *  system is already installed so adding content never overwrites it. */
    public static final String EXTRA_CONTENT_ONLY = "contentOnly";
    /** ADFA-4842: open Module management (the proot-module hub) directly. Entry-point-agnostic:
     *  Settings → Advanced and (later) Get More both launch this same activity with this extra. */
    public static final String EXTRA_MODULE_MGMT = "moduleMgmt";
    /** ADFA-4958: deep-link to a specific module's detail from Home (opens the hub, then the detail). */
    public static final String EXTRA_MODULE_DETAIL = "moduleDetail";
    /** ADFA-4958: open the maps content selector directly from Home (maps is a module with a selector step). */
    public static final String EXTRA_MAPS_SETUP = "mapsSetup";
    /** ADFA-5004: open the Wikipedia & ZIM content screen directly (from the reader's Get-more shortcut). */
    public static final String EXTRA_ZIM_SETUP = "zimSetup";
    /** ADFA-4952: open Backup & restore directly (Settings → Advanced). */
    public static final String EXTRA_BACKUP_RESTORE = "backupRestore";
    /** ADFA-4957: open BackupJobFragment(mode) directly — used to deep-link back to a LIVE backup/restore
     *  (from LibraryActivity's routing when the app is reopened / the notification is tapped). */
    public static final String EXTRA_BR_JOB_MODE = "brJobMode";
    private boolean contentEverything = false; // legacy (kept for compat; unused by the picker)
    private boolean contentPictures = true;    // legacy
    // Shared Wikipedia selection so picks survive the A/B flip.
    private final java.util.LinkedHashSet<String> wikiVariants = new java.util.LinkedHashSet<>();
    private boolean wikiIncluded = true;
    private String wikiView = "list"; // "list" | "grouped"

    // ADFA-4849: Wikipedia & ZIM content — selected content language + cross-category selection
    // cart ("project|lang|flavour" -> size bytes) that accumulates across category screens.
    private String zimLang = null;
    private boolean zimLangManual = false; // false = following the wizard/system default
    private final java.util.LinkedHashMap<String, Long> zimCart = new java.util.LinkedHashMap<>();

    // ADFA-4853: true while the ZIM flow runs inside the wizard (pre-install). The terminal step
    // then persists the cart to ZimWishlist instead of starting a live download.
    private boolean zimWizard = false;

    // ADFA-4900: true while the Maps flow runs inside the wizard (pre-install). The Confirm step then
    // banks the per-layer selection to MapsWishlist instead of starting a live runrole.
    private boolean mapsWizard = false;

    // ADFA-4910: true while the Books flow runs inside the wizard (pre-install). The Confirm step then
    // banks the picks to BooksWishlist instead of starting a live download.
    private boolean booksWizard = false;

    // ADFA-4910: the Books selection handed from the landing to the Confirm screen:
    // gutenberg_id -> {title, author, download_url}.
    private final java.util.LinkedHashMap<String, String[]> booksCart = new java.util.LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_k2go_setup);
        serverController = new org.iiab.controller.ServerController(this, this);   // ADFA-4952
        serverController.start();
        // ADFA-4932: draggable feedback FAB on this screen (screenshot + email).
        org.iiab.controller.feedback.presentation.FeedbackFab.installOn(this, "getmore");
        if (savedInstanceState == null) {
            boolean moduleMgmt = getIntent().getBooleanExtra(EXTRA_MODULE_MGMT, false);
            final String moduleDetail = getIntent().getStringExtra(EXTRA_MODULE_DETAIL);
            boolean mapsSetup = getIntent().getBooleanExtra(EXTRA_MAPS_SETUP, false);
            boolean zimSetup = getIntent().getBooleanExtra(EXTRA_ZIM_SETUP, false);
            boolean backupRestore = getIntent().getBooleanExtra(EXTRA_BACKUP_RESTORE, false);
            boolean contentOnly = getIntent().getBooleanExtra(EXTRA_CONTENT_ONLY, false);
            String brJobMode = getIntent().getStringExtra(EXTRA_BR_JOB_MODE);   // ADFA-4957
            androidx.fragment.app.Fragment first;
            if (brJobMode != null) {
                first = BackupJobFragment.newInstance(brJobMode);   // ADFA-4957: land on the live op screen
            } else if (backupRestore) {
                first = new BackupRestoreFragment();   // ADFA-4952
            } else if (moduleMgmt || moduleDetail != null) {
                selectedTier = readInstalledTier();   // ADFA-4842: module management hub (proot apps)
                first = new ModuleHubFragment();
            } else if (mapsSetup) {
                selectedTier = readInstalledTier();   // ADFA-4958: maps content selector, entered from Home
                first = new MapsChooseFragment();
            } else if (zimSetup) {
                selectedTier = readInstalledTier();   // ADFA-5004: Wikipedia & ZIM content, from the reader
                first = new ZimLandingFragment();
            } else if (contentOnly) {
                selectedTier = readInstalledTier();   // size content against the installed tier
                first = new GetMoreHubFragment();     // ADFA-4848: Get More opens the content hub
            } else {
                // ADFA-4874: a fresh wizard run — drop any wishlist left by an aborted first-run so
                // we never drain stale pre-install picks after a later install. Safe here: the user
                // has not chosen anything yet in this run.
                BooksWishlist.clear(this);
                ZimWishlist.clear(this);
                first = new Step1SystemFragment();
            }
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.k2go_setup_host, first)
                    .commit();
            if (moduleDetail != null) {
                findViewById(R.id.k2go_setup_host).post(() -> openModuleDetail(moduleDetail));
            }
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

    public String getZimLang() {
        if (zimLang == null) {
            // Default to the wizard's content language (same pref the install path uses), falling
            // back to the system language. "Manually selected" = it differs from the phone system.
            String sys = org.iiab.controller.applang.data.ContentLanguage.systemDefault();
            String stored = getSharedPreferences(getString(R.string.pref_file_internal), MODE_PRIVATE)
                    .getString("selected_lang_minimal", sys);
            zimLang = org.iiab.controller.applang.data.ContentLanguage.normalize(stored);
            zimLangManual = !zimLang.equals(sys);
        }
        return zimLang;
    }
    /** True when the content language was picked manually (differs from the system default). */
    public boolean isZimLangManual() { getZimLang(); return zimLangManual; }
    public void setZimLang(String l) {
        zimLang = l;
        zimLangManual = !l.equals(org.iiab.controller.applang.data.ContentLanguage.systemDefault());
    }
    /** Re-align the content language to the system/wizard default. */
    public void followSystemLang() {
        zimLang = org.iiab.controller.applang.data.ContentLanguage.systemDefault();
        zimLangManual = false;
    }
    public java.util.LinkedHashMap<String, Long> getZimCart() { return zimCart; }

    private InstallationPlanner.Tier readInstalledTier() {
        String t = getSharedPreferences(getString(R.string.pref_file_internal), MODE_PRIVATE)
                .getString("installed_tier", InstallationPlanner.Tier.STANDARD.name());
        try {
            return InstallationPlanner.Tier.valueOf(t);
        } catch (Exception e) {
            return InstallationPlanner.Tier.STANDARD;
        }
    }


    /** ADFA-4848: open a content type's screen from the Get More hub. Maps is wired to its flow;
     *  the rest are navigable placeholders for now so the hub is reviewable. */
    public void openContentType(String key, String title) {
        zimWizard = false;   // live (post-install) path; the ZIM terminal downloads, not wishlists
        mapsWizard = false;  // ADFA-4900: live (post-install) path; Maps installs, not wishlists
        booksWizard = false; // ADFA-4910: live (post-install) path; Books download, not wishlists
        androidx.fragment.app.Fragment f;
        if ("maps".equals(key)) f = new MapsLandingFragment();
        else if ("wikipedia".equals(key)) f = new ZimLandingFragment();   // Wikipedia & ZIM content
        else if ("books".equals(key)) f = new BooksLandingFragment();     // ADFA-4850: Books / Gutenberg
        else f = PlaceholderFragment.newInstance(title);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, f)
                .addToBackStack("getmore_" + key)
                .commit();
    }

    /** ADFA-4849: ZIM landing -> a category's detail (variants/titles, multi-select). */
    public void openZimCategory(String project) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, ZimCategoryFragment.newInstance(project))
                .addToBackStack("zim_cat_" + project)
                .commit();
    }

    /** ADFA-4849: ZIM landing "Review" -> Confirm (cross-category breakdown of the cart). */
    public void openZimConfirm() {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, new ZimConfirmFragment())
                .addToBackStack("zim_confirm")
                .commit();
    }

    /** ADFA-4849: Confirm -> Preparing (contained animation + real progress; mock until backend). */
    public void openZimPreparing() {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, new ZimPreparingFragment())
                .addToBackStack("zim_preparing")
                .commit();
    }

    /** ADFA-4849: "Run in background" from ZIM Preparing -> back to the Get More hub. */
    public void backToGetMoreHubZim() {
        getSupportFragmentManager().popBackStack("getmore_wikipedia",
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    /** ADFA-4853: the wizard's "Continue" — install the system now; content (Books/ZIM) is banked
     *  as wishlists and drains itself once the server is up (BooksProvisioner/ZimProvisioner). So
     *  the install is companion=false (OS/tier only; maps ships in the image), replacing the old
     *  Step 2 "Download library" trigger. */
    public void startWizardInstall() {
        // ADFA-4982: the real install is starting — mark setup complete NOW (it is no longer set at the
        // wizard's "download" choice, so bailing before this resumes the wizard). This also lets the
        // install LibraryActivity below show progress instead of redirecting back to the wizard.
        getSharedPreferences(getString(R.string.pref_file_internal), MODE_PRIVATE)
                .edit().putBoolean(getString(R.string.pref_key_setup_complete), true).apply();
        Intent i = new Intent(this, InstallService.class);
        i.setAction(InstallService.ACTION_START);
        i.putExtra(InstallService.EXTRA_TIER, getSelectedTier().name());
        i.putExtra(InstallService.EXTRA_COMPANION, false);
        i.putExtra(InstallService.EXTRA_ARCH, SystemStateEvaluator.termuxArch(this));
        i.putExtra(InstallService.EXTRA_REINSTALL, false);
        i.putExtra(InstallService.EXTRA_SKIP_MAPS, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        startActivity(new Intent(this, LibraryActivity.class).putExtra(LibraryActivity.EXTRA_INSTALLING, true));
        finish();
    }

    /** ADFA-4853: the wizard content step — the Get More hub in pre-install mode (tier-gated). */
    public void goToWizardContent() {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, GetMoreHubFragment.newInstance(true))
                .addToBackStack("wizard_content")
                .commit();
    }

    /** ADFA-4853: route a wizard (pre-install) content card. Books uses the offline catalog +
     *  wishlist; Wikipedia/Maps are placeholders until their wizard sources land. */
    public void openWizardContent(String key, String title) {
        if ("books".equals(key)) { openBooksWizard(); return; }
        if ("wikipedia".equals(key)) { openZimWizard(); return; }
        // ADFA-4900: in the wizard there is no rootfs yet, so Maps cannot run runrole. It banks the
        // per-layer selection (MapsWishlist) like Books/ZIM and MapsProvisioner applies it post-install.
        if ("maps".equals(key)) { openContentType(key, title); mapsWizard = true; return; }
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, PlaceholderFragment.newInstance(title))
                .addToBackStack("wizard_" + key)
                .commit();
    }

    /** ADFA-4853: ZIM in wizard mode — same offline browse (kiwix_catalog.csv), but the terminal
     *  step persists the cart to ZimWishlist instead of downloading live. */
    public void openZimWizard() {
        zimWizard = true;
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, new ZimLandingFragment())
                .addToBackStack("wizard_wikipedia")
                .commit();
    }

    public boolean isZimWizard() { return zimWizard; }

    /** ADFA-4853: ZIM Confirm terminal in wizard mode — bank the selection and return to the hub. */
    public void zimWizardConfirm() {
        ZimWishlist.add(this, zimCart);
        zimCart.clear();
        getSupportFragmentManager().popBackStack("wizard_wikipedia",
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    /** ADFA-4853: open Books in wizard mode (pre-install, offline catalog -> wishlist). */
    public void openBooksWizard() {
        booksWizard = true;
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, BooksLandingFragment.newInstance(true))
                .addToBackStack("wizard_books")
                .commit();
    }

    public boolean isBooksWizard() { return booksWizard; }

    /** ADFA-4910: the Books selection cart (gutenberg_id -> {title, author, download_url}), set by
     *  the landing when the user taps "Review" and read by BooksConfirmFragment. */
    public java.util.LinkedHashMap<String, String[]> getBooksCart() { return booksCart; }
    public void setBooksCart(java.util.LinkedHashMap<String, String[]> picks) {
        booksCart.clear();
        if (picks != null) booksCart.putAll(picks);
    }

    /** ADFA-4910: Books landing "Review" -> Confirm (list + total + honest note). */
    public void openBooksConfirm() {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, new BooksConfirmFragment())
                .addToBackStack("books_confirm")
                .commit();
    }

    /** ADFA-4910: Books Confirm terminal in wizard mode — bank the picks and return to the hub. */
    public void booksWizardConfirm() {
        for (java.util.Map.Entry<String, String[]> e : booksCart.entrySet()) {
            String[] v = e.getValue();
            String title = v != null && v.length > 0 ? v[0] : "";
            String url = v != null && v.length > 2 ? v[2] : "";
            BooksWishlist.add(this, e.getKey(), title, url);
        }
        booksCart.clear();
        getSupportFragmentManager().popBackStack("wizard_books",
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    /** ADFA-4910: Books Confirm terminal in live mode — hand the picks to the download service and
     *  open the downloads screen (per-book checklist + retry). */
    public void startBooksDownload() {
        java.util.List<String> ids = new java.util.ArrayList<>(), titles = new java.util.ArrayList<>(),
                urls = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String[]> e : booksCart.entrySet()) {
            String[] v = e.getValue();
            ids.add(e.getKey());
            titles.add(v != null && v.length > 0 ? v[0] : "");
            urls.add(v != null && v.length > 2 ? v[2] : "");
        }
        booksCart.clear();
        BooksDownloadService.start(getApplicationContext(),
                ids.toArray(new String[0]), titles.toArray(new String[0]), urls.toArray(new String[0]));
        // ADFA-4988: go to the downloads screen (its per-item list with download -> done checks),
        // matching ZIM/maps/modules — instead of returning to Get More and downloading invisibly.
        // Hint "books": the index opens the books detail when books is the only stream, else the cards.
        startActivity(new Intent(this, SetupProgressActivity.class)
                .putExtra(SetupProgressActivity.EXTRA_HINT_STREAM, "books"));
    }

    /** ADFA-4850: Books landing -> the download manager screen (per-book checklist + retry). */
    public void openBooksDownloads() {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, new BooksDownloadsFragment())
                .addToBackStack("books_downloads")
                .commit();
    }

    /** ADFA-4848: Maps landing -> "Choose layers & quality" (Option B). */
    public void openMapsChoose() {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, new MapsChooseFragment())
                .addToBackStack("maps_choose")
                .commit();
    }

    /** ADFA-4848: Choose -> Confirm (breakdown + total + time warning). ADFA-4900: also carries the
     *  per-layer level keys (aligned to the Choose groups; null = off) so Preparing can install. */
    public void openMapsConfirm(String[] names, String[] opts, long[] mb, String[] levels) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, MapsConfirmFragment.newInstance(names, opts, mb, levels))
                .addToBackStack("maps_confirm")
                .commit();
    }

    /** ADFA-4919: Get More Maps installs through the SAME install index as the wizard. Banks the
     *  per-layer selection to MapsWishlist and opens SetupProgressActivity, which drains it via
     *  MapsProvisioner and applies the proot gate (no background) + stage-based completion.
     *  WHY: one gated way to install a proot module, instead of the old standalone screen that
     *  bypassed the index. The server is up during Get More, so the index's readiness latches and
     *  the drain proceeds. This is the entry ADFA-4842 (module management) should generalize. */
    public void openMapsIndex(String[] levels, long totalMb) {
        String base = levels != null && levels.length > 0 && levels[0] != null ? levels[0] : "osm-z11";
        String sat = levels != null && levels.length > 1 && levels[1] != null ? levels[1] : "none";
        String ter = levels != null && levels.length > 2 && levels[2] != null ? levels[2] : "none";
        boolean search = levels != null && levels.length > 3 && levels[3] != null;
        MapsWishlist.save(this, base, sat, ter, search, totalMb);
        startActivity(new Intent(this, SetupProgressActivity.class));
    }

    /** ADFA-4842: open a module's detail (Play Store card) from the hub or a deep-link. */
    public void openModuleDetail(String yamlBaseKey) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, ModuleDetailFragment.newInstance(yamlBaseKey))
                .addToBackStack("module_detail")
                .commit();
    }

    /** ADFA-5011: open the dash-node REST core's detail (Play Store-style card, Rebuild-only). */
    public void openDashboardDetail() {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, new DashboardDetailFragment())
                .addToBackStack("dashboard_detail")
                .commit();
    }

    /** ADFA-4842: proceed to the install index for the scheduled modules. The modules are already
     *  banked in ModuleWishlist; the index drains them through the proot queue (ModuleProvisioner),
     *  same mechanism as maps. */
    public void openModuleIndex() {
        startActivity(new Intent(this, SetupProgressActivity.class));
    }

    /** ADFA-4952: open the dedicated backup/restore job screen (mode = MODE_BACKUP / MODE_RESTORE). */
    public void openBackupJob(String mode) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, BackupJobFragment.newInstance(mode))
                .addToBackStack("backup_job")
                .commit();
    }

    /** @deprecated ADFA-4919: the STANDALONE Get More Maps route (shows MapsPreparingFragment with
     *  its own "Run in background", no index, no gate). Superseded by openMapsIndex(). Left UNUSED
     *  on purpose (not deleted): ADFA-4842 (reactivate proot modules via module management) may want
     *  to generalize a "install these proot modules now" path from here — 4842 decides whether to
     *  reuse/generalize or delete. Deleting now could remove something module management wants. */
    public void openMapsPreparing(String[] levels) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, MapsPreparingFragment.newInstance(levels))
                .addToBackStack("maps_preparing")
                .commit();
    }

    /** ADFA-4900: true while Maps runs inside the wizard (pre-install) — Confirm banks the selection. */
    public boolean isMapsWizard() { return mapsWizard; }

    // ---- ADFA-4952: server lifecycle for backup/restore ----
    /** The host's ServerController (backup/restore use stopEnvironment()/startEnvironment()). */
    public org.iiab.controller.ServerController server() { return serverController; }

    @Override protected void onResume() {
        super.onResume();
        if (serverController != null) serverController.onResume();
    }

    @Override protected void onPause() {
        super.onPause();
        if (serverController != null) serverController.onPause();
    }

    // ServerController.Host (minimal — this host has no server LEDs/pulse UI; backup/restore show their
    // own status). The two protection methods drive the WatchdogService so the job isn't killed.
    @Override public void addToLog(String message) { Log.d("K2Go-SetupLibrary", message); }
    @Override public void startFusionPulse() { }
    @Override public void startExitPulse() { }
    @Override public void stopBtnProgress() { }
    @Override public void updateConnectivityLeds(boolean wifiOn, boolean hotspotOn) { }
    @Override public void refreshServerUi() { }
    @Override public Boolean getTargetServerState() { return targetServerState; }
    @Override public void setTargetServerState(Boolean target) { targetServerState = target; }
    @Override public boolean isNegotiating() { return false; }

    @Override public void enableSystemProtection() {
        Intent i = new Intent(this, org.iiab.controller.WatchdogService.class);
        i.setAction(org.iiab.controller.WatchdogService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }

    @Override public void disableSystemProtection() {
        Intent i = new Intent(this, org.iiab.controller.WatchdogService.class);
        i.setAction(org.iiab.controller.WatchdogService.ACTION_STOP);
        startService(i);
    }

    /** ADFA-4900: Maps Confirm terminal in wizard mode — bank the per-layer selection to MapsWishlist
     *  (MapsProvisioner applies it post-install) and return to the Get More hub. No live runrole. */
    public void mapsWizardConfirm(String[] levels, long totalMb) {
        String base = levels != null && levels.length > 0 && levels[0] != null ? levels[0] : "osm-z11";
        String sat = levels != null && levels.length > 1 && levels[1] != null ? levels[1] : "none";
        String ter = levels != null && levels.length > 2 && levels[2] != null ? levels[2] : "none";
        boolean search = levels != null && levels.length > 3 && levels[3] != null;
        MapsWishlist.save(this, base, sat, ter, search, totalMb);
        getSupportFragmentManager().popBackStack("getmore_maps",
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    /** @deprecated ADFA-4919: part of the standalone Get More route (called only by the standalone
     *  MapsPreparingFragment path, now unreachable). The install index reaches the same engine via
     *  MapsProvisioner.drain(). Kept UNUSED pending ADFA-4842 (see openMapsPreparing). WHY-kept: it
     *  is the thinnest "start these proot modules now" call, a candidate for 4842 to generalize.
     *  ADFA-4900: start the maps install through the module-queue engine (a queue of {"maps"} plus
     *  the per-layer selection). InstallService writes the full maps_* local_vars and runs runrole
     *  with the shared success/failure verdict, revert-on-fail and observable progress. {@code levels}
     *  is aligned to the Choose groups [base, satellite, terrain, search]; null = off. */
    public void startMapsInstall(String[] levels) {
        String base = levels != null && levels.length > 0 && levels[0] != null ? levels[0] : "osm-z11";
        String sat = levels != null && levels.length > 1 && levels[1] != null ? levels[1] : "none";
        String ter = levels != null && levels.length > 2 && levels[2] != null ? levels[2] : "none";
        boolean search = levels != null && levels.length > 3 && levels[3] != null;
        Intent i = new Intent(this, InstallService.class);
        i.setAction(InstallService.ACTION_START_MODULES);
        i.putExtra(InstallService.EXTRA_MODULES, new String[]{"maps"});
        i.putExtra(InstallService.EXTRA_MAPS_VECTOR, base);
        i.putExtra(InstallService.EXTRA_MAPS_SAT, sat);
        i.putExtra(InstallService.EXTRA_MAPS_TERRAIN, ter);
        i.putExtra(InstallService.EXTRA_MAPS_SEARCH, search);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }

    /** @deprecated ADFA-4919: only used by the standalone Get More "Run in background" button, which
     *  the index gate removes for proot. Unreachable once Get More routes through openMapsIndex().
     *  Kept UNUSED pending ADFA-4842 (see openMapsPreparing). (ZIM uses backToGetMoreHubZim, separate.)
     *  ADFA-4848: "Run in background" from Preparing -> drop the whole Maps flow off the back
     *  stack and return to the Get More hub; the build keeps running. */
    public void backToGetMoreHub() {
        getSupportFragmentManager().popBackStack("getmore_maps",
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

}
