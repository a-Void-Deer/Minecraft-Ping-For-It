# 52 · HUD 投影补 view bobbing + ping 提示改聊天框

任务：HUD 投影补 view bobbing / ping 提示改聊天框　审核人：3号　日期：2026-11-01
对象：`CommonClient.java`（hudRenderContext / bobHurt / bobView）、`ClientPingRuntime.java:1094`、`MinecraftLocalErrorSink.java:30`（未 commit；`改进方案.md` 不计入）。
基线复核：`bash tools/compile-check.sh` → `main 266 / client 125，0 error` ✓。

## 结论

**打回。** 主路径的 bob 数学/矩阵/fallback/聊天语义都正确，`cameraState.projectionMatrix` 不含 bob，**没有双乘**；但 `bobHurt` 的 `damageTiltStrength` 按 float 先乘，26.3 是 `double` 相乘后再 `d2f`，与规范第 1 点不一致（严格保真下需要改一行）。其余为文档/语义非阻断项。

---

## 一、阻断项

### B1 · `damageTiltStrength` 精度顺序不一致（`CommonClient.java:446`）

当前：
```java
pose.rotateDegrees(Axis.ZP, -hurtTime * 14.0F * (float) options.damageTiltStrength);
```
26.3 `GameRenderer.bobHurt` 字节码（`bobHurt` 偏移 117-136）：
```text
fload_3; fneg; f2d; ldc2 14.0d; dmul; optionsRenderState.damageTiltStrength(D); dmul; d2f; fstore 5; rotateDegrees(ZP, f5)
```
即 `(float)((double)(-hurtTime) * 14.0 * damageTiltStrength)`；当前实现先 `14.0F * (float)damageTiltStrength`，float 乘和 double 乘的舍入点不同。虽然只是细节，但这是本轮明确要求逐项对齐的常数/精度路径，且 iron law 不允许未登记的视觉差异。

**改法**：
```java
pose.rotateDegrees(Axis.ZP, (float)((double)(-hurtTime) * 14.0 * options.damageTiltStrength));
```
改完这一条即可复核通过。

---

## 二、通过项

### 1. bob 数学与调用顺序

逐项与 `GameRenderer.bobHurt` / `bobView` 字节码对照（`javap -c net.minecraft.client.renderer.GameRenderer`）：

| 项 | 26.3 | CommonClient | 判定 |
|---|---|---|---|
| `bobHurt` 入口 | `isLiving` 才继续 | `if (!entity.isLiving) return` | ✓ |
| 死亡分支 | `min(20.0f, deathTime)`；ZP `40 - 8000/(death+200)` | 同 | ✓ |
| hurtTime 负值 | `<0` 直接 return | 同 | ✓ |
| hurtTime 归一 | `hurtTime /= (float)hurtDuration` | 同 | ✓ |
| `sin` 参数 | `hurtTime^4 * πf`，f2d，`Mth.sin(double)` | 同（float 表达式再 widening） | ✓ |
| 轴顺序 | `YP(-hurtDir)` → `ZP(tilt)` → `YP(hurtDir)` | 同 | ✓ |
| 倾斜强度 | `(-hurtTime) * 14.0d * damageTiltStrength(double)` → `d2f` | float 先乘 | **B1** |
| `bobView` 条件 | `options.bobView` 才调用 | 同 | ✓ |
| `bobView` 平移 | `sin(walkπ)*bob*0.5`, `-abs(cos(walkπ)*bob)`, 0 | 同 | ✓ |
| `bobView` 旋转 | ZP `sin*3`，XP `abs(cos(walkπ-0.2)*bob)*5` | 同 | ✓ |
| `bobView` 入口 | `isPlayer` 才继续 | 同 | ✓ |

### 2. 是否重复应用 bob（明确结论）

**没有重复。** `Camera.extractRenderState` 用 `Projection.getMatrix(cameraState.projectionMatrix)` 写入的是 **P**，`viewRotationMatrix` 写入的是 **V**；`GameRenderer.renderLevel` 是 `new Matrix4f(cameraState.projectionMatrix)` 后 `projection.mul(bob)`（P×bob），**不修改 cameraState**。因此 `CommonClient` 复制 P 再乘 bob 与 vanilla 完全一致；fallback 的 `camera.getViewRotationProjectionMatrix()` 也是 P×V、不含 bob。

### 3. 矩阵语义

- `cameraState.projectionMatrix` = P，`viewRotationMatrix` = V；新实现 `modelView=V`、`projection=P×bob`。
- `MathUtils.worldToScreen` 的 `Vector4f.mul(Matrix4f)` 在 JOML 1.10.9 中是 **M × this**（column vector）；`Matrix4f.mul(right)` 是 **this × right**。
- 旧：`I * (P×V) * p`；新：`(P×bob) × V * p`。`P×bob×V` 与 vanilla 世界投影一致；除 bob 外无其它变化。
- `projection.mul(bob.last().pose())` 的方向与 `GameRenderer.renderLevel` 的 `Matrix4f.mul(pose)` 完全相同（P' = P×bob）。

### 4. fallback

- `renderState == null` → `levelState == null` → `cameraState == null` → fallback；
- `cameraState == null` / `entityRenderState == null` / `projectionMatrix == null` / `viewRotationMatrix == null` → fallback；
- fallback 走 `camera.getViewRotationProjectionMatrix()`（P×V，无 bob），不 NPE；`camera == null || !initialized` 时返回 null；首帧最多无 bob 一帧，可接受。
- 建议（非阻断）：把 `renderState.optionsRenderState == null` 也加进 fallback 条件，避免未来 GameRenderState 半初始化时在 `bobHurt` 解引用。

### 5. 影响面

- `hudRenderContext` 只被 `onRenderHud` 使用；`onRenderWorld` 的 `MarkerOverlayState.prepare` 是 marker 投影，期望受 bob 影响。
- `WheelHudRenderer.render(GuiGraphicsExtractor, ClientPingRuntime)` **不接收 WorldRenderContext**；`onRenderToggleNotice` 也不接收；屏幕空间元素不受影响。
- `MarkerHudRenderer` 只消费预先算好的 `ScreenPos`；bob 只改变 marker 的屏幕投影，符合预期。

### 6. 聊天行语义

- `LocalPlayer.sendSystemMessage(Component)` 字节码 → `ChatListener.handleSystemMessage(component, true)`；true 分支是 `ChatComponent.addServerSystemMessage(...)`，**进聊天框**，不是 `handleOverlay` / `Hud.setOverlayMessage`。`sendOverlayMessage` 才是 overlay，已替换正确。
- `sendCreatedChat` 只在 `applyCreated` 的 `newlySeen` 分支调用；`isNewMarkerReceipt` = `store.marker(id).isEmpty()`，**首次本地收到该 marker ID 才显示一次**；重传/同 ID 不再显示。
- 颜色/模板未改：`PingChatBuilder` 仍走 `pingforit.chat.pingmsg[.template]` / `pingforit.chat.<type>.template.override`；`MinecraftLocalErrorSink` 仍用 `pingforit.chat.local_error.prefix` + messageKey 的 24-bit 色。

---

## 三、非阻断

1. **`MinecraftLocalErrorSink.java:11` javadoc 过期**：注释仍写 `sendSystemMessage(..., false)`，但 26.3 方法是一参；建议改成 `sendSystemMessage(Component)` 或 `handleSystemMessage(..., true/false)` 的真实语义。
2. **`sendSystemMessage` 内部用 `handleSystemMessage(..., true)`（server system），上游 `displayClientMessage(message, false)` 的等价语义更接近 `handleSystemMessage(..., false)`（client system）**；两者都进聊天框，视觉/颜色/模板相同，但聊天日志/系统消息标记可能不同。若要求逐字节对齐，可评估改用 `handleSystemMessage(..., false)` 或 `ChatComponent.addClientSystemMessage`。
3. **`renderState.optionsRenderState` 未纳入 fallback 判空**（见上）；当前 GameRenderState 初始化完整，风险低。
4. **bob 之外的投影效果（nausea/portal 的 rotate/scale）仍未复刻**；这是既有差异，本轮只补 bob，不是新回归；若要 HUD 全量匹配世界，需另行登记/实现。

---

## 四、待实机

- [ ] 走路/后退/侧移：marker 与世界同步晃动；轮盘、toggle notice 保持屏幕固定。
- [ ] 受伤/死亡：HUD 倾斜与世界一致（重点看 damageTilt 修复后的角度）。
- [ ] 首帧/切换维度/暂停恢复：fallback 不 NPE，最多一帧无 bob。
- [ ] 聊天框：每次 ping 首次收到显示一行；重传不重复；颜色/模板/本地错误前缀正确；不是 actionbar。
- [ ] `sendSystemMessage` 的 server-system 语义是否影响聊天过滤/日志（非阻断，记录）。
