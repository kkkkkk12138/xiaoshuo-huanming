# Android 核心体验修复设计

## 文档状态

- 目标版本：Android `0.2.0 (3)`
- 基线版本：Android `0.1.1 (2)`
- 技术路线：Jetpack Compose + Material 3 + 统一 WebView 阅读内核
- 范围：公开网页链接、TXT、无 DRM 可重排 EPUB
- 不在本次范围：网站登录、其他浏览器 Cookie、OCR 悬浮覆盖、DRM EPUB

## 问题背景

115 秒华为 P70 实机录屏证明测试文件和系统文件选择器工作正常，主要问题来自 App 本身。录屏中的关键现象如下：

| 时间 | 现象 | 判断 |
|---|---|---|
| 约 0–20 秒 | 首页进入文件选择器并打开 TXT | 文件输入链路可用 |
| 约 24–64 秒 | 新增规则、切换输入框、点击生效 | 操作步骤多，缺少校验和状态反馈 |
| 约 68 秒 | “宝宝”被替换为“11” | 替换引擎并非整体失效 |
| 约 80 秒 | 返回首页后最近阅读显示 `document:1000037924` | 文件显示名解析错误，进度仍为 0% |
| 约 92–115 秒 | 用户在夸克复制 URL，回到 App 点击“打开网页链接”无反应 | 首页 URL 回调为空实现 |

`0.1.1` 已修复阅读页按钮在窄屏/大字体下挤成一行，以及设置入口覆盖状态栏的问题。本设计继续处理尚未解决的核心流程和整体界面层级。

## 修复目标

用户应能在不理解 WebView、规则运行时或文件 URI 的前提下完成以下流程：

```text
打开公开链接或本地文件
→ 阅读内容
→ 新增或编辑换名规则
→ 明确知道规则是否有效
→ 回到正文立即看到结果
→ 下次从真实标题和正确进度继续阅读
```

完成标准：

- 首页的两个主要入口均可用，不存在点击无反应。
- 规则不完整时不能提交，并说明具体原因。
- 点击生效后必须出现“成功、零匹配或失败”之一。
- 规则应用和数据库保存对用户表现为一个完整操作，不留下半完成状态。
- 最近阅读显示真实文件名或网页标题，并保存 TXT/EPUB 进度。
- `320dp` 宽度和 `2.0` 字体缩放下仍可完成全部操作。

## 信息架构

### 首页

首页使用单一 `Scaffold`，包含：

- 顶部应用栏：产品名称与设置入口。
- 主操作区：
  - `打开网页链接`
  - `打开 TXT / EPUB`
- 最近阅读列表。
- 底部隐私说明。

取消额外悬浮的“设置”文字，不再通过 `AdaptiveReaderChrome` 在内容上方叠加独立入口。

### 阅读页

阅读页分为三层：

1. 顶部应用栏：返回、当前标题、更多菜单。
2. 中间阅读区：WebView，占据剩余空间。
3. 底部操作栏：规则；EPUB 场景增加上一章、目录、下一章。

阅读设置放入顶部“更多”菜单，不再占用永久工具栏位置。紧凑屏幕优先保留“规则”，章节操作可在两行内排列；`2.0` 字体缩放时“规则、上一章、目录、下一章”文字完整可见并可操作。

### 规则面板

手机使用完全展开的 `ModalBottomSheet`，平板和折叠屏使用右侧支持面板。两种形态共享以下结构：

- 固定标题区：`换名规则`、规则数量、关闭。
- 可滚动规则列表。
- 固定操作区：新增规则、状态信息、主按钮。
- 键盘出现时使用 `imePadding()`，主按钮不得被键盘遮挡。

手机端使用 `rememberModalBottomSheetState(skipPartiallyExpanded = true)`。高度不足、横屏或分屏时，标题保留关闭能力，规则数量可换行；操作区不强制固定，整个内容降级为可滚动布局。聚焦输入框使用 `BringIntoViewRequester`，按钮只规定最小触控高度 `48dp`，不限制文字换行后的实际高度。

## 网页链接入口

### 交互

点击“打开网页链接”后显示 URL 输入对话框：

- 单行 URL 输入框。
- `粘贴`按钮，仅在用户点击时读取剪贴板。
- `取消`和`打开`。
- 输入框支持键盘“完成”。

打开过程：

```text
用户输入或粘贴
→ 去除首尾空白
→ URL 安全校验
→ HTTPS 直接打开
→ HTTP 显示未加密确认
→ 创建阅读会话
```

### 校验

- 接受公开 `https://`。
- `http://` 必须二次确认。
- 拒绝空文本、相对地址、凭据 URL、私网 IP、localhost、`.local`、`file:`、`content:`、`javascript:`、`intent:` 和自定义协议。
- 校验错误显示在输入框下方，不使用瞬时 Toast 代替表单错误。
- 对话框确认后仍复用现有 `InputResolver` 和 `UrlPolicy`，不得创建第二套 URL 规则。
- 初始 URL、HTTP 30x 重定向和后续主框架导航均执行同一策略；HTTPS 降级到 HTTP 时重新确认。
- 本次不宣称通过域名解析阻止所有 DNS rebinding；安全承诺限定为 URI 解析、危险协议、私网/回环 IP 字面量和逐次导航控制。

URL 输入值保持单行横向滚动，标签和错误允许多行。`320dp + 2.0` 字体时，对话框内容可滚动，按钮可改为纵向排列，并使用 `imePadding()` 与 `BringIntoViewRequester` 保证当前输入框可见。

### WebView 安全边界

远程网页模式继续执行：

- 禁止 `file://`、`content://`、`javascript:`、`intent:` 和自定义协议。
- `allowFileAccess = false`、`allowContentAccess = false`。
- `allowFileAccessFromFileURLs = false`、`allowUniversalAccessFromFileURLs = false`。
- `mixedContentMode = MIXED_CONTENT_NEVER_ALLOW`。
- 不使用 `addJavascriptInterface` 暴露原生能力。
- 禁止新窗口、下载、客户端证书和忽略 SSL 错误。

TXT/EPUB 使用 `WebViewAssetLoader` 的受控 HTTPS origin。远程页面不能访问本地阅读资源；EPUB 脚本和远程资源继续默认禁用。

本版本不承诺登录态、Cookie 导入或第三方登录流程可用。登录保护的最小可测条件为：

- 每次远程主框架导航开始时，WebView 保持隐藏且不可触摸；安全检查通过后才显示并恢复交互，避免检查完成前输入敏感信息。
- 主框架 URL path 按 `/` 分段并忽略大小写；独立片段等于 `login`、`signin`、`passport` 或 `auth` 时，在加载前显示阻止页。
- 其他页面主框架提交后执行只读检查；若提交时已存在可见的 `input[type=password]`，立即 `stopLoading()`、清除焦点并导航到受控空白页，再显示阻止页。
- 阻止页显示“不支持在本应用内登录”，提供`返回首页`和`打开其他公开链接`。
- 检测不读取输入值，不记录表单内容。
- 该规则不承诺识别提交后由 SPA 动态插入的密码框或所有登录页；未满足上述条件的页面仍只受 URL 与 WebView 安全策略控制。

验收测试使用本地固定页面：一个 path 为 `/login`，另一个普通 path 在提交时包含可见密码输入框。两者都必须出现阻止页；检查被人为延迟时，WebView 仍不可触摸，密码框不能获得焦点或接收输入。

## 规则编辑

### 草稿模型

`RuleEditorState` 增加：

```kotlin
runtimeState: RuntimeState
validationErrors: Map<RuleId, RuleValidationError>
applyState: ApplyState
hasUnsavedChanges: Boolean
readerSessionId: String
runtimeGeneration: Long
applyGeneration: Long
```

状态定义：

```text
RuntimeState = Loading | Ready | Failed(ReaderError) | OutOfSync(ReaderError)
ApplyState = Idle | Applying | Success(summary) | Failed(ReaderError)
```

`hasUnsavedChanges` 比较规范化后的草稿与持久规则，按稳定 RuleId 和显示顺序判断。配置变化保留草稿；进程重建只恢复已持久化规则，不恢复未提交草稿。

每次打开阅读内容生成新的 `readerSessionId`，每次创建 WebView 或提交新的主框架 DOM 递增 `runtimeGeneration`，每次应用规则递增 `applyGeneration`。所有 WebView 回调必须匹配当前会话与 runtime generation；规则应用回调还必须匹配 apply generation，才能更新 UI、关闭面板或保存结果。离开页面后到达的旧回调直接丢弃。

关闭存在未保存草稿的面板时显示：

```text
放弃本次修改？
[继续编辑] [放弃修改]
```

### 输入行为

- 新增规则后自动聚焦“原名”。
- 原名键盘动作为“下一步”，跳到新名。
- 新名键盘动作为“完成”，收起键盘。
- 输入内容实时保留，不因切换编辑行丢失。
- 点击摘要行原地编辑，其他行保持摘要。
- 删除最后一条规则后仍保留“应用并恢复原文”能力。

### 校验

提交前统一执行：

- 原名去除首尾空白后不能为空。
- 新名去除首尾空白后不能为空。
- 原名和新名不能完全相同。
- 不允许两个有效规则使用相同原名。
- 无效行显示行内错误，不再静默过滤。

提交时先生成 `normalizedRules`：原名和新名均执行共享的 `sharedTrim()`；校验、持久化和 Runtime 使用同一份规范化结果。重复原名按规范化后的精确文本、区分大小写比较，RuleId 不因内容修剪而变化。

### 匹配算法

- 原名按未经 Unicode NFC/NFD 规范化的 UTF-16 code unit 序列做字面量匹配，不解释为正则，不执行大小写折叠或区域化转换。
- 匹配区分大小写。
- 每个原始文本节点执行单次从左到右扫描。
- 匹配不跨相邻 Text 节点、元素边界、Shadow DOM 或 iframe。
- 同一位置可匹配多个原名时，优先 UTF-16 code unit 数更长的规则；长度相同时按用户规则顺序，后者仅作为确定性兜底。
- 命中后消耗原名长度，重叠区间不再二次命中。
- 替换结果不再次进入本轮匹配，因此 `A → B` 与 `B → C` 不会把原文 A 级联成 C。
- `perRule` 按每次从原文命中的规则累加，所有规则计数之和等于 `replacementCount`。
- Android 与 TypeScript 不分别调用平台默认 `trim()`；规范化使用共享的固定空白字符表和同一组契约测试，避免特殊空白字符在平台间产生差异。

Safari 与 Android 必须复用同一 TypeScript 文本引擎契约，避免两个平台对相同规则产生不同结果。

### 规则作用域

首版规则是全局规则集，不绑定文档、网站或阅读会话：

- 打开任意网页、TXT 或 EPUB 时自动加载并应用全部持久规则。
- 切换文档不会清除规则。
- 在任意阅读内容中修改规则会影响之后打开的所有内容。
- 文档级规则和网站级规则不在本次范围，数据库不新增作用域字段。

主按钮规则：

- 运行时未就绪：禁用，显示`正在准备阅读内容`。
- 存在校验错误：禁用。
- 正在应用：禁用，显示加载状态。
- 草稿为空但此前有规则：显示`应用并恢复原文`。
- 其他情况：显示`保存并生效`。

系统返回规则：

- IME 可见时先收起键盘。
- 面板打开且有未保存修改时显示放弃确认。
- `Applying` 时禁止关闭面板；离开阅读页则作废当前 generation。
- `RuntimeState.Failed` 提供`重新载入内容`和`返回首页`。

### 运行时重建

`RuntimeState.Ready` 只表示“当前主框架 DOM 已安装 Runtime，且数据库中的持久规则已经成功应用，正文与持久状态同步”。新 WebView、配置变化导致的 WebView 重建或新的主框架提交统一执行：

```text
递增 runtimeGeneration
→ RuntimeState.Loading
→ 将旧 ApplyState.Applying 重置为 Idle，并作废旧 applyGeneration
→ 加载数据库规则
→ 安装 Runtime
→ 从不可变原文应用持久规则
→ 成功后 RuntimeState.Ready
```

初始规则应用失败进入 `RuntimeState.Failed`，不得短暂以 Ready 展示未替换正文。旧 runtime/apply generation 的回调一律丢弃；远程页面在上述初始化和登录检查都完成前保持隐藏且不可触摸。

## 规则生效事务

### 返回结果

TypeScript 运行时返回：

```ts
type ApplySuccess = {
  ok: true;
  activeRuleCount: number;
  changedTextNodeCount: number;
  replacementCount: number;
  perRule: Array<{
    ruleId: string;
    replacementCount: number;
  }>;
};
```

`replacementCount` 是当前已加载 DOM 中实际替换的文本出现次数，不是改变过的节点数。TXT 采用有限 DOM，因此不能声称扫描了整本书。

Android `RuleRuntime` 返回结构化 `RuleApplyResult`，不再只返回 `Result<Unit>`。

`runtime.applyRules(rules)` 的语义必须是：先从每个节点保存的不可变原文快照恢复，再完整应用新规则；禁止在上一次替换结果上叠加。每个文本节点保存原文与规则版本，同一节点在同一 generation 中最多处理一次，Observer 忽略由当前引擎提交产生的 mutation。

运行时仅处理当前主文档中已加载、可访问且属于正文范围的文本节点。排除 `script`、`style`、`noscript`、`textarea`、`input`、`contenteditable`、规则 UI 和跨域 iframe；Shadow DOM 首版不计入。

### 一致性流程

设应用前持久规则为 `previousRules`，用户草稿为 `draftRules`。Room 的 `replaceAll()` 必须在单个 `@Transaction` 中完成，数据库是跨进程恢复时唯一持久事实源：

```text
1. 规范化并校验 draftRules
2. 确认 RuntimeState.Ready，生成 applyGeneration
3. runtime.applyRules(draftRules)，从不可变原文重算当前 DOM
4. repository.replaceAll(draftRules)
5. 同时更新 persisted/draft，清除未保存标记
6. 关闭规则面板并显示结果
```

失败处理：

- 第 3 步失败：数据库不变，保留草稿、当前编辑行和输入选择，显示结构化运行时错误。
- 第 4 步失败：立即 `runtime.applyRules(previousRules)` 从不可变原文重算正文；数据库保持原值，保留草稿。
- 回滚失败：`runtimeState = OutOfSync(error)`、`applyState = Failed(error)`，禁止继续编辑，提示重新载入；重新载入成功后从数据库规则和原文确定性重建正文，并转为 `runtimeState = Ready`、`applyState = Idle`。
- 进程在步骤之间终止：DOM 随页面销毁，重启后始终从数据库规则重建，不保留临时草稿。
- 迟到回调的会话、runtime generation 或 apply generation 不匹配时不得更新任何状态。
- 操作成功时数据库和正文一致；失败或进程中断时可以从数据库与原文恢复，不静默保留不一致状态。

### 结果反馈

成功且有匹配：

```text
已保存 1 条规则，当前已加载内容替换 3 处
```

成功但零匹配：

```text
规则已保存，当前已加载内容未找到“宝宝”；继续阅读时仍会自动匹配
```

全部规则删除：

```text
已清除规则并恢复当前内容
```

成功后自动关闭规则面板，以 Snackbar 展示结果。失败时面板保持打开；`draftRules`、当前编辑 RuleId、输入框 selection 和焦点保持不变，只有对应规则被删除时才把焦点移动到下一条可编辑规则。

成功结果同时保存在当前阅读会话的 `lastApplyResult`，Snackbar 只是短期提示。规则面板再次打开时仍显示最近一次结果，直到用户再次编辑规则或结束阅读会话；TalkBack 使用 live region 宣读结果，配置变化不得重复弹出 Snackbar。

## 动态内容

运行时持有当前有效规则。`MutationObserver` 发现新增正文节点后自动应用规则。

计数口径：

- 点击“保存并生效”返回当前已加载 DOM 的同步匹配数。
- 后续动态加载的匹配继续替换，但不连续弹 Snackbar。
- 规则面板再次打开时可显示“当前规则已启用”，不累计一个无法验证的整本书总数。

## 最近阅读

### 标题

新增 `DocumentMetadataResolver`：

- TXT/EPUB 优先查询 `OpenableColumns.DISPLAY_NAME`。
- 查询失败时使用 URI 最后一段。
- 仍无法得到名称时使用 `TXT 文档`或`EPUB 文档`。
- 网页初始使用主机名；页面标题安全获取后更新为真实标题。

禁止把 `document:1000037924` 这类系统文档 ID 直接展示给用户。

所有标题先移除 C0/C1 控制字符和双向覆盖控制符，将换行折叠为空格，并限制为 100 个用户感知字符。URI fallback 先 percent-decode；结果为 `document:<id>`、纯数字 ID 或空白时直接使用通用名称。网页标题只有在当前会话主框架成功提交后才能更新，迟到标题不得覆盖新页面。

打开已有记录时使用 `upsertMetadataPreservingProgress()`，只更新标题、URI 和最后打开时间，不把已有进度写回 `0.0`。仅首次创建记录时初始化零进度；取得有效阅读位置后再通过 `saveProgress()` 更新进度字段。

### TXT 进度

TXT 的进度坐标统一定义为：解码后、替换前原文的 UTF-16 code unit 偏移。不得使用文件字节数、Unicode code point 数或替换后 DOM 文本长度。`TxtReaderDocument` 暴露 `totalUtf16Units`，每个渲染片段及其 Text 节点携带精确的 `sourceStart/sourceEnd`。

规则引擎为每个变换后的 Text 节点维护位置映射段：`sourceStart/sourceEnd` 与 `renderedStart/renderedEnd`。未替换文本一一映射；替换文本内部的位置按边界吸附到对应原名区间，区间起点映射原名起点、区间终点映射原名终点，中间位置按最近边界确定，确保映射单调且可逆到稳定锚点。

`characterOffset()` 使用 `caretPositionFromPoint()`，在不支持时回退 `caretRangeFromPoint()`，取得视口顶部安全采样点对应的 Text 节点及渲染后 UTF-16 offset，再通过上述映射换算为原文 offset。恢复时先定位包含原文 offset 的片段，通过反向映射得到当前渲染节点位置，再使用 `Range` 定位字符并滚动到视口。不得按片段高度比例推算字符偏移；修改、清除规则或替换文本长度变化时，原文锚点保持不变。

WebView 滚动时使用节流后的 `evaluateJavascript` 调用：

```js
window.__TXT_READER__.characterOffset()
```

保存：

- 当前原文 UTF-16 偏移。
- `scrollRatio = if (totalUtf16Units == 0) 0.0 else (offset / totalUtf16Units).coerceIn(0.0, 1.0)`。
- 最后打开时间。

触发时机：

- 滚动过程中每两秒最多持久化一次最近确认偏移。
- App 进入后台时尽力保存，不承诺进程被系统强杀前一定收到回调。
- 关闭阅读页时最多等待 300ms 获取新偏移；超时后保存最近确认值并返回。
- 每次保存携带递增 sequence。`ReadingProgressCoordinator` 在接收回调时丢弃旧 sequence，并通过单一 actor/mutex 串行执行 Room 写入，旧写入不得晚于新写入提交。

正常返回优先保存 300ms 内取得的新值；超时或进程强杀时，最多回退到最近两秒内已确认的位置。

首页显示：

```text
文件名.txt
TXT · 37%
```

### EPUB 与网页

- EPUB 在章节切换、滚动停止两秒、App 进入后台和关闭阅读页时保存章节 ID 与章节内比例。章节内比例定义为 `scrollY / max(1, scrollHeight - clientHeight)`，短于视口、非有限数或越界值统一规范为 `0.0` 或限制到 `[0.0, 1.0]`。
- 章节切换时先保存旧章最终位置，再切换并恢复新章。
- 重开 EPUB 时优先恢复保存章节；章节不存在时回退目录第一章并将比例置零，回退成功后立即把新章节和 `0.0` 持久化，避免后续重复失效回退。
- 同一字体和屏幕配置下，恢复比例与保存比例误差不得超过 `0.05`；布局配置变化时保证恢复到同一章节。
- 网页保留 URL；页面标题加载成功后更新。
- 返回首页前按上述 300ms 上限尝试获取新进度；超时后使用最近确认值再切换页面。

## 加载和错误状态

TXT/EPUB 解析期间不再临时显示一个无按钮的首页，而显示明确加载页：

```text
正在打开《文件名》
[取消]
```

取消必须终止解析 Job、关闭输入流并删除未完成缓存，然后返回首页。

错误分为：

- URL 格式或安全策略错误。
- 文件编码需要选择。
- EPUB 损坏、固定版式或 DRM。
- 阅读运行时加载失败。
- 规则保存失败。

错误使用结构化类型：

```kotlin
data class ReaderError(
    val code: ReaderErrorCode,
    val userMessage: String,
    val recoveryAction: RecoveryAction,
)

enum class RecoveryAction {
    RetryRuntime,
    RetryApply,
    ReopenDatabaseAndRetryApply,
    ReloadDocument,
    EditUrl,
    SelectEncoding,
    SelectAnotherFile,
    ReturnHome,
}
```

测试断言错误码和恢复动作，不依赖完整中文文案。每个错误必须提供重试、修改 URL、选择编码、重新选文件或返回首页中的至少一个下一步。

错误映射固定为：

| 场景 | ReaderErrorCode | RecoveryAction |
|---|---|---|
| URL 为空或格式错误 | `INVALID_URL` | `EditUrl` |
| URL 被安全策略拒绝 | `UNSAFE_URL` | `EditUrl` |
| TXT 编码无法确定 | `TXT_ENCODING_REQUIRED` | `SelectEncoding` |
| TXT 无法读取 | `TXT_READ_FAILED` | `SelectAnotherFile` |
| EPUB 损坏或结构非法 | `EPUB_INVALID` | `SelectAnotherFile` |
| EPUB 使用 DRM | `EPUB_DRM_UNSUPPORTED` | `SelectAnotherFile` |
| EPUB 为固定版式 | `EPUB_FIXED_LAYOUT_UNSUPPORTED` | `SelectAnotherFile` |
| 阅读运行时加载失败 | `RUNTIME_LOAD_FAILED` | `RetryRuntime` |
| 规则运行时应用失败且 Runtime 仍可用 | `RULE_APPLY_FAILED` | `RetryApply` |
| Room 规则保存失败且正文回滚成功 | `RULE_SAVE_FAILED` | `ReopenDatabaseAndRetryApply` |
| Room 保存失败且正文回滚失败 | `RUNTIME_OUT_OF_SYNC` | `ReloadDocument` |
| 文件读取权限失效 | `FILE_PERMISSION_LOST` | `SelectAnotherFile` |

`RetryApply` 重新执行完整的规则应用事务。`ReopenDatabaseAndRetryApply` 先重建数据库连接，再从规范化草稿重新执行 Runtime 应用、Room 保存和失败回滚，不允许只补写数据库。若重试前 Runtime 已失效，则升级为 `ReloadDocument`。

## 模块调整

新增：

```text
reader/
  ReaderUiState.kt
  RuleApplicationCoordinator.kt
  RuleValidation.kt
intake/
  UrlEntryDialog.kt
  DocumentMetadataResolver.kt
library/
  ReadingProgressCoordinator.kt
```

调整：

- `MainActivity`：只负责系统入口和顶层导航，移出文件元数据与进度细节。
- `HomeScreen`：接入 URL 对话框和真实最近阅读。
- `ReaderScreen`：改用 Scaffold 顶部栏/底部栏，消费统一 Reader UI state。
- `RuleEditorSheet`：实现焦点、键盘、行内校验和固定操作区。
- `ReaderViewModel`：保存草稿状态，调用 `RuleApplicationCoordinator`。
- `WebRuntimeController`：解析结构化应用结果。
- TypeScript runtime/text engine：返回实际出现次数。

## 数据兼容

- Room 数据库从版本 1 迁移到版本 2。
- `ReaderSessionEntity` 新增可空字段 `textOffset: Long?`、`textTotalAtSave: Long?`。
- `MIGRATION_1_2` 执行 `ALTER TABLE reader_sessions ADD COLUMN textOffset INTEGER` 和 `ALTER TABLE reader_sessions ADD COLUMN textTotalAtSave INTEGER`；SQLite `INTEGER` 对应 Kotlin `Long?`。
- `@Database(version = 2, exportSchema = true)`，生产 `Room.databaseBuilder` 必须注册 `MIGRATION_1_2`，不得使用 `fallbackToDestructiveMigration`。
- 新 TXT 记录同时保存原文 UTF-16 偏移、保存时总长度和 `scrollRatio`。
- 打开 TXT 时，如果保存总长度与当前总长度一致，优先恢复 `textOffset`；不一致时按旧 `scrollRatio × 当前总长度`恢复，并限制在合法范围。
- 版本 1 的旧记录两个新字段均为 null，首次打开按 `scrollRatio` 恢复，下一次进度保存后补齐字段。
- 首页百分比使用 `(ratio * 100).roundToInt().coerceIn(0, 100)`。
- Migration 1→2 只增加可空列，不删除或重建现有规则和阅读记录。
- 使用 `MigrationTestHelper` 创建真实 v1 数据库，写入规则及 TXT/EPUB 历史，迁移后由 Room 校验最终 schema，并断言旧数据保留、新字段为 null。
- 现有规则和最近阅读必须继续可读。
- Android 版本提升为 `0.2.0 (3)`。
- Debug Beta 可覆盖 `0.1.1 (2)`；正式 Release 仍需长期签名密钥。

## 无障碍与适配

- 所有主要点击区域不小于 `48dp`。
- `320dp、360dp、412dp` 宽度均可完成 URL、文件和规则流程。
- 字体缩放覆盖 `1.0、1.3、1.5、2.0`。
- 高度覆盖 `480dp、640dp`、横屏和分屏模式。
- 键盘出现时输入框和主操作按钮可见。
- `2.0` 字体下“规则”“保存并生效”“取消”“打开”完整可见，不使用省略号隐藏核心动作。
- TalkBack 能读出按钮用途、规则摘要、错误和应用结果。
- 状态栏、导航栏、刘海和挖孔区域使用系统 Insets。
- 用户可在设置中关闭动态颜色，关闭后使用固定的内置 light/dark 色板。

## 测试设计

### JVM 单元测试

- URL 输入去空白、HTTPS、HTTP 确认、危险协议和私网拒绝。
- 规则空值、同名、重复原名校验。
- 应用成功、运行时失败、数据库失败及回滚失败。
- 新主框架/WebView 的 Loading→Ready 状态转移，以及初始持久规则应用失败。
- 应用中旋转屏幕、跳转主框架和 Runtime 重建后，旧 generation 回调不能覆盖新草稿、新页面或新阅读会话，也不能永久停留在 Applying。
- TXT 偏移到百分比计算。
- 文件显示名解析降级顺序。
- 已有 37% TXT 记录重新打开时，元数据更新不得在首帧加载期间把进度覆盖为零。
- Room v1→v2 真实数据库迁移、旧数据保留和新字段 null。

### TypeScript 契约测试

- 返回出现次数而非仅节点数。
- 同一节点多次匹配正确计数。
- 多规则、最长原名优先、同长度用户顺序。
- `A → B、B → C`不发生级联。
- 重叠匹配只归属最终选中的规则。
- Emoji/代理对按 UTF-16 匹配；`é` 与 `e + combining acute` 不互相匹配。
- 跨相邻 Text 节点的原名不匹配；特殊空白字符的规范化在 Android 与 TypeScript 一致。
- 动态插入节点继续替换。
- Observer 不重复处理自身 mutation，不重复累计匹配数。
- 长替换、短替换和清除规则后原文坐标不变化。
- 恢复原文后计数归零。

### Compose 仪器测试

- 点击首页 URL 按钮显示输入对话框。
- 粘贴、错误提示和成功打开。
- 新增规则自动聚焦。
- IME 下一步/完成。
- 空规则和重复规则不能提交。
- 成功后关闭面板并显示 Snackbar。
- 零匹配提示不误导为整本书零匹配。
- `320dp + 2.0` 字体下按钮、输入框和错误均可见。
- `320×480dp`、横屏和分屏下，IME 打开且最后一条规则显示多行错误时仍可提交或返回。
- 最近阅读显示真实文件名与进度。

### WebView/真机测试

- 华为 P70/HarmonyOS Android 兼容层。
- Pixel/标准 Android 15 模拟器。
- 至少一台小米、OPPO/vivo 或三星设备。
- TXT 小文件、大文件、GB18030、UTF-8。
- TXT Emoji、组合字符、CRLF、空文件和偏移越界。
- TXT 长段落、混合字号和规则导致文本伸缩时，保存与恢复仍基于原文 Text 节点 UTF-16 坐标。
- EPUB2、EPUB3、损坏和 DRM 文件。
- EPUB 短章节、字体变化、固定版式和失效章节二次重开。
- 公开 HTTPS 页面、HTTP 确认、逐跳重定向、危险协议和登录保护。
- 远程页面不能访问本地 TXT/EPUB 资源，本地 EPUB 不能执行脚本或加载远程资源。
- 覆盖安装后规则和历史不丢失。

## 验收场景

### TXT 换名

1. 首页选择测试文件 `小说.txt`，原文总长为 10000 个 UTF-16 code unit。
2. 加载页显示真实文件名。
3. 阅读页打开规则面板。
4. 输入`宝宝 → 11`。
5. 点击保存并生效。
6. 面板自动关闭。
7. Snackbar 显示当前已加载内容替换数量。
8. 正文立即出现`11`。
9. 滚动到原文偏移 3700 后返回首页，显示`小说.txt · TXT · 37%`。
10. 重新打开文件，恢复的原文 UTF-16 偏移为 3700，允许一个渲染段落内的视口误差。

### EPUB 续读

1. 打开测试 EPUB 的第三章并滚动到章节比例 `0.40`。
2. 正常返回首页后重新打开。
3. 恢复到第三章，比例误差不超过 `0.05`。
4. 删除测试 EPUB 的第三章后再次打开，回退到目录第一章和比例 `0.0`。

### 公开网页

1. 首页点击“打开网页链接”。
2. 点击粘贴并获得测试地址 `https://example.com/novel/chapter-1`。
3. URL 校验通过后进入阅读页。
4. 危险协议和私网/回环 IP 字面量被阻止。
5. 规则生效并返回当前已加载内容匹配数。

### 登录保护

1. 打开测试地址 `/login`，在加载前显示登录阻止页。
2. 打开普通 path 但含可见 `input[type=password]` 的固定页面，主框架提交后显示登录阻止页。
3. 两个阻止页均不能输入密码，并提供返回首页和打开其他链接。

### 失败回滚

1. 已持久化规则 A。
2. 编辑为规则 B。
3. 模拟数据库保存失败。
4. 正文恢复规则 A。
5. 面板保留规则 B 草稿并显示失败原因。
6. 重启 App 后持久化规则仍为 A。

## 发布策略

- 完成 TDD 后发布 `v0.2.0-android-beta`。
- GitHub Release 首屏只突出 APK 和完整 ZIP，不引导普通用户下载 Source code。
- 发布说明明确列出从 `0.1.1` 覆盖安装及数据保留验证。
- 华为 P70 完成完整录屏复测后，再判断是否存在鸿蒙专属 WebView 问题。
- 只有在相同问题无法在标准 Android 复现、且确认由鸿蒙兼容层限制导致时，才考虑将鸿蒙标记为非支持平台。
