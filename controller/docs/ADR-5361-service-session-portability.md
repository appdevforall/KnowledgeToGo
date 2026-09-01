# ADR-5361 — A service session belongs to the agent that will use it; the box mints it for that agent

**Status:** accepted — implemented in ADFA-5361 (app + dash-node 1.2.11), device-verified.

**Scope note (form):** genericized per the ADR authoring convention — no personal names, no specific
device identifiers. "the test device" = the Android target the measurements were taken on; "the box"
= the on-device server tree (nginx :8085 fronting the content services and the dash-node REST core).

---

## 1. The bug, stated as a missing fact

ADFA-5043 gave the Books and Courses cards an auto-login: the app asks the box for a signed-in
session, the box logs in server-side with the stored admin credentials, and the app injects the
returned cookies into the WebView before loading the page. The reported symptom was that adding
books through Get More left the library in read-only Guest mode, permanently — the device owner
could not manage books they had just added themselves.

The missing fact is not "the cookie is lost" and not "Get More breaks something". It is:

> **A Calibre-Web session is bound to the agent that created it. The mint had no idea who would use it.**

Flask-Login binds a session to a fingerprint of the request's `User-Agent` (and address). The box
minted under its own agent; the WebView presented the cookie under a different one; the first
request therefore had the session rejected, the `remember_token` **deleted**, and a fresh
**anonymous** session issued in its place.

Which means the auto-login never delivered admin at all. It looked like it did — see §2.4 — and that
is why it shipped.

Two further defects turned a wrong identity into an unrecoverable one; both are recorded here
because each is an instance of a general trap, not a typo.

## 2. The measured evidence (do not infer it from versions)

### 2.1 The session does not travel between agents

The same freshly minted session, replayed through nginx, differing only in the `User-Agent`:

| Session presented with… | `/books/admin` link | `Set-Cookie: remember_token=; Max-Age=0` on the reply |
|---|---|---|
| the agent that minted it | **present** (Admin) | no |
| a WebView-shaped agent | absent (Guest) | **yes — the reply deletes the remember cookie** |
| a login performed *with* the WebView's agent | **present** (Admin) | no |

The third row is the fix, proven before it was written: the session works when the agent that
creates it is the agent that will use it.

### 2.2 The service sets its cookies under its own path prefix

    $ curl -sI http://<box>/books/ | grep -i set-cookie
    Set-Cookie: session=…; HttpOnly; Path=/books; SameSite=Lax

The box-side login talks to the service **directly** (no prefix), so its cookies come back with
`Path=/`. The WebView reaches the same service **through nginx**, under `/books`.

### 2.3 The sources map — who writes which cookie, where, and who wins

| Cookie | Written by | Path | Read by |
|---|---|---|---|
| `session` (admin) | the box's server-side login, direct to the service port | `/` | the service |
| `session` (guest) | the service itself, reached through its prefix | `/books` | the service — **and this one wins** |
| `remember_token` | either, depending on who logged in | as above | Flask-Login, but only when there is no session |

Two cookies of the same name at different paths are two different cookies. RFC 6265 §5.4 orders the
longer path first and the service reads the first one it is given, so the deeper copy shadows the
one the app installs — and nothing in the app ever removed it. An instrumentation test pins this
against the real WebView cookie store rather than against the RFC.

### 2.4 The signal that made a broken feature look healthy

Calibre-Web renders `You are now logged in as: 'Admin'` on the first page after a login. It is a
**flash message stored inside the injected session**, so it renders even when the session is then
rejected and the viewer is already anonymous. Every screenshot of the broken build shows it.

> **Corollary, and the cheapest lesson in this ADR:** a message is not proof of identity. See §7.

## 3. Decision

1. **The box mints the session for the agent that asks.** `GET /k2go-api/auth/:service/session`
   forwards the caller's own `User-Agent` through the entire login handshake — every request, not
   only the credential POST, because the fingerprint is established on the first (anonymous) one.
   The app sends its WebView's string, read from the very WebView that will present it.
2. **"This page opens as box admin" has one owner.** The portal derives the service from the target
   URL (`portal/domain/AutoLoginPolicy`) instead of each launcher passing an Intent extra. Two of
   three launchers had not been passing it, and those were the entry points reached right after
   adding content.
3. **The cookie jar is reconciled, not appended to.** Before installing a fresh session the app
   expires that service's cookies on every path they can live at (`portal/domain/SessionCookies`).
   Clear and set are one operation: a failed sign-in leaves the jar untouched, so a still-valid
   session is never discarded over a transient failure.
4. **The failure is legible.** The client logs the real cause, retries once for fast failures (a
   refused connection or a 5xx) after a short pause, never for a timeout or a 4xx, and tells the
   user. Same rule the box applies to its own retries (`sockets/net-retry.ts`).

Callers that consume a session *themselves* (the content-download runner, the delete path) pass no
agent and keep the box's own. The fact travels only where it is true.

## 4. Options considered

**A. Mint with the consumer's agent — chosen.** Small, local, and it makes the endpoint's contract
honest: *mint a session for the agent that asks*. Any caller — the app, a script, a future surface —
gets a session valid for itself by construction. Verified before implementation (§2.1).

**B. Log in inside the WebView.** Post the service's own login form from the page, so the session is
created by its consumer by definition. Rejected: it means handling each service's CSRF and form
shape in the client, and re-implementing per service what the box already does once.

**C. Relax the upstream service.** Disable Flask-Login's session protection, or turn off anonymous
browsing so a rejected session fails loudly instead of degrading to Guest. Rejected: it weakens a
security control of a third-party component to work around our own design, and it does not travel —
the next service will bind sessions its own way.

**D. Proxy every service request through the box.** The box holds the session and the WebView never
sees a cookie. Rejected as far out of proportion: it puts the REST core on the critical path of all
content browsing, for one identity problem.

## 5. Consequences

- The endpoint is now **agent-sensitive**. A caller that sends no `User-Agent` degrades to the
  previous behaviour — a session valid only for the box — and that degradation is logged, because
  "the session mints but never authenticates" is otherwise invisible.
- **The User-Agent must be the consumer's string verbatim.** The tempting alternative — a branded
  `K2Go WebView` — only works if the same string is also forced onto the WebView, and that breaks
  things that read it: PDF routing picks a pdf.js build from the Chrome major version in the UA, and
  the services' templates sniff it for their mobile layout. It would also be two places that must
  agree, which is the class of defect this ADR exists to remove.
- Kolibri receives the same treatment. Django does not bind sessions to the agent, so it is expected
  to be a no-op there; it is applied anyway because one rule per endpoint is the point, and a
  per-service exception is what later gets forgotten. Verified not to regress (§7).
- The app is one Intent extra and one god-class method lighter; the box has one Calibre-Web login
  instead of two. The duplicate had already drifted — the `remember_me` of ADFA-5043 reached one
  copy and not the other — which is the drift this bug rode in on.

### Open consequences, deliberately not closed here

- **Session rows accumulate with no owner.** Every authenticated open creates a session record in
  the service's user database and nothing ever deletes it. Not a regression — those sessions were
  already being minted, merely uselessly — but it is a fact without an owner on a device meant to
  run for years.
- **The service is derived once, at load.** Navigating inside the WebView from the box's home page
  to a service still opens it unauthenticated. No longer permanent, because the next card-opened
  session clears and reinstalls, but covering it means hooking navigation rather than startup.
- The inverse mapping (service name → URL segment) still lives in the settings probe. Same fact,
  other direction.

## 6. Checklist for the next service

Before adding a third service to the auto-login:

1. Is it fronted under a **path prefix**? Then its own cookies live at that prefix, and the jar must
   be reconciled there, not only at `/`.
2. Does the box **mint** the session, or does the consumer log in? If the box mints it, pass the
   consumer's `User-Agent`.
3. Does the service allow **anonymous browsing**? Then a rejected session degrades silently to a
   guest view instead of failing — assume every "it works" report is about a message, not an
   identity, until §7 is satisfied.

## 7. Verification

**What counts as proof of identity** — one of:

- the service's own navigation shows the admin account (not `Guest`), or
- an admin-only surface is present and usable (for Calibre-Web: `Edit Metadata` on a book, and the
  delete that follows it).

**What does not count:** the `You are now logged in as: 'Admin'` flash (§2.4). It renders from the
injected session even when the viewer is anonymous, and it is the reason the original auto-login was
accepted as working.

**Device-verified for this ADR**, with the WebView cookie store deleted first so nothing could be
attributed to leftovers: all three entry points open as admin; a full add-then-reopen cycle keeps
admin and allows editing and deleting the newly added book; the courses card reaches its super-admin
surface; and after the run the jar holds only the freshly minted, non-persistent cookies — the
persistent one left by an earlier manual sign-in is gone, which is what proves the clear pass ran.

**Covered by tests, not by device steps:** the sign-in failure path. On a healthy box the UI never
reaches it — a card whose service is down is not Ready, so it opens the action sheet instead of the
portal, and Get More hides the entry outright. Both were observed while trying to reproduce it by
hand; do not spend an afternoon repeating that. The retry is held still by a unit test against a
scripted local server (5xx retried once after a pause; 4xx asked exactly once, with a success queued
behind it that the test requires us never to reach), and the toast plus the log line were verified on
device by forcing a 401 with a deliberately wrong stored credential.

### Running the instrumentation test

The cookie reconciliation is pinned against the real WebView cookie store, which needs a device.
**Never** use `connectedAndroidTest` for it: that task uninstalls both APKs when it finishes, and on
a device holding an installed rootfs, uninstalling the app deletes the box. One run destroyed an
installed system while this ticket was being written; the build now refuses that task unless
`-PallowUninstall=true` is passed. Install and run explicitly instead:

    ./gradlew :app:installDebug :app:installDebugAndroidTest
    adb shell am instrument -w -e class org.iiab.controller.portal.SessionCookieReconcileTest \
      org.iiab.controller.test/androidx.test.runner.AndroidJUnitRunner
