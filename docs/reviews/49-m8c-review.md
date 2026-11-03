# 49 · M8c 复审：目标选择切换提示（toggle notice）迁入 26.3 HUD

任务：M8c（Lead 直派）　审核人：3号　日期：2026-11-01
对象：新增 `SelectionToggleNoticeRenderPolicy` / `SelectionToggleNoticeRenderer`，改 `CommonClient` / `FabricClient`（未提交）。
验证：只读源码 + upstream 对照 + javap（`Font$PreparedTextBuilder` / `ARGB` / `GuiGraphicsExtractor` / Fabric `HudElementRegistryImpl`）+ **重跑** `bash tools/compile-check.sh`（`main 266 / client 122，0 error`）。未跑 Gradle。

## 结论

**通过。** 没有阻断项。淡出 alpha、两个颜色、几何、shadow、HUD 层序、Object 重载删除、时钟与 policy 均复核正确；两条非阻断建议（HUD 失败隔离、alpha 取整可简化）见下。

---

## 一、阻断项

无。

---

## 二、非阻断

1. **HUD 失败隔离（建议）**：`CommonClient.onRenderToggleNotice`（`:322-327`）没有像 `onRenderHud` 那样包 `try/catch`；`SelectionToggleNoticeRenderer.draw` 的 `try/finally`（`:69-74`）只保证 `popMatrix`，如果 `graphics.text` 抛 `RuntimeException`，异常会冒出 HUD 层进入 `renderFrame`——按 AGENTS §14（GUI 异常 = 整屏冻结）应加一次性 WARN 的隔离。正常文本路径几乎不会抛，故非阻断。
2. **`Math.round(snapshot.alpha())`（`:60`）**：`Snapshot.alpha` 已是 0..255 的 **int**，`Math.round(int)` 只是扩宽再取整，语义恒等；建议直接 `snapshot.alpha()`，避免读者误以为 alpha 是 0..1。
3. **HUD 层序依赖 Fabric API 的 addLast 语义**：已对本项目解析到的 `fabric-rendering-v1-27.2.0` javap 确认（见下），但升级 API 时要复测；当前无问题。

---

## 三、保真差异清单（与 upstream 逐项）

| 项 | upstream（1.21.1） | 本实现（26.3） | 结论 |
|---|---|---|---|
| 淡出 | `RenderSystem.setShaderColor(1,1,1,alpha)`，alpha=`snapshot.alpha()/255f`，包住两段 drawString | 把 alpha byte 折进传给 `text` 的颜色：`(alphaByte<<24)|rgb`，两段各自 | **等价**（见下证据） |
| 颜色 | prefix style WHITE；state style `0x55FF55/0xFF5555`；drawString 颜色 WHITE | 同样 style RGB；state 传入 `alpha|green/red`，prefix 传入 `alpha|WHITE` | **等价**（样式色覆盖 RGB，传入色只贡献 alpha） |
| 几何 | `anchor=(guiWidth/2, guiHeight*0.25)`、`scale=sizePercent/100`、prefix `-totalWidth/2`、state `+prefixWidth`、`y=-font.lineHeight/2`、shadow=true | 完全相同（`policy` 哈希与 upstream 逐字节一致） | **一致** |
| HUD 层序 | 上游 late HUD pass（在 marker/wheel 之后） | `HudElementRegistry.addLast` 两次：marker 先、notice 后；wheel 在 marker 层内 | **一致**（见下证据） |
| 时钟 | `INTERACTION_TIME_SOURCE.nowMillis()` | 同一个 `InteractionTimeSource.system()`（`System.nanoTime()/1e6`），`SelectionToggleNoticeState.monotonicMillis` 同式 | **一致** |
| 颜色精度 | shader float alpha | byte alpha（state 的 alpha 本来就是 0..255 int） | **无可测差异**（GUI 最终也是 8-bit 颜色/混合） |
| 失败行为 | shader color 在 finally 重置 | matrix 在 finally pop；没有 RenderSystem 全局状态需要还原 | 不改变可见效果；失败隔离建议见非阻断 |

### 淡出证据（最关键）
- `Font$PreparedTextBuilder.getTextColor(TextColor)` 字节码：`if (styleColor != null) { int alpha = ARGB.alpha(this.color); int rgb = styleColor.getValue(); return ARGB.color(alpha, rgb); } else return this.color;`。
- `ARGB.color(int alpha,int rgb)` = `(alpha << 24) | (rgb & 0xFFFFFF)` → **传入颜色的 alpha + style 的 RGB**，正是本实现需要的语义；不是“style alpha 优先”也不是“0=不透明”。
- 阴影：`getShadowColor(Style,int)` 在 style 没有 shadowColor 时返回 `ARGB.scaleRGB(textColor, 0.25F)`；`ARGB.scaleRGB` 先取 `alpha(color)`，再用 `color(alpha, r*0.25, g*0.25, b*0.25)` 重组 → **shadow 保留 alpha**，会一起淡出。
- `alphaAt`（`SelectionToggleNoticeState:64-76`）返回 0..255 int，终值 0；`alpha=0` 时 `ARGB.color(0, styleRGB) = 0x00RRGGBB`，getShadowColor 也 alpha 0；GUI 文本按顶点色混合，没有“0 当不透明”的分支。

### HUD 层序证据
- `HudElementRegistryImpl.addLast` 字节码：`validateUnique(id); LAST.layers().addLast(HudLayer.ofElement(id, element));`；`LAST` 是静态单例 `RootLayer`，即**两个不同 id 都追加到同一个 LAST 根层**，List 的 addLast = FIFO。
- `FabricClient` 先注册 `MARKER_HUD_LAYER_ID`（`:49-51`），再注册 `TOGGLE_NOTICE_HUD_LAYER_ID`（`:56-58`）→ notice 的 layer 在 marker 之后执行；`onRenderHud` 内部先 `MarkerHudRenderer` 再 `WheelHudRenderer`（`CommonClient:295-299`）→ wheel 也在 notice 之前。

---

## 四、其它复核

- **Object 重载删除**：全仓 grep `onRenderToggleNotice` 仅命中新方法定义/调用（`CommonClient:322`、`FabricClient:58`），旧 `onRenderToggleNotice(Object)` 无任何调用者；删除不改变既有行为 ✓。
- **policy 文件逐字节相同**：`src/.../SelectionToggleNoticeRenderPolicy.java`、`deferred/render/...`、upstream 文件 SHA-256 均为 `6A4662F9E06447C3E64774332F8107BAF5644CA6B9605A5091F823C16BF6CF23` ✓。
- **时钟**：`INTERACTION_TIME_SOURCE = InteractionTimeSource.system()`，其 `nowMillis` = `System.nanoTime()/1_000_000L`；`SelectionToggleNoticeState.monotonicMillis` 同式 → 同一 epoch ✓。
- **try/finally**：`popMatrix` 需要 finally，保留正确；upstream 的 finally 是重置 shader color，本实现无全局状态，等价改 matrix pop 合理；非阻断建议是再包一层异常隔离，而不是去掉 try/finally。
- **编译**：`bash tools/compile-check.sh` → `main 266 / client 122，0 error` ✓。

---

## 五、待实机项

- [ ] 触发三种 toggle（pass-through / blacklist / fluids）：白色 prefix + 绿色/红色 state，位置在准星上方 `guiWidth/2, guiHeight*0.25`，shadow 可见。
- [ ] 时间线：2s 全亮 + 1s 渐隐；最后一帧 alpha=0 完全消失；shadow 与文字同步淡出。
- [ ] `configuration_notice_size=0` 时不显示；不同 GUI scale/窗口尺寸下 scale 正确。
- [ ] notice 绘制在 marker 点和轮盘之上（打开轮盘同时触发 toggle，确认 notice 不被 wheel 覆盖）。
- [ ] 快速连续 toggle 时只显示最新一条（state 语义，不在本 renderer 内）。
- [ ] 若刻意制造 text 异常，确认不会整屏冻结（对应非阻断 1 是否修）。

---

## 六、结论

**通过。** 淡出折进颜色的做法在 `Font$PreparedTextBuilder`/`ARGB` 字节码层面是等价的（含 shadow、alpha=0），颜色/几何/层序/policy/时钟均与 upstream 对齐；Object 重载删除无调用者；编译 0/0。建议顺手加 HUD 失败隔离与去掉多余的 `Math.round`，然后按 §五 实机确认 2s+1s 时间线与层序。