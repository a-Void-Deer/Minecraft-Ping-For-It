# 日志监测区（5号 · log-monitor）

> 写入范围：`docs/logs/**`。`run/` 下日志**只读**，禁止删改。
> 本目录只负责「采集 + 时间线 + 摘要」，不做归因结论的最终裁定（归因交 1号）。

## 1. 日志位置清单（现状 = 2026-10-28 14:2x 实测）

| 路径 | 现状 | 何时出现 | 用途 |
| --- | --- | --- | --- |
| `run/` | **只有 `run/resources/`**（空目录，13:56 建） | 首次 `runClient`/`runServer` 后填充 | Loom 的 dev 运行目录 |
| `run/logs/latest.log` | **不存在** | 首次启动游戏时 | 游戏主日志（log4j2） |
| `run/logs/*.log.gz`、`debug.log` | 不存在 | 每次启动轮转 | 历史日志 |
| `run/crash-reports/crash-*.txt` | **不存在** | 崩溃时 | 崩溃栈全文（最权威） |
| `run/options.txt`、`run/config/` | 不存在 | 首次启动 | 渲染/按键/模组配置 |
| `D:\Gradle\daemon\9.8.2\daemon-*.out.log` | **存在**（DEBUG 级，200KB+） | 每次 gradle 调用 | Gradle 全量日志：`BUILD SUCCESSFUL/FAILED`、`What went wrong`、Loom 版本、JVM |
| `build/reports/problems/problems-report.html` | 存在（128KB，14:04） | 配置阶段 | Gradle 弃用/配置问题（当前仅 1 条 WARNING） |
| `docs/build/build-attempt-*.log` | 存在（4号产出） | 每次构建尝试 | 构建控制台输出，**GBK 编码** |
| `build/libs/`、`build/classes/` | **不存在（0 个 .class）** | 首次真正 `build` 后 | 构建产物 |

> 结论：目前**没有任何游戏运行日志**，`runClient` 从未成功启动过。首启采集方案见 §4。

## 2. 采集手段（本机实测）

- **bash 可用**：GNU bash 5.3.15 (cygwin)，`tail -F`（支持 `--retry`）、`grep -E`、`sed`、`awk`、`wc` 都在。
- **坑 1（P0）**：`tail -F ... | tee ... | grep ...` 多进程管道会触发 msys 故障
  `fatal error - CreateFileMapping ..., Win32 error 5. Terminating.`（AGENTS.md #10 同源）。
  → **禁止用管道链 follow 日志**，改用单进程脚本 `tools/watch-file.ps1`。
- **坑 2（P0）**：Windows 控制台重定向出来的日志是 **GBK(cp936)**，不是 UTF-8。
  用 `read` 工具或 UTF-8 `grep` 会报 `invalid UTF-8 text` 或乱码。
  → 用 `tools/watch-file.ps1`（内置 `GetEncoding(936)`）或 `iconv -f GBK -t UTF-8 file`。
- **坑 3**：`D:\Gradle` 在 5号 的写入沙箱之外，只能读；`tasklist` 也被拒（`Access denied`）。
  → 「lck 文件是否被占用」这类判断 5号 **无法自证**，需 4号 用 pwsh 或有权限方验证。
- **大文件**：先用 `grep -n` 定位行号，再 `read(offset, limit)` 取上下文，不要整读。

常用命令（bash）：

```bash
# 只看异常（不 follow）
grep -nE "ERROR|FATAL|Exception|Caused by|Mixin apply failed" run/logs/latest.log | head -50
# 统计
wc -l run/logs/latest.log
# GBK 转码后再过滤
iconv -f GBK -t UTF-8 docs/build/build-attempt-1.log | grep -nE "Exception|FAILED"
```

## 3. 记录格式（每条一行，追加到 `docs/logs/LOG-TIMELINE.md`）

```
时间戳 | 级别 | 来源文件:行号 | 关键行 | 初步归因 | 需要1号分析
```

字段约定：
- **时间戳**：日志原文时间（`yyyy-MM-dd HH:mm:ss`），不是我发现的时间。
- **级别**：ERROR / WARN / FATAL / INFO(里程碑) / BUILD(构建结果)。
- **来源**：`相对路径:行号`，务必可复现定位。
- **关键行**：**原文照抄 ≤1 行**，不改写；长栈只抄首行 + `Caused by`。
- **初步归因**：只写「可能原因」，不确定标 `待验证`。
- **需要1号分析**：是/否。

空模板：

```markdown
| 时间戳 | 级别 | 来源:行号 | 关键行 | 初步归因 | 需1号 |
| --- | --- | --- | --- | --- | --- |
| YYYY-MM-DD HH:mm:ss | ERROR | run/logs/latest.log:0 | （原文一行） | （猜测，待验证） | 是 |
```

## 4. 首次 `runClient` 采集方案（预置，日志出现前就位）

1. **启动前**给 Lead 确认日志级别（见 §5），并清点 `run/` 基线（当前 = 只有 `run/resources/`）。
2. `runClient` 一启动，`run/logs/latest.log` 会在数秒内出现（Loom 先建 `run/`）。
3. 采集窗口 ≤3 分钟，用单进程增量读取：
   ```powershell
   pwsh -File docs/logs/tools/watch-file.ps1 -Path 'run\logs\latest.log' -Seconds 150
   ```
   只输出命中 `ERROR|FATAL|Exception|Caused by|Mixin|crash` 的新行；启动成功标志是命中
   `Backend library: LWJGL` 与主菜单前的 `Sound engine started`。
4. 客户端若是**静默失败**（进程退但没有 latest.log），改看：
   - `run/crash-reports/crash-*.txt`
   - gradle 侧 `D:\Gradle\daemon\9.8.2\daemon-*.out.log` 尾部（`What went wrong`）
   - `run/.mixin.out/`（若开了 `-Dmixin.debug.export=true`，mixin 失败会导出类文件）
5. 首启重点核验：`Mixin apply failed` / `InvalidInjectionException`。
   目标方法**已由 1号 静态确认存在**（`MinecraftServer#loadLevel` @ `MinecraftServer.java:395`、
   `Minecraft#run` @ `Minecraft.java:895`，均 `()V`），所以首启若仍报注入失败，
   优先查 **mixin 配置 `package` 与真实包名是否一致 / 类是否进了 `mixins` 数组**。
   **⚠ 别把 `Minecraft.java:560` 的 `run()` 当目标**——那是窗口关闭回调里的匿名 Runnable。
   完整速查表与判读规则见 [`LOG-TIMELINE.md` §五](LOG-TIMELINE.md)。

## 5. 建议日志级别（启动游戏前设定）

- **保持 log4j2 默认（root=INFO）**：`run/logs/latest.log` 在 INFO 下已包含加载、注册、
  资源包、mixin 错误；调到 DEBUG 会让首启日志膨胀数倍且噪声淹没真错误。
- **真正需要加的是 JVM 参数，不是日志级别**：
  - `-Dmixin.debug.export=true` → mixin 失败时把类导出到 `run/.mixin.out/`，**排查 P1 #4 首选**。
  - `-Dmixin.debug.verbose=true` → 只在专门查某个 mixin 时临时加。
- **Gradle 侧**：`build` 加 `--stacktrace`（失败时才有完整栈，成功不增加噪声）；
  `--info`/`--debug` 仅在被点名排查时临时用。
- 不要把 `-Xmx1G`（AGENTS.md P2 #5）和日志级别混为一谈：OOM 会表现为
  `java.lang.OutOfMemoryError: Java heap space`，与日志级别无关。

## 6. 监测纪律

- 单次窗口 **≤3 分钟**；超时先回报结果再申请续期。
- 发现 ERROR/崩溃栈：**原文保留**（复制到 `docs/logs/raw/`）+ 摘要进 LOG-TIMELINE，
  同时 `send_message` 给 lead 与 repo-analyst（附记录文件路径）。
- **不许编造日志内容**；没采到就写「未采到」，不确定标 `待验证`。
- 全项目同一时间只允许一个 gradle 任务：发现 daemon 日志正在写入就说明有构建在跑，
  此时**不要**再触发 gradle。
