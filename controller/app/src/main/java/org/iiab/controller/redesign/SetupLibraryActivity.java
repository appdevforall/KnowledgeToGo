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
    /** ADFA-5023: run the install wizard in REINSTALL mode — the normal flow, but the final install
     *  wipes the existing rootfs first (delete + install). Reached from Backup & restore's third card
     *  and from the damaged-system recovery. */
    public static final String EXTRA_REINSTALL_SETUP = "reinstallSetup";
    /** ADFA-5023: true for the whole wizard when launched in reinstall mode; read by startWizardInstall. */
    private boolean reinstallMode = false;
    /** ADFA-5023: debounce the wizard's "Continue" so repeat taps can't launch duplicate installs. */
    private boolean installStarting = false;
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
    // ADFA-5061: the carts and the content language used to be plain fields here and died
    // with the activity instance. They live in an activity-scoped ViewModel now, which
    // survives the recreations this activity does not declare (a light/dark change, "Don't
    // keep activities", a process death with task restore). The accessors below are kept
    // as the seam so the catalog screens are untouched.
    private org.iiab.controller.wizard.presentation.WizardSelectionViewModel selection;

    /**
     * ADFA-5061: resolved on first use rather than assigned in {@code onCreate}.
     * {@code super.onCreate()} dispatches {@code onCreate()} to restored fragments, so a
     * field assigned after it would be null for anything that reads a cart that early —
     * an ordering trap in the exact path this state was moved to survive.
     */
    private org.iiab.controller.wizard.presentation.WizardSelectionViewModel selection() {
        if (selection == null) {
            selection = new androidx.lifecycle.ViewModelProvider(this,
                    new org.iiab.controller.wizard.presentation.WizardSelectionViewModelFactory(this, this))
                    .get(org.iiab.controller.wizard.presentation.WizardSelectionViewModel.class);
        }
        return selection;
    }

    // ADFA-5061: the four `*Wizard` booleans that used to live here are gone. Each said
    // "this flow was opened from the wizard, so bank instead of download" — a description
    // of the door the user came through, not of the system, and lost on every activity
    // recreation. The Confirm screens now ask `ContentDoor`, which resolves it from facts
    // that are re-read rather than remembered. `reinstallMode` above is the one thing they
    // still need from here, and it survives because it is read back from the Intent.

    // ADFA-4910: the Books selection handed from the landing to the Confirm screen:
    // gutenberg_id -> {title, author, download_url}.

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_k2go_setup);
        serverController = new org.iiab.controller.ServerController(this, this);   // ADFA-4952
        serverController.start();
        // ADFA-4932: draggable feedback FAB on this screen (screenshot + email).
        org.iiab.controller.feedback.presentation.FeedbackFab.installOn(this, "getmore");
        // ADFA-5023: read reinstall mode from the intent every onCreate (survives a config-change
        // recreation) so the wizard's final install wipes first.
        reinstallMode = getIntent().getBooleanExtra(EXTRA_REINSTALL_SETUP, false);
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
            } else if (reinstallMode) {
                // ADFA-5023: reinstall = the normal first-run wizard, but the final install wipes first.
                org.iiab.controller.system.data.PendingContent.clearAll(this);
                first = new Step1SystemFragment();
            } else {
                // ADFA-4874: a fresh wizard run — drop any wishlist left by an aborted first-run so
                // we never drain stale pre-install picks after a later install. Safe here: the user
                // has not chosen anything yet in this run.
                // ADFA-4954: this now clears Maps too. It was the one type never added when the
                // others were, so an abandoned run's map selection survived into the next one.
                // The maps selector reached from Home takes the mapsSetup branch above and is
                // unaffected.
                org.iiab.controller.system.data.PendingContent.clearAll(this);
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

    // ADFA-5061: these used to read the preference, normalise it and work out what counted
    // as a manual choice, here, on behalf of a screen. The starting point is now resolved by
    // WizardSelectionViewModelFactory and the rule lives with the value; what is left is
    // delegation.
    public String getZimLang() { return selection().contentLang(); }
    /** True when the content language was picked manually (differs from the system default). */
    public boolean isZimLangManual() { return selection().isContentLangManual(); }
    public void setZimLang(String l) { selection().setContentLang(l); }
    /** Re-align the content language to the system/wizard default. */
    public void followSystemLang() { selection().followSystemLang(); }
    // ADFA-4954: the wizard has ONE content language; the fields above are named for
    // ZIM only because ZIM was the first catalog to use them. These neutral aliases let
    // a new content type read the same value without spelling "Zim" inside its own
    // package. Additive on purpose — the existing names stay so ZIM is untouched.
    /** The wizard's content language, shared by every catalog. */
    public String getContentLang() { return getZimLang(); }
    /** True when that language was picked manually rather than followed from the system. */
    public boolean isContentLangManual() { return isZimLangManual(); }
    public void setContentLang(String l) { setZimLang(l); }

    public java.util.LinkedHashMap<String, Long> getZimCart() { return selection().zimCart(); }

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
        // ADFA-5061: nothing to reset. This used to clear four flags so the Confirm screens
        // would download rather than bank; they now ask whether a system exists, and on this
        // path one does.
        androidx.fragment.app.Fragment f;
        if ("maps".equals(key)) f = new MapsLandingFragment();
        else if ("wikipedia".equals(key)) f = new ZimLandingFragment();   // Wikipedia & ZIM content
        else if ("books".equals(key)) f = new BooksLandingFragment();     // ADFA-4850: Books / Gutenberg
        // ADFA-4954: Courses. The same picker the wizard uses — the bundled catalog and Studio's
        // tree need no server, so it works identically here. Only the forward action differs.
        else if ("courses".equals(key))
            f = new org.iiab.controller.kolibri.presentation.KolibriBrowseFragment();
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
        // ADFA-5023: the Continue button had NO debounce; combined with a brief start delay this let a
        // desperate user double/triple-tap, launching duplicate install activities ("two offset screens").
        // Fire exactly once. (The install service also dedupes the actual install via its `started` guard.)
        if (installStarting) return;
        installStarting = true;
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
        // ADFA-5023: reinstall wipes the existing rootfs first. Stopping a LIVE server before the wipe is
        // done by the SERVICE (InstallService.runPipeline) — NOT here — so this navigation stays instant:
        // one tap goes straight to the boot gate instead of the wizard sitting there during stopEnvironment.
        i.putExtra(InstallService.EXTRA_REINSTALL, reinstallMode);
        i.putExtra(InstallService.EXTRA_SKIP_MAPS, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        // ADFA-5023: plain startActivity so a FRESH LibraryActivity is created and reads EXTRA_INSTALLING
        // in onCreate → the boot gate. (An earlier CLEAR_TOP reused the existing Library sitting on the
        // Settings tab, which doesn't re-read the extra via onNewIntent, and dumped the user back on
        // Settings.) Backing out mid-install is prevented by LibraryActivity.onBackPressed, not by
        // clearing the stack.
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
        if ("maps".equals(key)) { openContentType(key, title); return; }
        // ADFA-4954: Courses. No server exists in the wizard, so the picker reads the
        // bundled catalog and banks the order in KolibriWishlist; KolibriProvisioner
        // drains it post-install.
        if ("courses".equals(key)) { openKolibriWizard(); return; }
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, PlaceholderFragment.newInstance(title))
                .addToBackStack("wizard_" + key)
                .commit();
    }

    /**
     * ADFA-5061: whether this run is going to replace the system.
     *
     * <p>The one thing a content screen still has to ask the activity, because it is
     * not observable on the device: during a reinstall the old box stays installed,
     * healthy and answering right up to the moment it is wiped. Read back from the
     * Intent on every {@code onCreate}, so unlike the flags it replaced it survives
     * both a config-change recreation and the process being killed and restored.
     */
    public boolean isReplacingSystem() { return reinstallMode; }

    /**
     * The same answer for a fragment that may or may not be hosted here.
     *
     * <p>Six content screens need it and were each writing the instanceof-and-cast
     * themselves. One expression, one place — an unrecognised host answers "not
     * replacing", and the facts then decide on their own.
     */
    public static boolean replacingSystem(androidx.fragment.app.Fragment f) {
        return f != null && f.getActivity() instanceof SetupLibraryActivity
                && ((SetupLibraryActivity) f.getActivity()).isReplacingSystem();
    }

    /** ADFA-4954: the Courses picker (browse -> confirm), wizard door. */
    public void openKolibriWizard() {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host,
                        new org.iiab.controller.kolibri.presentation.KolibriBrowseFragment())
                .addToBackStack("wizard_courses")
                .commit();
    }

    /**
     * ADFA-4954: the topic picker for one channel. Reached from the chevron on a
     * channel row; the choice it makes lands in the activity-scoped catalog view
     * model, so this only has to put the screen on the stack.
     */
    public void openKolibriTopics(String channelId) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host,
                        org.iiab.controller.kolibri.presentation.KolibriTopicsFragment
                                .forChannel(channelId))
                .addToBackStack("kolibri_topics")
                .commit();
    }

    /**
     * ADFA-4954: the live download's progress screen, reached from the Get More
     * door. The same observe-only fragment the post-install index shows — the
     * download is one mechanism, so it gets one screen; only the way in differs.
     */
    public void openKolibriSeeding() {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host,
                        new org.iiab.controller.kolibri.presentation.KolibriSeedingFragment())
                .addToBackStack("kolibri_seeding")
                .commit();
    }

    /** ADFA-4954: review step of the Courses picker. */
    public void openKolibriConfirm() {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host,
                        new org.iiab.controller.kolibri.presentation.KolibriConfirmFragment())
                .addToBackStack("kolibri_confirm")
                .commit();
    }

    /**
     * ADFA-4954: the order is already written to KolibriWishlist by the confirm
     * screen, so this only unwinds the picker back to the content hub — the same
     * shape as zimWizardConfirm, minus the cart, because the selection lives in the
     * activity-scoped ViewModel rather than in a field here.
     */
    public void kolibriWizardConfirm() {
        getSupportFragmentManager().popBackStack("wizard_courses",
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    /** ADFA-4853: ZIM in wizard mode — same offline browse (kiwix_catalog.csv), but the terminal
     *  step persists the cart to ZimWishlist instead of downloading live. */
    public void openZimWizard() {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, new ZimLandingFragment())
                .addToBackStack("wizard_wikipedia")
                .commit();
    }

    /** ADFA-4853: ZIM Confirm terminal in wizard mode — bank the selection and return to the hub. */
    public void zimWizardConfirm() {
        ZimWishlist.add(this, selection().zimCart());
        selection().zimCart().clear();
        getSupportFragmentManager().popBackStack("wizard_wikipedia",
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    /** ADFA-4853: open Books in wizard mode (pre-install, offline catalog -> wishlist). */
    public void openBooksWizard() {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.k2go_setup_host, BooksLandingFragment.newInstance(true))
                .addToBackStack("wizard_books")
                .commit();
    }

    /** ADFA-4910: the Books selection cart (gutenberg_id -> {title, author, download_url}), set by
     *  the landing when the user taps "Review" and read by BooksConfirmFragment. */
    public java.util.LinkedHashMap<String, String[]> getBooksCart() { return selection().booksCart(); }
    public void setBooksCart(java.util.LinkedHashMap<String, String[]> picks) {
        selection().booksCart().clear();
        if (picks != null) selection().booksCart().putAll(picks);
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
        for (java.util.Map.Entry<String, String[]> e : selection().booksCart().entrySet()) {
            String[] v = e.getValue();
            String title = v != null && v.length > 0 ? v[0] : "";
            String url = v != null && v.length > 2 ? v[2] : "";
            BooksWishlist.add(this, e.getKey(), title, url);
        }
        selection().booksCart().clear();
        getSupportFragmentManager().popBackStack("wizard_books",
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    /** ADFA-4910: Books Confirm terminal in live mode — hand the picks to the download service and
     *  open the downloads screen (per-book checklist + retry). */
    public void startBooksDownload() {
        java.util.List<String> ids = new java.util.ArrayList<>(), titles = new java.util.ArrayList<>(),
                urls = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String[]> e : selection().booksCart().entrySet()) {
            String[] v = e.getValue();
            ids.add(e.getKey());
            titles.add(v != null && v.length > 0 ? v[0] : "");
            urls.add(v != null && v.length > 2 ? v[2] : "");
        }
        selection().booksCart().clear();
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

    /** ADFA-5023: start the install wizard in REINSTALL mode (delete + fresh install). Launched from
     *  the Backup & restore reinstall card, after the destructive confirm. A new activity instance so
     *  the wizard back stack is clean. */
    public void openReinstallWizard() {
        startActivity(new Intent(this, SetupLibraryActivity.class)
                .putExtra(EXTRA_REINSTALL_SETUP, true));
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
