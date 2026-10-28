package com.hiweny.dswebapi;

import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 极简 MCP (Model Context Protocol 2024-11-05) 服务器。
 * 端点：POST http://127.0.0.1:8787/mcp
 * 支持 initialize / tools/list / tools/call。
 */
public final class McpHandler {

    private final ApiService svc;

    public McpHandler(ApiService svc) { this.svc = svc; }

    public void handle(HttpBridgeServer.Request req, HttpBridgeServer.Response res) throws Exception {
        // 开关检查：mcp_enabled 为 false 时直接拒绝
        try {
            if (svc != null && !Util.prefs(svc).getBoolean("mcp_enabled", true)) {
                res.sendJson(403, "{\"ok\":false,\"error\":\"MCP disabled\"}");
                return;
            }
        } catch (Throwable ignored) {}
        String method = req.method == null ? "" : req.method;

        if ("GET".equalsIgnoreCase(method)) {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("server", "yuanbao-debug-mcp");
            o.put("protocol", "2024-11-05");
            o.put("hint", "POST JSON-RPC 2.0 to this endpoint");
            res.sendJson(o.toString());
            return;
        }
        if (!"POST".equalsIgnoreCase(method)) {
            res.sendJson(405, "{\"error\":\"method not allowed\"}");
            return;
        }

        JSONObject request;
        try {
            String body = req.body == null ? "" : req.body.trim();
            if (body.isEmpty()) { res.sendJson(400, "{\"error\":\"empty body\"}"); return; }
            request = new JSONObject(body);
        } catch (Exception e) {
            res.sendJson(400, "{\"error\":\"bad json\"}");
            return;
        }

        Object id = request.opt("id");
        String rpcMethod = request.optString("method", "");
        JSONObject params = request.optJSONObject("params");
        if (params == null) params = new JSONObject();

        JSONObject resp = new JSONObject();
        resp.put("jsonrpc", "2.0");
        if (id != null) resp.put("id", id);

        try {
            if ("initialize".equals(rpcMethod)) resp.put("result", onInitialize());
            else if ("tools/list".equals(rpcMethod)) resp.put("result", onToolsList());
            else if ("tools/call".equals(rpcMethod)) resp.put("result", onToolsCall(params));
            else if ("notifications/initialized".equals(rpcMethod) || "initialized".equals(rpcMethod) || "ping".equals(rpcMethod)) resp.put("result", new JSONObject());
            else resp.put("error", err(-32601, "method not found: " + rpcMethod));
        } catch (Exception e) {
            resp.put("error", err(-32603, "internal error: " + e.getMessage()));
        }

        res.sendJson(resp.toString());
    }

    private JSONObject onInitialize() throws Exception {
        JSONObject r = new JSONObject();
        r.put("protocolVersion", "2024-11-05");
        JSONObject caps = new JSONObject();
        caps.put("tools", new JSONObject());
        r.put("capabilities", caps);
        JSONObject info = new JSONObject();
        info.put("name", "yuanbao-debug-mcp");
        info.put("version", "1.0.0");
        r.put("serverInfo", info);
        return r;
    }

    private JSONObject onToolsList() throws Exception {
        JSONArray tools = new JSONArray();
        tools.put(tool("get_logs", "获取 App 运行日志（最近 300 条）", new JSONObject(), new JSONArray()));
        tools.put(tool("clear_logs", "清空运行日志", new JSONObject(), new JSONArray()));
        tools.put(tool("get_status", "获取 App 运行状态", new JSONObject(), new JSONArray()));

        JSONObject pEval = new JSONObject();
        pEval.put("code", prop("string", "要在页面执行的 JS 源码"));
        tools.put(tool("eval_js", "在元宝页面执行任意 JS", pEval, new JSONArray().put("code")));

        JSONObject pDom = new JSONObject();
        pDom.put("selector", prop("string", "CSS 选择器"));
        pDom.put("attr", prop("string", "属性名，留空返回 outerHTML"));
        pDom.put("all", prop("boolean", "true 返回数组"));
        tools.put(tool("query_dom", "按 CSS 选择器查元素", pDom, new JSONArray().put("selector")));

        tools.put(tool("reload", "重新加载页面", new JSONObject(), new JSONArray()));
        tools.put(tool("reinject_bridge", "重新注入 bridge.js", new JSONObject(), new JSONArray()));
        tools.put(tool("probe", "触发状态探测", new JSONObject(), new JSONArray()));
        tools.put(tool("new_chat", "触发新建对话", new JSONObject(), new JSONArray()));

        JSONObject result = new JSONObject();
        result.put("tools", tools);
        return result;
    }

    private JSONObject onToolsCall(JSONObject params) throws Exception {
        String name = params.optString("name", "");
        JSONObject args = params.optJSONObject("arguments");
        if (args == null) args = new JSONObject();
        String text;
        boolean isErr = false;
        try {
            if ("get_logs".equals(name)) text = Util.logText();
            else if ("clear_logs".equals(name)) { Util.clearLog(); text = "日志已清空"; }
            else if ("get_status".equals(name)) text = toolGetStatus();
            else if ("eval_js".equals(name)) text = toolEvalJs(args.optString("code", ""));
            else if ("query_dom".equals(name)) text = toolQueryDom(args);
            else if ("reload".equals(name)) { WebHost.reload(); text = "已触发 reload"; }
            else if ("reinject_bridge".equals(name)) { DeepSeekController.get().injectBridge(); text = "已重新注入 bridge.js"; }
            else if ("probe".equals(name)) { DeepSeekController.get().probe(); text = "已触发 probe"; }
            else if ("new_chat".equals(name)) text = toolNewChat();
            else { text = "未知工具: " + name; isErr = true; }
        } catch (Exception e) {
            text = "工具执行异常: " + e.getMessage();
            isErr = true;
        }
        return contentResult(text, isErr);
    }

    private String toolGetStatus() throws Exception {
        JSONObject o = new JSONObject();
        o.put("service_running", ApiService.serviceRunning);
        o.put("state", ApiService.state());
        o.put("logged_in", ApiService.isLoggedInText());
        o.put("total_calls", ApiService.totalCallsText());
        o.put("webview_ready", WebHost.isReady());
        o.put("bridge_attached", DeepSeekController.get().isAttached());
        o.put("controller_busy", DeepSeekController.get().isBusy());
        WebView wv = WebHost.get();
        if (wv != null) {
            final String[] url = new String[]{"?"};
            final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
            wv.post(new Runnable() { public void run() { try { url[0] = wv.getUrl(); } catch (Throwable ignored) {} l.countDown(); } });
            l.await(2, java.util.concurrent.TimeUnit.SECONDS);
            o.put("webview_url", url[0]);
        } else {
            o.put("webview_url", JSONObject.NULL);
        }
        return o.toString(2);
    }

    private String toolEvalJs(String code) {
        if (code == null || code.trim().isEmpty()) return "错误：code 为空";
        String r = DeepSeekController.get().evalRaw(code, 15);
        return r == null ? "(null)" : r;
    }

    private String toolQueryDom(JSONObject args) {
        String sel = args.optString("selector", "").trim();
        if (sel.isEmpty()) return "错误：selector 为空";
        String attr = args.optString("attr", "").trim();
        boolean all = args.optBoolean("all", false);
        String qs = escJs(sel);
        String attrJs = escJs(attr);
        String js;
        if (all) {
            js = "(function(){var ns=document.querySelectorAll(\"" + qs + "\");var out=[];for(var i=0;i<ns.length;i++){var n=ns[i];var a=\"" + attrJs + "\";var v;if(!a){v=n.outerHTML;if(v&&v.length>2000)v=v.slice(0,2000)+'...';}else if(a==='text'||a==='textContent'){v=n.textContent;}else{v=n.getAttribute(a);if(v===null&&a in n)v=n[a];}out.push({tag:n.tagName,cls:(n.className||'').toString().slice(0,120),val:v});}return JSON.stringify(out);})();";
        } else {
            js = "(function(){var n=document.querySelector(\"" + qs + "\");if(!n)return 'NULL_OR_NOT_FOUND';var a=\"" + attrJs + "\";if(!a){var h=n.outerHTML;if(h&&h.length>2000)h=h.slice(0,2000)+'...';return h;}if(a==='text'||a==='textContent')return n.textContent;var v=n.getAttribute(a);if(v===null&&(a in n))v=String(n[a]);return v===null?'(null)':String(v);})();";
        }
        return DeepSeekController.get().evalRaw(js, 15);
    }

    private String toolNewChat() throws Exception {
        JSONObject r = AutoMsg.newChatWithAutoMsg(svc);
        return r == null ? "(null)" : r.toString();
    }

    private static JSONObject tool(String name, String desc, JSONObject props, JSONArray required) throws Exception {
        JSONObject t = new JSONObject();
        t.put("name", name);
        t.put("description", desc);
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", props == null ? new JSONObject() : props);
        schema.put("required", required == null ? new JSONArray() : required);
        t.put("inputSchema", schema);
        return t;
    }

    private static JSONObject prop(String type, String desc) throws Exception {
        return new JSONObject().put("type", type).put("description", desc);
    }

    private static JSONObject contentResult(String text, boolean isErr) throws Exception {
        JSONObject r = new JSONObject();
        JSONArray content = new JSONArray();
        JSONObject c = new JSONObject();
        c.put("type", "text");
        c.put("text", text == null ? "" : text);
        content.put(c);
        r.put("content", content);
        r.put("isError", isErr);
        return r;
    }

    private static JSONObject err(int code, String msg) throws Exception {
        JSONObject e = new JSONObject();
        e.put("code", code);
        e.put("message", msg);
        return e;
    }

    private static String escJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
