# 06 · M3b 审核（S1 修复 / 桩安全 / 编译校验 / API 抽检 / deferred）

审核人：3号　基线：**HEAD = d8d610f**（工作区 clean，git status 空）　方法：只读；`git show HEAD:` + `git grep HEAD` + JDK-25 `javap`。**未跑 Gradle。**
规模：`src/main` 164 java、`src/client` 51 java、`deferred/` 92 java。

## 结论

**M3b 达标：S1 已真正修好；5 个桩都不抛异常、不破坏调用链；26.3 API 抽检 5/5 一致；deferred 无悬空引用；mixins 只剩 2 项且目标经 javap 验证有效。**
本地 javac 结果与 2号 自述一致（main 0 error / 263 class、client 0 error / 82 class），但校验工具有 4 个盲区（见 §3）。

---

## 一、S1 修复（ClientConfig equals/hashCode）

| 检查 | 结论 | 证据 |
|---|---|---|
| 字段总数 | **33 = 32 非 transient + 1 transient**（`blockDisplayPolicy`）。Lead 记的「32=31+1」少算 1 个：实际 32 个非 transient（20 个 int/boolean + 3 个枚举 + 2 个 List + channel + serverChannels + raycastDistance + 4 个 safeZone） | HEAD `ClientConfig` 字段区 |
| equals 覆盖 | **32/32 非 transient 全覆盖**（`:546-577`），逐一 `==`/`.equals` 比较；`blockDisplayPolicy` **未**进入 equals ✓ | `:543-578` |
| hashCode 覆盖 | **同一 32 项**（`:582-591`），`Objects.hash(...)`，无 transient 泄漏 ✓ | `:580-592` |
| equals/hashCode 一致性 | 两者字段集合完全相同（均含 `channel`/`serverChannels`，与上游 lombok 语义一致：上游只有 `@ToString.Exclude`） | 同上 |
| transient 泄漏 | 无：`blockDisplayPolicy` 是缓存，正确排除 | — |
| ConfigHandler 闭环 | `configHash = config.hashCode()` 共 **9 处**，save 成功后在 `:162` 刷新、load 两路分别在 `:198/:224` 之后刷新 → 内容哈希可反映后续 setter 变更 ✓ | `ConfigHandler.java:148,162,198,224,...` |
| ServerConfig | 手写 `equals`(`:101`)/`hashCode`(`:113`) 仍在，覆盖 6/6 字段（M3a 结论维持） | — |

> 潜在细节（非缺陷）：`Objects.hash` 32 参数会装箱并分配数组，仅在配置保存时调用，频率极低。

## 二、桩的安全性（5 个新类 + CommonClient 入口）

| 类 | 行为 | 安全性 |
|---|---|---|
| `BlockDisplayPolicy` | 只保留 whitelist/blacklist 原始条目；`compile()` 用 `List.copyOf(requireNonNullElse(...))` 防御性拷贝；`defaults()` 返回不可变单例 | 不抛异常、不可变、无 NPE；**但不再解析 registry/tag**（M7 恢复），当前渲染全部 deferred，无行为影响 |
| `BlockDisplayWhitelist` | `validateEntries(...)` **空实现**（注释写明「accept everything」） | 不抛异常 ✓；语义放宽：非法条目不再触发 `ConfigValidationException` → `recoverInvalidOnLoad()` 恢复路径失效（见 N1） |
| `SettingsScreen` | `extends Screen`，标题 `Component.translatable("settings.pingforit.title")`（lang 键存在 ✓）；`notifyServerDisconnected/notifyServerConfigSnapshot` 空实现 | 打开即空白屏、不崩；S2C 快照/断线通知被静默丢弃（M8，见 N2） |
| `WorldRenderContext` | **真实实现**（非桩）：public final 字段 + 构造器 + `of(...)` | 无副作用 |
| `ExternalBlockOutlineTransform` | **真实实现**：仅 `Objects.requireNonNull` 前置；`PoseStack`/`Matrix4f`/`Vec3` 均在 26.3 存在 | 不抛业务异常 |
| `CommonClient` 渲染入口 | `onRenderWorld(WorldRenderContext)` 仍执行 `GameContext.beginRenderEntityLookupFrame()` + `MarkerOverlayState.prepare(ctx, store, nameStore)`（`:257-264`）；`renderEntityOutlines/renderBlockOutlines/onRenderGUI/onRenderToggleNotice` 为 no-op，后者形参降为 `Object`（`:276,302-308,316,321`） | 空指针风险低：`onRenderFrame()`(`:326-340`) 有 level/player 判空；`onRenderWorld` 的调用方（LevelRendererMixin）目前在 deferred，等 M7 接线 |

**M4 接线影响**：`Object` 形参是显式 TODO（注释已写「26.3 buffer/frame 类型待重映射」），M4 只需把 HUD 那一个换成 `GuiGraphicsExtractor`，不会造成调用链返工——设计可接受。

## 三、compile-check 工具可信度

| 项 | 评估 |
|---|---|
| classpath | `MC common jar + D:/Gradle/caches/modules-2/files-2.1 下**全部 153 个 jar**`（去掉 sources/javadoc）→ 是 **Gradle 编译类路径的超集** |
| 主要偏差方向 | 超集 ⇒ **「本地绿、Gradle 红」** 是可能的（多出来的 jar 可能提供 Gradle 不提供的符号）。实测缓存中 **无** modmenu/voicechat/sable/DH/FTB/lombok（0 命中），故当前主要风险只是 guava/gson 等多版本并存掩盖冲突，**风险中低但不是零** |
| 反向偏差 | 本地红/Gradle 绿基本不可能：Gradle CP 的 MC + loader + fabric-api + 传递依赖都在缓存里，全被包含 |
| 等价性细节 | ✓ client 以 `build/ck/main` 作 CP（等价 split 源集）；✓ `--release 25`；✓ 未加 Gradle 也没有的额外 flag |
| **不覆盖** | ① processResources / 资源缺失（mixin json、services、assets）② datagen 任务 ③ mixin refmap/target 校验 ④ 重复类/打包冲突 |
| 计数可靠性 | 脚本**不清理** `build/ck`：删掉的源文件会残留 `.class` → 263/82 可能虚高。建议跑前 `rm -rf build/ck`（N5） |
| 实测 | `main 0 error / 263 class`、`client 0 error / 82 class`；client 日志仅 1 条 deprecation note（FabricClient） |

## 四、26.3 API 抽检（javap，5 项全对）

| 用法 | 26.3 实际签名 | 结论 |
|---|---|---|
| `GameProfile.name()` | `com.mojang.authlib.GameProfile extends java.lang.Record`，含 `id()/name()/properties()`（authlib 10.0.77） | ✓（`ServerCore:194,343,485,657,806,809` 等调用正确） |
| `ServerPlayer.level()` | `public ServerLevel level()` 存在；`serverLevel()` 已不存在 | ✓ |
| `InputConstants` | `KEY_LSHIFT`、`MOUSE_BUTTON_5`、`Type.KEYBOARD/MOUSE` 均在 | ✓（`InputUtils:25,28-33`） |
| `Team#getColor()` | `Optional<TeamColor> getColor()` | ✓ |
| `Minecraft.gui` + `Gui.screen()` | `public final Gui gui`；`Gui.screen()`、`Gui.setScreen(Screen)` 均在；`Minecraft.setScreenAndShow(Screen)` 在 | ✓（`CommonClient:135,189`、`InputUtils:115`、`WheelMouseCapture:105,140`） |
| 附加 | `ComponentSerialization`（`CODEC`/`STREAM_CODEC`）存在 | ✓ |
| Mixin 目标 | `KeyMapping.click(Key)`/`set(Key,boolean)`/`releaseAll()`、`Minecraft.setScreenAndShow(Screen)` 均存在 | ✓（当前 2 个 mixin 有效） |

## 五、deferred 完整性

- 92 个文件；**src 中指向「只存在于 deferred」的 import：0**（用 HEAD 的 src/deferred FQN 集合做差集比对）。
- `pingforit.mixins.json` 只剩 `KeyMappingMixin`、`MinecraftMixin` 2 项，且两者的目标方法经 javap 验证存在 ✓；`compatibilityLevel: JAVA_25`、无 refmap ✓。
- `deferred/` 在 `src/` 之外 → 不参与编译、不进入 jar ✓；其中没有被 resources（mixin 配置、services、assets）引用。

## 六、分级问题

### 阻断
无。

### 建议
- **N1**：`BlockDisplayWhitelist.validateEntries` 空实现 → 客户端配置里的非法 whitelist 条目不再被拒绝，`ClientConfig.recoverInvalidOnLoad()` 的恢复路径实际失效（`ClientConfig:178-183,286-287`）。建议在 `docs/PORTING-PROGRESS.md` 明记「M8 恢复前，配置校验为宽松模式」，避免把它当回归。
- **N2**：`SettingsScreen.notifyServerDisconnected/notifyServerConfigSnapshot` 空实现 → 快照/断线事件被丢弃；M8 前属预期，但 M4 联调时别把这些当作「服务端没发包」的依据。
- **N3**：`CommonClient` 的 `Object` 形参（`:276,302-308,316,321`）建议在 M4 直接替换为 `GuiGraphicsExtractor`（HUD）与 26.3 提交器类型，避免下阶段一次性重接线。
- **N4（新发现）**：`FabricClient` 仍用 **已废弃** 的 `ResourceManagerHelper` 与 `IdentifiableResourceReloadListener`（`javap -v` 确认 `@Deprecated`）。26.3 对应替代是 `net.fabricmc.fabric.api.resource.v1.ResourceLoader`（同 jar `fabric-resource-loader-v1`，另有 `SimpleReloadListener`/`ResourceReloaderKeys`）。建议 M8 前一并换掉，顺便消掉 javac 的那条 deprecation note。
- **N5**：`tools/compile-check.sh` 建议跑前 `rm -rf build/ck`（避免残留 class 抬高计数），并把 CP 从「全部缓存 jar」收敛为 `gradle dependencies` 解析结果，减少「本地绿 / Gradle 红」的假阴性。
- **N6**：`build/ck` 下遗留 437 个杂项文件（`api*.sh`/`e*.sh`/`check_eq*.js`），属 `build/` 范围，由 4号 清理即可。

## 七、复现命令（只读）

```
git show HEAD:src/client/java/nx/pingwheel/common/config/ClientConfig.java   # :543-592 equals/hashCode
git grep -c 'configHash = config.hashCode()' HEAD -- src/main/java/nx/pingwheel/common/config/ConfigHandler.java
javap -p -cp <minecraft-common-...-26.3.jar> net.minecraft.world.scores.Team | findstr getColor
javap -p -cp <minecraft-clientOnly-...-26.3.jar> net.minecraft.client.KeyMapping | findstr "click set releaseAll"
javap -cp <authlib>.jar com.mojang.authlib.GameProfile
# deferred 差集：src FQN 集合 vs deferred FQN 集合，检查 src 的 import 是否指向 deferred-only
```
