package com.hiweny.dswebapi;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * 新建对话 +（可选）自动消息的统一入口。
 *
 * <p>行为：
 * <ul>
 *   <li>「开启DS破甲提示词」开关关闭，或内容为空 → 仅新建对话（等价于 {@link DeepSeekController#newChat()}）。</li>
 *   <li>开关开启且内容非空 → 新建对话后在新会话串行发送该消息并等待回复完成。</li>
 * </ul>
 *
 * <p>供 MainActivity（手动新建）、ApiService（上下文达阈值自动轮换 / 上下文超限重试）、
 * McpHandler（MCP new_chat 工具）复用，避免各处的行为分叉。
 *
 * <p>返回的 JSONObject 至少包含 {@code newChatOk}、{@code ok}；自动发送时另外带
 * {@code sent / sessionId / content / thinking / error}（字段定义见 bridge.js 的 newChatGreeting 事件）。
 */
public final class AutoMsg {

    /** 开关：新会话是否自动发送预设内容。 */
    public static final String KEY_ENABLED = "auto_msg_enabled";
    /** 预设内容。 */
    public static final String KEY_TEXT = "auto_msg_text";

    private AutoMsg() {}

    private static SharedPreferences prefs(Context ctx) {
        return Util.prefs(ctx);
    }

    /**
     * 一次性迁移旧版存储键 {@code auto_msg_content} → {@link #KEY_TEXT}。
     * v11 及更早版本用 auto_msg_content，v12 起改名；不迁移会导致升级后已配置内容显示为空。
     * 旧键保留不删，便于回退到旧版本后仍可读取。
     */
    public static void migrateLegacyPrefs(Context ctx) {
        if (ctx == null) return;
        try {
            SharedPreferences sp = prefs(ctx);
            if (sp.contains(KEY_TEXT)) return;
            String legacy = sp.getString("auto_msg_content", "");
            if (legacy != null && !legacy.isEmpty()) {
                sp.edit().putString(KEY_TEXT, legacy).apply();
                Util.log("已迁移旧版自动消息内容(" + legacy.length() + "字) → " + KEY_TEXT);
            }
        } catch (Throwable ignored) {}
    }

    /** 开关是否开启（默认关）。 */
    public static boolean enabled(Context ctx) {
        return ctx != null && prefs(ctx).getBoolean(KEY_ENABLED, false);
    }

    /** 预设内容原文（未 trim）。 */
    public static String text(Context ctx) {
        return ctx == null ? "" : prefs(ctx).getString(KEY_TEXT, "");
    }

    /** 是否配置了非空内容。 */
    public static boolean hasText(Context ctx) {
        String t = text(ctx);
        return t != null && !t.trim().isEmpty();
    }

    /**
     * 新建对话；若已开启自动消息且内容非空，则发送并等待回复完成。
     *
     * @param ctx 任意 Context（内部取 applicationContext，避免持有 Activity）
     * @return 非 null 的 JSONObject；即使异常也返回带 error 的结果，便于调用方统一判空
     */
    public static JSONObject newChatWithAutoMsg(Context ctx) {
        Context app = ctx == null ? null : ctx.getApplicationContext();
        try {
            DeepSeekController c = DeepSeekController.get();
            // 关闭自动消息、内容为空、或拿不到 Context → 退化为普通新建对话
            if (app == null || !enabled(app) || !hasText(app)) {
                return c.newChat();
            }
            int timeout = prefs(app).getInt("timeout", 300);
            return c.newChatWithGreeting(text(app).trim(), timeout);
        } catch (Throwable e) {
            JSONObject o = new JSONObject();
            try {
                o.put("ok", false);
                o.put("newChatOk", false);
                o.put("error", "AUTO_MSG_FAIL:" + e.getMessage());
            } catch (Exception ignored) {}
            return o;
        }
    }

    /** 把 {@link #newChatWithAutoMsg} 的结果转成一句给用户看的提示。 */
    public static String describe(JSONObject r) {
        if (r == null) return "操作失败";
        boolean newChatOk = r.optBoolean("newChatOk", r.optBoolean("ok", false));
        if (!newChatOk) {
            String e = r.optString("error", "");
            return "新建对话失败" + (e.isEmpty() ? "（请在对话页手动操作）" : "（" + e + "）");
        }
        if (!r.optBoolean("sent", false)) {
            return "已新建对话（上下文计数已重置）";
        }
        if (r.optBoolean("ok", false)) {
            String c = r.optString("content", "").trim().replace('\n', ' ');
            if (c.length() > 60) c = c.substring(0, 60) + "…";
            return "已新建对话并自动发送" + (c.isEmpty() ? "" : "：" + c);
        }
        String e = r.optString("error", "");
        return "已新建对话，自动消息未收到回复" + (e.isEmpty() ? "" : "（" + e + "）");
    }
}
