package com.hiweny.dswebapi;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 全局唯一的 WebView 宿主（由前台服务持有），保证：
 *  - 与 Activity 生命周期解耦：Activity 关闭/进程后台化后仍持续运行，开机后自动恢复；
 *  - 会话唯一：Activity 打开时把这个同一个 WebView 挂进界面，登录态与 API 驱动共用一份。
 */
public final class WebHost {
    public static final String DS_URL = "https://chat.deepseek.com/";
    public static final String SIGN_IN_URL = "https://chat.deepseek.com/sign_in";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static volatile WebView sWebView;
    private static volatile String sBridgeJs = "";
    private static volatile boolean sDark = false;
    private static volatile boolean sPageLoaded = false;
    private static volatile boolean sPendingClearLogin = false;
    private static volatile boolean sPendingRestore = false;
    /** 账号切换完整完成标志：false 表示 cookie/localStorage/reload 流程未走完，请求必须等待。 */
    private static volatile boolean sRestoreDone = true;
    /** 隐形容器（透明全屏悬浮窗）：Activity 不持有 WebView 时挂到这里保持「可见」。 */
    private static volatile ViewGroup sOverlayParent;
    private static Runnable sProbeLoop;
    private static volatile Activity sActivity;
    private static volatile FileChooserHandler sFileChooser;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WebHost() {}

    public interface FileChooserHandler {
        boolean onShow(ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams params);
    }

    public static WebView get() { return sWebView; }
    public static boolean isReady() { return sWebView != null && sPageLoaded; }
    public static boolean isRestoreDone() { return sRestoreDone; }
    /** 是否正处于账号切换/初始化流程中（含 pending 标志与 restore 未完成）。 */
    public static boolean isSwitching() { return sPendingRestore || sPendingClearLogin || !sRestoreDone; }
    /** 等待切换流程彻底完成（cookie→加载→localStorage→reload→bridge 就绪），返回是否完成。 */
    public static boolean awaitSwitchReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!sPendingRestore && !sPendingClearLogin && sRestoreDone && sWebView != null && sPageLoaded) return true;
            try { Thread.sleep(200); } catch (InterruptedException e) { return false; }
        }
        return !sPendingRestore && !sPendingClearLogin && sRestoreDone && sWebView != null && sPageLoaded;
    }
    public static void setActivity(Activity a) { sActivity = a; }
    public static void setFileChooser(FileChooserHandler h) { sFileChooser = h; }

    public static void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else MAIN.post(r);
    }

    /** 确保 WebView 已创建并加载官网（幂等）。 */
    public static void ensure(final Context appCtx, final String bridgeJs) {
        if (bridgeJs != null && !bridgeJs.isEmpty()) sBridgeJs = bridgeJs;
        if (sWebView != null) return;
        runOnMain(() -> {
            if (sWebView != null) return;
            try {
                WebView wv = new WebView(appCtx.getApplicationContext());
                configure(wv);
                sWebView = wv;
                DeepSeekController.get().attach(wv, sBridgeJs, ApiService::onBridgeStatus);
                wv.loadUrl(DS_URL);
                Util.log("WebView 已创建并加载 " + DS_URL);
            } catch (Throwable t) {
                Util.log("WebView 创建失败: " + t.getMessage());
            }
        });
    }

    public static void setDark(boolean dark) {
        sDark = dark;
        final WebView wv = sWebView;
        if (wv == null) return;
        runOnMain(() -> {
            try {
                WebSettings s = wv.getSettings();
                if (Build.VERSION.SDK_INT >= 33) s.setAlgorithmicDarkeningAllowed(dark);
                else if (Build.VERSION.SDK_INT >= 29) s.setForceDark(dark ? WebSettings.FORCE_DARK_ON : WebSettings.FORCE_DARK_OFF);
            } catch (Throwable ignored) {}
            DeepSeekController.get().setTheme(dark);
        });
    }

    /** 把 WebView 挂进某个容器（Activity 打开时）。 */
    public static void attachTo(ViewGroup parent) {
        final WebView wv = sWebView;
        if (wv == null || parent == null) return;
        runOnMain(() -> {
            try {
                ViewGroup old = (ViewGroup) wv.getParent();
                if (old != null && old != parent) old.removeView(wv);
                if (wv.getParent() == null) parent.addView(wv, new ViewGroup.LayoutParams(-1, -1));
            } catch (Throwable t) { Util.log("挂载 WebView 失败: " + t.getMessage()); }
        });
    }

    /**
     * 保活：确保全局定时器处于运行态。
     *
     * 本应用自身**从不**调用 {@link WebView#pauseTimers()}（也不调用 onPause）；
     * 这里主动 resumeTimers() 只是反制任何第三方/系统层面的 pause，调用是幂等的。
     * 注意：不改动可见性——可见性由承载窗口（前台/PiP）与当前页签决定。
     */
    public static void keepAlive() {
        final WebView wv = sWebView;
        if (wv == null) return;
        runOnMain(() -> {
            try { wv.resumeTimers(); } catch (Throwable ignored) {}
        });
    }

    /** 从容器摘下（Activity 关闭时），但保留实例继续后台运行。 */
    public static void detachFromParent() {
        final WebView wv = sWebView;
        if (wv == null) return;
        runOnMain(() -> {
            try {
                ViewGroup p = (ViewGroup) wv.getParent();
                if (p != null) p.removeView(wv);
            } catch (Throwable ignored) {}
        });
    }

    /** 设置隐形容器（由 ApiService 创建并持有）。 */
    public static void setOverlayParent(ViewGroup parent) { sOverlayParent = parent; }

    /** 获取当前隐形容器。 */
    public static ViewGroup overlayParent() { return sOverlayParent; }

    /**
     * 归属切换：Activity 不再持有 WebView 时交还隐形容器，保证页面保持「可见」不被后台节流；
     * 无隐形容器（无悬浮窗权限）时退化为摘除挂载，仅由全局引用保活。
     */
    public static void handoffToOverlay() {
        ViewGroup g = sOverlayParent;
        if (g != null) attachTo(g);
        else detachFromParent();
    }

    /** 同步探活（阻塞至多 3 秒）：bridge 存活返回 pong；页面冻结/桥丢失时超时报错。 */
    public static JSONObject pingSync() { return DeepSeekController.get().ping(); }

    public static void reload() {
        final WebView wv = sWebView;
        if (wv != null) runOnMain(() -> { try { wv.reload(); } catch (Throwable ignored) {} });
    }

    /**
     * 多账号切换：清 cookie → 灌入目标账号登录态快照（或清空登录态）→ 重载官网。
     * @param id         目标账号 0..2
     * @param clearLogin true=清空登录态（用于「去登录」，需手动登录）；false=灌入快照恢复登录态（用于轮询/手动切换）
     */
    public static void switchTo(final int id, final boolean clearLogin) {
        AccountManager.setCurrent(id);
        // 切换冷却：20~30 分钟随机，冷却期内不再轮询切换（避免回复未生成就被切走）
        AccountManager.noteSwitch();
        runOnMain(() -> {
            final WebView wv = sWebView;
            if (wv == null) return;
            if (clearLogin) {
                sPendingClearLogin = true;
                AccountManager.clearLoginState(id);
                Util.log("已清空登录态，准备登录 账号" + (id + 1));
                try {
                    CookieManager.getInstance().removeAllCookies(value -> {
                        sPageLoaded = false;
                        wv.loadUrl(SIGN_IN_URL);
                        Util.log("cookie 已清空，加载登录页");
                    });
                } catch (Throwable t) {
                    Util.log("清 cookie 失败: " + t.getMessage());
                    sPageLoaded = false;
                    wv.loadUrl(SIGN_IN_URL);
                }
            } else {
                // 关键：cookie 已由 restoreCookies 同步灌入（不再依赖 removeAllCookies 异步回调），
                // 直接 loadUrl；sRestoreDone 在 reload 完成并重新注入 bridge 后才置 true。
                sPendingRestore = true;
                sRestoreDone = false;
                sPageLoaded = false;
                try {
                    AccountManager.restoreCookies(id, () -> {
                        AccountManager.markLoggedIn(id);
                        Util.log("已灌入 账号" + (id + 1) + " 登录态快照");
                        runOnMain(() -> { try { wv.loadUrl(DS_URL); } catch (Throwable ignored) {} });
                    });
                } catch (Throwable t) {
                    Util.log("灌 cookie 失败: " + t.getMessage());
                    runOnMain(() -> { try { wv.loadUrl(DS_URL); } catch (Throwable ignored) {} });
                }
            }
        });
    }

    /** 定时登录态轮询：DeepSeek 登录是 SPA，登录成功后前端路由跳转不触发 onPageFinished，
     * 只靠一次性 probe 会漏抓快照。这里每 5 秒轻量探测一次（读 localStorage.userToken），
     * 登录态变化后 AccountManager.onProbe 会自动抓快照/刷新状态灯。 */
    private static void startProbeLoop() {
        if (sProbeLoop != null) return;
        sProbeLoop = new Runnable() {
            @Override public void run() {
                if (sWebView != null && sPageLoaded && !sPendingRestore && !sPendingClearLogin) {
                    try { DeepSeekController.get().probe(); } catch (Throwable ignored) {}
                }
                if (sWebView != null && sProbeLoop != null) MAIN.postDelayed(this, 5000L);
            }
        };
        MAIN.postDelayed(sProbeLoop, 5000L);
    }

    private static void stopProbeLoop() {
        if (sProbeLoop != null) { MAIN.removeCallbacks(sProbeLoop); sProbeLoop = null; }
    }

    public static void injectBridge() { DeepSeekController.get().injectBridge(); }

    /** 彻底销毁（仅在用户主动停止服务时调用）。 */
    public static void destroy() {
        final WebView wv = sWebView;
        sWebView = null;
        sPageLoaded = false;
        stopProbeLoop();
        sOverlayParent = null;
        if (wv == null) return;
        runOnMain(() -> {
            try {
                ViewGroup p = (ViewGroup) wv.getParent();
                if (p != null) p.removeView(wv);
                wv.stopLoading();
                wv.destroy();
                Util.log("WebView 已销毁");
            } catch (Throwable ignored) {}
        });
    }

    /* ================= 内部 ================= */

    private static void configure(WebView wv) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
            }
        } catch (Throwable ignored) {}
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(100);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUserAgentString(DESKTOP_UA);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(true);
        applyDark(wv);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);
        wv.setBackgroundColor(sDark ? 0xFF0E1116 : 0xFFFFFFFF);
        wv.addJavascriptInterface(new JsBridge(), "DSB");
        wv.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Util.log("页面加载完成: " + url);
                sPageLoaded = true;
                if (sPendingClearLogin) {
                    // 去登录模式：立刻清空页面 localStorage（DeepSeek 登录态主要存这里），再 reload 一次
                    sPendingClearLogin = false;
                    view.evaluateJavascript(
                        "(function(){try{localStorage.clear();sessionStorage.clear();}catch(e){}return 'cleared';})();",
                        r -> { if (sWebView != null) sWebView.postDelayed(() -> { try { if (sWebView != null) sWebView.reload(); } catch (Throwable ignored) {} }, 150L); });
                    return;
                }
                if (sPendingRestore) {
                    // 切换账号模式：页面首次加载完成，灌入目标账号 localStorage（注意必须在主线程直接 evaluateJavascript，
                    // 不能走 evalRaw 的 await，否则主线程自锁超时），完成后 reload 一次让页面用新登录态重新渲染。
                    sPendingRestore = false;
                    String js = AccountManager.localStorageRestoreJs(AccountManager.current());
                    view.evaluateJavascript(js == null ? "" : js, r -> {
                        if (sWebView != null) sWebView.postDelayed(() -> { try { if (sWebView != null) { sPageLoaded = false; sWebView.reload(); } } catch (Throwable ignored) {} }, 150L);
                    });
                    return;
                }
                DeepSeekController.get().injectBridge();
                // 多账号：恢复当前账号的 localStorage（cookie 已在 loadUrl 前灌入）
                try { AccountManager.applyLocalStorage(AccountManager.current()); } catch (Throwable ignored) {}
                sRestoreDone = true;
                MAIN.postDelayed(() -> DeepSeekController.get().probe(), 1000L);
                startProbeLoop();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String u = req.getUrl().toString();
                return !(u.startsWith("http://") || u.startsWith("https://"));
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> cb, FileChooserParams params) {
                FileChooserHandler h = sFileChooser;
                if (h != null) { try { return h.onShow(cb, params); } catch (Throwable ignored) {} }
                try { cb.onReceiveValue(null); } catch (Throwable ignored) {}
                return false;
            }
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                if (m != null && m.message() != null && m.message().contains("DSWB")) Util.log("JS: " + m.message());
                return true;
            }
        });
    }

    private static void applyDark(WebView wv) {
        try {
            WebSettings s = wv.getSettings();
            if (Build.VERSION.SDK_INT >= 33) s.setAlgorithmicDarkeningAllowed(sDark);
            else if (Build.VERSION.SDK_INT >= 29) s.setForceDark(sDark ? WebSettings.FORCE_DARK_ON : WebSettings.FORCE_DARK_OFF);
        } catch (Throwable ignored) {}
    }

    public static String loadBridgeJs(Context ctx) {
        try (InputStream in = ctx.getAssets().open("bridge.js");
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Exception e) {
            Util.log("加载 bridge.js 失败: " + e.getMessage());
            return "";
        }
    }

    private static class JsBridge {
        @android.webkit.JavascriptInterface
        public void onEvent(String json) { DeepSeekController.get().onJsEvent(json); }
    }
}
