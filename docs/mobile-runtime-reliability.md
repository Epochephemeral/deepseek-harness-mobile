# 手机长期运行：本轮修复与验证

本轮保留后台进程恢复、通知链路及主会话列表隐藏子 Agent。自行设定的内容硬限制已撤回；随后用户明确选择历史和积压落盘、增量处理文字/排版，并授权选择大响应处理方案。当前按这一确认实现磁盘优先方案，不以清除内容、暂停实时接收或任意大小拒绝来降低内存。未涉及 Relay 配对 TLS、直连 Cookie 加密/备份等另外的安全审查项，不能把本轮修复视作生产认证。

## 后台恢复保留用户的连接意图

- 在 `noBackupFilesDir` 原子保存目标主机 ID + 完整 endpoint，以及主动断开、认证终止和 Android 后台时限耗尽状态。仅恢复仍在主机列表中且已开启后台保持的同一个目标。
- 连接启动先提交受 owner epoch 保护的运行状态和重试循环，再由 manager 自有 scope 保存主机。通知导航被取消不会留下没有重试循环的 `CONNECTING`；旧保存任务不能撤销新一次主动断开。
- Sticky 服务先满足 `startForeground` 时限，再异步读取设置并冷恢复。Worker 只做平台允许的尽力恢复，不绕过前台服务限制。
- 暴露 `backgroundDelivery` 状态。系统拒绝启动或后台时限耗尽不会被无限重试掩盖；真实回到前台后才能重新尝试前台保护。

这不保证在 force-stop、Doze、OEM 杀后台或长时间离线时实时送达。此项目尚未增加服务端推送、持久通知收件箱或断网期间的通知补投。系统六小时预算的真实耗尽与跨厂商长稳仍需单独验收。

## 通知沿主机和请求身份路由

- Application 启动时同步接上 SessionStore 的完成事件回调，并在返回前注册无重放事件流，避免首个问题抢在订阅之前到达。问题去重使用 waterfall `eventId`，同一个问题重放不重复提醒，新问题仍提醒。
- 按主机划分分类器、去重键、通知 tag 和目的地。迟到的旧主机事件不会变成新主机的通知。
- 点击通知只解析主机/会话身份，不把通知当成连接配置。只允许匹配现存保存主机的 ID + endpoint；冷启动和 `onNewIntent` 共用导航路径。
- 导航等待本次连接的会话列表和默认落点完成，再打开目标。主机更换、导航取消和超时均有退出路径。切换到空主机不会继续展示旧主机的可操作会话。

## 用户确认的磁盘优先方案

用户确认：旧内容本地落盘、按需读取；文字与排版增量处理；流积压落盘后继续处理；可接受增加磁盘占用和读写，统一在现有缓存页面统计、清理。大响应由本轮选择流式接收、磁盘暂存再解码，不设任意容量拒绝线。

### 历史完整保存在磁盘，内存只保留阅读窗口

- 完整接收的持久事件按 host endpoint + 保存主机 ID + 会话身份写入 SQLite 索引，重复 seq 替换，不受原有 gzip 预览容量/LRU 限制。数据库和处理暂存位于 `noBackupFilesDir/managed-cache`，避免 Android 自动回收普通 cacheDir 时把完整离线历史一并删掉，也不进入系统自动备份。
- 主聊天、轨迹和子 Agent 面板从本地双向加载，保留稳定行 key 和像素偏移。现代记录每 60 条持久消息采用约 4,000 条原始记录、8 MiB 序列化字节的读取目标；视口高水位为双页，尾部滚动后回到单页，避免每条事件重新解码整窗。先读长度元数据再解码；单条大记录及必要结构尾部可超过字节目标，不裁剪或拒绝内容。这是内存分页，不是历史淘汰，窗口外记录仍可往返读取。
- 历史列表与标签切换保留原页面的只读展示快照，恢复正确窗口、实际 key 和像素偏移后才重新展示；数据接收、落盘和当前操作权限不冻结。读取失败或明确清理后，可以保留有界 RAM 书签，但不会借用新 ticket 把刚清除的旧窗口重新写回磁盘。
- 翻旧内容时继续接收、落盘和处理最新事件；阅读锚点不被新消息拉走。回到最新、发送消息会先读取最新窗口，成功后才滚动；读取失败保留旧画面并提供重试，不能自动忙重试。
- HTTP 历史记录逐条解码，先进入不可见 staging，整个 RPC 验证成功且主机/连接/会话/请求/清理 epoch 仍匹配时才原子提交。清理使旧请求和延迟预览写入失效，不因 epoch 变化自动重新下载刚清理的历史。清理之后新收到的事件可以单独落盘。
- 旧 gzip 预览继续使用原有 `cacheDir/session-history`、16 MiB 单条和用户选择的容量；设置明确标为“预览缓存容量”，不再让人误认为它限制完整归档。旧预览仍是可重建副本，不承担日志恢复或实时权限状态的权威性。

### 文字和排版不再逐片段重做整段历史

- 持久窗口未改变时复用 fold。当前回复只合并新增 chunk，未变化的块复用不可变结果；原始 chunk 写入受保护的临时日志，现代预览不再每次重放全部 chunk。
- Markdown 已完成、上下文独立的前缀复用解析结果和 Compose 子组件；未完成的尾部继续解析。替换、回退、代码围栏和 CRLF 的变化与完整解析保持一致，不清除预览、不截断内容。
- **仍有边界**：增长中的单个不可变字符串需要重新生成；未完成的长段落/代码块仍可能整体解析、排版。旧协议 `assistant/chunk` 跨事件拼接且可能依赖空 settlement 的回退内容，因此兼容窗口保留完整用户回合，可能超出上述内存目标。单个超大事件、DTO、opaque JSON 或 multipart 附件也仍可能占用较多内存；不能宣称所有输入都恒定内存。

### 流积压是磁盘队列，不是超限断连

- Mux 每逻辑流和主机事件的每个订阅者使用有序文件队列，内存只留读写位置和唤醒信号；没有隐藏的无限任务队列。已消费前缀会回收，结束/取消/关闭释放临时文件。
- 不按帧大小、条数、流数量或积压容量主动拒绝、丢弃或断连。实际磁盘 I/O 失败明确报告并交连接所有者处理，不悄悄退回无限 RAM 队列。
- 这些文件属于当前连接代际，**不是跨进程事件收件箱**。进程死亡后不重放旧权限/实时帧；仍依赖服务器新基线恢复。崩溃留下的无主暂存可通过统一清理回收。零订阅者时仍保持原有无重放语义。

### 大响应的选择和代价

普通 JSON RPC 先流式写入受保护暂存，再从输入流直接解码类型化 envelope/value，避免整份响应 String 加整份 JSON 树的中间副本。历史数组通过 `sessionPageEach` 逐记录处理；staging 按小批次写入，避免每条记录一次数据库同步。保留 charset/BOM、gzip、multipart、业务错误及取消时的释放语义。

代价是额外磁盘读写，而且完整 HTTP 响应下载结束后才开始类型化解码。最终业务 DTO、单个记录及 multipart 二进制仍可能很大；本轮没有用 32 MiB 或其他任意阈值拒绝合法内容。

### 统一统计和手动清理

设置显示总占用、预览、完整历史、流处理暂存、RPC 暂存，以及清理时保留的处理中数据。统计只在设置可见时定期读取，不逐事件遍历磁盘。容量选择仅影响可重建预览；完整历史由用户手动清理，不自动淘汰。

清理前确认会失去离线历史，但不会删除远端会话、主机或登录信息。当前 RAM 画面仍可阅读；活跃队列、当前回复日志和 RPC 文件保留到处理结束。清理在同一 epoch 锁内关闭归档再删除无主文件，不依赖可能在磁盘不足时失败的 VACUUM，也不会 unlink 正在使用的队列。

### 撤回记录仍然有效

此前未经确认加入的内容淘汰、2 Mi payload/预览限制、32 MiB RPC 拒绝、积压超限断连及历史冻结均未恢复。当前采用的是用户随后明确选择的磁盘分页和增量方案。以后若需要裁剪内容、停止实时更新、拒绝合法响应或改变保留策略，仍须先说明影响并征求确认。

## 主列表只显示对话

工作区、置顶、最近、搜索及归档共用 `origin != "subagent"` 的展示规则。不删除 store 内的子 Agent 数据，父会话的子 Agent 面板仍保留。普通 fork 即使有 `parentSessionId` 也不会被误隐藏。

## 验证方式

使用 JDK 17、SDK 35 和仓库 Gradle wrapper。Android 集成测试运行在本地 API 35 模拟器，不连接用户真实 Harness，也不改用户保存的主机或凭据。

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
ANDROID_SERIAL=emulator-5554 ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.labteto.dshmobile.notify.NotificationDeliveryTest,com.labteto.dshmobile.connection.BackgroundConnectionPersistenceTest,com.labteto.dshmobile.connection.BackgroundConnectionManagerTest,com.labteto.dshmobile.data.SessionHistoryStoreTest,com.labteto.dshmobile.data.SessionHistoryDiskTest,com.labteto.dshmobile.data.HistoryCacheUiTest,com.labteto.dshmobile.data.HistoryCacheNetworkTest,com.labteto.dshmobile.data.SessionHistoryPagingRecoveryTest,com.labteto.dshmobile.data.ArchivedSubagentTranscriptTest,com.labteto.dshmobile.ui.screens.main.ChatTranscriptTailTest,com.labteto.dshmobile.ui.screens.main.ChatTranscriptJumpTest,com.labteto.dshmobile.ui.screens.main.ChatTranscriptDiskPagingTest,com.labteto.dshmobile.ui.screens.main.TrajectoryDiskPagingTest,com.labteto.dshmobile.ui.screens.main.HistoryReadingRestoreTest,com.labteto.dshmobile.ui.screens.main.SubagentTranscriptViewportTest,com.labteto.dshmobile.ui.screens.main.OrphanToolResultTest,com.labteto.dshmobile.ui.screens.main.TrajectoryPartialTurnTest,com.labteto.dshmobile.data.ReconnectNoticesUiTest
```

回归覆盖真实通知发送、问题重放和主机隔离、冷恢复持久化、认证终止、取消连接保存竞态、跨主机选择清空、历史跳转、重连提示，以及超过已撤回阈值仍保留完整内容。它们不等于真实 OS 杀进程和 72 小时耗电/内存测试。

磁盘优先实现最终组合验证：core 375 项（1 项跳过）、app 454 项（3 项跳过），共 **825 项单元测试通过，0 失败**；API 35 定向集成回归 105 项，**104 项通过、1 项真实账号网络用例按条件跳过，0 失败**。Debug APK 构建成功，`git diff --check` 通过。结果保存在[汇总](<../app/build/reports/runtime-reliability/final-summary-20261027.json>)和[模拟器 JUnit 报告](<../app/build/reports/runtime-reliability/api35-final-suite-20261027.xml>)；这些构建目录产物会随 clean 清除。

新增回归覆盖 100,001 条现代历史、完整旧协议长回合、CursorWindow 以上的单条记录、百 KiB 记录的字节分页和双向完整遍历、18 MiB 工具结果及后续结构帧、40 MiB RPC、慢消费者/取消释放，以及清理期间的 staging、书签、旧请求和最新基线竞态。Compose 回归验证实际 key 与像素偏移恢复、最新窗口发布后才跳转、失败后手动重试、设置清理确认/取消、孤立工具结果的完整显示和复制，以及没有回合开始标记的轨迹页面不丢节点。未连接真实 Harness，也未运行真机 72 小时内存/耗电长稳。

此前组合执行暴露的首帧订阅竞态修复仍保留。取消连接回归等待实际 DataStore 提交后，再通过生产冷恢复 snapshot API 检查完整主机和设置，不放宽原有 5 秒上限。

原工作区的 7 个 MissingTranslation 错误（刷新/Zen 文案）未纳入本次修改；未跳过或放宽 Lint 门禁。
