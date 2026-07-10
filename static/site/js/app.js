document.addEventListener("DOMContentLoaded", () => {

    // 1. Start language system
    let userLang = (navigator.language || navigator.userLanguage).substring(0, 2).toLowerCase();

    // Function to apply the translations to the HTML
    const applyTranslations = () => {
        if (!window.i18n) return;

        const elements = document.querySelectorAll('[data-i18n]');
        elements.forEach(el => {
            const key = el.getAttribute('data-i18n');
            if (window.i18n[key]) {
                el.innerText = window.i18n[key];
            }
        });
    };

    // Locales the content page ships (kept in parity with the Android app).
    // 'en' is the source of truth and the fallback for any missing locale.
    const supportedLangs = ['en', 'es', 'fr', 'hi', 'ru', 'de', 'it', 'ar', 'ja', 'zh',
        'ko', 'nl', 'tr', 'vi', 'pl', 'cs', 'id', 'fa', 'uk', 'ro', 'el', 'sk', 'bg',
        'sr', 'lt', 'no', 'hu', 'az', 'bn', 'gu', 'ta', 'sw', 'yo'];
    const RTL_LANGS = ['ar', 'fa'];

    // Function to load the language file dynamically
    const loadScript = (lang) => {
        const script = document.createElement('script');
        // Fallback to 'en' if the user's language file doesn't exist
        const finalLang = supportedLangs.includes(lang) ? lang : 'en';

        // Reflect the active locale on <html> so :lang() styling and assistive tech
        // announce the right language, and flip direction for RTL scripts.
        document.documentElement.lang = finalLang;
        document.documentElement.dir = RTL_LANGS.includes(finalLang) ? 'rtl' : 'ltr';

        script.src = `lang/${finalLang}.js`;
        script.onload = applyTranslations;
        document.head.appendChild(script);
    };

    // Execute the language loader
    loadScript(userLang);

    // ==========================================
    // MONITORING AND DISCOVERY LOGIC
    // ==========================================

    const services = {
        'books': '/books/',
        'code': '/code/',
        'kiwix': '/kiwix/',
        'kolibri': '/kolibri/',
        'maps': '/maps/',
        'matomo': '/matomo/',
        'dashboard': '/dashboard/'
    };

    const statusBanner = document.getElementById("backend-status");
    const appButtons = document.querySelectorAll(".btn");

    // 2. Intelligent Discovery
    const discoverApps = async () => {
        const promises = Object.entries(services).map(async ([appName, url]) => {
            const btn = document.querySelector(`.btn-${appName}`);
            if (!btn) return;

            try {
                // Perform a quick ping
                const response = await fetch(url, { method: "HEAD", cache: "no-store" });

                if (response.status !== 404) {
                    // IF IT EXISTS: Show it in the UI, but initially "disabled"
                    btn.style.display = "flex";
                    btn.classList.add("disabled");

                    // Quick trick to force DOM reflow so the CSS opacity transition triggers
                    setTimeout(() => btn.style.opacity = "1", 10);
                } else {
                    // DOES NOT EXIST (e.g., 32-bits without Kiwix): Keep it hidden and remove from monitoring
                    btn.classList.add("not-installed");
                    delete services[appName];
                }
            } catch (error) {
                // If the network fails (server is down but the app is installed)
                // Reveal it as disabled so the user knows the app exists
                // and let the continuous monitor attempt to revive it
                btn.style.display = "flex";
                btn.classList.add("disabled");
                setTimeout(() => btn.style.opacity = "1", 10);
            }
        });

        // Wait for all pings to finish concurrently
        await Promise.all(promises);
    };

    // 3. Smart Monitor (Adaptive Polling)
    const MIN_INTERVAL = 5000;
    const MAX_INTERVAL = 60000;
    const MULTIPLIER = 1.5;
    let currentInterval = MIN_INTERVAL;

    const checkBackendStatus = async () => {
        let isServerTotallyDown = true;

        for (const [appName, url] of Object.entries(services)) {
            const btn = document.querySelector(`.btn-${appName}`);
            if (!btn || btn.classList.contains("not-installed")) continue;

            try {
                const response = await fetch(url, { method: "HEAD", cache: "no-store" });

                if (response.ok) {
                    btn.classList.remove("disabled");
                    isServerTotallyDown = false;
                } else {
                    btn.classList.add("disabled");
                }
            } catch (error) {
                btn.classList.add("disabled");
            }
        }

        // Red Banner Logic and Time Control
        if (isServerTotallyDown) {
            statusBanner.style.display = "block";
            statusBanner.classList.remove("hidden");
            currentInterval = MIN_INTERVAL; 
        } else {
            statusBanner.style.display = "none";
            statusBanner.classList.add("hidden");
            currentInterval = Math.min(currentInterval * MULTIPLIER, MAX_INTERVAL); 
        }

        setTimeout(checkBackendStatus, currentInterval);
    };

    // 4. BOOT SEQUENCE
    const init = async () => {
        await discoverApps();    
        checkBackendStatus();    
    };

    init(); 

    // ==========================================
    // 5. INTERFACE LOGIC (OVERLAY)
    // ==========================================
    const overlay = document.getElementById('loadingOverlay');
    const textLabel = document.getElementById('loadingText');

    appButtons.forEach(btn => {
        btn.addEventListener('click', function(e) {
            if (this.classList.contains('disabled') || this.classList.contains('not-installed')) {
                e.preventDefault();
                return;
            }

            const appName = this.innerText.replace(/[\uD800-\uDBFF][\uDC00-\uDFFF]|\uD83C[\uDF00-\uDFFF]|\uD83D[\uDC00-\uDE4F]/g, '').trim();
            textLabel.innerText = 'Opening ' + appName + '...';
            overlay.style.display = 'flex';
        });
    });

    window.addEventListener('pageshow', function(event) {
        overlay.style.display = 'none';
    });
});
