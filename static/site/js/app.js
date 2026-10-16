document.addEventListener("DOMContentLoaded", () => {

    // ==========================================================
    // THEME (ADFA-5059): system -> light -> dark, cycled by one
    // subtle icon button; persisted in localStorage; "system"
    // means no data-theme (CSS follows prefers-color-scheme).
    // The pre-paint apply lives inline in <head> to avoid a flash.
    // ==========================================================
    const THEME_KEY = "k2go-theme";
    const THEME_ORDER = ["system", "light", "dark"];
    // K2GO-472: Material 3 icon paths (24x24, currentColor): contrast (system) / light_mode / dark_mode.
    // applyTheme swaps only the <path d> so the SVG wrapper and sizing stay put.
    const THEME_PATH = {
        system: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18V4c4.41 0 8 3.59 8 8s-3.59 8-8 8z",
        light: "M12 7c-2.76 0-5 2.24-5 5s2.24 5 5 5 5-2.24 5-5-2.24-5-5-5zm0 8c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3zM2 13h2c.55 0 1-.45 1-1s-.45-1-1-1H2c-.55 0-1 .45-1 1s.45 1 1 1zm18 0h2c.55 0 1-.45 1-1s-.45-1-1-1h-2c-.55 0-1 .45-1 1s.45 1 1 1zM11 2v2c0 .55.45 1 1 1s1-.45 1-1V2c0-.55-.45-1-1-1s-1 .45-1 1zm0 18v2c0 .55.45 1 1 1s1-.45 1-1v-2c0-.55-.45-1-1-1s-1 .45-1 1zM5.99 4.58c-.39-.39-1.03-.39-1.41 0-.39.39-.39 1.03 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0s.39-1.03 0-1.41L5.99 4.58zm12.37 12.37c-.39-.39-1.03-.39-1.41 0-.39.39-.39 1.03 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0 .39-.39.39-1.03 0-1.41l-1.06-1.06zm1.06-10.96c.39-.39.39-1.03 0-1.41-.39-.39-1.03-.39-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0l1.06-1.06zM7.05 18.36c.39-.39.39-1.03 0-1.41-.39-.39-1.03-.39-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0l1.06-1.06z",
        dark: "M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9 9-4.03 9-9c0-.46-.04-.92-.1-1.36-.98 1.37-2.58 2.26-4.4 2.26-2.98 0-5.4-2.42-5.4-5.4 0-1.81.89-3.42 2.26-4.4-.44-.06-.9-.1-1.36-.1z"
    };
    let themeMode = localStorage.getItem(THEME_KEY) || "system";

    const themeBtn = document.getElementById("themeToggle");
    const applyTheme = (mode) => {
        const root = document.documentElement;
        if (mode === "light" || mode === "dark") root.setAttribute("data-theme", mode);
        else root.removeAttribute("data-theme");
        if (themeBtn) {
            const p = themeBtn.querySelector(".theme-ico path");
            if (p) p.setAttribute("d", THEME_PATH[mode] || THEME_PATH.system);
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
        'code-addons': '/code-addons/',   // K2GO-472: card revealed only when installed (404 -> hidden)
        'code-assets': '/code-assets/',   // K2GO-472
        'forgejo': '/forgejo/',
        'kiwix': '/kiwix/',
        'kolibri': '/kolibri/',
        'maps': '/maps/',
        'matomo': '/matomo/',
        'k2go-docs': '/k2go-docs/'
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
