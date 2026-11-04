# 63 · M9-3 Distant Horizons 集成 · 实现规格（方案 A：编译期用独立 API jar）

- 作者：1号 repo-analyst｜日期：2026-11-04｜用途：**施工单**（2号 照做、3号 照审）
- 上游对象：`Mod_Project/Minecraft-Ping-For-It-0.4.2-pfi-beta1/common/src/main/java/nx/pingwheel/common/integration/DistantHorizonsIntegration.java`（201 行）
- 我们的目标文件：`common/src/main/java/nx/pingwheel/common/integration/DistantHorizonsIntegration.java`（现为 76 行 stub）
- API 面依据：`javap`（JDK 25）对 `run/mods/DistantHorizons-3.3.4-26.3-fabric-neoforge.jar` 的实测，命令与输出见 §9

> **落地状态（2026-11-04）**：**已按本规格实现并通过复审**（`docs/reviews/64-m9-3-dh-integration-review.md`：通过、无阻塞），`compile-check` 绿（262 / 4 / 126，无 `com/seibel` 泄漏）；**实机待验**。

## 0. 结论速览

1. **上游契约一句话**：`traceDistantAsync(start, dir, completion)` 是「一次且仅一次」的异步接管接口 —— 返回 `true` 表示**一定会**回调一次（命中或空），返回 `false` 表示**永不回调**、调用方必须自己同步完成；回调发生在 **ForkJoinPool.commonPool 线程**（`supplyAsync` 未指定 executor），所以回调里只能做线程跳转与不可变数据搬运。
2. **API 面齐全**：上游用到的 7 个类型/成员（`DhApi.Delayed.worldProxy`、`DhApi.Delayed.terrainRepo`、`IDhApiWorldProxy.getSinglePlayerLevel`、`IDhApiTerrainDataRepo.raycast`、`createSoftCache`、`IDhApiTerrainDataCache`、`DhApiResult.success/payload`、`DhApiRaycastResult.pos`、`DhApi.getApiMajor/Minor/PatchVersion`）在 26.3 的 DH 3.3.4 jar 里**逐字存在、签名一致**（§5）。完整 jar 自带 **API 7.2.0**，与独立 `DistantHorizonsApi-7.2.0.jar` **同版本**。
3. **构建接线最大风险**：方案 A 若只「新增 `compile-libs/`」而**不把完整 jar 从 `ext.integrationJars` 的 include 里剔除**，`run/mods` 里的完整 jar 仍会进编译类路径 —— 隔离失效、API 漂移不可观测（§4.2 有明确改法）。

## 1. 上游契约（逐字）

```java
public static boolean traceDistantAsync(Vec3 rayStart, Vec3 direction, Consumer<Optional<BlockHitResult>> completion)
```

| 维度 | 上游行为（`DistantHorizonsIntegration.java:53-96`） |
|---|---|
| **返回 true 的条件** | `ModContext.HasDistantHorizons == true` 且 `LINK_GUARD` 未熔断，且 `CompletableFuture.supplyAsync(...)` **成功排入** commonPool。此后**恰好调用一次** `completion`：命中传 `Optional.of(hit)`，无命中/内部失败传 `Optional.empty()` |
| **返回 false 的条件** | ① flag 关闭（`:54-56`）；② 连接期已熔断（同上）；③ `supplyAsync` 抛 `LinkageError` → 熔断并返回 false（`:62-64`）；④ `supplyAsync` 抛其它 `RuntimeException`（如 `RejectedExecutionException`）→ **只 debug 记录、不熔断**并返回 false（`:65-72`） |
| **完成次数** | 由 `future.whenComplete` 唯一路径保证一次；`whenComplete` 内部不会二次调用 `completion`（`LinkageError` 分支与非链接分支都只 accept 一次，`:74-93`） |
| **线程** | 追踪体在 **`ForkJoinPool.commonPool()`** 线程执行；`whenComplete` 也在该完成线程。**回调不保证在主线程** —— 我们的调用点用 `game.execute(...)` 跳回客户端主线程（`ClientPingRuntime.java:828`） |
| **失败分类** | `rootCause(throwable)` 最多展开 **16 跳**并防自环（`:105-119`）；`root instanceof LinkageError` → `LINK_GUARD.disable`（一次 WARN）+ 空结果；其它 → `debugException("distanthorizons trace failed", root)` + 空结果 |
| **降级语义** | 「返回 false ⇒ 调用方自己同步完成」，因此**永远不会 strand**（调用点注释 `:721-736` 明文写了这一点） |
| **日志（逐字）** | 熔断：`"{} integration disabled"`（由 `IntegrationLinkGuard:33`，`{}` = `distanthorizons`）；排程失败：`debugException("distanthorizons schedule failed", error)`；追踪失败：`debugException("distanthorizons trace failed", root)`；未捕获链接错误：`warnException("distanthorizons integration disabled: unguarded link failure", error)`；版本诊断：`LOGGER.info("Distant Horizons API Version: %s.%s.%s".formatted(major, minor, patch))` |

**内部实现要点（照抄即可）**：

- 常量：`CACHE_MAX_AGE_SECONDS = 10L`、`RAYCAST_RANGE = 4096`、`CACHE_LOCK = new Object()`（`:30-32`）。
- 字段：`private static IDhApiTerrainDataCache terrainCache = null;`、`private static Instant lastCacheLoad = Instant.EPOCH;`、`private static final IntegrationLinkGuard LINK_GUARD = new IntegrationLinkGuard("distanthorizons");`（`:34-37`）。
- 缓存策略：进 `synchronized (CACHE_LOCK)` 判断 `terrainCache == null || Duration.between(lastCacheLoad, Instant.now()).getSeconds() > 10` → `DhApi.Delayed.terrainRepo.createSoftCache()`，然后把 cache 取出、**在锁外**做 raycast（`:140-155`）。
- 命中结果构造：`new BlockHitResult(new Vec3(payload.pos.x, payload.pos.y, payload.pos.z), Direction.UP, new BlockPos((int) x, (int) y, (int) z), true)`（`:161-163`；`pos` 是 `DhApiVec3i`，三个分量都是 `int`）。
## 2. 我们的差异

### 2.1 调用点：逐字相同，**实现时不要改调用点**

| 位置 | 上游 | 我们 |
|---|---|---|
| import | `common/.../client/ClientPingRuntime.java:57` | `fabric/src/client/java/nx/pingwheel/common/client/ClientPingRuntime.java:58` |
| flag 门 | `:720` `if (ModContext.HasDistantHorizons)` | `:722` 同 |
| 调用 | `:740-742` `traceDistantAsync(rayOrigin, cameraDirection, distantHit -> completeDistantHit(...))` | `:742-744` 同 |
| LinkageError 兜底 | `:743-747` 关 flag + `logUnguardedLinkFailure` | `:745-749` 同 |
| `!scheduled` 回退 | `:749-751` 用 `missHit` 同步 `completeCapture` | `:751-753` 同 |

结论：**契约签名与语义已对齐**，M9-3 只需**替换 `DistantHorizonsIntegration` 的实现**（把 stub 换回上游 201 行版本，做 26.3 的 import/编译适配）。

### 2.2 stub 现状（`common/.../integration/DistantHorizonsIntegration.java`，76 行）

| 成员 | 现状 | 回填动作 |
|---|---|---|
| `traceDistantAsync` | `:29-35` 直接 `return false` | 换成上游 `:53-96` 全文 |
| `rootCause` | `:42-56` 已与上游**逐字一致**（含 16 跳上限） | **不动** |
| `traceDistant` | `:62-65` `return Optional.empty()` | 换成上游 `:121-168` |
| `logVersionDiagnostics` | `:67-70` 空体 | 换成上游 `:174-185` |
| `logUnguardedLinkFailure` | `:72-75` 空体 | 换成上游 `:194-200` |

### 2.3 26.3 下有没有 API 变化：**没有**

- §5 的 javap 表逐条命中；`DhApi.Delayed.terrainRepo` 与 `worldProxy` 仍是**非 final 的 public static 字段**（DH 在初始化时回填），因此「先判 null 再调用」的写法必须保留。
- 需要新增的 import（相对 stub）：`com.seibel.distanthorizons.api.DhApi`、`com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache`、`java.time.Duration`、`java.time.Instant`、`java.util.concurrent.CompletableFuture`（`BlockPos`/`Direction`/`Vec3`/`BlockHitResult` stub 已有）。
- `Global.debugException/warnException` 已存在（`common/src/main/java/nx/pingwheel/common/Global.java:40,48`），签名 `(String constantContext, Throwable)`。

## 3. 实现规格（逐方法）

### 3.1 类头与字段（照抄上游 `:27-37`）

```java
public class DistantHorizonsIntegration {
	private DistantHorizonsIntegration() {}
	private static final Object CACHE_LOCK = new Object();
	private static final long CACHE_MAX_AGE_SECONDS = 10L;
	private static final int RAYCAST_RANGE = 4096;
	private static final IntegrationLinkGuard LINK_GUARD = new IntegrationLinkGuard("distanthorizons");
	private static IDhApiTerrainDataCache terrainCache = null;
	private static Instant lastCacheLoad = Instant.EPOCH;
```

### 3.2 `traceDistantAsync(Vec3, Vec3, Consumer<Optional<BlockHitResult>>) -> boolean`

| 项 | 规格 |
|---|---|
| 行为 | 见 §1 表；**只有** `supplyAsync` 成功后才返回 true |
| 线程 | 追踪体跑在 `ForkJoinPool.commonPool()`；回调也在该线程（**不得**在回调前后触碰 `Minecraft.getInstance()` 的实时状态 —— 调用方已用 `game.execute` 跳回主线程） |
| 异常 | 只捕 `LinkageError`（熔断）与 `RuntimeException`（排程失败，**不熔断**，debug 记录）；追踪体内的异常由 `whenComplete` 的 `rootCause` 分类 |
| 日志 | 只用 §1 的四个既有文案；**不要**新增任何 info 级日志（会污染验收判据） |
| 状态 | 无实例状态；`LINK_GUARD.disabled()` 一旦为真则后续调用立即返回 false |

### 3.3 `traceDistant(Vec3, Vec3) -> Optional<BlockHitResult>`（private）

| 项 | 规格 |
|---|---|
| 开头 | `if (LINK_GUARD.disabled()) return Optional.empty();`（上游 `:122-124`） |
| 取 level | `DhApi.Delayed.worldProxy == null` → empty；`getSinglePlayerLevel()` 返回 null → empty（`:127-135`） |
| 缓存 | 见 §1 内部实现要点（锁内刷新、锁外 raycast） |
| 射线 | `DhApi.Delayed.terrainRepo.raycast(levelWrapper, start.x, start.y, start.z, (float) dir.x, (float) dir.y, (float) dir.z, RAYCAST_RANGE, cache)` |
| 结果判定 | `!rayCastResult.success || payload == null` → empty；否则构造 `BlockHitResult`（`Direction.UP`、isInside=true） |
| 异常 | `catch (LinkageError)` → `LINK_GUARD.disable(error)` + empty；**不 catch RuntimeException**（交给 `whenComplete` 分类，保持上游语义） |

### 3.4 `rootCause(Throwable) -> Throwable`：**保持现状**（`:42-56` 已逐字一致；M10 有 5 条测试依赖它）。

### 3.5 `logVersionDiagnostics()`

- 前置：`!ModContext.HasDistantHorizons || LINK_GUARD.disabled()` → return。
- 行为：`LOGGER.info("Distant Horizons API Version: %s.%s.%s".formatted(DhApi.getApiMajorVersion(), DhApi.getApiMinorVersion(), DhApi.getApiPatchVersion()))`。
- 异常：`catch (LinkageError)` → `LINK_GUARD.disable(error)`。
- 调用点已存在：`ModContext.indexMods()` 里 `if (HasDistantHorizons) DistantHorizonsIntegration.logVersionDiagnostics();`（`ModContext.java:25-27`），**不需要改**。

### 3.6 `logUnguardedLinkFailure(LinkageError)`

- 前置：`LINK_GUARD.disabled()` → return（避免重复 WARN）。
- 行为：`warnException("distanthorizons integration disabled: unguarded link failure", error)`。

## 4. 构建接线（方案 A）

### 4.1 新目录 `compile-libs/`（仓库根）

- 放 **`DistantHorizonsApi-7.2.0.jar`**（用户手动下载：`https://cdn.modrinth.com/data/Xs0XTOVv/versions/LTP8vW3B/DistantHorizonsApi-7.2.0.jar`，即 Modrinth `DistantHorizonsApi` 7.2.0 的主文件）。
- 与 `run/mods` 的分工：**`compile-libs/` 只做编译期 API**；`run/mods` 里保留 **`DistantHorizons-3.3.4-26.3-fabric-neoforge.jar`（完整 mod）** 作运行时。
- `.gitignore`：与 `run/` 同策略（jar 不入库），目录留 `.gitkeep` + README 一行说明。

### 4.2 `buildSrc/src/main/groovy/multiloader-common.gradle`

| 改动 | 具体写法 |
|---|---|
| 新增 glob | `ext.integrationApiJars = rootProject.fileTree(dir: rootProject.file("compile-libs"), include: ["DistantHorizonsApi-*.jar"], exclude: ["*-sources.jar", "*-javadoc.jar"])` |
| **从旧 glob 剔除 DH 两项** | 把 `ext.integrationJars` 的 include 里的 **`DistantHorizons-*.jar` 与 `DistantHorizonsApi-*.jar` 都删掉**（否则完整 jar 仍会进编译类路径，方案 A 失效；见 §0-3） |
| 新增检查函数 | `ext.attachIntegrationApiJarCheck = { String taskName, String requiredPrefix, String message -> ... }`，与 `attachIntegrationJarCheck` 同构，但扫描 **`compile-libs/`**；日志前缀固定为 `compileOnly integration API jars (<task>): [...]`（**与 run/mods 那行区分开**，便于排查） |
| 保留 | 现有 `attachIntegrationJarCheck`（modmenu / voicechat）完全不动 |

### 4.3 `common/build.gradle`

```groovy
dependencies {
	compileOnly integrationApiJars   // 新增：DH API（compile-libs）
}
attachIntegrationApiJarCheck("compileJava", "DistantHorizonsApi-",
	"Missing compile-time API jar for the Distant Horizons integration: put " +
		"DistantHorizonsApi-7.2.0.jar into compile-libs/ " +
		"(https://cdn.modrinth.com/data/Xs0XTOVv/versions/LTP8vW3B/DistantHorizonsApi-7.2.0.jar)")
```

- 为什么挂在 `:common:compileJava`：`DistantHorizonsIntegration` 在 **`common/src/main`**（共享源集）；`ClientPingRuntime` 在 `fabric/src/client`，但**不直接引用任何 DH 类**（只经 `DistantHorizonsIntegration` 门面）。
- `:common:compileJava` 上已有 voicechat 检查（`common/build.gradle:22`）；`tasks.matching{...}.configureEach{ doFirst{...} }` 支持多个 doFirst，**两条都会执行**，无需合并。

### 4.4 `fabric/build.gradle`：**无需改动**（`compileOnly integrationJars` 继续只覆盖 modmenu；DH 不进 client 源集）。

### 4.5 `tools/compile-check.sh|.ps1`

- 在现有 `INTEGRATION_GLOBS`（run/mods）之外新增一段：`COMPILE_LIBS_GLOBS=("DistantHorizonsApi-*.jar")`，来源目录 **`compile-libs/`**，同样排除 `-sources` / `-javadoc`。
- 打印单独一行：`compile-check: integration API jars from compile-libs: <name>`；0 命中时**只警告不退出**（与现状一致：允许在没放 API jar 时跑其它检查）。
- 两个脚本必须同步（`.sh` 与 `.ps1` 各一份，逻辑等价）。

### 4.6 `.github/workflows/build.yml`

- **改动**：把 `fetch optional integration jars` 里 DH 那行改成下 **API jar** 到 `compile-libs/`：`curl -fsSL --retry 3 -o compile-libs/DistantHorizonsApi-7.2.0.jar "https://cdn.modrinth.com/data/Xs0XTOVv/versions/LTP8vW3B/DistantHorizonsApi-7.2.0.jar"`，并在步骤开头 `mkdir -p compile-libs run/mods`。
- **不再下完整 DH jar 到 `run/mods`**：CI 只跑 `./gradlew build`，**从不启动游戏**，编译只需要 API；少下 ~20 MB，并避免「CI 用完整 jar 编译 / 本地用 API 编译」的漂移（即 3号 在 M9-1/M9-2 提过的 B2 类问题）。
- `modmenu` 与 `voicechat` 两行**保持不变**（它们仍是 `run/mods` 的 compileOnly 来源）。

### 4.7 本地 dev 运行时

- `run/mods/DistantHorizons-3.3.4-26.3-fabric-neoforge.jar` **保留**（`run/mods` 同时是 dev 的 mods 目录）。它不再参与编译，但仍会被 `runClient` 真实加载 —— 这正是验收需要的。

## 5. API 面核对表（javap 实测；jar = `run/mods/DistantHorizons-3.3.4-26.3-fabric-neoforge.jar`，API 7.2.0）

| 类 | 成员 | 签名 | 存在 |
|---|---|---|---|
| `DhApi` | `getApiMajorVersion()` | `public static int` | ✅ |
| `DhApi` | `getApiMinorVersion()` | `public static int` | ✅ |
| `DhApi` | `getApiPatchVersion()` | `public static int` | ✅ |
| `DhApi$Delayed` | `worldProxy` | `public static IDhApiWorldProxy`（**非 final**） | ✅ |
| `DhApi$Delayed` | `terrainRepo` | `public static IDhApiTerrainDataRepo`（**非 final**） | ✅ |
| `IDhApiWorldProxy` | `getSinglePlayerLevel()` | `public abstract IDhApiLevelWrapper getSinglePlayerLevel() throws IllegalStateException` | ✅ |
| `IDhApiTerrainDataRepo` | `createSoftCache()` | `public abstract IDhApiTerrainDataCache createSoftCache()` | ✅ |
| `IDhApiTerrainDataRepo` | `raycast(...)` | `public abstract DhApiResult<DhApiRaycastResult> raycast(IDhApiLevelWrapper, double, double, double, float, float, float, int, IDhApiTerrainDataCache)` | ✅ |
| `IDhApiTerrainDataCache` | 类型 | `interface … extends AutoCloseable`（`clear()` / `close()`） | ✅ |
| `DhApiResult` | `success` / `payload` | `public final boolean success` / `public final T payload` | ✅ |
| `DhApiRaycastResult` | `pos` | `public final DhApiVec3i pos`（三个 `int` 分量） | ✅ |

- `DhApi.Delayed` 的静态字段由 DH 在初始化阶段回填 —— **必须保留 null 判空**。
- jar 内 `com/seibel/distanthorizons/api/**` 共 **150 个 class**；`ModInfo` 常量实测 `API_MAJOR_VERSION=7` / `API_MINOR_VERSION=2` / `API_PATCH_VERSION=0`、`VERSION="3.3.4"`。

## 6. 验收计划

| 场景 | 步骤 | 判据 |
|---|---|---|
| **不装 DH** | 移走 `run/mods` 的 DH jar → 启动 → ping | 日志 `optional integrations: distantHorizons=false`；ping 行为与现在完全一致（`traceDistantAsync` 不被调用） |
| **装 DH + 远距离命中** | 放回完整 jar → 进世界 → 朝原版视距外、DH LOD 内的方块 ping | 生成 marker 且 `kind=BLOCK`；日志出现 `Distant Horizons API Version: 7.2.0`；**0 条** `distanthorizons schedule failed` / `trace failed` |
| **装 DH + 近距离命中** | 朝 3 格内方块 ping | 走原版射线（`hitResult != MISS`），**根本不进** DH 分支；行为与不装 DH 一致 |
| **降级路径** | 进世界前（DH 的 worldProxy 尚未就绪时）触发一次 ping | 日志出现 `distanthorizons trace failed`（DEBUG 级）且 ping **仍然完成**（回退到 vanilla miss 的快照）——确认「不 strand」 |
| **与 OIT 共存** | 开 Improved Transparency 再 ping | 因「DH 会强制关闭 OIT」的已知限制（`24` #19 实测结论），此时仍走经典通道；只需确认**没有新增 ERROR/WARN** |

## 7. 风险清单

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | **线程模型**：`supplyAsync` 用 commonPool，池饱和时可能抛 `RejectedExecutionException` | 返回 false，调用方同步回退（**不会 strand**） | 已由契约覆盖；**不要**给 `supplyAsync` 传自定义 executor（偏离上游） |
| R2 | **`LinkageError` 覆盖**：调用点只兜 `traceDistantAsync` 调用本身的 `LinkageError`；追踪体内的 `LinkageError` 由 `whenComplete` 的 `rootCause` 熔断 | 少任一处都会留下未捕获链接错误 | 两处都保留（§2.1 表），实现时**不要**删 `rootCause` 分支 |
| R3 | **API 版本漂移**：当前完整 jar 与 API jar 同为 7.2.0；用户日后升级 DH 时可能带 API 8.x | 编译期 API 与运行时不一致 → 运行时 `NoSuchMethodError` | `logVersionDiagnostics` 会打印实际版本，验收时**核对日志版本 == 7.2.0**；升级时同步替换 `compile-libs` 里的 API jar |
| R4 | **DH × OIT 互斥**：装 DH 时 OIT 被关闭，`outline` 相位分支永不触发（`24` #19） | 本集成的验收**不能**顺带验 OIT | 验收分开做；已登记为已知限制 |
| R5 | **4096 格射线性能**：`createSoftCache` 10 秒窗口 + 每次 ping 一次 raycast | 极端情况下 ping 有可感知延迟（但异步） | **不要**加「预取 / 结果缓存」这类上游没有的优化（保真铁律） |
| R6 | **`compile-libs` 与 `run/mods` 混用** | API jar 若丢进 `run/mods`，虽无 `fabric.mod.json` 不会被加载，但会让两套 glob 语义纠缠 | 明确「`compile-libs` 只编译、`run/mods` 只运行」，两处 glob 不交叉（§4.2 已剔除） |

## 8. 上游测试里能搬的断言（M10 用）

| 来源 | 断言 | 可搬性 |
|---|---|---|
| `DistantHorizonsIntegrationTest`（77 行，5 个 `@Test`） | ① `CompletionException` → `LinkageError` 解包；② 嵌套 `ExecutionException`/`CompletionException` → 最深根因；③ 非链接失败原样返回；④ 双向 cause 环终止；⑤ 40 层深链只走 16 跳 | ✅ **全部可搬**（纯 JDK + 包级访问 `rootCause`，不碰 MC） |
| `OptionalDependencySafetyTest:61-70` | 「稳定类不得含 `com/seibel/distanthorizons` 等可选包片段」的字节扫描 | ✅ 可搬，但**类清单要按当前布局重写**（上游清单里的 `nx/pingwheel/common/...` 在本项目属 `fabric/src/client`，包名不变） |
| `OptionalDependencySafetyTest:73-103` | flag 全关时 `traceDistantAsync` 返回 false、Sable/VoiceChat/FTB 边界短路 | ✅ 可搬（需 MC 的 `Vec3`，测试基座要带 MC classpath） |
| **建议新增** | 字节扫描断言 `DistantHorizonsIntegration.class` 里**确实**含 `com/seibel/distanthorizons/api/` 前缀 —— 防止将来又被「填成 stub」 | ✅ 建议加到 M10 |

## 9. 附录：javap 命令与判定

```bash
# 1) API class 计数（期望 150）
unzip -l run/mods/DistantHorizons-3.3.4-26.3-fabric-neoforge.jar | grep -c "com/seibel/distanthorizons/api/.*\.class"

# 2) 逐类签名（JDK 25 的 javap；JDK 24 读不了 class major 69）
J="/c/Program Files/Java/jdk-25/bin/javap"
JAR=run/mods/DistantHorizons-3.3.4-26.3-fabric-neoforge.jar
"$J" -classpath "$JAR" com.seibel.distanthorizons.api.DhApi
"$J" -classpath "$JAR" "com.seibel.distanthorizons.api.DhApi\$Delayed"   # 必须转义 $，否则 bash 展开成空
"$J" -classpath "$JAR" com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo
"$J" -classpath "$JAR" com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy
"$J" -classpath "$JAR" com.seibel.distanthorizons.api.objects.data.DhApiRaycastResult

# 3) 运行时 API 版本
"$J" -constants -classpath "$JAR" com.seibel.distanthorizons.coreapi.ModInfo | grep API_
```

> 一句话：**上游实现可以直接搬**（API 面逐条命中、调用点已对齐），M9-3 的工作量集中在「构建接线（方案 A 的 compile-libs 隔离）」与「验收」，而不是代码改造。
