document.addEventListener("DOMContentLoaded", () => {

    // ==========================================================
    // THEME (ADFA-5059): system -> light -> dark, cycled by one
    // subtle icon button; persisted in localStorage; "system"
    // means no data-theme (CSS follows prefers-color-scheme).
    // The pre-paint apply lives inline in <head> to avoid a flash.
    // ==========================================================
    const THEME_KEY = "k2go-theme";
    const THEME_ORDER = ["system", "light", "dark"];
    const THEME_GLYPH = { system: "🌓", light: "☀️", dark: "🌙" };
    let themeMode = localStorage.getItem(THEME_KEY) || "system";

    const themeBtn = document.getElementById("themeToggle");
    const applyTheme = (mode) => {
        const root = document.documentElement;
        if (mode === "light" || mode === "dark") root.setAttribute("data-theme", mode);
        else root.removeAttribute("data-theme");
        if (themeBtn) {
            const ico = themeBtn.querySelector(".theme-ico");
            if (ico) ico.textContent = THEME_GLYPH[mode] || THEME_GLYPH.system;
            const label = (window.i18n && window.i18n["theme_label"]) || "Theme";
            const which = (window.i18n && window.i18n["theme_" + mode]) || mode;
            themeBtn.setAttribute("aria-label", label + ": " + which);
            themeBtn.setAttribute("title", label + ": " + which);
        }
    };
    applyTheme(themeMode);
    if (themeBtn) {
        themeBtn.addEventListener("click", () => {
            themeMode = THEME_ORDER[(THEME_ORDER.indexOf(themeMode) + 1) % THEME_ORDER.length];
            try { localStorage.setItem(THEME_KEY, themeMode); } catch (e) { /* private mode */ }
            applyTheme(themeMode);
        });
    }

    // ==========================================================
    // LANGUAGE (kept): navigator.language -> lang/<code>.js, 33
    // locales, RTL for ar/fa, 'en' as source of truth + fallback.
    // ==========================================================
    let userLang = (navigator.language || navigator.userLanguage || "en").substring(0, 2).toLowerCase();

    const applyTranslations = () => {
        if (!window.i18n) return;
        document.querySelectorAll("[data-i18n]").forEach(el => {
            const key = el.getAttribute("data-i18n");
            if (window.i18n[key]) el.innerText = window.i18n[key];
        });
        applyTheme(themeMode);   // refresh the toggle's translated aria-label
    };

    const supportedLangs = ['en', 'es', 'fr', 'hi', 'ru', 'de', 'it', 'ar', 'ja', 'zh',
        'ko', 'nl', 'tr', 'vi', 'pl', 'cs', 'id', 'fa', 'uk', 'ro', 'el', 'sk', 'bg',
        'sr', 'lt', 'no', 'hu', 'az', 'bn', 'gu', 'ta', 'sw', 'yo'];
    const RTL_LANGS = ['ar', 'fa'];

    // Same cache-bust token index.html carries, so the lang file is refetched after a deploy too.
    const bust = (window.__CACHEBUST__ && window.__CACHEBUST__ !== "__CB_TOKEN__") ? ("?v=" + window.__CACHEBUST__) : "";
    const loadScript = (lang) => {
        const finalLang = supportedLangs.includes(lang) ? lang : 'en';
        document.documentElement.lang = finalLang;
        document.documentElement.dir = RTL_LANGS.includes(finalLang) ? 'rtl' : 'ltr';
        const script = document.createElement('script');
        script.src = `lang/${finalLang}.js${bust}`;
        script.onload = applyTranslations;
        document.head.appendChild(script);
    };
    loadScript(userLang);

    // ==========================================================
    // DISCOVERY + MONITOR (kept): hide anything not installed
    // (404); reveal installed as "connecting" until the server
    // answers; adaptive 5-60s polling; all-down banner.
    // ==========================================================
    const services = {
        'books': '/books/',
        'code': '/code/',
        'kiwix': '/kiwix/',
        'kolibri': '/kolibri/',
        'maps': '/maps/',
        'matomo': '/matomo/',
        'k2go-docs': '/k2go-docs/',
        'dashboard': '/dashboard/'
    };

    const statusBanner = document.getElementById("backend-status");

    const reveal = (btn) => {
        btn.classList.remove("hidden");
        btn.classList.add("disabled");            // starts "connecting" until confirmed
        setTimeout(() => { btn.style.opacity = "1"; }, 10);   // fade-in
    };

    // 1) Discovery: decide which cards exist at all.
    const discoverApps = async () => {
        await Promise.all(Object.entries(services).map(async ([appName, url]) => {
            const btn = document.querySelector(`.card-${appName}`);
            if (!btn) return;
            try {
                const response = await fetch(url, { method: "HEAD", cache: "no-store" });
                if (response.status !== 404) {
                    reveal(btn);                              // installed -> show (connecting)
                } else {
                    btn.classList.add("not-installed");       // absent (e.g. 32-bit, no Kiwix) -> stay hidden
                    delete services[appName];                 // and drop from monitoring
                }
            } catch (error) {
                reveal(btn);                                  // installed but server down -> show as connecting
            }
        }));
    };

    // 2) Adaptive monitor: flip connecting <-> ready; banner when all are down.
    const MIN_INTERVAL = 5000, MAX_INTERVAL = 60000, MULTIPLIER = 1.5;
    let currentInterval = MIN_INTERVAL;

    const checkBackendStatus = async () => {
        let allDown = true;
        for (const [appName, url] of Object.entries(services)) {
            const btn = document.querySelector(`.card-${appName}`);
            if (!btn || btn.classList.contains("not-installed")) continue;
            try {
                const response = await fetch(url, { method: "HEAD", cache: "no-store" });
                if (response.ok) { btn.classList.remove("disabled"); allDown = false; }   // ready
                else { btn.classList.add("disabled"); }                                   // connecting
            } catch (error) { btn.classList.add("disabled"); }
        }
        // Only claim "backend down" when there is actually something to monitor — a box with nothing
        // installed leaves `services` empty, and the loop above wouldn't flip allDown, so without this
        // guard the red banner would show with no monitored services.
        const monitored = Object.keys(services).length;
        if (monitored > 0 && allDown) {
            statusBanner.classList.remove("hidden");
            currentInterval = MIN_INTERVAL;
        } else {
            statusBanner.classList.add("hidden");
            currentInterval = Math.min(currentInterval * MULTIPLIER, MAX_INTERVAL);
        }
        setTimeout(checkBackendStatus, currentInterval);
    };

    (async () => { await discoverApps(); checkBackendStatus(); })();

    // ==========================================================
    // OVERLAY (kept): "Opening <app>…" on tap; hide on back.
    // ==========================================================
    const overlay = document.getElementById('loadingOverlay');
    const textLabel = document.getElementById('loadingText');

    document.querySelectorAll(".card").forEach(btn => {
        btn.addEventListener('click', function (e) {
            if (this.classList.contains('disabled') || this.classList.contains('not-installed')) {
                e.preventDefault();
                return;
            }
            const nameEl = this.querySelector('.card-name');
            const name = nameEl ? nameEl.innerText.trim() : '';
            const opening = (window.i18n && window.i18n['opening']) || 'Opening';
            textLabel.innerText = name ? (opening + ' ' + name + '…') : opening + '…';
            overlay.style.display = 'flex';
        });
    });

    window.addEventListener('pageshow', () => { overlay.style.display = 'none'; });
});
