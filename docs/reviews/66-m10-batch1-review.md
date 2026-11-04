
# 66 · M10 首批改动复审

任务：复审 M10 首批 4 项（许可/公证、locale、GameContext、glow 诊断）
审核人：3号　日期：2026-11-04　对象：当前未提交 diff
只读源码 + 文件核对；未改代码。compile-check：common 262 / fabric-main 4 / fabric-client 126，0 error。

## 结论

**可合入，无阻塞项。** 许可事实成立（上游与本项目都是 MIT，Apache 文本与上游逐字一致，notices 与 jar 内容能对上）；locale 8 个文件全部可解析、无重复键、占位符一致，pl/tr 的 4 条 override 是有意留空且不会让通用句变成英文；GameContext 的 lastWorld 清理语义正确，render cache 已有独立的断开清理；glow 计数和 DEBUG 没有行为变化。当前 jar 是改动前产物（notices 尚未打进去），合入后必须重新 build 再验 C。

## A. 许可事实

- 本项目 LICENSE：The MIT License (MIT)，与上游 LICENSE 的 SHA256 完全相同（089E801F...A29594），都是 21 行；A 成立。
- 上游 LICENSE：MIT，Copyright (c) 2023（署名行留空），与 2号 描述一致。
- 上游 LICENSE-APACHE-2.0：200 行；本地 LICENSE-APACHE-2.0 同为 200 行，按 LF 规范化后的 SHA256 与上游完全相同（8D1E80F7...CEBD）；Windows CRLF 造成原始字节 hash 不同，不是内容差异。
- THIRD-PARTY-NOTICES.md：第 38–237 行正好是 200 行 Apache 全文；与本地 LICENSE-APACHE-2.0 逐行 Compare-Object 0 差异，与上游同文件也 0 差异；A 成立。
- MavenComparableVersion.java 头注释是 Apache-2.0（Licensed to the Apache Software Foundation...），notices 的 Apache 归属成立。
- 2号 说“上游 by Luken”：在 Mod_Project 上游树的 LICENSE、README、fabric.mod.json、构建文件中都没有找到 Luken 字样（LICENSE 署名行为空）；无法用仓库文件独立确认这个作者名。若 Luken 来自外部项目信息，则可保留；否则建议把作者名改成可核实的来源。不是阻塞项。
- 结论：MIT/APACHE/本地 LICENSE/notices 的事实都正确；只有 Luken 的作者归属是“未能从仓库核实”，不是已证实错误。

## B. notices 完整性

- 当前 jar（fabric/build/libs/Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar）是改动前构建的旧产物，根目录只有 fabric.mod.json、icon.png、pingforit.mixins.json、LICENSE_pingforit；**没有 THIRD-PARTY-NOTICES.md**。这不是代码遗漏，而是尚未重新 build；合入后必须重跑 build 再用 unzip 验。
- 旧 jar 中实际第三方/归属相关条目：
  - assets/pingforit/**（8 lang、sounds.json、ping.ogg、arrow.png、ping.png）与 icon.png；
  - MavenComparableVersion*.class（7 个 class：主类 + 6 个 Item 内部类）；
  - LICENSE_pingforit（本项目 MIT）；
  - 没有其它 vendored 代码/数据，也没有 Distant Horizons / VoiceChat / ModMenu 的 class（它们都是 compileOnly，不入 jar）。
- notices 的 Assets 段覆盖了上述 assets/icon；Vendored 段覆盖 MavenComparableVersion；本项目 LICENSE 段覆盖 LICENSE_pingforit；Apache 全文已内嵌在同一文件；没有发现漏项。
- Apache 全文与上游逐行一致；notices 第 38 行开始是 Apache License，第 212 行是 END OF TERMS，后面是 APPENDIX，总 237 行。
- 结论：notices 内容完整；唯一未完成的是“新 notices 还没进当前 jar”，属于构建验证项。

## C. 打包接线

- fabric/build.gradle 的 jar 任务新增 from(rootProject.file('THIRD-PARTY-NOTICES.md'))；文件原名入 jar 根，不会与 LICENSE_pingforit 冲突，也不会与 common 资源/classes 合并路径冲突。
- jar 仍只合并 common 的 classesDirs，资源仍只经 processResources；notices 是唯一新增根文件，不会产生重复条目。
- 产物名/结构不变：Ping-For-It-0.4.2-pfi-beta1-fabric-26.3.jar；§10 自检新增一个根文件 THIRD-PARTY-NOTICES.md，其余条目不变。
- 当前旧 jar 没有 notices，C 的实际打包结果未验证；合入后必须 ./gradlew clean build，再确认：
  - jar 根有 THIRD-PARTY-NOTICES.md；
  - 根仍有 LICENSE_pingforit；
  - unzip -l | sort | uniq -d 为空；
  - assets/icon/MavenComparableVersion 仍在。
- 结论：接线正确，静态可合入；C 的产物验证待重新 build。

## D. locale 正确性

### D1 基准与数量

- en_us 163 keys；zh_cn 163、zh_tw 163、de_de 165、es_ar 165、fr_fr 165、pl_pl 159、tr_tr 159。
- zh_cn 相对 en_us：missing 0 / extra 0；zh_tw missing 0 / extra 0。
- de/es/fr：missing 0，但各有 2 个 en_us 没有的 key：pingforit.chat.loot.template.override、pingforit.chat.destroy.template.override。它们不在 en_us 基准里，属于既有的额外/死键，不影响新增翻译的正确性；若 M10 locale 脚本要求“所有 locale 与 en_us 完全同键”，这两条会被报 extra，建议在脚本里显式列为允许的旧键或删除。
- pl/tr：各 missing 4：pingforit.chat.danger.template.override、go_to、take、request；其余 159 键齐全。
- “155 用键”的基准：en_us 的 163 减去 8 个死键 = 155 是 2号/Lead 的统计口径；我用源码字符串扫描无法可靠复现（大多数 key 是通过 LanguageUtils.settings(...) 动态拼的，静态字面量扫描会把大量活键误判成 dead）。抽查的 10 个 key（settings.pingforit.title、settings.pingforit.ping_volume、settings.pingforit.wheel_inner_radius、unit.pingforit.meters、unit.pingforit.degrees、command.pingforit.channel.get.response、notice.pingforit.on、pingforit.chat.pingmsg、pingforit.chat.danger.template.override、value.pingforit.vanilla_team）都在 en_us 存在且被代码路径使用。建议 M10 增加一个基于 LanguageUtils/SettingsCategory 的真实用键脚本，把 155/8 固定下来；当前不阻塞。

### D2 JSON/编码/重复键

- 8 个文件全部用 UTF-8 + ConvertFrom-Json 解析成功，无语法错误、无尾逗号。
- 启发式重复键扫描：8 个文件均 no duplicate。
- 新增行数：zh_cn +5、zh_tw +13、de_de +9、es_ar +9、fr_fr +12、pl_pl +11、tr_tr +11；与描述一致。

### D3 占位符

- 对每个 en_us 中存在的 key，比较 %s/%d 与 {name} 集合：所有 locale 的集合完全一致，无缺失/多余占位符。
- 新增的 settings...：%s、unit.*：%s、*.template.override：{playerName}/{pingType}/{targetName} 都与 en_us 对齐；没有把 {} 写成 %s 的地方。
- de/es/fr 的 danger override 也带齐 3 个 {name} 占位符。

### D4 pl/tr 不补 4 条 override 是否成立

- 代码路径：PingChatBuilder.templateOverrideKey(pingType) 生成 pingforit.chat.<id>.template.override；调用方只在“当前选中 locale 自己存在该 key”时使用 override，否则退回通用 TEMPLATE_KEY 的本地化文本（PingChatBuilder:74-75）。
- 因此 pl/tr 不填 override 时，聊天仍使用该 locale 的通用句，而不是 en_us 的 override 英文；填英文 override 反而会把通用句替换成英文，确实更差。判断成立，4 条留空正确。
- 建议在 locale 脚本里把这 4 条列为“允许缺失”，避免 M10 全量扫描误报。

### D5 语义抽查

- de：Radialmenü-Innenradius/Außenradius/Deckkraft/Haltezeit/Zeitlimit、Abbruchkegel-Halbwinkel；语义正确。
- es：Radio interior/exterior、Opacidad、Tiempo de mantenimiento/espera、Semiángulo del cono de cancelación；语义正确。
- fr：Rayon intérieur/extérieur、Opacité、Durée de maintien、Délai d'expiration、Demi-angle du cône d'annulation；语义正确（法语空格也保留）。
- pl：Wewnętrzny/Zewnętrzny promień koła、Krycie、Czas przytrzymania、Limit czasu、Półkąt stożka anulowania；语义正确。
- tr：Tekerlek iç/dış yarıçapı、opaklığı、basılı tutma süresi、zaman aşımı、İptal konisi yarım açısı；语义正确。
- zh_cn/zh_tw：輪盤內/外半徑、不透明度、按住時間、逾時、取消視野半角；语义正确。
- 未发现明显语义错误；de/es/fr 的 loot/destroy 两条旧 extra 不影响新加项。

## E. GameContext 修复语义

- clearLastWorld() 只把 lastWorld 置 null；updateDimension 的条件是 Game.level == null || lastWorld == Game.level，所以断线重连/换存档后第一次 updateDimension 必然重新赋值 dimension，语义正确。
- 其它静态缓存：
  - dimension 是 int，不持有 level；clearLastWorld 不重置它，但下一次 updateDimension 会覆盖；没有内存滞留。
  - RENDER_ENTITY_LOOKUP_CACHE 虽然是静态并持有 world/entity，但已有独立清理：CommonClient.onLeaveServer() 调 GameContext.clearRenderEntityLookupCache()（CommonClient:119），onRenderFrame 在 Game.level == null 时也会清（CommonClient:563-565）；不需要在 clearLastWorld 里重复清。
  - MarkerOverlayState/SelectionToggleNoticeState 等也在 onLeaveServer 清理；没有发现只依赖 lastWorld 的其它缓存。
- 事件注册：ClientPlayConnectionEvents.DISCONNECT 在 FabricClient.onInitializeClient 内注册（FabricClient:65-66），属于客户端入口；lambda 不使用 handler/client 参数，只调静态 clearLastWorld，不会 NPE；与既有 registerLeaveServerEvent 的 onLeaveServer 可重复清理，但都是幂等。
- 结论：语义正确，无回归。

## F. 诊断改动

- checkMissedDispatch 只在 pendingGlow 时累加 glowingDispatches；同一帧多个 BER 派发会各计一次。这里的单位是 dispatch 不是 frame，120 的语义本来就是“120 次发光派发”，不是“120 帧”；如果产品希望按帧计，需要另加 per-frame 去重。当前没有把同一次派发重复计数：每次 begin/restore 只对应一次 restore，nesting 的内层/外层各自是独立 dispatch。
- consumedDispatches 只在 pendingConsumed 时 +1；missedDispatches 仍只在 !pendingConsumed && !modelHookApplied 时 +1，阈值/一次性行为未变。
- firstConsumptionLogged 在第一次 currentColorOr 真正消费颜色时打一条 DEBUG；只在渲染线程，一次；不会刷屏。
- 120 那条仍是 LOGGER.debug，新增 dispatchHookApplied/modelHookApplied/glowingDispatches/consumedDispatches 参数；没有升级为 INFO/WARN，没有改变任何渲染/路由行为。
- 结论：计数时机正确（按 dispatch），DEBUG 不刷屏，无行为变化；只有“120 次派发可能少于 120 帧”这一语义需要文档写清。

## 非阻塞建议

1. 合入后重新 build，按 C 的 4 项检查 jar；当前 jar 不含 notices。
2. M10 locale 脚本固定 155/8 基准，并把 pl/tr 4 条 override、de/es/fr 2 条 extra 列为允许。
3. 核实 Luken 的作者归属来源，或在 notices 中改成可核实的上游仓库署名。
4. 若产品把 120 理解为“帧”，给 glow 诊断增加 per-frame 去重；当前按 dispatch 计数是自洽的。
