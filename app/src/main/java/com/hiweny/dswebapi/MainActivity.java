package com.hiweny.dswebapi;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面（控制台 / 对话页 / PiP / 悬浮球）。
 *
 * <p>要改这个工程？先读仓库根目录 docs/开发指南.md：里面有工程结构地图、
 * 「加设置项 / 加 HTTP 路由 / 加页面内动作 / 加 PiP 按钮」四个落点怎么写，
 * 以及 PiP 后台保活踩过的坑（finish 会被 autoEnter 弹回、退出前要先关 autoEnter 等）。</p>
 */
public class MainActivity extends Activity {
    private static final int TAB_CONTROL = 0, TAB_WEB = 1;
    private static final int REQ_FILE = 1001;
    private static final int REQ_NOTIF = 1002;
    private static final int REQ_BACKUP = 1003;
    private static final String[] UA = new String[]{};

    /** 启动参数：拉起 App 后直接进入画中画（悬浮球单击）。 */
    public static final String EXTRA_ENTER_PIP = "com.hiweny.dswebapi.ENTER_PIP";
    /** 画中画窗口上的自定义按钮：缩小为悬浮球。 */
    private static final String ACTION_PIP_MIN = "com.hiweny.dswebapi.PIP_MIN";
    /** 画中画窗口上的自定义按钮：切换窗口大小。 */
    private static final String ACTION_PIP_SIZE = "com.hiweny.dswebapi.PIP_SIZE";

    /** 画中画窗口尺寸预设（宽高比）。系统只允许通过比例间接控制 PiP 大小。 */
    private static final Rational[] PIP_SIZES = {
            new Rational(9, 16),   // 小
            new Rational(1, 1),    // 中
            new Rational(16, 9)    // 大
    };
    private static final String[] PIP_SIZE_NAMES = { "小", "中", "大" };
    /** -1 = 「自动」（跟随网页可视区比例）。 */
    private static final String KEY_PIP_SIZE = "pip_size_idx";

    private Theme theme;
    private LinearLayout rootView, bottomNav;
    private ScrollView controlScroll;
    private FrameLayout webPanel;
    private View controlPanel;
    private int currentTab = TAB_CONTROL;

    private TextView tvService, tvWeb, tvLogin, tvCalls, tvCtx, tvLast, tvBase, tvKey, tvLog, tvMcp;
    private TextView tvAccNow;
    private LinearLayout navIconBoxControl, navIconBoxWeb;
    private TextView navIconControl, navIconWeb, navLabelControl, navLabelWeb;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastBack = 0;

    /** 画中画（PiP）：退后台时把承载 WebView 的窗口钉在「可见」，避免 Chromium 后台节流。 */
    private volatile boolean inPip = false;
    private int tabBeforePip = TAB_WEB;
    /** 我们自己拉起外部 Activity（文件选择/系统设置）时，短时间内抑制自动 PiP。 */
    private long suppressPipUntil = 0L;
    /** 待处理：onResume 后进入画中画（由悬浮球单击拉起 App 时置位）。 */
    private boolean pendingEnterPip = false;
    /** 当前 Activity 是否 RESUMED，用于区分「前台点击悬浮球」与「后台拉起」。 */
    private boolean foregroundResumed = false;
    /** 画中画自定义按钮的广播接收器。 */
    private BroadcastReceiver pipActionReceiver;

    /** 跳转到外部页面（文件选择器、系统设置等），并抑制随后误触发的自动画中画。 */
    private void startExternal(Intent i, int reqCode) {
        suppressPipUntil = android.os.SystemClock.uptimeMillis() + 1500L;
        // API 31+ 的 autoEnter 也可能被这次跳转触发，临时关掉（onResume 会重新开启）
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                setPictureInPictureParams(new PictureInPictureParams.Builder()
                        .setAutoEnterEnabled(false).build());
            } catch (Throwable ignored) {}
        }
        if (reqCode >= 0) startActivityForResult(i, reqCode);
        else startActivity(i);
    }

    private ValueCallback<Uri[]> filePathCallback;

    private final Runnable statsTask = new Runnable() {
        @Override public void run() {
            refreshStats();
            handler.postDelayed(this, 2000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AccountManager.init(this);   // 兜底：服务未启动时 UI 也能安全访问账号状态
        AutoMsg.migrateLegacyPrefs(this);   // 迁移旧版 auto_msg_content，避免升级丢配置
        theme = Theme.of(this);
        enableEdgeToEdge();
        buildUi();
        WebHost.setActivity(this);
        WebHost.setDark(theme.dark);
        WebHost.setFileChooser(this::onShowFileChooser);
        ApiService.start(this);
        requestNotifPermission();
        requestBatteryWhitelist();
        if (Util.prefs(this).getBoolean("keep_screen", true)) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        handler.post(attachWebRunnable);
        handler.postDelayed(statsTask, 1500L);
        WebHost.ensure(this, WebHost.loadBridgeJs(this));
        registerPipActions();
        handlePipIntent(getIntent());
    }

    private final Runnable attachWebRunnable = new Runnable() {
        int tries = 0;
        @Override public void run() {
            if (WebHost.get() != null) {
                syncWebHostParent();
            } else if (tries++ < 20) {
                handler.postDelayed(this, 300L);
            }
        }
    };

    /**
     * 统一决定 WebView 归属（隐形容器方案的核心）：
     *  - 对话页可见 且（App 前台 或 PiP 中）→ 挂 Activity 的 webPanel（用户可交互/可见）；
     *  - 其余任何情况（控制台页、退后台、Activity 销毁）→ 交还 ApiService 的隐形容器，
     *    让页面保持「可见」状态，不被 Chromium/系统后台节流冻结。
     */
    private void syncWebHostParent() {
        boolean panelOwns = webPanel != null
                && webPanel.getVisibility() == View.VISIBLE
                && (foregroundResumed || inPip);
        if (panelOwns) WebHost.attachTo(webPanel);
        else WebHost.handoffToOverlay();
    }

    /* ================= 窗口 / 主题 ================= */

    @SuppressWarnings("deprecation")
    private void enableEdgeToEdge() {
        Window w = getWindow();
        View decor = w.getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        w.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        w.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        applyBarIcons();
    }

    @SuppressWarnings("deprecation")
    private void applyBarIcons() {
        View decor = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!theme.dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        decor.setSystemUiVisibility(flags);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean nowDark = (newConfig.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (nowDark != theme.dark) {
            theme = Theme.of(this);
            WebHost.setDark(theme.dark);
            buildUi();
            syncWebHostParent();
            handler.post(attachWebRunnable);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncWebHostParent();
        // 声明 PiP 参数（含 autoEnter，API 31+）：隐形容器不可用才退后台自动进入画中画
        applyPipParams();
        WebHost.keepAlive();
        refreshStats();
        foregroundResumed = true;
        if (pendingEnterPip) { pendingEnterPip = false; enterPipWhenReady(3); }
    }

    @Override
    protected void onPause() {
        foregroundResumed = false;
        super.onPause();
        // 退后台：WebView 交还隐形容器保持可见（PiP 中则保持挂在 webPanel）
        syncWebHostParent();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePipIntent(intent);
    }

    /**
     * 用户按 Home / 最近任务等「主动退后台」时触发（来电、息屏等不会触发）。
     * 在 API 24–30 上主动拉起 PiP；API 31+ 由 setAutoEnterEnabled 兜底，
     * 这里仍尝试一次以保证确定性（失败无副作用）。
     */
    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        // 跳过我们自己拉起外部页面（文件选择/系统设置）导致的 leave
        if (android.os.SystemClock.uptimeMillis() < suppressPipUntil) return;
        if (pipEnabled() && !inPip && !isFinishing() && WebHost.get() != null
                && !ApiService.overlayActive()) enterPip();
    }

    private boolean pipEnabled() {
        return Util.prefs(this).getBoolean("pip_background", true);
    }

    /** 计算 PiP 窗口宽高比（PiP 仅接受 0.418 ~ 2.39）。 */
    @SuppressWarnings("deprecation")
    private Rational pipAspect() {
        int idx = Util.prefs(this).getInt(KEY_PIP_SIZE, -1);
        if (idx >= 0 && idx < PIP_SIZES.length) return PIP_SIZES[idx];
        int w = 0, h = 0;
        if (webPanel != null) { w = webPanel.getWidth(); h = webPanel.getHeight(); }
        if (w <= 0 || h <= 0) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        if (w <= 0 || h <= 0) return new Rational(9, 16);
        double r = (double) w / h;
        if (r < 0.42) w = (int) Math.round(h * 0.42);
        else if (r > 2.39) h = (int) Math.round(w / 2.39);
        return new Rational(Math.max(1, w), Math.max(1, h));
    }

    private PictureInPictureParams buildPipParams() {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
        b.setAspectRatio(pipAspect());
        if (Build.VERSION.SDK_INT >= 31)
            b.setAutoEnterEnabled(pipEnabled() && !isFinishing() && !ApiService.overlayActive());
        if (Build.VERSION.SDK_INT >= 26) {
            try { b.setActions(buildPipActions()); } catch (Throwable ignored) {}
        }
        return b.build();
    }

    /**
     * 画中画窗口上的自定义按钮：缩小为悬浮球 / 切换大小。
     * 加 PiP 按钮 / 排查保活问题见 docs/开发指南.md §3.4 与 §4（踩坑经验）。
     */
    private List<RemoteAction> buildPipActions() {
        List<RemoteAction> acts = new ArrayList<>(2);
        acts.add(new RemoteAction(
                Icon.createWithResource(this, R.drawable.ic_pip_minimize),
                "缩小为悬浮球", "缩小为悬浮球", pipAction(ACTION_PIP_MIN, 1)));
        acts.add(new RemoteAction(
                Icon.createWithResource(this, R.drawable.ic_pip_resize),
                "画中画大小", "切换画中画窗口大小", pipAction(ACTION_PIP_SIZE, 2)));
        return acts;
    }

    private PendingIntent pipAction(String action, int reqCode) {
        Intent i = new Intent(action).setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(this, reqCode, i, flags);
    }

    /** 注册画中画按钮的接收器（仅注册一次）。 */
    private void registerPipActions() {
        if (Build.VERSION.SDK_INT < 26 || pipActionReceiver != null) return;
        pipActionReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String a = intent == null ? null : intent.getAction();
                if (ACTION_PIP_MIN.equals(a)) minimizeToBall();
                else if (ACTION_PIP_SIZE.equals(a)) cyclePipSize();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_PIP_MIN);
        f.addAction(ACTION_PIP_SIZE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pipActionReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(pipActionReceiver, f);
    }

    /** 更新 PiP 参数；API 26+ 才有效，失败不影响主流程。 */
    private void applyPipParams() {
        if (Build.VERSION.SDK_INT < 26) return;
        try { setPictureInPictureParams(buildPipParams()); } catch (Throwable ignored) {}
    }

    /** 进入画中画：先保证「对话页」可见（PiP 里显示的就是 WebView），并确保前台服务在跑。 */
    private boolean enterPip() {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            tabBeforePip = currentTab;
            if (currentTab != TAB_WEB) switchTab(TAB_WEB);
            if (webPanel != null) webPanel.setVisibility(View.VISIBLE);
            WebHost.attachTo(webPanel);
            WebHost.keepAlive();
            ApiService.start(this);   // 前台服务兜底，保证进程/WebView 不被回收
            if (Build.VERSION.SDK_INT >= 26) {
                boolean ok = enterPictureInPictureMode(buildPipParams());
                Util.log("请求进入画中画: " + (ok ? "成功" : "被系统拒绝"));
                if (!ok && !inPip) switchTab(tabBeforePip);   // 被拒则还原页签
                return ok;
            } else {
                enterPictureInPictureMode();   // API 24–25：无返回值，无法探测结果
                Util.log("请求进入画中画（API<26）");
                return true;
            }
        } catch (Throwable t) {
            Util.log("进入画中画失败: " + t.getMessage());
            if (!inPip) switchTab(tabBeforePip);
            return false;
        }
    }

    /** 延时进入画中画；窗口尚未就绪时系统会拒绝，故带重试。 */
    private void enterPipWhenReady(final int retries) {
        handler.postDelayed(() -> {
            if (inPip || isFinishing() || isDestroyed()) return;
            if (enterPip()) return;
            if (retries > 0) enterPipWhenReady(retries - 1);
            else Util.log("进入画中画失败：重试耗尽");
        }, 250L);
    }

    /** 处理「单击悬浮球 → 进入画中画」的启动参数。 */
    private void handlePipIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_ENTER_PIP, false)) return;
        intent.removeExtra(EXTRA_ENTER_PIP);
        if (foregroundResumed) enterPipWhenReady(3);
        else pendingEnterPip = true;
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        handlePipChanged(isInPictureInPictureMode);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        handlePipChanged(isInPictureInPictureMode);
    }

    /** 进入 PiP 时隐藏所有非网页 UI，让 WebView 铺满并保持可见；退出时还原。 */
    private void handlePipChanged(boolean inPipNow) {
        if (inPip == inPipNow) return;
        inPip = inPipNow;
        Util.log("画中画状态: " + (inPipNow ? "进入（WebView 保持可见）" : "退出"));
        if (inPipNow) {
            if (bottomNav != null) bottomNav.setVisibility(View.GONE);
            if (controlPanel != null) controlPanel.setVisibility(View.GONE);
            if (webPanel != null) webPanel.setVisibility(View.VISIBLE);
            syncWebHostParent();
            WebHost.keepAlive();
            setBallVisible(false);   // PiP 期间收起悬浮球，避免与 PiP 窗口重叠
        } else {
            if (bottomNav != null) bottomNav.setVisibility(View.VISIBLE);
            switchTab(tabBeforePip);
            syncWebHostParent();
            setBallVisible(true);
        }
    }

    /** 按用户开关意图同步悬浮球显隐（服务未运行时静默忽略）。 */
    private void setBallVisible(boolean visible) {
        try {
            ApiService svc = ApiService.instance();
            if (svc == null) return;
            if (visible) svc.showFloatingBall();
            else svc.hideFloatingBall();
        } catch (Throwable ignored) {}
    }

    /** 画中画「减号」按钮：退出 PiP 并把界面缩进悬浮球。 */
    private void minimizeToBall() {
        Util.log("画中画：缩小为悬浮球");
        setBallVisible(true);
        // 关键：先关掉 API 31+ 的 autoEnter，否则退后台会被系统立刻再次拉进 PiP
        suppressPipUntil = android.os.SystemClock.uptimeMillis() + 2000L;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                setPictureInPictureParams(new PictureInPictureParams.Builder()
                        .setAspectRatio(pipAspect()).setAutoEnterEnabled(false).build());
            } catch (Throwable ignored) {}
        }
        // 退出 PiP 的可靠方式是 finish()（moveTaskToBack 在 PiP 下多数机型不生效）。
        // WebView 由 WebHost/DeepSeekController 静态持有，Activity 销毁后页面状态不丢。
        try { finish(); } catch (Throwable ignored) {}
    }

    /** 画中画「大小」按钮：按 小→中→大 循环切换窗口尺寸。 */
    private void cyclePipSize() {
        if (Build.VERSION.SDK_INT < 26) { toast("当前系统不支持调整画中画大小"); return; }
        int cur = Util.prefs(this).getInt(KEY_PIP_SIZE, -1);
        int next = (cur + 1) % PIP_SIZES.length;
        Util.prefs(this).edit().putInt(KEY_PIP_SIZE, next).apply();
        Util.log("画中画尺寸切换为：" + PIP_SIZE_NAMES[next]);
        try { setPictureInPictureParams(buildPipParams()); } catch (Throwable ignored) {}
        toast("画中画大小：" + PIP_SIZE_NAMES[next]);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 兜底：若因为 PiP 被系统关闭（✕）而销毁，此刻球仍是隐藏的，需恢复（pref 关闭时无副作用）
        if (inPip) setBallVisible(true);
        if (pipActionReceiver != null) {
            try { unregisterReceiver(pipActionReceiver); } catch (Throwable ignored) {}
            pipActionReceiver = null;
        }
        handler.removeCallbacksAndMessages(null);
        WebHost.setActivity(null);
        WebHost.handoffToOverlay();
    }

    @Override
    public void onBackPressed() {
        if (currentTab == TAB_WEB) { switchTab(TAB_CONTROL); return; }
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) { super.onBackPressed(); moveTaskToBack(true); }
        else { lastBack = now; toast("再按一次返回键退出（服务继续后台运行）"); }
    }

    /* ================= UI 构建 ================= */

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable roundedBg(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }
    private GradientDrawable strokeBg(int color, int stroke, int radiusDp) {
        GradientDrawable g = roundedBg(color, radiusDp);
        g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    private void buildUi() {
        rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setBackgroundColor(theme.bg());
        FrameLayout container = new FrameLayout(this);
        controlPanel = buildControlPanel();
        container.addView(controlPanel, new FrameLayout.LayoutParams(-1, -1));
        webPanel = new FrameLayout(this);
        webPanel.setBackgroundColor(theme.bg());
        webPanel.setVisibility(View.GONE);
        container.addView(webPanel, new FrameLayout.LayoutParams(-1, -1));
        rootView.addView(container, new LinearLayout.LayoutParams(-1, 0, 1f));
        rootView.addView(buildBottomNav(), new LinearLayout.LayoutParams(-1, -2));
        setContentView(rootView);
        applyWindowInsets();
        updateNavStyle();
    }

    @SuppressWarnings("deprecation")
    private void applyWindowInsets() {
        if (rootView == null) return;
        rootView.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            if (controlScroll != null) {
                controlScroll.setPadding(dp(14), top + dp(12), dp(14), dp(12));
                controlScroll.setClipToPadding(false);
            }
            if (bottomNav != null) bottomNav.setPadding(0, 0, 0, bottom);
            return insets;
        });
        rootView.requestApplyInsets();
    }

    private View buildBottomNav() {
        bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.VERTICAL);
        bottomNav.setBackgroundColor(theme.headerBg());
        bottomNav.setElevation(dp(10));
        View divider = new View(this);
        divider.setBackgroundColor(theme.divider());
        bottomNav.addView(divider, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(24), dp(8), dp(24), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        LinearLayout c = buildNavItem("\uD83C\uDF9B", "控制台", v -> switchTab(TAB_CONTROL));
        LinearLayout w = buildNavItem("\uD83D\uDCAC", "对话页", v -> switchTab(TAB_WEB));
        row.addView(c, lp);
        row.addView(w, lp);
        bottomNav.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return bottomNav;
    }

    private LinearLayout buildNavItem(String icon, String label, final View.OnClickListener onClick) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        final LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(22), dp(5), dp(22), dp(5));
        GradientDrawable g = roundedBg(theme.accent(), 20);
        g.setAlpha(0);
        pill.setBackground(g);
        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(21f);
        pill.addView(ic);
        col.addView(pill);
        TextView lb = new TextView(this);
        lb.setText(label);
        lb.setTextSize(11.5f);
        lb.setGravity(Gravity.CENTER);
        lb.setPadding(0, dp(3), 0, 0);
        col.addView(lb);
        if ("\uD83C\uDF9B".equals(icon)) { navIconBoxControl = pill; navIconControl = ic; navLabelControl = lb; }
        else { navIconBoxWeb = pill; navIconWeb = ic; navLabelWeb = lb; }
        col.setOnClickListener(v -> { onClick.onClick(v); animateNavPill(pill); });
        return col;
    }

    private void animateNavPill(LinearLayout pill) {
        ScaleAnimation a = new ScaleAnimation(0.7f, 1f, 0.7f, 1f, 1, 0.5f, 1, 0.5f);
        a.setDuration(200);
        pill.startAnimation(a);
    }

    private void switchTab(int tab) {
        currentTab = tab;
        boolean control = tab == TAB_CONTROL;
        controlPanel.setVisibility(control ? View.VISIBLE : View.GONE);
        webPanel.setVisibility(control ? View.GONE : View.VISIBLE);
        syncWebHostParent();
        updateNavStyle();
        if (control) refreshStats();
    }

    private void updateNavStyle() {
        boolean c = currentTab == TAB_CONTROL;
        styleNav(navIconBoxControl, navLabelControl, c);
        styleNav(navIconBoxWeb, navLabelWeb, !c);
    }

    private void styleNav(LinearLayout pill, TextView label, boolean active) {
        if (pill == null || pill.getBackground() == null) return;
        ((GradientDrawable) pill.getBackground()).setAlpha(active ? 40 : 0);
        label.setTextColor(active ? theme.accent() : theme.textSub());
        label.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
    }

    /* ================= 控制面板 ================= */

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(strokeBg(theme.cardBg(), theme.divider(), 16));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, dp(6));
        c.setLayoutParams(lp);
        return c;
    }

    private TextView cardTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13.5f);
        t.setTextColor(theme.accent());
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private TextView line(String label, String value) {
        TextView t = new TextView(this);
        t.setText(label + "：" + value);
        t.setTextSize(13.5f);
        t.setTextColor(theme.text());
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    /**
     * 控制台设置面板。
     * 加设置项见 docs/开发指南.md §3.1；注意 currentSetting / putSetting / defOn 三处要同步改。
     */
    private View buildControlPanel() {
        controlScroll = new ScrollView(this);
        controlScroll.setBackgroundColor(theme.bg());
        controlScroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        controlScroll.addView(col, new LinearLayout.LayoutParams(-1, -2));

        // 标题
        TextView h = new TextView(this);
        h.setText("DeepSeek Web API");
        h.setTextSize(20f);
        h.setTextColor(theme.text());
        h.setTypeface(null, Typeface.BOLD);
        col.addView(h);
        TextView sub = new TextView(this);
        sub.setText("把 chat.deepseek.com 官网封装成本机 OpenAI 兼容接口");
        sub.setTextSize(12f);
        sub.setTextColor(theme.textSub());
        sub.setPadding(0, dp(2), 0, dp(6));
        col.addView(sub);

        // 状态
        LinearLayout st = card();
        st.addView(cardTitle("运行状态"));
        tvService = line("服务", "—"); st.addView(tvService);
        tvWeb = line("网页", "—"); st.addView(tvWeb);
        tvLogin = line("登录", "—"); st.addView(tvLogin);
        tvCalls = line("调用", "—"); st.addView(tvCalls);
        tvCtx = line("会话上下文", "—"); st.addView(tvCtx);
        tvLast = line("最近", "—"); st.addView(tvLast);
        col.addView(st);

        // 接口
        LinearLayout api = card();
        api.addView(cardTitle("接口信息"));
        tvBase = line("Base URL", "—"); api.addView(tvBase);
        tvKey = line("API Key", "—"); api.addView(tvKey);
        api.addView(rowButtons(new String[][]{
                {"复制 Base URL", "copy_base"}, {"复制 API Key", "copy_key"}
        }));
        col.addView(api);

        // MCP 调试服务
        LinearLayout mcp = card();
        mcp.addView(cardTitle("MCP 调试服务"));
        tvMcp = line("端点", "—"); mcp.addView(tvMcp);
        mcp.addView(makeSwitch("启用 MCP 调试", "AI 可远程调用日志/DOM/JS 排查问题（本地端口，安全）", "mcp_enabled"));
        mcp.addView(rowButtons(new String[][]{{"复制 MCP 地址", "copy_mcp"}}));
        col.addView(mcp);

        // 多账号轮询 + 备份
        LinearLayout multi = card();
        multi.addView(cardTitle("多账号轮询"));
        tvAccNow = line("当前账号", "—"); multi.addView(tvAccNow);
        multi.addView(makeSwitch("启用多账号轮询", "开启后请求自动分配给「最久未使用」的已登录账号", "multi_account"));
        multi.addView(makeRow("切换冷却（分钟）", "cooldown_min"));
        for (int i = 0; i < AccountManager.COUNT; i++) {
            multi.addView(accountCard(i));
        }
        multi.addView(rowButtons(new String[][]{
                {"备份账号状态", "backup_export"}, {"导入备份", "backup_import"}
        }));
        col.addView(multi);

        // 设置
        LinearLayout set = card();
        set.addView(cardTitle("设置"));
        set.addView(makeRow("端口", "port"));
        set.addView(makeRow("API Key", "apikey"));
        set.addView(makeRow("超时（秒）", "timeout"));
        set.addView(makeRowCycle("思考策略", "thinking_mode", new String[]{"auto", "on", "off"}, "跟随/强制开/强制关"));
        set.addView(makeRowCycle("搜索策略", "search_mode", new String[]{"auto", "on", "off"}, "跟随/强制开/强制关"));
        set.addView(makeSwitch("无状态模式", "每次发送完整对话（不依赖官网会话记忆）", "stateless"));
        set.addView(makeSwitch("自动新开对话", "上下文达阈值时自动新建对话，并尽力删除旧会话", "auto_newchat"));
        set.addView(makeSwitch("开启DS破甲提示词", "每次新建对话后自动发送下方内容并等待回复完成（占用一轮对话）", "auto_msg_enabled"));
        set.addView(makeRowText("自动消息内容", "auto_msg_text", "留空则只新建对话；建议放固定开场指令/上下文"));
        set.addView(makeRow("上下文上限（tokens）", "context_tokens"));
        set.addView(makeRow("新对话阈值（%）", "newchat_threshold"));
        set.addView(makeSwitch("保持屏幕常亮", "提高后台存活率（可关）", "keep_screen"));
        set.addView(makeSwitch("悬浮球（快捷入口 / 助保活）", "屏幕上常驻小圆球，AI 回复时有动效；点击回到 App", "floating_ball"));
        set.addView(makeSwitch("后台画中画保活", "退到后台时自动进入画中画，让网页保持可见，避免被系统节流", "pip_background"));
        col.addView(set);

        // 操作
        LinearLayout act = card();
        act.addView(cardTitle("操作"));
        act.addView(rowButtons(new String[][]{
                {"启动服务", "start_svc"}, {"停止服务", "stop_svc"}
        }));
        act.addView(rowButtons(new String[][]{
                {"新建对话", "new_chat"}, {"重载网页", "reload"}
        }));
        act.addView(rowButtons(new String[][]{
                {"无障碍保活设置", "access"}, {"电池优化白名单", "battery"}
        }));
        act.addView(rowButtons(new String[][]{
                {"通知权限", "notif"}, {"清空调试日志", "clearlog"}
        }));
        col.addView(act);

        // 日志
        LinearLayout logCard = card();
        logCard.addView(cardTitle("运行日志"));
        tvLog = new TextView(this);
        tvLog.setTextSize(11.5f);
        tvLog.setTextColor(theme.textSub());
        tvLog.setLineSpacing(0, 1.15f);
        logCard.addView(tvLog);
        col.addView(logCard);

        return controlScroll;
    }

    /** 长文本设置行：名称为主，右侧最多 2 行预览；点击弹出可滚动的多行编辑框。 */
    private LinearLayout makeRowText(final String name, final String key, final String hint) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(name); t.setTextSize(13.5f); t.setTextColor(theme.text());
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(t);
        final TextView v = new TextView(this);
        v.setTextSize(12.5f);
        v.setGravity(Gravity.END);
        v.setMaxLines(2);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setTag(key);
        top.addView(v, new LinearLayout.LayoutParams(dp(170), -2));
        row.addView(top);
        if (hint != null && !hint.isEmpty()) {
            TextView h = new TextView(this);
            h.setText(hint); h.setTextSize(11f); h.setTextColor(theme.textSub());
            row.addView(h);
        }
        applyTextPreview(v, key);
        row.setOnClickListener(x -> editSettingText(name, key, (TextView) v));
        return row;
    }

    private void applyTextPreview(TextView v, String key) {
        String s = String.valueOf(currentSetting(key));
        if (s.trim().isEmpty()) { v.setText("未设置"); v.setTextColor(theme.textSub()); }
        else { v.setText(s.replace("\n", " ⏎ ")); v.setTextColor(theme.accent()); }
    }

    /** 多行文本编辑：默认 5 行、最多 12 行，外层可滚动，避免长内容撑破对话框。 */
    private void editSettingText(final String name, final String key, final TextView view) {
        final EditText et = new EditText(this);
        et.setText(String.valueOf(currentSetting(key)));
        et.setTextColor(theme.text());
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setMinLines(5);
        et.setMaxLines(12);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setHorizontallyScrolling(false);
        ScrollView sv = new ScrollView(this);
        sv.addView(et, new android.widget.FrameLayout.LayoutParams(-1, -2));
        sv.setPadding(dp(16), dp(8), dp(16), dp(8));
        new AlertDialog.Builder(this).setTitle("修改 " + name).setView(sv)
                .setPositiveButton("保存", (d, w) -> {
                    String v = et.getText().toString().trim();
                    putSetting(key, v);
                    applyTextPreview(view, key);
                    toast(v.isEmpty() ? "已清空，将只新建对话" : "已保存（" + v.length() + " 字）");
                })
                .setNegativeButton("取消", null).show();
    }

    private LinearLayout makeRow(final String name, final String key) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(13.5f);
        t.setTextColor(theme.text());
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(t);
        TextView v = new TextView(this);
        v.setText(String.valueOf(currentSetting(key)));
        v.setTextSize(13.5f);
        v.setTextColor(theme.accent());
        v.setTag(key);
        row.addView(v);
        row.setOnClickListener(x -> editSetting(name, key, (TextView) v));
        return row;
    }

    private LinearLayout makeRowCycle(final String name, final String key, final String[] values, final String hint) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        TextView t = new TextView(this);
        t.setText(name); t.setTextSize(13.5f); t.setTextColor(theme.text());
        col.addView(t);
        TextView h = new TextView(this);
        h.setText(hint); h.setTextSize(11f); h.setTextColor(theme.textSub());
        col.addView(h);
        row.addView(col);
        final TextView v = new TextView(this);
        v.setText(String.valueOf(currentSetting(key)));
        v.setTextSize(13.5f);
        v.setTextColor(theme.accent());
        row.addView(v);
        row.setOnClickListener(x -> {
            String cur = String.valueOf(currentSetting(key));
            int idx = 0;
            for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) idx = i;
            String next = values[(idx + 1) % values.length];
            putSetting(key, next);
            v.setText(next);
            DeepSeekController.get().configure(
                    String.valueOf(currentSetting("thinking_mode")), String.valueOf(currentSetting("search_mode")));
            toast(name + " → " + next);
        });
        return row;
    }

    private View makeSwitch(final String name, String desc, final String key) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        TextView t = new TextView(this);
        t.setText(name); t.setTextSize(13.5f); t.setTextColor(theme.text());
        col.addView(t);
        TextView h = new TextView(this);
        h.setText(desc); h.setTextSize(11f); h.setTextColor(theme.textSub());
        col.addView(h);
        row.addView(col);
        Switch sw = new Switch(this);
        boolean defOn = "keep_screen".equals(key) || "stateless".equals(key) || "auto_newchat".equals(key) || "mcp_enabled".equals(key)
                || "pip_background".equals(key);
        sw.setChecked(Util.prefs(this).getBoolean(key, defOn));
        sw.setOnCheckedChangeListener((b, checked) -> {
            Util.prefs(this).edit().putBoolean(key, checked).apply();
            if ("pip_background".equals(key)) applyPipParams();
            if ("keep_screen".equals(key)) {
                if (checked) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            if ("auto_msg_enabled".equals(key) && checked && !AutoMsg.hasText(this)) {
                toast("已开启，但「自动消息内容」为空，将只新建对话");
            }
            if ("floating_ball".equals(key)) {
                if (checked) {
                    if (!ApiService.canOverlay(MainActivity.this)) {
                        Toast.makeText(MainActivity.this, "请先授予「悬浮窗」权限，授权后重新打开本开关", Toast.LENGTH_LONG).show();
                        try {
                            startExternal(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName())), -1);
                        } catch (Exception ignored) {}
                        sw.setChecked(false);   // 待授权后再开
                        return;
                    }
                    ApiService.start(MainActivity.this);
                    ApiService svc = ApiService.instance();
                    if (svc != null) svc.showFloatingBall();
                } else {
                    ApiService svc = ApiService.instance();
                    if (svc != null) svc.hideFloatingBall();
                }
            }
        });
        row.addView(sw);
        return row;
    }

    /** 单个账号子卡：名称 + 状态灯 + 启用开关 + 上次使用/调用次数 + 去登录/用此账号按钮。 */
    private LinearLayout accountCard(final int id) {
        LinearLayout card = card();
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        row1.setPadding(0, dp(4), 0, dp(4));
        TextView name = new TextView(this);
        name.setText(AccountManager.name(id));
        name.setTextSize(14f);
        name.setTypeface(null, Typeface.BOLD);
        name.setTextColor(theme.text());
        name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row1.addView(name);
        TextView st = new TextView(this);
        st.setTextSize(12.5f);
        st.setTag("acc_status_" + id);
        row1.addView(st);
        Switch sw = new Switch(this);
        sw.setChecked(AccountManager.enabled(id));
        sw.setOnCheckedChangeListener((b, on) -> AccountManager.setEnabled(id, on));
        row1.addView(sw);
        card.addView(row1);
        TextView meta = new TextView(this);
        meta.setTextSize(12f);
        meta.setTextColor(theme.textSub());
        meta.setTag("acc_meta_" + id);
        card.addView(meta);
        card.addView(rowButtons(new String[][]{
                {"去登录", "acc_login_" + id}, {"用此账号", "acc_use_" + id}
        }));
        return card;
    }

    private LinearLayout rowButtons(String[][] items) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, 0);
        for (String[] it : items) {
            TextView b = new TextView(this);
            b.setText(it[0]);
            b.setTextSize(13f);
            b.setTextColor(theme.text());
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(10), dp(10), dp(10), dp(10));
            b.setBackground(strokeBg(theme.btnBg(), theme.btnStroke(), 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            b.setLayoutParams(lp);
            final String action = it[1];
            b.setOnClickListener(v -> onAction(action));
            row.addView(b);
        }
        return row;
    }

    /* ================= 设置读写 ================= */

    private Object currentSetting(String key) {
        android.content.SharedPreferences sp = Util.prefs(this);
        switch (key) {
            case "port": return sp.getInt("port", 8787);
            case "apikey": return sp.getString("api_key", "sk-deepseek");
            case "timeout": return sp.getInt("timeout", 300);
            case "thinking_mode": return sp.getString("thinking_mode", "on");
            case "search_mode": return sp.getString("search_mode", "off");
            case "context_tokens": return sp.getInt("context_tokens", 1000000);
            case "newchat_threshold": return sp.getInt("newchat_threshold", 70);
            case "cooldown_min": return AccountManager.cooldownMinutes();
            case "auto_msg_text": return sp.getString("auto_msg_text", "");
        }
        return "";
    }

    private void putSetting(String key, String value) {
        android.content.SharedPreferences.Editor e = Util.prefs(this).edit();
        if ("port".equals(key)) { try { e.putInt("port", Integer.parseInt(value)); e.remove("port_active"); } catch (Exception ignored) {} }
        else if ("timeout".equals(key)) { try { e.putInt("timeout", Integer.parseInt(value)); } catch (Exception ignored) {} }
        else if ("context_tokens".equals(key)) { try { e.putInt("context_tokens", Integer.parseInt(value)); } catch (Exception ignored) {} }
        else if ("newchat_threshold".equals(key)) { try { e.putInt("newchat_threshold", Integer.parseInt(value)); } catch (Exception ignored) {} }
        else if ("cooldown_min".equals(key)) { try { AccountManager.setCooldownMinutes(Integer.parseInt(value)); } catch (Exception ignored) {} }
        else e.putString(key, value);
        e.apply();
    }

    private void editSetting(final String name, final String key, final TextView view) {
        final EditText et = new EditText(this);
        et.setText(String.valueOf(currentSetting(key)));
        et.setTextColor(theme.text());
        et.setInputType(("port".equals(key) || "timeout".equals(key) || "context_tokens".equals(key)
                || "newchat_threshold".equals(key) || "cooldown_min".equals(key)) ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle("修改 " + name).setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    String v = et.getText().toString().trim();
                    putSetting(key, v);
                    if ("cooldown_min".equals(key)) {
                        view.setText(String.valueOf(AccountManager.cooldownMinutes()));
                        toast("切换冷却已设为 " + AccountManager.cooldownMinutes() + " 分钟");
                    } else {
                        view.setText(v);
                        if ("port".equals(key)) { toast("端口已修改，重启服务后生效"); }
                        else if ("apikey".equals(key)) { toast("API Key 已修改"); }
                    }
                    refreshStats();
                })
                .setNegativeButton("取消", null).show();
    }

    /* ================= 操作 ================= */

    /** 确保服务与 WebView 就绪后再执行动作（「去登录/用此账号」前置）。
     * 未启动时拉起服务并轮询等待 WebView 创建完成（最多 6 秒），再执行业务。 */
    private void ensureWebReadyThen(final Runnable r) {
        if (WebHost.get() != null) {
            r.run();
            return;
        }
        toast("正在启动服务并加载网页…");
        ApiService.start(this);
        handler.postDelayed(new Runnable() {
            int tries = 0;
            @Override public void run() {
                if (WebHost.get() != null || tries++ >= 12) {
                    r.run();
                    return;
                }
                handler.postDelayed(this, 500);
            }
        }, 500);
    }

    private void onAction(String action) {
        // 多账号动态动作：acc_login_N / acc_use_N
        if (action != null && action.startsWith("acc_login_")) {
            int id = Integer.parseInt(action.substring("acc_login_".length()));
            ensureWebReadyThen(() -> {
                WebHost.switchTo(id, true);
                switchTab(TAB_WEB);
                toast("已清除登录态，请在网页中登录「" + AccountManager.name(id) + "」，登录成功自动保存快照");
                refreshStats();
            });
            return;
        }
        if (action != null && action.startsWith("acc_use_")) {
            int id = Integer.parseInt(action.substring("acc_use_".length()));
            ensureWebReadyThen(() -> {
                WebHost.switchTo(id, false);
                toast("正在切换到「" + AccountManager.name(id) + "」并恢复登录态…");
                refreshStats();
            });
            return;
        }
        switch (action) {
            case "copy_base": copy("http://127.0.0.1:" + currentSetting("port") + "/v1"); break;
            case "copy_key": copy(String.valueOf(currentSetting("apikey"))); break;
            case "copy_mcp": copy("http://127.0.0.1:" + currentSetting("port") + "/mcp"); break;
            case "start_svc": ApiService.start(this); toast("正在启动服务…"); handler.postDelayed(this::refreshStats, 800); break;
            case "stop_svc": ApiService.stop(this); toast("服务已停止"); handler.postDelayed(this::refreshStats, 800); break;
            case "new_chat": doNewChat(); break;
            case "reload": WebHost.reload(); toast("正在重载网页…"); break;
            case "access": openAccessibility(); break;
            case "battery": requestBatteryWhitelist(); break;
            case "notif": requestNotifPermission(); break;
            case "clearlog": Util.clearLog(); refreshStats(); toast("日志已清空"); break;
            case "backup_export": {
                String f = AccountManager.exportBackup(this);
                toast(f != null ? "备份已导出 → Download/" + f : "导出失败，详见日志");
                break;
            }
            case "backup_import": {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.addCategory(Intent.CATEGORY_DEFAULT);
                startExternal(i, REQ_BACKUP);
                break;
            }
        }
    }

    private void doNewChat() {
        ApiService.resetContext();
        new Thread(() -> {
            JSONObject r = AutoMsg.newChatWithAutoMsg(MainActivity.this);
            final String tip = AutoMsg.describe(r);
            handler.post(() -> toast(tip));
        }).start();
    }

    private void copy(String s) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("ds", s));
        toast("已复制: " + s);
    }

    private void openAccessibility() {
        try {
            startExternal(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), -1);
            toast("请开启「API 桥保活服务」以获得更强后台存活");
        } catch (Exception e) { toast("无法打开无障碍设置"); }
    }

    private void requestBatteryWhitelist() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startExternal(i, -1);
                }
            }
        } catch (Exception ignored) {}
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }
    }

    /* ================= 状态刷新 ================= */

    private void refreshStats() {
        if (tvService == null) return;
        tvService.setText(ApiService.serviceRunning ? ApiService.state() : "未运行（点击启动服务）");
        tvWeb.setText(WebHost.isReady() ? "已加载 chat.deepseek.com" : (WebHost.get() != null ? "加载中…" : "未初始化"));
        tvLogin.setText(ApiService.isLoggedInText());
        tvCalls.setText("总 " + ApiService.totalCallsText());
        tvCtx.setText(ApiService.contextInfoText());
        tvLast.setText(ApiService.lastCallText());
        tvBase.setText("http://127.0.0.1:" + currentSetting("port") + "/v1");
        tvKey.setText(String.valueOf(currentSetting("apikey")));
        if (tvMcp != null) tvMcp.setText("http://127.0.0.1:" + currentSetting("port") + "/mcp  " + (Util.prefs(this).getBoolean("mcp_enabled", true) ? "[已启用]" : "[已关闭]"));
        if (tvAccNow != null) {
            int cur = AccountManager.current();
            tvAccNow.setText("账号" + (cur + 1) + " · " + AccountManager.name(cur) + "  " + AccountManager.statusDot(cur) + AccountManager.statusText(cur));
        }
        for (int i = 0; i < AccountManager.COUNT; i++) {
            TextView st = (TextView) controlScroll.findViewWithTag("acc_status_" + i);
            if (st != null) {
                st.setText(AccountManager.statusDot(i) + " " + AccountManager.statusText(i));
                String dot = AccountManager.statusDot(i);
                int color = 0xFFB26A00;
                if ("⚪".equals(dot)) color = 0xFF909090;
                else if ("🟢".equals(dot)) color = 0xFF2E7D32;
                else if ("🟠".equals(dot)) color = 0xFFE65100;
                else if ("🔴".equals(dot)) color = 0xFFB71C1C;
                st.setTextColor(color);
            }
            TextView meta = (TextView) controlScroll.findViewWithTag("acc_meta_" + i);
            if (meta != null) {
                meta.setText("上次使用 " + (AccountManager.lastUsed(i) > 0 ? Util.timeHM(AccountManager.lastUsed(i)) : "—")
                        + " · 调用 " + AccountManager.calls(i) + " 次");
            }
        }
        if (tvLog != null) tvLog.setText(Util.logText());
    }

    /* ================= 文件选择 ================= */

    private boolean onShowFileChooser(ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams params) {
        if (filePathCallback != null) { filePathCallback.onReceiveValue(null); }
        filePathCallback = cb;
        try {
            Intent intent = params.createIntent();
            startExternal(intent, REQ_FILE);
            return true;
        } catch (Exception e) {
            filePathCallback = null;
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    List<Uri> list = new ArrayList<>();
                    for (int i = 0; i < n; i++) list.add(data.getClipData().getItemAt(i).getUri());
                    results = list.toArray(new Uri[0]);
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
        if (requestCode == REQ_BACKUP) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                int n = AccountManager.importBackup(this, data.getData());
                toast(n >= 0 ? "导入成功：" + n + " 个账号" : "导入失败（错误码 " + n + "），详见日志");
                refreshStats();
            }
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    /** 小工具：让设置读写也能走 Util.prefs 的包装。 */
    private static class SharedPreferencesHelper {
        private final android.content.Context ctx;
        SharedPreferencesHelper(android.content.Context c) { this.ctx = c; }
    }
}
