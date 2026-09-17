/*
 * ============================================================================
 * Name        : KolibriGuardController.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-395 (ADR-395). Metered-cost gate for the NATIVE Kolibri app.
 *
 *               Kolibri runs in the rootfs and is shown in the PortalActivity WebView at the box
 *               /kolibri/ page. It has its OWN content-import (download) manager, which our Get More
 *               flow and ContentAdmission do NOT cover: tapping "Import" in Kolibri's own UI starts a
 *               server-side download from Kolibri Studio over the device's metered link, with no
 *               consent prompt. Device recon (K2GO-395): the import is enqueued by
 *                 POST /api/tasks/tasks/     (referer /kolibri/en/device/)
 *               and cancelled by POST /api/tasks/tasks/<id>/cancel/. The task list polls
 *               GET /api/tasks/tasks/?queue=content.
 *
 *               Lifecycle mirrors FqrController / KiwixManageController: PortalActivity forwards
 *               prepareForUrl(url) (add the JS bridge only on /kolibri/), onPageFinished(url) (inject
 *               the hook on the kolibri page), and onDestroy -> detach().
 *
 *               How it gates: the injected hook wraps XMLHttpRequest (axios, which Kolibri uses) and
 *               fetch. A POST to /api/tasks/tasks/ that carries a REMOTE import task (heuristic on the
 *               body: contains "remote"/"channelupdate", not a local "disk" import) is PAUSED; the hook
 *               calls the native bridge K2GoKolibri.gateImport(reqId, body), which runs
 *               NetworkPolicyGate.guardHeavyStart on the UI thread and resolves back via
 *               evaluateJavascript(window.__k2goKolibriResolve) -- proceed on consent (or unmetered /
 *               already-consented, which resolves instantly), abort on decline. A task POST that is NOT
 *               classified as a remote import is logged (console.warn -> logcat) so a Kolibri task-API
 *               change is visible rather than a silent miss.
 *
 *               The task-API shape is Kolibri internal and may change across versions -- the hook is
 *               intentionally narrow and fails OPEN (any error lets the request through) so a Kolibri
 *               change never bricks import; the console.warn above is the regression signal. gateImport
 *               also resolves if the prompt cannot be shown, so a parked request is never left hanging.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.app.Activity;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.appdevforall.k2go.networkpolicy.presentation.NetworkPolicyGate;
import org.appdevforall.k2go.util.WebPath;

public final class KolibriGuardController {

    private final Activity activity;
    private final WebView webView;

    public KolibriGuardController(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    /** Add the JS bridge only for the /kolibri/ page and remove it elsewhere (defence in depth: the
     *  box is trusted, but keep the interface off every other page). Takes effect on the next load. */
    public void prepareForUrl(String url) {
        if (isKolibriPage(url)) webView.addJavascriptInterface(this, "K2GoKolibri");
        else webView.removeJavascriptInterface("K2GoKolibri");
    }

    /** Inject the fetch/XHR hook once the kolibri page has loaded. Idempotent in-page. */
    public void onPageFinished(String url) {
        if (isKolibriPage(url)) webView.evaluateJavascript(HOOK_JS, null);
    }

    /** Host is going away: drop the bridge. The hook lives in the page's JS context and dies with it. */
    public void detach() {
        webView.removeJavascriptInterface("K2GoKolibri");
    }

    /**
     * Called from the page hook (binder thread) when a Kolibri remote-import POST is parked. Shows the
     * consent prompt on the UI thread and resolves the parked request back in JS: proceed on consent
     * (or unmetered / already-consented), abort on decline. If the prompt cannot be shown (activity
     * finishing), resolves proceed so the request is never left parked -- consistent with fail-open.
     */
    @JavascriptInterface
    public void gateImport(String reqId, String body) {
        if (reqId == null) return;
        final String id = reqId;
        activity.runOnUiThread(() -> {
            try {
                NetworkPolicyGate.guardHeavyStart(activity, () -> resolve(id, true), () -> resolve(id, false));
            } catch (RuntimeException e) {
                resolve(id, true);
            }
        });
    }

    private void resolve(String reqId, boolean granted) {
        // Only the id we minted in JS (alphanumeric + '_'); never interpolate arbitrary text.
        if (reqId == null || !reqId.matches("[A-Za-z0-9_]+")) return;
        webView.evaluateJavascript(
                "window.__k2goKolibriResolve&&window.__k2goKolibriResolve('" + reqId + "'," + granted + ");",
                null);
    }

    /** True when the URL path is a box Kolibri page. */
    static boolean isKolibriPage(String url) {
        String p = WebPath.pathOf(url);
        return p.equals("/kolibri") || p.startsWith("/kolibri/");
    }

    // The page hook. Wraps XHR (axios) + fetch; parks a remote-import task POST behind the native gate,
    // warns on any other task POST (regression signal), and fails OPEN on any error.
    private static final String HOOK_JS =
            "(function(){"
          + "if(window.__k2goKolibriHooked)return;window.__k2goKolibriHooked=true;"
          + "window.__k2goKolibriPending={};"
          + "window.__k2goKolibriResolve=function(id,g){var p=window.__k2goKolibriPending[id];if(!p)return;"
          + "delete window.__k2goKolibriPending[id];try{p(g);}catch(e){}};"
          + "function isTaskPost(m,u){try{"
          + "if(!m||(''+m).toUpperCase()!=='POST')return false;if(!u)return false;"
          + "var s=(''+u).split('?')[0].split('#')[0];if(s.charAt(s.length-1)==='/')s=s.substring(0,s.length-1);"
          + "return s.endsWith('/api/tasks/tasks');"
          + "}catch(e){return false;}}"
          + "function isRemoteImport(b){var t=(typeof b==='string')?b:'';return /remote|channelupdate/i.test(t)&&!/disk/i.test(t);}"
          + "function park(body,proceed,cancel){"
          + "var id='k'+Date.now()+'_'+Math.random().toString(36).slice(2);"
          + "window.__k2goKolibriPending[id]=function(g){if(g)proceed();else cancel();};"
          + "try{K2GoKolibri.gateImport(id,(typeof body==='string')?body:'');}catch(e){proceed();}}"
          + "function classify(m,u,b,proceed,cancel){"
          + "if(!isTaskPost(m,u))return false;"
          + "if(isRemoteImport(b)){park(b,proceed,cancel);return true;}"
          + "try{console.warn('K2Go-Kolibri: ungated task POST',(typeof b==='string')?b.slice(0,160):'');}catch(e){}"
          + "return false;}"
          + "try{var XO=XMLHttpRequest.prototype.open,XS=XMLHttpRequest.prototype.send;"
          + "XMLHttpRequest.prototype.open=function(m,u){this.__k2m=m;this.__k2u=u;return XO.apply(this,arguments);};"
          + "XMLHttpRequest.prototype.send=function(b){var self=this,a=arguments;"
          + "if(classify(self.__k2m,self.__k2u,b,function(){XS.apply(self,a);},"
          + "function(){try{self.abort();}catch(e){}}))return;"
          + "return XS.apply(self,arguments);};}catch(e){}"
          + "try{var OF=window.fetch;if(OF){window.fetch=function(i,n){"
          + "var m=(n&&n.method)||(i&&i.method)||'GET';var u=(typeof i==='string')?i:((i&&i.url)||'');var b=n&&n.body;"
          + "var handled=false,pr,rj;var p=new Promise(function(res,rej){pr=res;rj=rej;});"
          + "handled=classify(m,u,(typeof b==='string')?b:'',function(){OF(i,n).then(pr,rj);},"
          + "function(){rj(new DOMException('Canceled by network policy','AbortError'));});"
          + "return handled?p:OF(i,n);};}}catch(e){}"
          + "console.log('K2Go-Kolibri gate armed');"
          + "})();";
}
