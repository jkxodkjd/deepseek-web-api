package com.hiweny.dswebapi;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 多账号轮询管理器：
 *  - 3 个账号（0/1/2），每个账号独立：启用开关、名称、登录态快照(cookie+localStorage)、最近使用时间、累计调用次数；
 *  - 调度器 pick()：在「已启用 且 已就绪」的账号里选 lastUsed 最小的（最久未用优先）；
 *  - 快照机制：Android CookieManager 全局共享，无法多 WebView 隔离登录态，
 *    因此走「单 WebView + 快照轮换」——切换账号 = 清 cookie + 灌入目标账号快照 + 重载官网；
 *  - 备份：把全部账号状态（含登录态快照）导出为 JSON 到 Download，可随时导入恢复。
 */
public final class AccountManager {
    public static final int COUNT = 3;
    public static final String DS_URL = "https://chat.deepseek.com/";

    private static volatile Context sCtx;
    private static volatile int sCurrent = 0;
    /** 上次账号切换时刻（毫秒）。切换冷却期内不轮询切换，避免「刚发完消息就被切走导致回复丢失」。 */
    private static volatile long sLastSwitchAt = System.currentTimeMillis();
    /** 切换冷却时长的缓存值（毫秒），0 表示未初始化，下次 cooldownMs() 从设置重算。 */
    private static volatile long sCooldownMs = 0L;
    /** 冷却分钟数的设置键与默认值。 */
    private static final String PREF_COOLDOWN_MIN = "cooldown_min";
    public static final int DEFAULT_COOLDOWN_MIN = 10;

    private AccountManager() {}

    /** 应用启动时调用一次：初始化默认账号名。 */
    public static void init(Context ctx) {
        sCtx = ctx.getApplicationContext();
        for (int i = 0; i < COUNT; i++) {
            if (prefs().getString(key(i, "_name"), "").isEmpty()) {
                prefs().edit().putString(key(i, "_name"), "账号" + (i + 1)).apply();
            }
        }
    }

    private static SharedPreferences prefs() {
        return sCtx.getSharedPreferences("dswebapi", Context.MODE_PRIVATE);
    }

    private static String key(int id, String suffix) { return "acct_" + id + suffix; }

    /* ================= 基础属性 ================= */

    public static String name(int id) { return prefs().getString(key(id, "_name"), "账号" + (id + 1)); }
    public static void setName(int id, String n) { prefs().edit().putString(key(id, "_name"), n).apply(); }
    public static boolean enabled(int id) { return prefs().getBoolean(key(id, "_enabled"), true); }
    public static void setEnabled(int id, boolean on) { prefs().edit().putBoolean(key(id, "_enabled"), on).apply(); }
    public static String cookieOf(int id) { return prefs().getString(key(id, "_cookie"), ""); }
    public static String lsOf(int id) { return prefs().getString(key(id, "_ls"), ""); }
    public static long lastUsed(int id) { return prefs().getLong(key(id, "_lastUsed"), 0L); }
    public static int calls(int id) { return prefs().getInt(key(id, "_calls"), 0); }
    public static boolean loggedIn(int id) { return prefs().getBoolean(key(id, "_loggedIn"), false); }
    /** 切换/恢复快照成功后标记已登录（快照有 userToken 即视为就绪）。 */
    public static void markLoggedIn(int id) { prefs().edit().putBoolean(key(id, "_loggedIn"), true).apply(); }

    public static int current() { return sCurrent; }
    public static void setCurrent(int id) { if (id >= 0 && id < COUNT) sCurrent = id; }

    /** 标记本次使用：更新最近使用时间 + 累计调用次数。 */
    public static void markUsed(int id) {
        prefs().edit()
                .putLong(key(id, "_lastUsed"), System.currentTimeMillis())
                .putInt(key(id, "_calls"), calls(id) + 1)
                .apply();
    }

    /** 读取用户自定义的切换冷却分钟数；未设置或非法时回退默认 10 分钟。 */
    public static int cooldownMinutes() {
        try {
            if (sCtx == null) return DEFAULT_COOLDOWN_MIN;
            int m = prefs().getInt(PREF_COOLDOWN_MIN, DEFAULT_COOLDOWN_MIN);
            return m > 0 ? m : DEFAULT_COOLDOWN_MIN;
        } catch (Throwable t) {
            return DEFAULT_COOLDOWN_MIN;
        }
    }

    /** 保存用户自定义的切换冷却分钟数（限 1~1440 分钟），并让缓存失效立即生效。 */
    public static void setCooldownMinutes(int minutes) {
        int m = minutes;
        if (m < 1) m = 1;
        if (m > 1440) m = 1440;
        if (sCtx != null) prefs().edit().putInt(PREF_COOLDOWN_MIN, m).apply();
        sCooldownMs = 0L;
        Util.log("切换冷却已设为 " + m + " 分钟");
    }

    /** 切换冷却时长（毫秒）：从用户设置换算，惰性缓存。 */
    public static long cooldownMs() {
        long cached = sCooldownMs;
        if (cached <= 0L) {
            cached = (long) cooldownMinutes() * 60L * 1000L;
            if (cached <= 0L) cached = (long) DEFAULT_COOLDOWN_MIN * 60L * 1000L;
            sCooldownMs = cached;
        }
        return cached;
    }

    /** 记录一次账号切换：重置冷却计时，并按当前用户设置刷新冷却时长。 */
    public static void noteSwitch() {
        sLastSwitchAt = System.currentTimeMillis();
        sCooldownMs = (long) cooldownMinutes() * 60L * 1000L;
        Util.log("账号切换冷却已重置：" + cooldownMinutes() + " 分钟");
    }

    /** 当前账号是否在切换冷却期内：冷却期内不切换到其他账号，避免频繁切换打断页面回复。 */
    public static boolean withinCooldown() {
        int cur = current();
        if (cur < 0 || !enabled(cur) || !hasSnapshot(cur)) return false;
        long now = System.currentTimeMillis();
        // 刚切换过：冷却期内不轮询
        if (now - sLastSwitchAt < cooldownMs()) return true;
        // 当前账号刚用过（lastUsed 距今 < 冷却）：继续用它，不切走
        if (now - lastUsed(cur) < cooldownMs()) return true;
        return false;
    }

    /* ================= 状态文本 / 调度器 ================= */

    /** 是否有有效登录态快照：DeepSeek 登录凭证核心在 localStorage.userToken，仅 cookie 无 token 不算。 */
    public static boolean hasSnapshot(int id) {
        return lsOf(id).contains("userToken");
    }

    /** 仅有 cookie 无 token 的残缺快照（导出过/导入过但抓取失败），用于 UI 提示。 */
    public static boolean hasCookieOnly(int id) {
        return !cookieOf(id).isEmpty() && !hasSnapshot(id);
    }

    /** 状态灯：只有「当前账号 + probe 确认真实在线」才亮绿；完整快照待验证亮黄；仅 cookie 残缺亮橙；否则灰/红。 */
    public static String statusDot(int id) {
        if (!enabled(id)) return "⚪";
        if (id == current() && loggedIn(id)) return "🟢";
        if (hasSnapshot(id)) return "🟡";
        if (hasCookieOnly(id)) return "🟠";
        return "🔴";
    }

    public static String statusText(int id) {
        if (!enabled(id)) return "已停用";
        if (id == current() && loggedIn(id)) return "就绪";
        if (hasSnapshot(id)) return "有快照";
        if (hasCookieOnly(id)) return "缺token";
        return "未登录";
    }

    /**
     * 调度器：最久未用优先。
     * 遍历「已启用 且 已就绪（有登录态快照）」的账号，取 lastUsed 最小的返回；无可用返回 -1。
     */
    public static int pick() {
        // 切换冷却期内：稳定使用当前账号（前提：当前账号已启用且就绪），不轮询切换
        if (withinCooldown()) return current();
        // 冷却期外：最久未用优先
        int best = -1;
        long oldest = Long.MAX_VALUE;
        for (int i = 0; i < COUNT; i++) {
            if (!enabled(i) || !hasSnapshot(i)) continue;
            long t = lastUsed(i);
            if (t < oldest) { oldest = t; best = i; }
        }
        return best;
    }

    public static boolean anyReady() { return pick() >= 0; }

    /* ================= 快照 ================= */

    /**
     * probe 结果归账：全局 WebView 的 probe 事件对应「当前账号」。
     * 记录登录态；登录成功且尚无 cookie 快照 → 自动抓取快照（登录一次，之后切换靠快照）。
     */
    public static void onProbe(boolean loggedIn) {
        int id = sCurrent;
        if (loggedIn) {
            prefs().edit().putBoolean(key(id, "_loggedIn"), true).apply();
            if (!hasSnapshot(id)) {
                Util.log("账号" + (id + 1) + " 登录成功，自动抓取登录态快照...");
                captureSnapshot(id);
            }
        } else if (hasSnapshot(id)) {
            // probe=false 但快照仍在：多为 UI 渲染时机误报（旧判据看输入框）。
            // 重新抓快照验证：真登出→新 ls 无 token→回调写 false；误报→token 仍在→保持绿。
            Util.log("账号" + (id + 1) + " probe=false 但有 token 快照，重新抓取验证...");
            captureSnapshot(id);
        } else {
            prefs().edit().putBoolean(key(id, "_loggedIn"), false).apply();
        }
    }

    /** 抓取当前 WebView 的登录态快照（cookie + localStorage），保存到账号 id。
     * cookie 同步记录；localStorage 异步回调补记（不阻塞主线程，避免 evaluateJavascript 回调死锁）。 */
    public static void captureSnapshot(final int id) {
        WebHost.runOnMain(() -> {
            try {
                String cookie = CookieManager.getInstance().getCookie(DS_URL);
                boolean hasCookie = cookie != null && !cookie.isEmpty();
                String ls0 = lsOf(id);
                // 仅当快照已完整（或本次 cookie 变化）才更新 cookie；loggedIn 由 localStorage 回调校验后决定
                prefs().edit()
                        .putString(key(id, "_cookie"), cookie == null ? "" : cookie)
                        .apply();
                Util.log("账号" + (id + 1) + " cookie 快照已保存 (" + (cookie == null ? 0 : cookie.length()) + "B) 当前 ls=" + (ls0 == null ? 0 : ls0.length()) + "B");
            } catch (Throwable t) {
                Util.log("抓取 cookie 失败: " + t.getMessage());
            }
            // localStorage 快照：异步 evaluateJavascript，回调里落盘（绝不能 await 主线程）
            try {
                String js = "(function(){var o={};try{for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);o[k]=localStorage.getItem(k);}}catch(e){}return JSON.stringify(o);})();";
                android.webkit.WebView wv = WebHost.get();
                if (wv == null) return;
                wv.evaluateJavascript(js, raw -> {
                    try {
                        String ls = decodeJsString(raw);
                        if (ls != null && ls.startsWith("{")) {
                            boolean hasToken = ls.contains("userToken");
                            prefs().edit()
                                    .putString(key(id, "_ls"), ls)
                                    .putBoolean(key(id, "_loggedIn"), hasToken)
                                    .apply();
                            Util.log("账号" + (id + 1) + " localStorage 快照已保存 (" + ls.length() + "B, token=" + hasToken + ")");
                        }
                    } catch (Throwable t) {
                        Util.log("localStorage 快照落盘失败: " + t.getMessage());
                    }
                });
            } catch (Throwable t) {
                Util.log("抓取 localStorage 失败: " + t.getMessage());
            }
        });
    }

    /** 恢复账号 id 的 cookie 到全局 CookieManager（调用方需已位于主线程）。
     * 注意 removeAllCookies 是异步的，必须在回调里再灌。onDone 在灌入完成后调用。 */
    public static void restoreCookies(int id) { restoreCookies(id, null); }

    public static void restoreCookies(final int id, final Runnable onDone) {
        final CookieManager cm = CookieManager.getInstance();
        try {
            // 同步覆盖方式：removeAllCookies 的异步回调可能延迟数秒到数十秒（WebView 主线程/JS 队列忙时），
            // 会让账号切换卡死。DeepSeek 登录态核心是 userToken，先删旧 token 再逐条同步覆盖快照 cookie 即可。
            try { cm.setCookie(DS_URL, "userToken=; Max-Age=0"); } catch (Throwable ignored) {}
            String cookie = cookieOf(id);
            if (cookie != null && !cookie.isEmpty()) {
                for (String part : cookie.split(";")) {
                    part = part.trim();
                    if (part.isEmpty()) continue;
                    try { cm.setCookie(DS_URL, part); } catch (Throwable ignored) {}
                }
            }
            cm.flush();
            Util.log("账号" + (id + 1) + " cookie 已灌入（同步 " + (cookie == null ? 0 : cookie.length()) + "B）");
        } catch (Throwable t) {
            Util.log("恢复 cookie 失败: " + t.getMessage());
        }
        if (onDone != null) onDone.run();
    }

    /** 生成「恢复 localStorage」的 JS 脚本。
     * 有快照：清空后灌入目标快照；无快照/空快照：只清空（必须清！否则上一账号的 userToken 残留 → 假登录/假绿）。 */
    public static String localStorageRestoreJs(int id) {
        final String CLEAR = "(function(){try{localStorage.clear();}catch(e){}return 'cleared';})();";
        String ls = lsOf(id);
        if (ls == null || ls.isEmpty()) return CLEAR;
        try {
            JSONObject o = new JSONObject(ls);
            if (o.length() == 0) return CLEAR;
            return "(function(){try{localStorage.clear();}catch(e){}var o=" + o + ";for(var k in o){try{localStorage.setItem(k,o[k]);}catch(e){}}return 'ok';})();";
        } catch (Throwable t) {
            Util.log("构造 localStorage 恢复脚本失败: " + t.getMessage());
            return CLEAR;
        }
    }

    /** 恢复账号 id 的 localStorage（页面加载完成后调用）。
     * 直接 evaluateJavascript 异步执行，主线程内调用也不会死锁。 */
    public static void applyLocalStorage(int id) {
        String js = localStorageRestoreJs(id);
        if (js == null) return;
        final android.webkit.WebView wv = WebHost.get();
        if (wv == null) return;
        WebHost.runOnMain(() -> {
            try { wv.evaluateJavascript(js, null); }
            catch (Throwable t) { Util.log("恢复 localStorage 失败: " + t.getMessage()); }
        });
    }

    /** 清空账号 id 的登录态记录（「去登录」重新登录时调用，旧快照作废）。 */
    public static void clearLoginState(int id) {
        prefs().edit()
                .putBoolean(key(id, "_loggedIn"), false)
                .putString(key(id, "_cookie"), "")
                .putString(key(id, "_ls"), "")
                .apply();
    }

    /* ================= 备份：导出 / 导入 ================= */

    /** 组装备份 JSON（含全部账号状态与登录态快照）。 */
    public static JSONObject exportJson() {
        JSONObject root = new JSONObject();
        try {
            root.put("app", "DSWebAPI_MultiAccount");
            root.put("version", 1);
            root.put("exportedAt", System.currentTimeMillis());
            JSONArray arr = new JSONArray();
            for (int i = 0; i < COUNT; i++) {
                JSONObject o = new JSONObject();
                o.put("id", i);
                o.put("name", name(i));
                o.put("enabled", enabled(i));
                o.put("loggedIn", loggedIn(i));
                o.put("cookie", cookieOf(i));
                o.put("ls", lsOf(i));
                o.put("lastUsed", lastUsed(i));
                o.put("calls", calls(i));
                arr.put(o);
            }
            root.put("accounts", arr);
        } catch (Exception e) {
            Util.log("备份组装失败: " + e.getMessage());
        }
        return root;
    }

    /** 导出备份到 Download（MediaStore，Android 10+ 安全写入）。成功返回文件名，失败返回 null。 */
    public static String exportBackup(Context ctx) {
        try {
            byte[] data = exportJson().toString().getBytes("UTF-8");
            String fname = "DSWebAPI_备份_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.CHINA)
                    .format(new java.util.Date()) + ".json";
            ContentResolver cr = ctx.getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fname);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            }
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return null;
            OutputStream os = cr.openOutputStream(uri);
            if (os == null) return null;
            os.write(data);
            os.flush();
            os.close();
            Util.log("备份已导出: " + fname);
            return fname;
        } catch (Exception e) {
            Util.log("导出备份失败: " + e.getMessage());
            return null;
        }
    }

    /** 从 Uri 导入备份。返回导入成功的账号数；-1 无法打开 -2 格式错误 -3 解析失败。 */
    public static int importBackup(Context ctx, Uri uri) {
        try {
            java.io.InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return -1;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            JSONObject root = new JSONObject(bos.toString("UTF-8"));
            JSONArray arr = root.optJSONArray("accounts");
            if (arr == null) return -2;
            SharedPreferences.Editor ed = prefs().edit();
            int cnt = 0;
            for (int i = 0; i < arr.length() && cnt < COUNT; i++) {
                JSONObject o = arr.getJSONObject(i);
                int id = o.optInt("id", cnt);
                if (id < 0 || id >= COUNT) id = cnt;
                ed.putString(key(id, "_name"), o.optString("name", "账号" + (id + 1)));
                ed.putBoolean(key(id, "_enabled"), o.optBoolean("enabled", true));
                String ck = o.optString("cookie", "");
                String ls = o.optString("ls", "");
                // 备份文件里的 loggedIn 不可信（可能误报），导入一律置 false；在线与否由切换后的 probe 实测
                ed.putBoolean(key(id, "_loggedIn"), false);
                ed.putString(key(id, "_cookie"), ck);
                ed.putString(key(id, "_ls"), o.optString("ls", ""));
                ed.putLong(key(id, "_lastUsed"), o.optLong("lastUsed", 0L));
                ed.putInt(key(id, "_calls"), o.optInt("calls", 0));
                cnt++;
            }
            ed.apply();
            Util.log("备份已导入 " + cnt + " 个账号");
            return cnt;
        } catch (Exception e) {
            Util.log("导入备份失败: " + e.getMessage());
            return -3;
        }
    }

    /** 把 evaluateJavascript 的字符串结果解码（外层 JSON 引号包裹还原）。 */
    private static String decodeJsString(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            try {
                JSONObject tmp = new JSONObject("{\"v\":" + s + "}");
                return tmp.optString("v");
            } catch (Exception e) {
                return s;
            }
        }
        return s;
    }
}
