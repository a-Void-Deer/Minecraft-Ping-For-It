
# 68 · B6（测试基座）+ B7（locale 工具）复审

任务：独立复审 B6 离线测试基座 / B7 locale 校验工具
日期：2026-11-05　审核人：3号　对象：common/src/test/java（55 java）、common/build.gradle 末尾 test disabled、tools/LocaleCheck.java + locale-check.* + locale-allowlist.txt、tools/test-check.*
只读声明：未修改被测源码/脚本/语言文件；只写本文件。临时副本放在 .tmp-locale68（已清理）。

跑过的命令与结果：
- bash tools/test-check.sh → tests=319 passed=319 failed=0，status=PASSED exit=0；[main] sources=162 classes=262。
- bash tools/locale-check.sh → baseline en_us keys=163；8 locale missing/extra/duplicate/placeholder 全 0；used=155 dead=8；allowlist used-ok=2 missing-ok=8 extra-ok=6；problems=none；exit 0。
- git diff --no-index 抽样 12 个移植测试；上游/本地 @Test 计数；覆盖率统计；临时目录 JSON 解析/重复键/占位符/strict 测试。
- 未能跑 Gradle（沙箱禁止）；common/build.gradle 的 test disabled 只做静态审阅。

## A. 移植保真

### A1 12 个抽样逐断言对比

选择覆盖 marker / domain / config / interaction / util / math / name 的 12 个类；本地与上游同名文件的 body 在去掉 import 后 diff 无变化，@Test 数量全部一致：

| 上游类 | 上游 @Test | 本地 @Test | 结果 |
|---|---:|---:|---|
| marker/MarkerWinnerTest | 11 | 11 | 等价，无断言改写 |
| marker/TargetKeyTest | 16 | 16 | 等价 |
| marker/ServerMarkerStoreTest | 28 | 28 | 等价 |
| domain/EntityLocatorTest | 5 | 5 | 等价 |
| domain/PingTypeCatalogTest | 8 | 8 | 等价 |
| config/MavenComparableVersionTest | 2 | 2 | 等价 |
| interaction/ActiveInteractionTest | 15 | 15 | 等价 |
| interaction/TargetSnapshotTest | 18 | 18 | 等价 |
| interaction/cancel/CancelMarkerCandidateTest | 3 | 3 | 等价 |
| util/ClaimedInputStateTest | 3 | 3 | 等价 |
| math/RaycastPolicyTest | 1 | 1 | 等价 |
| name/TargetNameJsonTest | 7 | 7 | 等价 |

结论：这 12 个类**没有静默丢弃用例，没有把 assertEquals 放宽为 assertTrue(x != null)，没有改期望常量，没有破坏 @BeforeEach 语义**（这些类也没有 @AfterEach/@BeforeAll）。A1 通过。

### A2 覆盖率与未搬清单

- 上游 common/src/test/java = **188 java / 1351 个 @Test**；本地 common/src/test/java = **55 java**，其中 7 个是 testing 基座，**48 个测试类 / 319 个 @Test**。
- 48 个本地测试类中，**47 个是上游同名移植，1 个是本轮自写 RateLimiterTest（10 用例）**；移植的 47 个类共 **309 个上游用例**，本地总数 319 = 309 + 10。
- 未搬 **141 个上游 java**：其中 2 个是 support（TestEntitySupport、TestPlatformContextService），**139 个测试类**。按目录的上游/本地对应（类/用例）：

| 目录 | 上游 类/用例 | 本地 类/用例 |
|---|---:|---:|
| common（根） | 1/7 | 0/0 |
| chat | 1/16 | 0/0 |
| client | 9/49 | 0/0 |
| client/duration | 1/4 | 0/0 |
| client/marker | 8/102 | 0/0 |
| client/outline | 47/310 | 0/0 |
| client/rate | 2/12 | 0/0 |
| client/wheel | 3/48 | 0/0 |
| config | 15/98 | 2/6 |
| core | 2/13 | 2/13 |
| domain | 9/62 | 7/43 |
| integration | 3/8 | 0/0 |
| integration/externalblock | 3/9 | 1/4 |
| integration/sable | 1/2 | 1/2 |
| integration/sable/client | 3/5 | 0/0 |
| integration/sable/server | 3/5 | 1/2 |
| interaction | 9/58 | 4/40 |
| interaction/cancel | 5/36 | 4/17 |
| interaction/state | 6/55 | 3/7 |
| interaction/wheel | 1/5 | 1/5 |
| marker | 15/146 | 9/92 |
| math | 6/27 | 1/1 |
| name | 6/43 | 2/18 |
| network | 8/89 | 0/0 |
| registry | 1/9 | 1/9 |
| render | 4/13 | 0/0 |
| resolve | 4/49 | 2/26 |
| screen | 6/47 | 0/0 |
| util | 6/24 | 7/34（含自写 RateLimiterTest） |
| **合计** | **188/1351** | **48/319** |

未搬的抽查 5 个理由：

- marker/MarkerCreationServiceTest：imports net.minecraft.server.MinecraftServer / ServerLevel → **必须 MC，理由成立**。
- client/outline/BlockDisplayPolicyTest：imports ResourceLocation / RenderShape → **必须 MC，理由成立**。
- name/TargetNameComposerTest：imports Component / PlainTextContents / TranslatableContents → **必须 MC，理由成立**。
- client/marker/ClientMarkerStoreTest：测试的是 fabric/src/client 的 ClientMarkerStore；common/src/test 没有 client 源集 → **跨源集，理由成立**。
- screen/SettingsCategoryCatalogTest：测试 fabric/src/client 的 screen 类；同上 → **跨源集，理由成立**。
- **反例（未搬但能搬）**：config/ClientConfigBoundsTest 只 import JUnit 与 ClientConfigBounds（纯 common），**没有任何 MC 依赖**；它没有被搬。这是 A2 的覆盖缺口（非阻塞，但应补）。同理有少数 config/纯逻辑测试未搬，建议用“能否在 common/src/test 编译”作为二次筛选。
- B6 的口径本来就是“A 组零 MC 依赖”，不是 188 全量移植；因此 47/188 不是缺陷本身，但 **ClientConfigBoundsTest 这类纯测试漏搬**需要明确。

### A3 RateLimiterTest（10 用例）

- zeroRegenerateIntervalNeverRejects：setRates(0,1)；实现 timeWindow=0，elapsed clamp 到 0，leftOver=0，不进入 negative → 1000 次全部 false；断言与实现一致，能抓住“0 间隔导致拒绝”的回归。
- idlingForAFullWindowRestoresTheWholeBudget：regen=200, limit=2；睡眠 500ms 后 elapsed 被 clamp 到 window=400，leftOver=200，第一次 false；第二次 elapsed≈200 → false；第三次 leftOver<0 → true。与 token bucket 语义一致，断言不是恒真。
- concurrentChecksStayWithinThePerInstanceBudget：每个线程**各自 new RateLimiter**，共享的只有只读 static 配置；每个 limiter 精确允许 3 次。它验证了“实例预算独立”，但**没有验证同一个 limiter 被多线程共享**；RateLimiter.startTime 没有同步，若未来要求同实例并发安全，这条测试抓不到。命名比实际覆盖更强，属非阻塞测试缺口。
- 其余 7 条（first、exactly limit、rejection stays、one token refill、fresh instance、large window、independent instances）均与实现一致，断言非空。

### A4 基座

- AllTests 注册 48 个**字符串类名**；testClasses() 用 Class.forName，ClassNotFoundException 立即抛 IllegalStateException，**不会静默跳过**；TestRunner 不 catch 启动阶段的 collect 异常，类缺失会直接非 0 退出。通过。
- TestRunner 每个 @Test 新建实例并调用 @BeforeEach；JUnit 的 per-test 生命周期成立。**没有 @AfterEach/@BeforeAll 支持**，但本轮 47 个移植类都没有这些注解（上游用到它们的类都在未搬的 client/outline 等），当前不受影响；未来搬这类测试时需补。
- Assertions 的失败消息带 expected/actual（assertEquals/assertTrue/assertThrows），定位足够；没有 assertAll/assertIterableEquals，但移植测试未使用（编译通过、319 全绿）。
- 结论：A4 通过；未来补 @AfterEach 与同实例并发测试是改进项。

## B. common/build.gradle 关掉 test 任务

- 改动只在 common/build.gradle 末尾：tasks.matching { it.name == 'test' }.configureEach { enabled = false }，不动 build/check/jar/processResources 的依赖声明；没有新增测试依赖。
- 本机 Gradle 缓存里 junit*.jar = 0（find D:/Gradle/caches/modules-2 -iname 'junit*.jar' 无结果），所以当前无法解析 JUnit；test 源码只用自定义 testing 基座。testCheck 直接 javac+java，319 通过。
- 关掉 common 的 test 后，Gradle 的 check 仍可能声明依赖 test，但 test 被 disabled；**我没有运行 Gradle，无法实测 Gradle 9 的 failOnNoDiscoveredTests 分支**。静态判断：enabled=false 应足以避免 test 执行；compileTestJava 若作为依赖被调度，也能编译（自定义基座无 JUnit）。**“Gradle 9 默认 failOnNoDiscoveredTests=true”这一句未能本地核实**，建议在真实 gradlew clean build 时确认。
- 更稳写法：test { enabled = false } 与当前等价；onlyIf { false } 只跳过执行、依赖仍可能跑，不更稳。当前写法可接受，属非阻塞。

## C. B7 locale 工具

- 仓库实际运行：baseline en_us 163；8 locale missing/extra/duplicate/placeholder 全 0；used=155 dead=8；allowlist used-ok=2 missing-ok=8 extra-ok=6；problems=none；exit 0。与 Lead 事实一致。
- 最小 JSON 解析器临时副本测试（.tmp-locale68，仓库语言文件未改）：
  - 值含 \"、\n、\u4e2d、冒号/逗号/{}、空字符串、文件末尾换行 → 全部解析成功（baseline keys=6）。
  - de_de 注入重复键 p、故意 %d vs baseline %s、extra → 输出 duplicate=1、placeholder-mismatch=1、extra=1。
  - --strict 在存在未白名单问题时 exit 1（实测 STRICT_EXIT=1）。通过。
  - 限制：解析器不跳过 UTF-8 BOM（带 BOM 的临时文件会在 pos 1 报 expected "）；仓库语言文件无 BOM，不受影响。baseline en_us 自身的重复键不会进入 duplicates 输出（主循环只从 i=1 开始统计），建议未来把 baseline 也纳入。
- duplicate 检测：p 重复被识别；placeholder 同时比对 %s/%d 与 {name}（PLACEHOLDER = %[sd]|\{[A-Za-z0-9_]+\}），两类都覆盖。
- used 推导：readModId 从 common/src/main/java/nx/pingwheel/common/Global.java 读取 MOD_ID（不是写死）；scanLanguageCalls 覆盖 settings/command/of/keyOf 与变量链；scanSettingsCatalog 读 SettingsCategoryCatalog，scanNavigation 读分类，scanGroups 读 subgroup，scanPingTypes 读 PingTypeCatalog，scanEnumValues 读枚举；String.join 等价的 category.MOD_ID.path 拼接成立。used=155/dead=8 与旧口径一致。
- 白名单 used-ok：
  - key.category.pingforit.name：InputUtils.java:20 KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "name")) 生成，成立。
  - subtitles.pingforit.ping：sounds.json 的 ping.subtitle 指向它，成立。
  - missing-ok 8 条（pl/tr 各 4 条 chat override）与 extra-ok 6 条（de/es/fr 各 2 条 loot/destroy）与已知差异一致。
- --strict：问题数 >0 时 exit 1，代码路径成立（实测）。
- 结论：C 通过；BOM 与 baseline duplicate 两个是小型非阻塞边界。

## 结论

**可合入，无代码阻塞。**

- A1/A3/A4 通过；A2 覆盖为 47/188 上游类、309/1351 上游用例 + 自写 10，B6 口径是 A 组，但 ClientConfigBoundsTest 这类纯测试漏搬是覆盖缺口（非阻塞建议补）。
- B 的 test disabled 静态合理；未能本地跑 Gradle，failOnNoDiscoveredTests 结论未核实。
- B7 工具实测通过；BOM 与 baseline duplicate 是边界。
- 非阻塞建议：补 @AfterEach 支持、把 RateLimiter 同实例并发纳入测试、补 ClientConfigBoundsTest 等纯测试、LocaleCheck 支持 BOM 与 baseline duplicate、真实跑一次 gradlew clean build 确认 test disabled。

