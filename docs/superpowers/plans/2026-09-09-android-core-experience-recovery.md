# Android 核心体验恢复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Android `0.1.1 (2)` 恢复为首页入口可用、规则应用一致、URL/登录边界明确、TXT/EPUB 可可靠续读且在小屏大字下可完成操作的 `0.2.0 (3)`。

**Architecture:** Room 是跨进程唯一事实源，规则提交由独立协调器按“Runtime 先应用、Room 后提交、保存失败则 Runtime 回滚”执行；会话、runtime、apply 三层 generation 隔离迟到回调。公开网页与本地阅读继续共用受控 WebView，但 URL/登录门禁、元数据、TXT 原文坐标和 EPUB 章节位置分别由小型组件负责，Compose 只消费统一状态。

**Tech Stack:** Kotlin 2.3.10、Jetpack Compose / Material 3、Room 2.8.4、Hilt、AndroidX WebKit、Kotlin Coroutines、TypeScript 5.9、Vitest 2.1、JSDOM、JUnit 4、AndroidX Test。

**Design:** `docs/superpowers/specs/2026-09-09-android-core-experience-recovery-design.md`

---

## 开工约束与文件地图

- 工作目录固定为仓库根；Gradle 命令统一使用 `./android/gradlew -p android ...`。
- 每个任务严格 RED → GREEN → 受影响回归 → 独立提交；不得先改生产代码。
- 不使用 `fallbackToDestructiveMigration()`；不另建第二套 URL 校验；不把网页登录支持扩入本次范围。
- JVM 测试不得依赖中文完整文案，只断言 `ReaderErrorCode`、`RecoveryAction` 和可操作状态；Compose 测试可断言关键可见文字。
- `connectedDebugAndroidTest` 需要 API 36 模拟器或真机；无设备时该任务不算完成。
- Room schema 输出目录为 `android/app/schemas/`，版本 1 基线必须从当前 v1 实体导出并提交，不能手写一个与历史 APK 不一致的 schema。

计划新增的生产文件：

```text
android/app/src/main/java/com/xiaoshuo/yijianhuanming/
├── intake/DocumentMetadataResolver.kt
├── intake/UrlEntryDialog.kt
├── library/ReadingProgressCoordinator.kt
└── reader/
    ├── ReaderUiState.kt
    ├── RuleApplicationCoordinator.kt
    └── RuleValidation.kt
```

现有关键修改点：

```text
android/app/build.gradle.kts
android/app/src/main/java/com/xiaoshuo/yijianhuanming/MainActivity.kt
android/app/src/main/java/com/xiaoshuo/yijianhuanming/data/{AppDatabase,ReaderSessionDao,ReaderSessionEntity,RuleEntity}.kt
android/app/src/main/java/com/xiaoshuo/yijianhuanming/di/AppModule.kt
android/app/src/main/java/com/xiaoshuo/yijianhuanming/content/web/{LoginRiskPolicy,SecureWebViewClient}.kt
android/app/src/main/java/com/xiaoshuo/yijianhuanming/content/txt/TxtContentSource.kt
android/app/src/main/java/com/xiaoshuo/yijianhuanming/content/epub/EpubContentSource.kt
android/app/src/main/java/com/xiaoshuo/yijianhuanming/library/{LibraryViewModel,RecentReadingList}.kt
android/app/src/main/java/com/xiaoshuo/yijianhuanming/navigation/AppNavHost.kt
android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/{HomeScreen,ReaderScreen,ReaderViewModel,RuleEditorSheet,WebRuntimeController}.kt
android/app/src/main/assets/reader/local-reader.js
src/content/textEngine.ts
src/android-runtime/{runtime,types}.ts
tests/{android-runtime,textEngine}.test.ts
```

---

### Task 1: 锁定 Room v1 基线并实现 1→2 无损迁移

**Files:**
- Modify: `android/app/build.gradle.kts`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/data/AppDatabase.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/data/ReaderSessionEntity.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/di/AppModule.kt`
- Modify: `android/app/src/androidTest/java/com/xiaoshuo/yijianhuanming/data/AppDatabaseTest.kt`
- Create: `android/app/schemas/com.xiaoshuo.yijianhuanming.data.AppDatabase/1.json`
- Create: `android/app/schemas/com.xiaoshuo.yijianhuanming.data.AppDatabase/2.json`

- [ ] **Step 1: 先导出并冻结当前 v1 schema**

在 KSP 参数中加入：

```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
}
```

保持 `AppDatabase.version = 1`，运行：

```bash
./android/gradlew -p android :app:kspDebugKotlin
git diff --exit-code -- android/app/src/main android/app/src/test android/app/src/androidTest
```

Expected: 生成 `android/app/schemas/com.xiaoshuo.yijianhuanming.data.AppDatabase/1.json`；生产 Kotlin 文件无变化。先提交这个历史基线，避免迁移测试引用伪造 schema。

```bash
git add android/app/build.gradle.kts android/app/schemas
git commit -m "test(android): freeze room v1 schema"
```

- [ ] **Step 2: 写真实迁移 RED 测试**

在 `AppDatabaseTest` 增加 `MigrationTestHelper`，创建 v1 文件库并直接插入一条规则、一个 TXT 和一个 EPUB 会话：

```kotlin
@get:Rule
val migration = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(),
    AppDatabase::class.java,
)

@Test
fun migration_1_2_preserves_rules_and_history_and_adds_nullable_txt_offsets() {
    migration.createDatabase("migration-test", 1).apply {
        execSQL("""INSERT INTO rules VALUES ('r1','宝宝','林晚',0)""")
        execSQL(
            """INSERT INTO reader_sessions
               (sourceId,type,title,uri,chapterId,scrollRatio,lastOpenedAt)
               VALUES ('txt','TXT','小说.txt','content://txt',NULL,0.37,100)"""
        )
        execSQL(
            """INSERT INTO reader_sessions
               (sourceId,type,title,uri,chapterId,scrollRatio,lastOpenedAt)
               VALUES ('epub','EPUB','书','content://epub','c3',0.4,200)"""
        )
        close()
    }

    migration.runMigrationsAndValidate("migration-test", 2, true, MIGRATION_1_2).use { db ->
        db.query("SELECT id FROM rules").use { assertTrue(it.moveToFirst()); assertEquals("r1", it.getString(0)) }
        db.query(
            "SELECT sourceId,textOffset,textTotalAtSave FROM reader_sessions ORDER BY sourceId"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("epub", it.getString(0))
            assertTrue(it.isNull(1))
            assertTrue(it.isNull(2))
            assertTrue(it.moveToNext())
            assertEquals("txt", it.getString(0))
            assertTrue(it.isNull(1))
            assertTrue(it.isNull(2))
        }
    }
}
```

Run:

```bash
./android/gradlew -p android :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.xiaoshuo.yijianhuanming.data.AppDatabaseTest
```

Expected: RED；`MIGRATION_1_2` 或 v2 schema 不存在。

- [ ] **Step 3: 实现最小迁移并注册到生产数据库**

`ReaderSessionEntity` 追加：

```kotlin
val textOffset: Long? = null,
val textTotalAtSave: Long? = null,
```

`AppDatabase.kt` 改为 `version = 2, exportSchema = true`，并导出：

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE reader_sessions ADD COLUMN textOffset INTEGER")
        db.execSQL("ALTER TABLE reader_sessions ADD COLUMN textTotalAtSave INTEGER")
    }
}
```

`AppModule.provideDatabase()`：

```kotlin
Room.databaseBuilder(context, AppDatabase::class.java, "name-replacer.db")
    .addMigrations(MIGRATION_1_2)
    .build()
```

Run:

```bash
./android/gradlew -p android :app:kspDebugKotlin :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.xiaoshuo.yijianhuanming.data.AppDatabaseTest
```

Expected: GREEN；Room 校验 v2 schema，旧规则、TXT/EPUB 记录保留，新列为 null。

- [ ] **Step 4: 提交**

```bash
git add android/app/build.gradle.kts android/app/schemas \
  android/app/src/main/java/com/xiaoshuo/yijianhuanming/data/AppDatabase.kt \
  android/app/src/main/java/com/xiaoshuo/yijianhuanming/data/ReaderSessionEntity.kt \
  android/app/src/main/java/com/xiaoshuo/yijianhuanming/di/AppModule.kt \
  android/app/src/androidTest/java/com/xiaoshuo/yijianhuanming/data/AppDatabaseTest.kt
git commit -m "feat(android): migrate reader database to version two"
```

---

### Task 2: 统一规则规范化、校验与单次扫描契约

**Files:**
- Create: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/RuleValidation.kt`
- Create: `android/app/src/test/java/com/xiaoshuo/yijianhuanming/reader/RuleValidationTest.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/data/RuleEntity.kt`
- Modify: `android/app/src/test/java/com/xiaoshuo/yijianhuanming/data/RuleNormalizerTest.kt`
- Modify: `src/content/textEngine.ts`
- Modify: `tests/textEngine.test.ts`
- Modify: `tests/fixtures/name-replacement-contract.json`

- [ ] **Step 1: 写 Android 规范化与校验 RED**

定义测试所需 API：

```kotlin
enum class RuleValidationError { SOURCE_REQUIRED, TARGET_REQUIRED, SAME_VALUE, DUPLICATE_SOURCE }
data class ValidatedRules(
    val normalized: List<ReplaceRule>,
    val errors: Map<String, RuleValidationError>,
)
fun validateRules(rules: List<ReplaceRule>): ValidatedRules
fun sharedTrim(value: String): String
```

测试覆盖空原名、空新名、同名、规范化后重复、区分大小写、RuleId/顺序不变，以及固定空白表中的 ASCII whitespace、NBSP、BOM：

```kotlin
@Test fun duplicate_sources_are_reported_after_shared_trim() {
    val result = validateRules(listOf(
        ReplaceRule("a", "\u00A0宝宝 ", "甲", 0),
        ReplaceRule("b", "宝宝\uFEFF", "乙", 1),
    ))
    assertEquals(RuleValidationError.DUPLICATE_SOURCE, result.errors["a"])
    assertEquals(RuleValidationError.DUPLICATE_SOURCE, result.errors["b"])
    assertEquals(listOf("a", "b"), result.normalized.map { it.id })
}
```

Run:

```bash
./android/gradlew -p android :app:testDebugUnitTest \
  --tests "*RuleValidationTest" --tests "*RuleNormalizerTest"
```

Expected: RED；校验类型和 `sharedTrim` 不存在。

- [ ] **Step 2: 写 TypeScript 匹配 RED**

向共享 fixture 加入最长优先、同长度顺序、重叠、非级联、代理对、组合字符、跨 Text 节点和特殊空白案例。测试至少断言：

```ts
it('uses one left-to-right pass without cascading', () => {
  document.body.innerHTML = '<p>AB A 😀 é e\u0301</p>';
  const result = engine.applyToDocument(document, [
    { id: 'ab', source: 'AB', target: 'A' },
    { id: 'a', source: 'A', target: 'B' },
  ]);
  expect(document.body.textContent).toBe('A B 😀 é e\u0301');
  expect(result.perRule).toEqual([
    { ruleId: 'ab', replacementCount: 1 },
    { ruleId: 'a', replacementCount: 1 },
  ]);
  expect(result.replacementCount).toBe(2);
});
```

Run:

```bash
npx vitest run tests/textEngine.test.ts tests/android-runtime.test.ts
```

Expected: RED；当前 `reduce(split/join)` 会级联且没有计数结果。

- [ ] **Step 3: 实现共享固定 trim 与单次扫描**

Android `sharedTrim` 和 TypeScript fixture 使用同一固定边界空白字符表；不调用平台默认 `trim()`。`textEngine.ts` 的 API 改为：

```ts
export type TextApplySummary = {
  changedTextNodeCount: number;
  replacementCount: number;
  perRule: Array<{ ruleId: string; replacementCount: number }>;
};

applyToDocument(doc: Document, rules: ReplaceRule[]): TextApplySummary;
applyToNode(root: Node, rules: ReplaceRule[]): TextApplySummary;
```

每个原始 Text 节点只扫描一次；候选按 `source.length`（UTF-16 code unit）降序、再按用户顺序；命中后消费 source 长度，输出不再次匹配。继续复用 `domFilter.ts` 排除脚本、输入和可编辑内容。

- [ ] **Step 4: 验证 GREEN**

```bash
npx vitest run tests/textEngine.test.ts tests/android-runtime.test.ts tests/content-script.test.ts
./android/gradlew -p android :app:testDebugUnitTest \
  --tests "*RuleValidationTest" --tests "*RuleNormalizerTest"
```

Expected: GREEN；Android/TypeScript 对共享 fixture 的规范化和顺序一致，`A→B, B→C` 不级联。

- [ ] **Step 5: 提交**

```bash
git add src/content/textEngine.ts tests/textEngine.test.ts \
  tests/fixtures/name-replacement-contract.json \
  android/app/src/main/java/com/xiaoshuo/yijianhuanming/data/RuleEntity.kt \
  android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/RuleValidation.kt \
  android/app/src/test/java/com/xiaoshuo/yijianhuanming/data/RuleNormalizerTest.kt \
  android/app/src/test/java/com/xiaoshuo/yijianhuanming/reader/RuleValidationTest.kt
git commit -m "feat(rules): enforce shared deterministic replacement contract"
```

---

### Task 3: 返回结构化 Runtime 结果并处理动态正文

**Files:**
- Modify: `src/android-runtime/types.ts`
- Modify: `src/android-runtime/runtime.ts`
- Modify: `tests/android-runtime.test.ts`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/WebRuntimeController.kt`
- Modify: `android/app/src/test/java/com/xiaoshuo/yijianhuanming/reader/WebRuntimeControllerTest.kt`
- Modify: `android/app/src/test/java/com/xiaoshuo/yijianhuanming/reader/WebRuntimeScriptEncoderTest.kt`

- [ ] **Step 1: 写 Runtime RED**

TypeScript 断言 `ApplySuccess` 返回 `activeRuleCount/changedTextNodeCount/replacementCount/perRule`；同一节点两个命中计 2；清空规则计数为 0；MutationObserver 自动处理新增正文且忽略引擎自己的 mutation。

Kotlin 目标类型：

```kotlin
data class RuleCount(val ruleId: String, val replacementCount: Int)
data class RuleApplyResult(
    val activeRuleCount: Int,
    val changedTextNodeCount: Int,
    val replacementCount: Int,
    val perRule: List<RuleCount>,
)

interface RuleRuntime {
    suspend fun applyRules(rules: List<ReplaceRule>): Result<RuleApplyResult>
}
```

测试使用假的 JavaScript evaluator 返回 JSON 字符串，断言 `WebRuntimeController` 解析 `replacementCount = 3`，而不是把 `"true"` 当成功。

Run:

```bash
npx vitest run tests/android-runtime.test.ts
./android/gradlew -p android :app:testDebugUnitTest \
  --tests "*WebRuntimeControllerTest" --tests "*WebRuntimeScriptEncoderTest"
```

Expected: RED；TS 结果缺少计数，Kotlin 仍返回 `Result<Unit>`。

- [ ] **Step 2: 实现不可变原文、版本和 Observer**

`runtime.ts` 为每个 Text 节点保存不可变原文及当前 generation 的处理标记；`applyRules()` 每次先从原文重算全部已加载节点。Observer 只处理新增正文节点，提交 mutation 时临时抑制 observer；`dispose()` 断开 observer。`restoreOriginalText()` 等价于 `applyRules([])` 并返回结构化零计数。

`WebRuntimeController` 的包装脚本统一 `JSON.stringify(result)`，Kotlin 严格解析 `{ok:true,...}`；`ok:false` 映射为失败，不再接受 `undefined` 为成功。

- [ ] **Step 3: 验证 GREEN 与 bundle**

```bash
npx vitest run tests/android-runtime.test.ts tests/textEngine.test.ts
npm run build:android-runtime
./android/gradlew -p android :app:testDebugUnitTest --tests "*WebRuntime*Test"
```

Expected: GREEN；生成 bundle；动态插入只替换一次，当前同步结果计数准确。

- [ ] **Step 4: 提交**

```bash
git add src/android-runtime src/content/textEngine.ts tests/android-runtime.test.ts \
  android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/WebRuntimeController.kt \
  android/app/src/test/java/com/xiaoshuo/yijianhuanming/reader
git commit -m "feat(runtime): report replacement results and observe new content"
```

---

### Task 4: 建立统一阅读状态和三层 generation

**Files:**
- Create: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/ReaderUiState.kt`
- Create: `android/app/src/test/java/com/xiaoshuo/yijianhuanming/reader/ReaderUiStateTest.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/ReaderViewModel.kt`
- Modify: `android/app/src/test/java/com/xiaoshuo/yijianhuanming/reader/ReaderViewModelTest.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/WebRuntimeController.kt`

- [ ] **Step 1: 写状态机 RED**

生产类型必须精确为：

```kotlin
enum class ReaderErrorCode {
    INVALID_URL, UNSAFE_URL, TXT_ENCODING_REQUIRED, TXT_READ_FAILED,
    EPUB_INVALID, EPUB_DRM_UNSUPPORTED, EPUB_FIXED_LAYOUT_UNSUPPORTED,
    RUNTIME_LOAD_FAILED, RULE_APPLY_FAILED, RULE_SAVE_FAILED,
    RUNTIME_OUT_OF_SYNC, FILE_PERMISSION_LOST
}
enum class RecoveryAction {
    RetryRuntime, RetryApply, ReopenDatabaseAndRetryApply, ReloadDocument,
    EditUrl, SelectEncoding, SelectAnotherFile, ReturnHome
}
data class ReaderError(
    val code: ReaderErrorCode,
    val userMessage: String,
    val recoveryAction: RecoveryAction,
)
sealed interface RuntimeState {
    data object Loading : RuntimeState
    data object Ready : RuntimeState
    data class Failed(val error: ReaderError) : RuntimeState
    data class OutOfSync(val error: ReaderError) : RuntimeState
}
sealed interface ApplyState {
    data object Idle : ApplyState
    data object Applying : ApplyState
    data class Success(val summary: RuleApplyResult) : ApplyState
    data class Failed(val error: ReaderError) : ApplyState
}
```

`RuleEditorState` 增加 `runtimeState/validationErrors/applyState/hasUnsavedChanges/readerSessionId/runtimeGeneration/applyGeneration/lastApplyResult`。测试依次证明：

1. 新会话 ID 使旧会话回调无效；
2. 新主框架递增 runtime generation、进入 Loading、取消 Applying；
3. 新 apply generation 使旧 apply 回调无效；
4. Runtime 安装并成功应用持久规则后才进入 Ready；
5. 初始应用失败直接进入 `Failed(RUNTIME_LOAD_FAILED)`。

Run:

```bash
./android/gradlew -p android :app:testDebugUnitTest \
  --tests "*ReaderUiStateTest" --tests "*ReaderViewModelTest"
```

Expected: RED；当前状态只有 `isApplying/error`，不能表达同步边界。

- [ ] **Step 2: 最小实现状态转换**

把 token 聚合为不可变值：

```kotlin
data class RuntimeToken(val readerSessionId: String, val runtimeGeneration: Long)
data class ApplyToken(
    val readerSessionId: String,
    val runtimeGeneration: Long,
    val applyGeneration: Long,
)
```

所有异步完成函数先比较完整 token；不匹配时原样返回 state。`beginRuntime()` 必须递增 runtime/apply generation 并清除 Applying；`markRuntimeReady()` 只能接收当前 token。

- [ ] **Step 3: 验证 GREEN**

```bash
./android/gradlew -p android :app:testDebugUnitTest \
  --tests "*ReaderUiStateTest" --tests "*ReaderViewModelTest" --tests "*WebRuntimeControllerTest"
```

Expected: GREEN；旋转、跳转和离开页面的迟到回调不能改变当前 state。

- [ ] **Step 4: 提交**

```bash
git add android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader \
  android/app/src/test/java/com/xiaoshuo/yijianhuanming/reader
git commit -m "feat(android): model reader runtime generations"
```

---

### Task 5: 用协调器实现规则应用与 Room 回滚事务

**Files:**
- Create: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/RuleApplicationCoordinator.kt`
- Create: `android/app/src/test/java/com/xiaoshuo/yijianhuanming/reader/RuleApplicationCoordinatorTest.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/ReaderViewModel.kt`
- Modify: `android/app/src/test/java/com/xiaoshuo/yijianhuanming/reader/ReaderViewModelTest.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/data/RoomRuleRepository.kt`

- [ ] **Step 1: 写四路径 RED**

用记录调用顺序的 fake runtime/repository 覆盖：

```text
成功：apply(draft) → replaceAll(draft)
Runtime 失败：apply(draft)，Room 0 次调用
Room 失败：apply(draft) → replaceAll(draft) → apply(previous)
Room 与回滚都失败：结果为 RUNTIME_OUT_OF_SYNC
```

额外断言：任何失败均保留规范化草稿、editingRuleId、selection/focus 描述；成功才同步 persisted/draft、关闭编辑并写 `lastApplyResult`。空草稿调用 `applyRules(emptyList())`，不使用第二条恢复路径。

Run:

```bash
./android/gradlew -p android :app:testDebugUnitTest \
  --tests "*RuleApplicationCoordinatorTest" --tests "*ReaderViewModelTest"
```

Expected: RED；现有实现先保存 Room 再执行 Runtime，违反顺序。

- [ ] **Step 2: 实现最小协调器**

协调器返回：

```kotlin
sealed interface RuleTransactionResult {
    data class Success(val summary: RuleApplyResult) : RuleTransactionResult
    data class RuntimeFailed(val error: ReaderError) : RuleTransactionResult
    data class SaveFailedRolledBack(val error: ReaderError) : RuleTransactionResult
    data class OutOfSync(val error: ReaderError) : RuleTransactionResult
}
```

唯一流程是规范化校验 → 检查 Ready → 获取 ApplyToken → Runtime 应用 draft → Room `replaceAll` → 成功；Room 失败时 Runtime 从不可变原文应用 previous。`ReopenDatabaseAndRetryApply` 必须重跑完整流程，不允许只补写 Room。

- [ ] **Step 3: 验证 GREEN**

```bash
./android/gradlew -p android :app:testDebugUnitTest \
  --tests "*RuleApplicationCoordinatorTest" --tests "*ReaderViewModelTest"
```

Expected: GREEN；失败回滚后数据库仍为 previous，OutOfSync 禁用继续编辑并只开放重新载入。

- [ ] **Step 4: 提交**

```bash
git add android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader \
  android/app/src/main/java/com/xiaoshuo/yijianhuanming/data/RoomRuleRepository.kt \
  android/app/src/test/java/com/xiaoshuo/yijianhuanming/reader
git commit -m "feat(android): make rule application recoverable"
```

---

### Task 6: 恢复 URL 入口并收紧逐次导航与登录保护

**Files:**
- Create: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/intake/UrlEntryDialog.kt`
- Create: `android/app/src/androidTest/java/com/xiaoshuo/yijianhuanming/intake/UrlEntryDialogTest.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/MainActivity.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/HomeScreen.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/content/web/LoginRiskPolicy.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/content/web/SecureWebViewClient.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/ReaderWebView.kt`
- Modify: `android/app/src/test/java/com/xiaoshuo/yijianhuanming/intake/UrlPolicyTest.kt`
- Modify: `android/app/src/test/java/com/xiaoshuo/yijianhuanming/content/web/{LoginRiskPolicyTest,NavigationPolicyTest}.kt`
- Modify: `android/app/src/androidTest/java/com/xiaoshuo/yijianhuanming/content/web/WebViewSecurityTest.kt`

- [ ] **Step 1: 写入口与策略 RED**

JVM 测试覆盖去首尾固定空白、空值/相对地址/凭据 URL、localhost、`.local`、私网与回环 IP 字面量、危险协议、HTTPS allow、HTTP confirm、HTTPS→HTTP 重定向再次确认；登录 path 的独立片段忽略大小写，集合精确为 `login/signin/passport/auth`。

Compose 测试：

```kotlin
compose.onNodeWithText("打开网页链接").performClick()
compose.onNodeWithText("网页地址").assertIsDisplayed()
compose.onNodeWithText("打开").assertIsNotEnabled()
compose.onNodeWithText("粘贴").performClick()
compose.onNodeWithText("打开").assertIsEnabled()
```

再输入 `javascript:alert(1)`，断言输入框行内错误仍可见；HTTP 必须先出现二次确认。

Run:

```bash
./android/gradlew -p android :app:testDebugUnitTest \
  --tests "*UrlPolicyTest" --tests "*LoginRiskPolicyTest" --tests "*NavigationPolicyTest"
./android/gradlew -p android :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.xiaoshuo.yijianhuanming.intake.UrlEntryDialogTest
```

Expected: RED；`MainActivity` 的 `onOpenUrl = {}` 无行为，`auth` 未被拦截。

- [ ] **Step 2: 实现 URL 对话框并复用 InputResolver**

`UrlEntryDialog` 只在点击“粘贴”时调用注入的 `readClipboard()`；输入值单行横向滚动，标签/错误可换行。确认调用现有 `InputResolver`/`UrlPolicy`，返回：

```kotlin
sealed interface UrlEntryResult {
    data class Open(val input: ReaderInput.WebUrl) : UrlEntryResult
    data class ConfirmHttp(val input: ReaderInput.WebUrl) : UrlEntryResult
    data class Invalid(val error: ReaderError) : UrlEntryResult
}
```

对话框内容使用 `verticalScroll`、`imePadding()`、`BringIntoViewRequester`；窄屏大字时按钮改为纵向。HTTP 确认只绑定当前精确 URL。

- [ ] **Step 3: 实现加载前不可交互的登录门禁**

每次远程主框架 `onPageStarted` 立即令 WebView `INVISIBLE` 且 `isEnabled = false`。URL policy 通过、Runtime 安装、持久规则应用和登录检查均完成后才显示并恢复触摸。path 命中直接停止并进入受控阻止页；普通 path 提交时若存在可见 password input，立即：

```kotlin
view.stopLoading()
view.clearFocus()
view.loadUrl("about:blank")
callbacks.onLoginRiskDetected()
```

阻止页提供“返回首页”“打开其他公开链接”。检查只返回布尔值，不读取 input value。

- [ ] **Step 4: 验证 GREEN**

```bash
./android/gradlew -p android :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

Expected: URL 对话框、逐跳策略、固定 `/login` 页面和普通 path 密码表单测试通过；人为延迟检查时 WebView 不可见且不可触摸。

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/xiaoshuo/yijianhuanming/MainActivity.kt \
  android/app/src/main/java/com/xiaoshuo/yijianhuanming/intake \
  android/app/src/main/java/com/xiaoshuo/yijianhuanming/content/web \
  android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader \
  android/app/src/test android/app/src/androidTest
git commit -m "feat(android): restore safe url entry and login guard"
```

---

### Task 7: 修复文档标题与元数据更新覆盖进度

**Files:**
- Create: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/intake/DocumentMetadataResolver.kt`
- Create: `android/app/src/test/java/com/xiaoshuo/yijianhuanming/intake/DocumentMetadataResolverTest.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/data/ReaderSessionDao.kt`
- Modify: `android/app/src/androidTest/java/com/xiaoshuo/yijianhuanming/data/AppDatabaseTest.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/MainActivity.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/library/RecentReadingList.kt`
- Modify: `android/app/src/test/java/com/xiaoshuo/yijianhuanming/library/LibraryViewModelTest.kt`

- [ ] **Step 1: 写标题与保进度 RED**

测试 resolver 的降级顺序：`DISPLAY_NAME` → percent-decoded URI last segment → `TXT 文档/EPUB 文档`；`document:1000037924`、纯数字、空白必须降级。标题移除 C0/C1 与双向覆盖控制符、折叠换行、最多 100 个 grapheme cluster。

数据库测试先存 `scrollRatio=0.37, textOffset=3700`，再调用目标 API：

```kotlin
dao.upsertMetadataPreservingProgress(
    sourceId = "txt",
    type = "TXT",
    title = "小说.txt",
    uri = "content://txt",
    lastOpenedAt = 200,
)
```

断言 ratio/offset 不变。网页标题更新必须携带当前 session/runtime token。

Run:

```bash
./android/gradlew -p android :app:testDebugUnitTest --tests "*DocumentMetadataResolverTest"
./android/gradlew -p android :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.xiaoshuo.yijianhuanming.data.AppDatabaseTest
```

Expected: RED；当前 `persistBasicSession()` 用 0.0 全量 upsert 覆盖已有进度。

- [ ] **Step 2: 实现字段级 DAO 更新**

DAO 使用“存在则 UPDATE 元数据，不存在才 INSERT 0 进度”的 `@Transaction`；`saveProgress()` 只更新位置与时间。`MainActivity` 移除 `lastPathSegment` 和 `persistBasicSession` 细节，统一调用 resolver + DAO。网页初始标题用 host，`onPageFinished` 的安全标题仅在 token 当前时更新。

首页百分比：

```kotlin
val percent = (session.scrollRatio.coerceIn(0.0, 1.0) * 100).roundToInt()
```

- [ ] **Step 3: 验证 GREEN**

```bash
./android/gradlew -p android :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

Expected: GREEN；已有 37% TXT 首帧元数据更新后仍为 37%，系统 document ID 不展示。

- [ ] **Step 4: 提交**

```bash
git add android/app/src/main/java/com/xiaoshuo/yijianhuanming/intake/DocumentMetadataResolver.kt \
  android/app/src/main/java/com/xiaoshuo/yijianhuanming/data/ReaderSessionDao.kt \
  android/app/src/main/java/com/xiaoshuo/yijianhuanming/MainActivity.kt \
  android/app/src/main/java/com/xiaoshuo/yijianhuanming/library/RecentReadingList.kt \
  android/app/src/test android/app/src/androidTest
git commit -m "fix(android): preserve progress while resolving document titles"
```

---

### Task 8: 用原文 UTF-16 坐标保存和恢复 TXT

**Files:**
- Create: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/library/ReadingProgressCoordinator.kt`
- Create: `android/app/src/test/java/com/xiaoshuo/yijianhuanming/library/ReadingProgressCoordinatorTest.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/content/txt/TxtContentSource.kt`
- Modify: `android/app/src/main/assets/reader/local-reader.js`
- Modify: `src/content/textEngine.ts`
- Modify: `tests/textEngine.test.ts`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/ReaderScreen.kt`
- Create: `android/app/src/androidTest/java/com/xiaoshuo/yijianhuanming/reader/TxtReaderProgressTest.kt`

- [ ] **Step 1: 写坐标映射 RED**

TypeScript 测试构造 `宝宝A😀`，分别应用长替换、短替换和清空规则；断言渲染位置映射到同一原文 UTF-16 anchor，代理对不按 code point 计数。`characterOffset()` 必须优先 `caretPositionFromPoint()`、回退 `caretRangeFromPoint()`，不得用 chunk 高度比例。

Kotlin 测试：

```kotlin
assertEquals(0.37, txtRatio(3700, 10_000), 0.0)
assertEquals(3700L, restoreTxtOffset(3700, 10_000, 0.12, 10_000))
assertEquals(5000L, restoreTxtOffset(3700, 10_000, 0.50, 10_001))
assertEquals(0L, restoreTxtOffset(null, null, 0.0, 0))
```

并用乱序 fake write 验证 sequence=2 完成后，sequence=1 不得覆盖；所有 Room 写入串行。

Run:

```bash
npx vitest run tests/textEngine.test.ts
./android/gradlew -p android :app:testDebugUnitTest --tests "*ReadingProgressCoordinatorTest"
```

Expected: RED；当前 JS 按 section 高度估算，文档也没有 `totalUtf16Units`。

- [ ] **Step 2: 实现原文映射和恢复**

`TxtReaderDocument` 增加 `totalUtf16Units: Long`；每个 chunk/Text 节点携带精确 `sourceStart/sourceEnd`。引擎保存单调 mapping segment：

```ts
type PositionSegment = {
  sourceStart: number; sourceEnd: number;
  renderedStart: number; renderedEnd: number;
};
```

未替换区间一一映射；替换内部按最近边界吸附，首尾精确对应原名首尾。`window.__TXT_READER__.characterOffset()` 和 `restoreCharacterOffset(offset)` 通过 Range/caret 与 mapping 转换，不再按高度比例。

- [ ] **Step 3: 实现节流、后台与 300ms 关闭预算**

`ReadingProgressCoordinator` 每个会话维护递增 sequence、最近确认值和单 actor/mutex。滚动最多每 2 秒 evaluate 一次；`ON_STOP` 尽力保存；返回时 `withTimeoutOrNull(300)` 请求新位置，超时保存最近确认值后立即导航。保存 `textOffset/textTotalAtSave/scrollRatio/lastOpenedAt`。

- [ ] **Step 4: 验证 GREEN**

```bash
npx vitest run tests/textEngine.test.ts tests/android-runtime.test.ts
./android/gradlew -p android :app:testDebugUnitTest
./android/gradlew -p android :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.xiaoshuo.yijianhuanming.reader.TxtReaderProgressTest
```

Expected: 长短替换、Emoji、CRLF、空文件、越界 offset 均通过；正常关闭优先新 offset，超时回退最近两秒确认值。

- [ ] **Step 5: 提交**

```bash
git add src/content/textEngine.ts tests/textEngine.test.ts \
  android/app/src/main/assets/reader/local-reader.js \
  android/app/src/main/java/com/xiaoshuo/yijianhuanming/content/txt/TxtContentSource.kt \
  android/app/src/main/java/com/xiaoshuo/yijianhuanming/library/ReadingProgressCoordinator.kt \
  android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/ReaderScreen.kt \
  android/app/src/test android/app/src/androidTest
git commit -m "feat(android): persist txt progress in source coordinates"
```

---

### Task 9: 完成 EPUB 章节进度、失效回退与串行保存

**Files:**
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/content/epub/EpubContentSource.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/ReaderScreen.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/library/ReadingProgressCoordinator.kt`
- Modify: `android/app/src/test/java/com/xiaoshuo/yijianhuanming/content/epub/EpubNavigationParserTest.kt`
- Modify: `android/app/src/androidTest/java/com/xiaoshuo/yijianhuanming/reader/EpubReaderTest.kt`

- [ ] **Step 1: 写 EPUB RED**

测试规范化：

```kotlin
assertEquals(0.0, normalizeChapterRatio(Double.NaN), 0.0)
assertEquals(0.0, normalizeChapterRatio(-1.0), 0.0)
assertEquals(1.0, normalizeChapterRatio(2.0), 0.0)
```

仪器测试固定三章：第三章保存 0.40 后重开，恢复同章且误差 ≤ 0.05；移除第三章后回退第一章 0.0，并立即持久化该回退。章节切换必须记录“保存旧章完成”先于“加载新章”。

Run:

```bash
./android/gradlew -p android :app:testDebugUnitTest --tests "*EpubNavigationParserTest"
./android/gradlew -p android :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.xiaoshuo.yijianhuanming.reader.EpubReaderTest
```

Expected: RED；当前失效章节只在内存回退，未立即修复数据库，滚动停止也不保存。

- [ ] **Step 2: 实现 EPUB 保存触发与恢复**

比例公式固定为：

```js
const max = Math.max(1, document.documentElement.scrollHeight - document.documentElement.clientHeight);
const value = document.documentElement.scrollHeight <= document.documentElement.clientHeight
  ? 0 : window.scrollY / max;
```

章节切换先通过 coordinator 保存旧章最终 ratio，再加载新章；滚动停止 2 秒、`ON_STOP`、返回 300ms 预算均保存。字体/屏幕变化至少保持章节 ID；同配置 ratio 误差 ≤ 0.05。

- [ ] **Step 3: 验证 GREEN**

```bash
./android/gradlew -p android :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

Expected: 正常章节恢复、短章节、NaN/越界、失效章节二次重开均通过。

- [ ] **Step 4: 提交**

```bash
git add android/app/src/main/java/com/xiaoshuo/yijianhuanming/content/epub/EpubContentSource.kt \
  android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/ReaderScreen.kt \
  android/app/src/main/java/com/xiaoshuo/yijianhuanming/library/ReadingProgressCoordinator.kt \
  android/app/src/test android/app/src/androidTest
git commit -m "feat(android): make epub resume deterministic"
```

---

### Task 10: 重建首页、阅读页和规则面板交互

**Files:**
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/navigation/AppNavHost.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/HomeScreen.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/ReaderScreen.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/RuleEditorSheet.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/ReaderViewModel.kt`
- Modify: `android/app/src/test/java/com/xiaoshuo/yijianhuanming/reader/ReaderToolbarLayoutTest.kt`
- Modify: `android/app/src/androidTest/java/com/xiaoshuo/yijianhuanming/{HomeScreenTest,AdaptiveReaderTest}.kt`
- Modify: `android/app/src/androidTest/java/com/xiaoshuo/yijianhuanming/reader/RuleEditorSheetTest.kt`

- [ ] **Step 1: 写自适应与表单 RED**

Compose 测试覆盖：

- 首页单一 `Scaffold`，顶部产品名/设置，两个入口、最近阅读和隐私说明；不再出现叠加的 `AdaptiveReaderChrome` 设置行。
- 阅读页顶部返回/标题/更多，中间 WebView，底部“规则”；EPUB 增加上一章/目录/下一章。
- 新增规则自动聚焦原名；原名 IME Next 聚焦新名；新名 Done 收键盘。
- 空字段、同名、重复原名显示行内错误且主按钮禁用。
- Runtime Loading/Failed/OutOfSync 和 Applying 时按钮遵循设计状态。
- 删除最后一条后按钮显示“应用并恢复原文”。
- 有未保存修改关闭时显示“放弃本次修改？”；Applying 禁止关闭。
- 成功自动关闭并 Snackbar；零匹配文案限定“当前已加载内容”；失败保留面板和草稿。
- `lastApplyResult` 再开面板仍显示，配置变化不重复 Snackbar，结果使用 live region。

Run:

```bash
./android/gradlew -p android :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.xiaoshuo.yijianhuanming
```

Expected: RED；当前规则按钮始终叫“全部生效”，没有校验、焦点、放弃确认或结果反馈。

- [ ] **Step 2: 实现 Scaffold 和规则面板**

手机 `rememberModalBottomSheetState(skipPartiallyExpanded = true)`；宽度 ≥ 840dp 使用右侧 supporting pane。规则面板标题、列表、操作区分层；高度不足时整体可滚动；使用 `imePadding()` 与每行 `BringIntoViewRequester`。按钮只设 `heightIn(min = 48.dp)`，核心动作文字不得 `Ellipsis`。

保存成功文案由 summary 纯函数生成：

```kotlin
when {
    summary.activeRuleCount == 0 -> "已清除规则并恢复当前内容"
    summary.replacementCount == 0 -> "规则已保存，当前已加载内容未找到匹配；继续阅读时仍会自动匹配"
    else -> "已保存 ${summary.activeRuleCount} 条规则，当前已加载内容替换 ${summary.replacementCount} 处"
}
```

- [ ] **Step 3: 验证小屏大字**

测试参数至少覆盖 `320×480dp + fontScale 2.0`、横屏和分屏；最后一条规则出现多行错误且 IME 打开时，输入框、关闭和主按钮仍可操作。再覆盖 360/412dp 与 fontScale 1.0/1.3/1.5。

```bash
./android/gradlew -p android :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

Expected: GREEN；“规则”“保存并生效”“取消”“打开”完整可见，主要触控区 ≥ 48dp。

- [ ] **Step 4: 提交**

```bash
git add android/app/src/main/java/com/xiaoshuo/yijianhuanming/navigation/AppNavHost.kt \
  android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader \
  android/app/src/androidTest android/app/src/test
git commit -m "feat(android): restore adaptive reader workflows"
```

---

### Task 11: 加载、取消、结构化错误与恢复动作

**Files:**
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/MainActivity.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/ReaderUiState.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/reader/ReaderScreen.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/content/txt/TxtContentSource.kt`
- Modify: `android/app/src/main/java/com/xiaoshuo/yijianhuanming/content/epub/EpubContentSource.kt`
- Create: `android/app/src/test/java/com/xiaoshuo/yijianhuanming/reader/ReaderErrorMappingTest.kt`
- Modify: `android/app/src/androidTest/java/com/xiaoshuo/yijianhuanming/AdaptiveReaderTest.kt`

- [ ] **Step 1: 写错误映射与取消 RED**

表驱动测试逐项断言设计文档中的 13 个 `ReaderErrorCode → RecoveryAction` 映射。仪器测试开始一个可阻塞 TXT/EPUB open job，断言加载页显示 `正在打开《真实文件名》` 和“取消”；点击取消后 job cancelled、输入流 closed、未完成缓存删除并返回首页。

Run:

```bash
./android/gradlew -p android :app:testDebugUnitTest --tests "*ReaderErrorMappingTest"
./android/gradlew -p android :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.xiaoshuo.yijianhuanming.AdaptiveReaderTest
```

Expected: RED；当前解析期退回无按钮首页，并用 Toast 表达不可恢复错误。

- [ ] **Step 2: 实现显式 Loading/Error UI**

`MainActivity` 仅转发系统输入和顶层事件；解析 job、文件元数据、取消清理由 reader state owner 管理。所有异常在边界映射为结构化 `ReaderError`；错误页按 `RecoveryAction` 只显示有效下一步。`RetryApply` 和 `ReopenDatabaseAndRetryApply` 调用 Task 5 的完整事务；Runtime 失效时升级为 `ReloadDocument`。

- [ ] **Step 3: 验证 GREEN**

```bash
./android/gradlew -p android :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

Expected: 每种错误至少一个可执行恢复动作；取消无缓存残留；测试不依赖完整中文错误文案。

- [ ] **Step 4: 提交**

```bash
git add android/app/src/main/java/com/xiaoshuo/yijianhuanming \
  android/app/src/test android/app/src/androidTest
git commit -m "feat(android): expose recoverable loading and error states"
```

---

### Task 12: 版本升级、全量自动回归与设备验收

**Files:**
- Modify: `android/app/build.gradle.kts`
- Modify: `tests/android-project.test.ts`
- Modify: `tests/android-release.test.ts`
- Modify: `.github/workflows/android.yml`

- [ ] **Step 1: 写版本与迁移门禁 RED**

将工程测试期望改为：

```ts
expect(build).toContain('versionCode = 3');
expect(build).toContain('versionName = "0.2.0"');
expect(build).toContain('.addMigrations(MIGRATION_1_2)');
expect(build).not.toContain('fallbackToDestructiveMigration');
```

CI 必须同时执行 JVM、Room migration instrumentation、Compose/WebView instrumentation、lint 和 assembleDebug。

Run:

```bash
npx vitest run tests/android-project.test.ts tests/android-release.test.ts
```

Expected: RED；当前版本仍为 `0.1.1 (2)`。

- [ ] **Step 2: 升级版本并跑完整 GREEN**

修改 `android/app/build.gradle.kts` 为 `versionCode = 3`、`versionName = "0.2.0"`，然后：

```bash
npm ci
npm test
npm run build:android-runtime
./android/gradlew -p android clean \
  :app:testDebugUnitTest \
  :app:connectedDebugAndroidTest \
  :app:lintDebug \
  :app:assembleDebug
```

Expected: Vitest、JVM、Room migration、Compose/WebView 仪器测试全部通过；lint 无 error；生成可安装 Debug APK。

- [ ] **Step 3: 覆盖升级与安全回归**

先在设备安装 `0.1.1 (2)`，建立规则 A、37% TXT、EPUB 第三章 40% 数据，再覆盖：

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell dumpsys package com.xiaoshuo.yijianhuanming | grep -E "versionCode|versionName"
```

Expected: 显示 `versionCode=3` / `versionName=0.2.0`；规则和历史保留；TXT 显示真实文件名与 37%；EPUB 恢复第三章约 40%。

按固定清单在 Pixel Android 15 模拟器与华为 P70 各跑一次：

1. HTTPS 打开、HTTP 二次确认、HTTPS→HTTP 重定向再次确认；
2. `/login` 与提交时可见 password input 均阻止输入；
3. UTF-8/GB18030、Emoji、组合字符、CRLF、空 TXT；
4. EPUB2/3、短章、失效章节、损坏/DRM/fixed-layout；
5. 远程页面不能访问 appassets，本地 EPUB 不执行脚本或远程资源；
6. `320×480dp + 2.0` 字体、横屏、分屏、IME；
7. 数据库保存失败后正文回滚 A、草稿保留 B；重启后仍为 A。

- [ ] **Step 4: 提交**

```bash
git add android/app/build.gradle.kts tests/android-project.test.ts \
  tests/android-release.test.ts .github/workflows/android.yml
git commit -m "build(android): prepare 0.2.0 core experience beta"
```

---

## 最终提交边界

预期提交顺序：

```text
test(android): freeze room v1 schema
feat(android): migrate reader database to version two
feat(rules): enforce shared deterministic replacement contract
feat(runtime): report replacement results and observe new content
feat(android): model reader runtime generations
feat(android): make rule application recoverable
feat(android): restore safe url entry and login guard
fix(android): preserve progress while resolving document titles
feat(android): persist txt progress in source coordinates
feat(android): make epub resume deterministic
feat(android): restore adaptive reader workflows
feat(android): expose recoverable loading and error states
build(android): prepare 0.2.0 core experience beta
```

每个提交只包含对应任务列出的文件。若某任务 GREEN 需要修改未列出的生产文件，先更新本计划中的文件清单与原因，再编码；不得用一个“修测试”提交混入跨任务行为。

## 完成定义

- Room v1→v2 真实迁移通过，覆盖安装不丢规则、历史或位置。
- 规则规范化、Runtime、Room 使用同一份有序规则；成功一致，失败可确定性回滚。
- 三层 generation 阻止旧 WebView/页面/apply 回调污染当前会话。
- 首页 URL 和文件入口均可用；登录边界与 WebView 安全策略逐次执行。
- TXT 以替换前原文 UTF-16 offset 续读；EPUB 以章节 ID + 章节内 ratio 续读。
- 最近阅读不显示系统 document ID，元数据更新不把进度归零。
- `320×480dp + fontScale 2.0`、横屏、分屏和 IME 场景可完成关键操作。
- `npm test`、Android JVM、instrumentation、lint、assembleDebug 全绿，并完成 Pixel 与华为 P70 覆盖升级验收。
