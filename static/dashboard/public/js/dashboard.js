// ==========================================
// K2Go Dashboard - Core Logic
// ==========================================

const socket = io();

// --- Theme Manager ---
const themeManager = {
    init() {
        const savedTheme = localStorage.getItem('theme') || 'auto';
        this.setTheme(savedTheme);
    },
    setTheme(theme) {
        const root = document.documentElement;
        if (theme === 'auto') {
            const systemTheme = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
            root.setAttribute('data-bs-theme', systemTheme);
        } else {
            root.setAttribute('data-bs-theme', theme);
        }
        localStorage.setItem('theme', theme);
        this.updateToggleButton(theme);
    },
    cycleTheme() {
        const current = localStorage.getItem('theme') || 'auto';
        const next = current === 'light' ? 'dark' : (current === 'dark' ? 'auto' : 'light');
        this.setTheme(next);
    },
    updateToggleButton(theme) {
        const iconSpan = document.getElementById('theme-toggle-icon');
        if (!iconSpan) return;
        const icons = { light: '☀️', dark: '🌙', auto: '🌓' };
        iconSpan.innerText = icons[theme] || icons.auto;
    }
};

// --- i18n System ---
// Locales the dashboard ships (kept in parity with the Android app). 'en' is the
// source of truth and the fallback for any missing locale/key.
const SUPPORTED_LANGS = ['en', 'es', 'fr', 'hi', 'pt', 'ru'];

// Translate a key, falling back to the given English text (or the key) when the active
// locale is missing that key. Safe for dynamically-rendered strings (see maps/kiwix JS).
window.t = (key, fallback) => (window.i18n && window.i18n[key]) || fallback || key;

const applyTranslations = () => {
    if (!window.i18n) return;
    // Text content (default): only overwrite when the key exists, so a missing key keeps
    // the element's built-in English text.
    document.querySelectorAll('[data-i18n]').forEach(el => {
        const key = el.getAttribute('data-i18n');
        if (window.i18n[key]) el.innerText = window.i18n[key];
    });
    // Attribute variants: placeholder and title.
    document.querySelectorAll('[data-i18n-placeholder]').forEach(el => {
        const key = el.getAttribute('data-i18n-placeholder');
        if (window.i18n[key]) el.setAttribute('placeholder', window.i18n[key]);
    });
    document.querySelectorAll('[data-i18n-title]').forEach(el => {
        const key = el.getAttribute('data-i18n-title');
        if (window.i18n[key]) el.setAttribute('title', window.i18n[key]);
    });
};

const loadLanguage = () => {
    const userLang = (navigator.language || navigator.userLanguage || 'en').substring(0, 2).toLowerCase();
    const lang = SUPPORTED_LANGS.includes(userLang) ? userLang : 'en';
    const script = document.createElement('script');
    script.src = `lang/${lang}.js`;
    // If the locale file fails to load, fall back to English so the UI is never blank.
    script.onload = applyTranslations;
    script.onerror = () => {
        const fallback = document.createElement('script');
        fallback.src = 'lang/en.js';
        fallback.onload = applyTranslations;
        document.head.appendChild(fallback);
    };
    document.head.appendChild(script);
};

// --- Tabs Logic ---
function switchTab(tabName) {
    // Turn off ONLY the main tabs
    document.querySelectorAll('[id^="tab-"]').forEach(el => el.classList.remove('active'));

    // Hide all app panels
    document.querySelectorAll('.app-panel').forEach(el => el.classList.add('hidden-section'));

    // Turn on the clicked main tab and its panel
    const activeTab = document.getElementById(`tab-${tabName}`);
    if (activeTab) activeTab.classList.add('active');

    const activePanel = document.getElementById(`panel-${tabName}`);
    if (activePanel) activePanel.classList.remove('hidden-section');

    // If we enter the books panel, force the correct sub-tab to be highlighted
    if (tabName === 'books') {
        // activeBookTab always remembers which sub-tab you were on (defaults to 'local')
        document.getElementById(`nav-${activeBookTab === 'educational' ? 'edu' : activeBookTab}`).classList.add('active');
    }
}

document.addEventListener("DOMContentLoaded", () => {
    themeManager.init();
    loadLanguage();
    switchTab('home');
    discoverDashboardModules();
    floatingMenuManager.init();
});

// ==========================================
// APP: Books Manager Logic
// ==========================================

let activeBookTab = 'local';
let remoteBooksData = [];
let localBooksData = [];
let selectedRemoteBooks = [];
let activeDownloads = 0;
let pendingCalibreAction = null;
const MAX_DOWNLOADS = 5;
let selectedRemoteBook = null; 
let catalogIsSynced = false;

// --- Synchronization Engine Listeners ---
socket.on('books_sync_status', (data) => {
    const container = document.getElementById('books-content-container');
    if (data.status === 'checking' || data.status === 'downloading') {
        container.innerHTML = `
            <div class="text-center text-info py-5">
                <div class="spinner-border spinner-border-sm me-2"></div>${data.msg}
            </div>`;
    }
    else if (data.status === 'error' && !catalogIsSynced) {
        setTimeout(() => switchBookTab('local'), 2000);
    }
});

socket.on('books_ready_to_load', () => {
    catalogIsSynced = true;
    switchBookTab('local'); // Default to Local books after sync
});

// --- Tab Navigation ---
function switchBookTab(tabId) {
    activeBookTab = tabId;

    document.querySelectorAll('#books-tab-nav .nav-link').forEach(el => el.classList.remove('active'));
    document.getElementById(`nav-${tabId === 'educational' ? 'edu' : tabId}`).classList.add('active');
    document.getElementById('global-book-search').value = '';

    const actionBar = document.getElementById('books-action-bar');
    if (tabId === 'local') {
        actionBar.classList.add('hidden-section');
        socket.emit('request_local_books');
    } else {
        actionBar.classList.remove('hidden-section');
        socket.emit('request_books', { filter: tabId, query: '' });
    }
}

// --- Search Router ---
function handleGlobalSearch(query) {
    if (query.length > 0 && query.length < 3) return;

    if (activeBookTab === 'local') {
        const filtered = localBooksData.filter(b =>
            b.title.toLowerCase().includes(query.toLowerCase()) ||
            (b.author && b.author.toLowerCase().includes(query.toLowerCase()))
        );
        renderLocalBooks(filtered);
    } else {
        socket.emit('request_books', { filter: 'search', query: query });
    }
}

// ==========================================
// RENDER: LOCAL BOOKS (Rich Cards)
// ==========================================
socket.on('local_books_ready', (books) => {
    localBooksData = books;
    if (activeBookTab === 'local') {
        renderLocalBooks(books);
        } else if (remoteBooksData.length > 0) {
        renderRemoteBooks(remoteBooksData);
    }
});

socket.on('refresh_local_books', () => {
    socket.emit('request_local_books');
});

function renderLocalBooks(books) {
    const container = document.getElementById('books-content-container');
    container.className = "row row-cols-1 row-cols-sm-2 row-cols-md-2 row-cols-lg-3 row-cols-xl-4 g-4";
    container.innerHTML = '';

    if (books.length === 0) {
        container.innerHTML = `<div class="col-12 text-center text-secondary py-5">${window.t('books_no_local','No local books found. Check the Top 100 tab!')}</div>`;
        return;
    }

    books.forEach(book => {
        const coverUrl = `/books/cover/${book.id}`;
        const readUrl = `/books/read/${book.id}/epub`;

        const card = `
            <div class="col" id="local-card-${book.id}">
                <div class="card h-100 border-0 shadow-sm zim-card">
                    <img src="${coverUrl}" onerror="this.src='https://via.placeholder.com/300x450?text=No+Cover'" class="card-img-top" style="height: 250px; object-fit: cover; border-radius: 8px 8px 0 0;">
                    <div class="card-body p-3 d-flex flex-column">
                        <h6 class="fw-bold mb-1 text-truncate" title="${book.title}">${book.title}</h6>
                        <small class="text-secondary mb-2">${book.author || window.t('common_unknown','Unknown')} • ${book.year || window.t('common_na','N/A')}</small>
                        <div class="mt-auto d-flex gap-2">
                            <a href="${readUrl}" target="_blank" class="btn btn-sm btn-success flex-grow-1 fw-bold">${window.t('books_read','Read')}</a>
                            <button id="btn-del-${book.id}" class="btn btn-sm btn-outline-danger" onclick="deleteLocalBook(${book.id})">🗑️</button>
                        </div>
                    </div>
                </div>
            </div>
        `;
        container.innerHTML += card;
    });
}

function deleteLocalBook(id) {
    if (confirm(window.t('books_delete_confirm','Are you sure you want to delete this book?'))) {
        console.log(`[Dashboard] 🗑️ Starting deletion of workbook ID: ${id}`);

        // We give visual feedback: We change the trash can for a spinner
        const btn = document.getElementById(`btn-del-${id}`);
        if (btn) {
            btn.innerHTML = '<span class="spinner-border spinner-border-sm"></span>';
            btn.classList.add('disabled');
        }

        pendingCalibreAction = { type: 'delete', payload: id };
        socket.emit('delete_local_book', id);
        console.log(`[Dashboard] 📡 Signal 'delete_local_book' sent to the Node.js server`);
    }
}

// ==========================================
// RENDER: REMOTE BOOKS (Text List w/ Checkboxes)
// ==========================================
socket.on('books_list_ready', (books) => {
    remoteBooksData = books;
    if (activeBookTab !== 'local') renderRemoteBooks(books);
});

function renderRemoteBooks(books) {
    const container = document.getElementById('books-content-container');
    container.className = "list-group shadow-sm";
    container.innerHTML = '';

    if (books.length === 0) {
        container.innerHTML = `<div class="list-group-item text-center text-secondary py-5 border-0">${window.t('books_no_remote','No books found in remote catalog.')}</div>`;
        return;
    }

    books.forEach(book => {
        const localMatch = localBooksData.find(lb => lb.title.toLowerCase() === book.title.toLowerCase());
        const isChecked = selectedRemoteBooks.includes(book.gutenberg_id) ? 'checked' : '';
        const isLocalDisabled = localMatch ? 'disabled' : '';
        const opacity = localMatch ? 'opacity-75' : '';
        const badgeText = localMatch ? window.t('books_badge_local','Local') : window.t('books_badge_info','Info');
        const badgeClass = localMatch ? 'bg-success' : 'bg-primary';
        const localIdParam = localMatch ? localMatch.id : 'null';

        const listItem = `
            <label class="list-group-item list-group-item-action d-flex align-items-center py-3 border-secondary cursor-pointer ${opacity}">
                <input class="form-check-input me-3 mt-0 flex-shrink-0" type="checkbox" value="${book.gutenberg_id}" onchange="toggleBookSelection(this, '${book.gutenberg_id}')" ${isChecked} ${isLocalDisabled}>

                <div class="flex-grow-1" style="min-width: 0;">
                    <h6 class="mb-1 fw-bold text-primary text-truncate">${book.title}</h6>
                    <div class="d-flex justify-content-between align-items-center">
                        <small class="text-secondary text-truncate pe-2">${book.author}</small>
                        <span class="badge ${badgeClass} rounded-pill flex-shrink-0" onclick="event.preventDefault(); openBookModal('${book.gutenberg_id}', ${localIdParam})">${badgeText}</span>
                    </div>
                </div>
            </label>
        `;
        container.innerHTML += listItem;
    });
}

// --- Checkbox & Batch Download Logic ---
function toggleBookSelection(checkbox, bookId) {
    if (checkbox.checked) {
        if (selectedRemoteBooks.length + activeDownloads >= MAX_DOWNLOADS) {
            checkbox.checked = false;
            const modal = new bootstrap.Modal(document.getElementById('bookLimitWarningModal'));
            modal.show();
            return;
        }
        selectedRemoteBooks.push(bookId);
    } else {
        selectedRemoteBooks = selectedRemoteBooks.filter(id => id !== bookId);
    }
    updateBookDownloadBtn();
}

function updateBookDownloadBtn() {
    const btn = document.getElementById('books-batch-download-btn');
    if (selectedRemoteBooks.length > 0) {
        btn.classList.remove('disabled');
        btn.innerText = `${window.t('books_download_btn','DOWNLOAD')} (${selectedRemoteBooks.length})`;
    } else {
        btn.classList.add('disabled');
        btn.innerText = window.t('books_download_btn','DOWNLOAD');
    }
}

function downloadSelectedBooks() {
    if (selectedRemoteBooks.length === 0) return;

    const booksToDownload = selectedRemoteBooks.map(id => {
        const b = remoteBooksData.find(b => b.gutenberg_id === id);
        if (b) {
            return {
                id: b.gutenberg_id,
                title: b.title,
                url: b.download_url
            };
        }
        return undefined;
    }).filter(b => b !== undefined);

    activeDownloads += booksToDownload.length;

    const btn = document.getElementById('books-batch-download-btn');
    btn.classList.add('disabled');
    btn.innerHTML = `<span class="spinner-border spinner-border-sm me-2"></span>${window.t('books_downloading','DOWNLOADING...')}`;

    pendingCalibreAction = { type: 'download', payload: booksToDownload };
    socket.emit('download_books_batch', booksToDownload);

    selectedRemoteBooks = [];

    setTimeout(() => {
        switchBookTab('local');
        window.scrollTo({ top: 0, behavior: 'smooth' });
        updateBookDownloadBtn();
    }, 1500);
}

// --- Security & Text Formatting ---
function formatBookDescription(text) {
    if (!text || text === 'No description available for this book.') {
        return window.t('books_no_description','No description available for this book.');
    }

    // Escape html to prevent XSS
    const escapeHTML = (str) => {
        return str.replace(/[&<>'"]/g, tag => ({
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            "'": '&#39;',
            '"': '&quot;'
        }[tag] || tag));
    };

    let safeText = escapeHTML(text);

    // Safe regex site/domain to parse urls
    const wikiRegex = /(https:\/\/[a-zA-Z0-9-]+\.wikipedia\.org\/[^\s)\]'".]+)/g;

    safeText = safeText.replace(wikiRegex, (url) => {
        return `<a href="${url}" target="_blank" rel="noopener noreferrer" class="text-primary text-decoration-underline fw-bold">${url}</a>`;
    });

    // Translate new lines to html
    return safeText.replace(/\n/g, '<br>');
}

// --- The Modal Experience ---
function openBookModal(id, localBookId = null) {
    const book = remoteBooksData.find(b => b.gutenberg_id === id);
    if (!book) return;

    selectedRemoteBook = book;

    const coverUrl = `https://www.gutenberg.org/cache/epub/${book.gutenberg_id}/pg${book.gutenberg_id}.cover.medium.jpg`;

    document.getElementById('modal-cover-img').src = coverUrl;
    document.getElementById('modal-title').innerText = book.title;
    document.getElementById('modal-author').innerText = book.author;
    document.getElementById('modal-desc').innerHTML = formatBookDescription(book.description);

    const btn = document.getElementById('modal-download-btn');

    if (localBookId) {
        btn.className = "btn btn-success w-100 fw-bold text-uppercase";
        btn.innerText = window.t('books_read_calibre','📖 Read in Calibre-Web');
        btn.onclick = function() {
            window.open(`/books/read/${localBookId}/epub`, '_blank');
            const modalEl = document.getElementById('bookDetailsModal');
            bootstrap.Modal.getInstance(modalEl).hide();
        };
    } else if (activeDownloads >= MAX_DOWNLOADS) {
        btn.className = "btn btn-secondary w-100 fw-bold disabled text-uppercase";
        btn.innerText = window.t('books_queue_full','⏳ Queue Full (Wait for current downloads)');
        btn.onclick = null;
    } else {
        btn.className = "btn btn-primary w-100 fw-bold text-uppercase";
        btn.innerText = window.t('books_download_calibre','📥 Download & Add to Calibre-Web');
        btn.onclick = function() {
            startDownloadFromModal();
        };
    }

    const modalEl = document.getElementById('bookDetailsModal');
    const modal = bootstrap.Modal.getOrCreateInstance(modalEl);
    modal.show();
}

function startDownloadFromModal() {
    if (!selectedRemoteBook || activeDownloads >= MAX_DOWNLOADS) return;

    activeDownloads++;

    const modalEl = document.getElementById('bookDetailsModal');
    const modal = bootstrap.Modal.getOrCreateInstance(modalEl);
    modal.hide();

    const bookData = [{
        id: selectedRemoteBook.gutenberg_id,
        title: selectedRemoteBook.title,
        url: selectedRemoteBook.download_url
    }];

    pendingCalibreAction = { type: 'download', payload: bookData };
    socket.emit('download_books_batch', bookData);

    const btn = document.getElementById('books-batch-download-btn');
    if (btn) {
        btn.classList.remove('disabled');
        btn.innerHTML = `<span class="spinner-border spinner-border-sm me-2"></span>${window.t('books_downloading','DOWNLOADING...')}`;
    }

    setTimeout(() => {
        switchBookTab('local');
        window.scrollTo({ top: 0, behavior: 'smooth' });
        updateBookDownloadBtn();
    }, 1000);
}

// --- Status Updates ---
socket.on('book_status_update', (data) => {
    if (data.status === 'completed' || data.status === 'error') {
        if (activeDownloads > 0) activeDownloads--;
    }

    if (data.status === 'error') {
        alert(`${window.t('books_error_alert','❌ The book download or upload failed.')}\n\n${window.t('books_error_reason','Server Reason:')} ${data.message}\n\n${window.t('books_error_hint','Make sure you are not logged in multiple tabs at once.')}`);
    }
});

// Initialization
socket.on('connect', () => {
    console.log('[Socket] Connected to server. Triggering catalog sync...');
    socket.emit('request_local_books');
    socket.emit('trigger_catalog_sync');
});

// ==========================================
// CALIBRE-WEB FALLBACK AUTHENTICATION
// ==========================================

// 1. Listen for auth failure from the backend
socket.on('calibre_auth_required', () => {
    // Hide the download spinner/status if it's stuck
    const batchBtn = document.getElementById('books-batch-download-btn');
    if (batchBtn && activeDownloads > 0) {
        batchBtn.classList.remove('disabled');
        batchBtn.innerText = `${window.t('books_download_btn','DOWNLOAD')} (${selectedRemoteBooks.length || activeDownloads})`;
        // Reset the active downloads since the batch failed
        activeDownloads = 0;
    }

    // Show the login modal
    const loginModal = new bootstrap.Modal(document.getElementById('calibreLoginModal'));
    loginModal.show();
});

// 2. Submit new credentials
function submitCalibreLogin() {
    const user = document.getElementById('calibre-user-input').value.trim();
    const pass = document.getElementById('calibre-pass-input').value.trim();

    if (!user || !pass) {
        alert(window.t('calibre_enter_both','Please enter both username and password.'));
        return;
    }

    // Send to backend
    socket.emit('update_calibre_credentials', { user: user, pass: pass });

    // Hide Modal
    const modalInstance = bootstrap.Modal.getInstance(document.getElementById('calibreLoginModal'));
    modalInstance.hide();

    // Clear the password field for security
    document.getElementById('calibre-pass-input').value = '';
}

// 3. Listen for update confirmation and auto-retry the pending action
socket.on('calibre_auth_updated', () => {
    // We hide the modal just in case it wasn't hidden properly
    const modal = bootstrap.Modal.getInstance(document.getElementById('calibreLoginModal'));
    if (modal) modal.hide();

    if (pendingCalibreAction) {
        console.log("Auto-retrying pending action:", pendingCalibreAction.type);

        // We relaunch the "Loading..." interface if it was a download
        if (pendingCalibreAction.type === 'download') {
            const btn = document.getElementById('books-batch-download-btn');
            if (btn) {
                btn.classList.add('disabled');
                btn.innerHTML = `<span class="spinner-border spinner-border-sm me-2"></span>${window.t('books_downloading','DOWNLOADING...')}`;
            }
            activeDownloads += pendingCalibreAction.payload.length;
            socket.emit('download_books_batch', pendingCalibreAction.payload);

            setTimeout(() => switchBookTab('local'), 1000);
        }
        // We resend the order if it was to delete
        else if (pendingCalibreAction.type === 'delete') {
            socket.emit('delete_local_book', pendingCalibreAction.payload);
        }

        // We clear the memory so that it doesn't happen again.
        pendingCalibreAction = null;
    }
});

// 4. Toggle Password Visibility
function togglePasswordVisibility() {
    const passInput = document.getElementById('calibre-pass-input');
    const toggleBtn = document.getElementById('toggle-pass-btn');

    if (passInput.type === 'password') {
        passInput.type = 'text';
        toggleBtn.innerText = '🙈'; // Ojo cerrado
    } else {
        passInput.type = 'password';
        toggleBtn.innerText = '👁️'; // Ojo abierto
    }
}

// ==========================================
// MOBILE FLOATING MENU MANAGER (Advanced Physics)
// ==========================================
const floatingMenuManager = {
	breakpoint: 576,
    init() {
        this.menu = document.querySelector('.app-sidebar');
        if (!this.menu) return;

        this.isDragging = false;
        this.edge = 'right';
        this.inactivityTimeout = null;
        this.velocityX = 0; // To measure if the user performs a hard swipe

        if (window.innerWidth >= this.breakpoint) return;

        this.menu.addEventListener('pointerdown', this.onPointerDown.bind(this));
        document.addEventListener('pointermove', this.onPointerMove.bind(this));
        document.addEventListener('pointerup', this.onPointerUp.bind(this));
        document.addEventListener('pointercancel', this.onPointerUp.bind(this));
        window.addEventListener('resize', this.snapToEdge.bind(this));
        this.menu.addEventListener('click', () => this.resetTimer());

        // Initialize snapped to one edge on load
        this.snapToEdge();
        this.resetTimer();
    },

    onPointerDown(e) {
        if (e.target.closest('a') || e.target.closest('button')) return;

        this.isDragging = true;
        this.velocityX = 0;

        // Remove edge fusion and hidden state on grab (returns to floating pill)
        this.menu.classList.remove('collapsed-left', 'collapsed-right', 'fused-left', 'fused-right');
        this.menu.classList.add('dragging');

        this.menu.setPointerCapture(e.pointerId);
        clearTimeout(this.inactivityTimeout);
    },

    onPointerMove(e) {
        if (!this.isDragging) return;

        // Capture velocity and direction (positive=right, negative=left)
        this.velocityX = e.movementX;

        let newX = e.clientX - (this.menu.offsetWidth / 2);
        this.menu.style.left = `${newX}px`;
    },

onPointerUp(e) {
        if (!this.isDragging) return;
        this.isDragging = false;
        this.menu.classList.remove('dragging');
        this.menu.releasePointerCapture(e.pointerId);

        const screenWidth = window.innerWidth;

        // 1. "Smash" Detection (Throw by velocity)
        // Evaluate if the user forcefully threw the menu to one side
        const isSmashLeft = this.velocityX < -15;
        const isSmashRight = this.velocityX > 15;
        let isSmash = false;

        // 2. Evaluate the exact physical position of the pill's center
        const menuRect = this.menu.getBoundingClientRect();
        const menuCenterX = menuRect.left + (menuRect.width / 2);

        // 3. DECISION MAKING
        if (isSmashLeft) {
            this.edge = 'left';
            isSmash = true;
        } else if (isSmashRight) {
            this.edge = 'right';
            isSmash = true;
        } else {
            // IF NOT THROWN: Apply the 50% screen rule
            // If the pill's center crosses the middle of the screen, switch sides
            if (menuCenterX < screenWidth / 2) {
                this.edge = 'left';
            } else {
                this.edge = 'right';
            }
        }

        // Execute movement and timer
        this.snapToEdge();
        this.resetTimer(isSmash);
    },

snapToEdge() {
        // 1. KILL SWITCH (Landscape or Desktop)
        if (window.innerWidth >= this.breakpoint) {
            this.menu.style.left = '';
            this.menu.style.transform = ''; // Clear transformations
            this.menu.classList.remove('fused-left', 'fused-right', 'collapsed-left', 'collapsed-right', 'dragging');
            return;
        }

        // 2. NORMAL MOBILE LOGIC
        const screenWidth = window.innerWidth;

        if (this.edge === 'left') {
            this.menu.style.left = '0px';
            this.menu.classList.add('fused-left');
            this.menu.classList.remove('fused-right');
        } else {
            this.menu.style.left = `${screenWidth - this.menu.offsetWidth}px`;
            this.menu.classList.add('fused-right');
            this.menu.classList.remove('fused-left');
        }
    },

    resetTimer(isSmash = false) {
        // On Landscape or PC, clear the timer so it never collapses
        if (window.innerWidth >= this.breakpoint) {
            clearTimeout(this.inactivityTimeout);
            return;
        }

        clearTimeout(this.inactivityTimeout);
        this.menu.classList.remove('collapsed-left', 'collapsed-right');

        const timeoutDuration = isSmash ? 200 : 4000;

        this.inactivityTimeout = setTimeout(() => {
            if (!this.isDragging && window.matchMedia("(orientation: portrait)").matches) {
                this.menu.classList.add(this.edge === 'left' ? 'collapsed-left' : 'collapsed-right');
            }
        }, timeoutDuration);
    }
};

// ==========================================
// MODULE DISCOVERY LOGIC
// ==========================================
const discoverDashboardModules = async () => {
    // Only check modules that we know might not be installed
    const modules = ['kiwix', 'maps', 'books'];

    const promises = modules.map(async (mod) => {
        try {
            const response = await fetch(`/${mod}/`, { method: "HEAD", cache: "no-store" });

            if (response.status !== 404) {
                // Module exists, reveal its card and sidebar button
                const card = document.getElementById(`card-${mod}`);
                const tabBtn = document.getElementById(`tab-${mod}`); // Assuming the sidebar button has this ID format

                if (card) card.style.display = 'block';
                if (tabBtn) tabBtn.style.display = 'flex';
            }
        } catch (error) {
            // If there's a network error (container is down), reveal it anyway
            // assuming it is installed, just offline.
            const card = document.getElementById(`card-${mod}`);
            const tabBtn = document.getElementById(`tab-${mod}`);
            if (card) card.style.display = 'block';
            if (tabBtn) tabBtn.style.display = 'flex';
        }
    });

    await Promise.all(promises);
};
