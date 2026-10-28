# DeepSeek Web API 反代 · 踩坑记录（PITFALLS）

> 所有在开发/调试/维护这个项目时踩过的坑、根因、修复方法，按类别整理。
> 下次动手前先读一遍，能省掉大半排障时间。

---

## 一、多账号切换 / 请求卡死类（最致命，直接导致 chat/completions 零响应）

### 坑 1：切换太激进，刚发完信息就被切走 → 丢回复
- **现象**：多账号轮询时，每次请求都 pick「最久未用账号」并立即切换，上一轮回复还没流式生成完就被切走，客户端收到空响应/断连。
- **根因**：`AccountManager.pick()` 原来没有冷却概念，每次 handleChat 都可能触发 `WebHost.switchTo()`。
- **修复**：
  - 加 20~30 分钟随机冷却：`noteSwitch()` 记录切换时间，`withinCooldown()` 判断冷却期内不切。
  - `sLastSwitchAt` 初始化为 **App 启动时间**（不是 0！见坑 4）。
  - 冷却条件：距上次切换 < 冷却 **或** 当前账号 lastUsed 距今 < 冷却 → 继续用当前账号，不切走。
- **避免**：改切换策略只动 `AccountManager.pick()` / `withinCooldown()`，别改 `ApiService.handleChat` 的多账号块。

### 坑 2：awaitAccountReady 用 loggedIn 标志判定 → 卡满 30s 超时
- **现象**：日志显示切换流程完整走完（两次「页面加载完成」+「bridge loaded」= `sRestoreDone=true`），但请求仍卡 30 秒后超时，客户端已断开。
- **根因**：`awaitAccountReady(acc)` 判定条件含 `AccountManager.loggedIn(acc)`（prefs 的 `_loggedIn` 布尔标志）。账号**有快照**（`_cookie`/`_ls` 串有内容，pick 能选中它）但标志没同步为 true → 条件永远不满足。
- **修复**：判定改为 `hasSnapshot(acc) || loggedIn(acc)`；切换灌完快照后立即 `AccountManager.markLoggedIn(id)` 补标志（在 `WebHost.switchTo` 的 restoreCookies 回调里）。
- **核心规则**：**「就绪/可切换」判断一律用 `hasSnapshot()`**（快照有 token 即视为就绪），`_loggedIn` 只用于 UI 状态灯。

### 坑 3：画中画 / 后台节流导致 evaluateJavascript 回调不可靠
- **现象**：App 进画中画后，切换状态机 `sRestoreDone` 永远 false，所有请求卡 `awaitSwitchReady(30s)`；或灌 localStorage 的回调迟迟不触发，reload 不执行。
- **根因**：WebView 进画中画/退后台后 Chromium 节流，`evaluateJavascript` 的 ValueCallback 可能不回调或极慢。
- **修复**：
  - 切换流程设计为「灌 cookie（CookieManager 同步）→ loadUrl → onPageFinished → 灌 localStorage → reload → 第二次 onPageFinished → 就绪」，每步都有日志可查。
  - await 判定不依赖回调链路（见坑 2 的修复）。
- **避免**：看到日志「请求进入画中画」后，页面事件都会慢半拍，先看日志再下结论，别急着改超时时间。

### 坑 4：sLastSwitchAt 初始化为 0 → 启动即满足冷却 → 启动期就乱切
- **现象**：App 刚启动，WebView 还在加载，pick 就触发切换，切换卡死。
- **根因**：`sLastSwitchAt = 0`，`now - 0 > cooldown` 恒成立。
- **修复**：`sLastSwitchAt = System.currentTimeMillis()`（启动时间），启动后前 20~30 分钟不切换，给页面/bridge 稳定期。

### 坑 5：旧版本（1.0.9 单账号）可用，新版本多账号引入的问题
- **现象**：旧版源码 `WebHost.onPageFinished` 极简（页面加载完成 → 直接 injectBridge → 1 秒后 probe），稳定可用；新版加了 pending/restore 状态机后开始卡。
- **根因**：多账号切换本质 = 改 cookie + 改 localStorage + 重载页面，流程比单账号复杂得多，每一步依赖回调/时序。
- **经验**：
  - `DeepSeekController` 的发送/注入逻辑新旧一致，**发送逻辑本身没问题**，问题全在多账号块。
  - 对比源码时先 `diff` 列出文件差异，再定位新增逻辑，能快速缩小排查范围。

---

## 二、构建 / 安装类

### 坑 6：手改 applicationId 想「装成另一个版本」→ 数据目录独立，快照全丢
- **现象**：把 applicationId 从 `com.hiweny.dswebapi` 改成 `com.hiweny.dswebapkk`，装完发现 3 个账号的快照全部丢失（prefs 跟着包名走），端口也没起对。
- **根因**：Android 的 SharedPreferences / 数据目录按包名隔离，改包名 = 全新 App，旧数据不可见。
- **修复**：改回原包名重装。**想并存多版本用 `buildTypes` / `applicationIdSuffix`（如 `.beta`），绝不手改源码里的 applicationId**；调试完记得检查 `app/build.gradle`。

### 坑 7：pm install 读不了 /sdcard 上的 APK
- **现象**：`pm install -r /sdcard/Download/xxx.apk` 报 `System server has no access to read file context u:object_r:fuse:s0`。
- **根因**：system_server 对 fuse（sdcard）上下文的文件无读取权限。
- **修复**：先 `cp /sdcard/Download/xxx.apk /data/local/tmp/` 再 `pm install -r /data/local/tmp/xxx.apk`。

### 坑 8：gradle 构建参数（proot 环境专属）
- **必须**：`gradle assembleDebug --no-daemon -Dorg.gradle.vfs.watch=false -Djava.io.tmpdir=<项目>/.gradle-tmp`
  - `--no-daemon`：proot 下 daemon 模式有 VFS 问题。
  - `-Dorg.gradle.vfs.watch=false`：proot VFS 不支持文件监听。
  - `-Djava.io.tmpdir=项目目录/.gradle-tmp`：proot 的 /tmp 有 unlink 问题。
- **禁止**：构建命令用 `bash_exec_bg`（后台）——会被拒绝；必须用 `bash_exec`（自动获取构建锁）。

---

## 三、测试 / 环境类

### 坑 9：proot 环境会杀死长耗时 curl 测试进程
- **现象**：用 `bash_exec_bg`/`nohup`/`setsid`/前台跑长 curl 盯页面，进程 3 次被杀（exit 137/28）。
- **根因**：proot 沙箱对长驻/长耗时进程不友好。
- **修复**：改用「MCP 8999 观察 + 短超时 curl + App 日志」组合：curl `-m 60` 前台跑，同时看 `mcp kk get_status/get_logs`。

### 坑 10：管道退出码陷阱
- **现象**：`curl ... | head -c 2000; echo EXIT=$?` 显示 EXIT=0，但 curl 其实没连上（8988 没监听）。
- **根因**：`$?` 是管道**最后一个命令**（head）的退出码，curl 的失败被吞了。
- **修复**：用 `${PIPESTATUS[0]}` 取第一个命令的退出码；或 curl 不带管道先看返回码。

### 坑 11：OPPO/ColorOS 冻结后台端口（8988）
- **现象**：端口 8988 监听一段时间后被系统冻结，请求连不上。
- **根因**：OPPO HansManager 对后台 App 的 socket 做冻结。
- **修复**：用户手动把 App 加入系统电池/后台白名单；`netstat -tlnp` 确认端口存活。

---

## 四、状态 / 日志判读类

### 坑 12：日志是排障第一手资料，先看日志再改代码
- 切换卡死时的标准日志序列（健康）：
  1. `页面加载完成: https://chat.deepseek.com/`（第一次，cookie 生效）
  2. `已灌入 账号N 登录态快照`（restoreCookies 回调）
  3. `页面加载完成`（第二次，localStorage 生效 + reload 完成）
  4. `JS: [DSWB] bridge loaded @ ...`（bridge 重注入 = `sRestoreDone=true`）
- 只有第 1 条没有第 2/3/4 条 → 卡在灌快照/回调；有第 3/4 条但请求还超时 → 查 await 判定条件（坑 2），不是查超时时间。

### 坑 13：MCP 状态字段速查
- `controller_busy: true` = 请求正卡在 DeepSeekController.sendPrompt。
- `webview_ready` / `bridge_attached` 都是 false = WebView 没起来，先看 MainActivity 启动日志。
- `webview_url` 停在首页 = 切换/灌快照流程没走完。

---

## 五、一句话清单（速查版）

| 症状 | 先查 | 修复指向 |
|---|---|---|
| 请求卡满 30s 超时 | 日志有没有两次「页面加载完成」 | 坑 2：await 判定用 hasSnapshot |
| 启动就切账号 | sLastSwitchAt 初始值 | 坑 4：初始化为启动时间 |
| 丢回复/空响应 | 是不是刚发完就切换 | 坑 1：20~30 分钟冷却 |
| 画中画后全卡 | 日志「请求进入画中画」 | 坑 3：别依赖回调，看日志 |
| 装完快照全丢 | applicationId 是不是被改了 | 坑 6：改回原包名 |
| pm install 失败 | 是不是读 /sdcard | 坑 7：先 cp 到 /data/local/tmp |
| curl 显示成功但没内容 | 是不是管道了 | 坑 10：用 ${PIPESTATUS[0]} |
| 端口连不上 | netstat 看监听 | 坑 11：加白名单 |
