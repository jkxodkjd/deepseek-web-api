# DeepSeekWebAPI 更新日志

> 记录每个版本的更新内容。最新版在最上面。
> APK 与源码包同步发布到 `/sdcard/Download/`。

---

## v10 — overlay 隐形容器版（2025-10-18）

**APK**: `DeepSeekWebAPI-ds自动破甲-overlay-v10.apk`（同 `DeepSeekWebAPI-最新版.apk`）
**源码**: `deepseek-web-api-source.zip`

### 更新内容
1. **后台不冻结根治方案 — 隐形容器（Overlay）**
   - 核心思路：WebView 被系统冻结是因为「窗口不可见」。把 WebView 从 Activity 抽出，挂到 ApiService 常驻的全屏透明隐形容器窗口上，让 WebView 始终认为自己「可见」，彻底绕开冻结/节流。
   - `ApiService.mountOverlayIfPossible()`：创建 `TYPE_APPLICATION_OVERLAY`（API<26 用 `TYPE_PHONE`）全屏窗口
   - 窗口参数：`MATCH_PARENT × MATCH_PARENT`、`alpha=0.01` 全透明、`FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` 触摸穿透、`FLAG_HARDWARE_ACCELERATED` 硬加速、`PixelFormat.TRANSLUCENT`
   - `WebHost.setOverlayParent()`：支持把 WebView 从 Activity 容器切换到 overlay 容器
   - `MainActivity` 四处挂载/摘除逻辑同步，Activity 仅在交互时「借回」WebView
   - 无悬浮窗权限时自动降级为 PiP（画中画）路径
2. **心跳自愈机制（Self-Heal）**
   - tick 漂移检测：监控 bridge 轮询节奏，检测到节流/漂移立即告警
   - 心跳探活：定时向 bridge 发探活请求，失败自动重注入 bridge.js 或刷新页面
3. **悬浮球保留**：180×180 可拖动悬浮球（点击唤回交互页）

### 实测验证
- 窗口 `fl=NOT_TOUCHABLE NOT_FOCUSABLE` + `isVisibleRequested=true` + `Surface shown=true`
- home 退后台 30 秒+：进程存活、ApiService 前台服务、窗口保持可见
- 注入 1s setInterval，6 秒精确 +6 → **零冻结**
- API 端点 `http://127.0.0.1:8999/v1` 运行中

---

## v9 — ds自动破甲版（2025-10-15）

**APK**: `DeepSeekWebAPI-ds自动破甲.apk`

### 更新内容
1. 绕过 DeepSeek 官网对非官方 API 调用的降智检测（「自动破甲」）
2. 多账号支持完善：账号切换等待、超时返回 503
3. Prompt 超长自动截断（omittedChars/omittedImages 记录）

---

## v8 — 切换修复版（2025-10-14）

**APK**: `DeepSeekWebAPI-画中画版-切换修复版.apk`
**源码**: `DeepSeekWebAPI-画中画版-源码-切换修复版.zip`

### 更新内容
1. 修复账号切换竞态：切换中重复请求会等待切换完成（`awaitSwitchReady` 30s 超时）
2. 单账号模式跳过切换流程直接 `markUsed`

---

## v7 — 登录修复版（2025-10-14）

**APK**: `DeepSeekWebAPI-画中画版-登录修复版.apk` / `DeepSeekWebAPI-画中画版-登录轮询修复版.apk`

### 更新内容
1. 修复官网登录态误报问题
2. 登录轮询机制修复，登录状态实时感知

---

## v6 — 多账号版（2025-10-14）

**APK**: `DeepSeekWebAPI-画中画版-带MCP-多账号.apk`
**源码**: `DeepSeekWebAPI-画中画版-源码-多账号.zip`

### 更新内容
1. 多账号切换：`acc != AccountManager.current()` 时自动切换账号
2. 账号就绪等待（`awaitAccountReady` 30s 超时）

---

## v5 — MCP 集成版（2025-10-14）

**APK**: `DeepSeekWebAPI-画中画版-带MCP.apk`
**源码**: `DeepSeekWebAPI-画中画版-源码-带MCP.zip`

### 更新内容
1. 内置 MCP Server（`127.0.0.1:8999/mcp`），支持远程调用 WebView 操作
2. 工具集：get_logs / clear_logs / get_status / eval_js / query_dom / reload / reinject_bridge / probe / new_chat

---

## v4 — 画中画版（2025-10-14）

**APK**: `DeepSeekWebAPI-画中画版.apk` 系列

### 更新内容
1. PiP（画中画）降级路径：无悬浮窗权限时把 WebView 放入画中画窗口避免冻结
2. 悬浮球雏形

---

## v3 — v1.0.9（2025-10-11）

**APK**: `DeepSeekWebAPI-v1.0.9.apk`
**源码**: `DeepSeekWebAPI-源码-1.0.9-pip.zip`

### 更新内容
1. WebView 后台保活增强
2. 基础反代 API 稳定性修复

---

## v2 — v1.0.7（2025-10-09）

**APK**: `DeepSeekWebAPI-v1.0.7.apk`

### 更新内容
1. 首个可安装版本
2. 基础 OpenAI 兼容接口 `/v1/chat/completions`

---

## v1 — 初始开发版（2025-10-09 前）

### 更新内容
1. 基于 deepseek-web-api 开源项目改造
2. 手机端 WebView 加载 chat.deepseek.com + 桥接 JS
