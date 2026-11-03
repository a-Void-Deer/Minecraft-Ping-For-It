# 重命名审核：modid → isharmla（只读复核）

审核人：3号·代码审核员　时间：2026-10-28 14:51　对象：2号 的重命名改动
**结论：重命名本身通过（源码侧全干净，0 处残留）；但「改名完成」还不能算，需一次 clean build + 搬 icon 后复验。**
分级：阻断 0 / 严重 1（build/ 旧产物）/ 建议 4。

## 一、逐项核查（全部我自己 grep/read/解包验证，未采信 2号 自检）

| # | 检查项 | 结论 | 证据 |
|---|---|---|---|
| 1 | 改名的 5 处配置 | 全部一致 | settings.gradle:13 `'isharmla'`；build.gradle:18 `"isharmla"`；gradle.properties:14 `group=com.isharmla`；fabric.mod.json:3 `"id": "isharmla"`；ExampleMod.java:11 `MOD_ID = "isharmla"` |
| 2 | 源码残留 `com.example` / `modid` | **0 命中** | `grep -rn 'com\.example\|modid' src --include=*.java --include=*.json` → NO HITS（src 下唯一 `modid` 是图标文件的**路径**，见 #7） |
| 3 | package 声明 == 目录 | 4/4 一致 | ExampleMod.java:1 `com.isharmla` ↔ src/main/java/com/isharmla/；ExampleMixin.java:1 `com.isharmla.mixin` ↔ .../com/isharmla/mixin/；ExampleModClient.java:1 `com.isharmla.client`；ExampleClientMixin.java:1 `com.isharmla.client.mixin` |
| 4 | entrypoint 全限定名 == 类 | 2/2 一致 | fabric.mod.json:19 `com.isharmla.ExampleMod`（public class，implements ModInitializer）；:22 `com.isharmla.client.ExampleModClient`（implements ClientModInitializer） |
| 5 | mixin 配置 package + 类名 | 2/2 一致 | isharmla.mixins.json:3 `com.isharmla.mixin` + `:6` `ExampleMixin` ↔ 文件存在；isharmla.client.mixins.json:3 `com.isharmla.client.mixin` + `:6` `ExampleClientMixin` ↔ 文件存在（client 配置仍在 src/client/resources，正确） |
| 6 | 旧文件是否删净 / 有无同包双份 | 干净 | `find src -type f` 只有 8 个文件；`find . -name '*.orig' -o -name '*.rej' -o -name '*.bak'` 无输出；不存在两套同名类 |
| 7 | 资源目录 | 有 1 处已知待搬 | src/main/resources/assets/modid/icon.png 仍在；`assets/isharmla/` 已建但**是空目录**（git 不跟踪空目录） |
| 8 | fabric.mod.json 其余引用 | 正确 | :15 icon → assets/isharmla/icon.png；:26 `isharmla.mixins.json`；:28 `isharmla.client.mixins.json` —— 三个文件/目录名与实体逐一对得上（icon 除外，见 #7） |
| 9 | CI / workflow | 无硬编码名 | `.github/workflows/build.yml` 里无 modid/isharmla 字样（只用 ./gradlew build） |

## 二、严重 1：build/ 里全是改名前的产物（会让「新构建」变成脏产物）

现况（全部 14:28/14:30 生成，早于重命名）：
- `build/classes/java/main/com/example/*.class` + `build/classes/java/client/com/example/client/...`（4 个 .class）
- `build/resources/main/{fabric.mod.json, modid.mixins.json}`、`build/resources/client/modid.client.mixins.json`、`build/resources/main/assets/modid/icon.png`
- `build/resources/main/fabric.mod.json` 实测 :3 `"id": "modid"`、:19 `:22` `com.example.*`
- `build/libs/`：`modid-1.0.0.jar`、`modid-1.0.0-sources.jar`、`Isharmla-1.0.0.jar`、`Isharmla-1.0.0-sources.jar`（4 个 jar 内全是 com/example + id=modid）

评估（分三类，结论不同，别混为一谈）：
1. **类文件**：`build/tmp/compileJava/previous-compilation-data.bin` 与 `.gradle/9.8.2/executionHistory.bin` 都在 → Gradle 增量编译会识别「源文件被删」并清掉对应 `.class`（stale output 清理）。**预期自动干净，但属"预期"，必须以构建后的 jar 内容为准。**
2. **资源**：processResources 同理，`build/resources/main/modid.mixins.json` 预期会被清掉；若没清，jar 里会出现新旧两份 mixin 配置（旧的不被引用但污染，且容易误判）。
3. **build/libs 的旧 jar：不会被任何任务删除**。jar 输出名已随 rootProject.name 变成 `isharmla-1.0.0.jar`；另外注意 Windows NTFS **大小写不敏感**，新构建写 `isharmla-1.0.0.jar` 会**覆盖** 现有的 `Isharmla-1.0.0.jar`（同一个文件），但 `modid-1.0.0*.jar` 会一直躺着。

风险后果：谁把 `build/libs/modid-1.0.0.jar` 拖进 `mods/`，加载的是旧 id=modid 的模组（能启动、不报错、纯静默错误），极容易把验证结论搞反。
→ **建议 Lead 批准 4号 执行 `clean build`**（build/ 全部可重建，git 也忽略它，无不可逆损失）；若坚持不 clean，则必须 build 后核对：

```bash
export JAVA_HOME="C:/Program Files/Java/jdk-25"
export GRADLE_USER_HOME="D:/Gradle"
./gradlew -g D:/Gradle build
unzip -l build/libs/isharmla-1.0.0.jar
unzip -p build/libs/isharmla-1.0.0.jar fabric.mod.json | grep '"id"'
```
期望：jar 内只有 `com/isharmla/**` 4 个 class、`isharmla.mixins.json`、`isharmla.client.mixins.json`、fabric.mod.json、LICENSE_isharmla；**不得出现** `com/example`、`modid.*.json`；id = `isharmla`。之后手动删掉 `build/libs/modid-1.0.0*.jar` 两个旧包。

## 三、建议 4 项

- N1 **git 现状（已更新，原判断作废）**：`main` 已多次提交，**重命名内容已入库**（改名真身在 `030015c`），`git status --porcelain src` 为空。提交次数这类易腐事实不写死，需要时用 `git log` 查。**注意标题与内容错位**：改名内容实际在 `030015c`（标题「第一次提交」，15 文件 / 6 个重命名），而标题写 rename 的 `d10f436` 只动了 docs —— 找回滚点别认错。仍**严禁** `git reset --hard` / `git clean -fdx`。
- N2 **icon 待搬**：用户把 png 放到 `src/main/resources/assets/isharmla/icon.png` 即可；`assets/isharmla/` 现为空目录，注意 git 不跟踪空目录，别以为已提交。
- N3 **模板文案未换**：fabric.mod.json:5-13 仍是 `"name": "Example Mod"`、`"authors": ["Me!"]`、contact 指向 FabricMC 官方仓库 —— 功能开发前替换（属 2号 范围）。
- N4 **装饰性残留（settings 那条已修）**：settings.gradle:12 已在 `e5a329f` 改为 `// Should match the project/mod name`（复验：settings/build/gradle.properties 内容级 grep `modid` = 0 命中）；尚存：空目录 `src/main/java/com/example/mixin`、`src/client/java/com/example/client/mixin`（IDE 里会造成误导，删除无损且不入 git）；`.idea/modules/modid.*.iml` 三个旧模块文件名（内容已无 modid，IDE 重新 sync 会替换）。

## 四、Q5 专项：icon 缺失到底什么后果？

**答案：既不阻断校验，也不会打 warning —— 是纯静默的"没图标"。**（比 AGENTS.md §6-3 写的"运行时会报图标缺失"更准确，建议 1号 修正文档）
证据（fabric-loader 0.20.1 源码 jar，路径 `D:/Gradle/caches/modules-2/files-2.1/net.fabricmc/fabric-loader/0.20.1/a407a49c.../fabric-loader-0.20.1-sources.jar`）：
- `net/fabricmc/loader/impl/metadata/V1ModMetadataParser.java:588-627` 的 `readIcon()` 只把 icon 读成字符串 / size→path 映射，**没有任何文件存在性检查**；
- 全 `net/fabricmc/loader/impl/` 里 `getIconPath` 只有 metadata 自身实现与 `BuiltinMetadataWrapper.java:121` 的转发，**没有消费者**（loader 自带 GUI 用的是 `/ui/icon/` 内置图）。
→ fabric.mod.json 校验不会失败、启动日志不会有 icon 相关 WARN/ERROR。唯一可见影响：将来若装 Mod Menu 之类的模组列表 UI，会显示占位/无图标；原版与 Fabric API 都不读这个字段。

## 五、待 Lead 决策
1. 是否批准 4号 执行 `clean build`（推荐；或退而求其次：build 后手动删两个 modid 旧 jar + 核对 jar 内容）。
2. ~~是否提交重命名~~ → **已入库**（见 N1），此项关闭；余下只有空目录清理需要授权有写权限的角色（2号/我均无 src 写权）。
3. **建议给改名前状态打个本地 tag**（如 `git tag pre-rename 54feab1`）：仓库无远端、哈希可被 amend/rebase 重写，tag 比文字哈希稳；这是 additive 操作、可 `git tag -d` 撤销，但属写仓库，需你批准后由有 git 权限的角色执行。
