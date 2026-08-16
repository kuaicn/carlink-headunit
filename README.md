# CarLink 车机端（carlink-headunit）

车机互联系统的**车机端 App**：运行在 Android 车机上的普通应用（无任何系统权限），负责把手机画面全屏投到车机屏幕，并把车机的触控操作回传给手机。

在整个 CarLink 方案中，手机侧由两个仓库组成（一句话带过）：

- [carlink-scrcpy](https://github.com/kuaicn/carlink-scrcpy)：scrcpy server 的魔改版，以库形式运行在手机端「互联服务」privapp 进程内，负责采集虚拟屏画面、H.264/H.265 编码推流、接收并注入触控事件；
- carlink-launcher：运行在手机虚拟屏上的车机桌面（投屏画面的内容来源）。

本仓库是第三条腿：车机侧客户端。

## 功能清单

- 连接界面：输入手机 IP（默认 `192.168.43.1`，记住上次输入）与控制端口（默认 `27183`），IPv4 点分四段与端口范围校验（非法输入直接提示，不发起连接），显示连接状态与**中文化的断开原因**；连接期间锁定「连接」按钮，防止重复点击进入两个投屏 Activity 争抢手机侧唯一会话；**自动连接**：启动时读取当前 WiFi 的默认网关 IP（手机热点下即手机地址，LinkProperties 默认路由优先、DHCP 信息回退），预填后自动发起连接，每次启动只自动连一次，失败回到连接页手动修改；已保存地址既非出厂默认值也非当前网关时视为手动选择，保留不覆盖、不自动连接；**自动重试**：连接阶段遇瞬时失败（拒绝/超时/不可达等网络错误、手机回 busy）自动重试最多 3 次（间隔 2s，busy 时 3s，状态行显示重试进度），协议级拒绝（无共同编码、非法显示参数等）立即失败不重试，重试等待期间按返回键可随时取消；
- 全屏投屏：沉浸式全屏 + 屏幕常亮，MediaCodec 硬件解码（H.264/H.265，按本机解码器能力与手机协商），SurfaceView 渲染，即时出帧（低时延优先）；
- 触控回传：单点/多点触控按 scrcpy 控制协议回传（含历史采样点批量回放，提升滑动平滑度），fit-center 坐标映射（letterbox 区域内的触点钳制到视频边缘而非丢弃，手势不中断）；发送队列为有界队列（256 条），溢出时优先丢弃可被新事件覆盖的 MOVE，DOWN/UP 与按键消息不丢；视频尺寸尚未知晓时丢弃整段手势（避免服务端收到没见过 DOWN 的 UP）；
- 按键行为：投屏中按**返回键** = 向手机虚拟屏注入 BACK（不退出投屏）；连接进行按**返回键** = 取消连接；**双击屏幕左上角**或**长按返回键** = 退出投屏、回到连接界面（长按为无触摸车机/旋钮操作预留的纯按键退出路径）；
- 断线处理：任一通道断开（手机侧结束、网络异常、Surface 销毁）即整体收尾并返回连接界面；失败原因本地化为中文提示（超时/拒绝/网络不可达/握手失败/会话占用/无共同编码/解码错误等，未知原因保留原文详情）；Surface 销毁（Home/电源键等）属正常收尾，不再误报"未知原因"；**停帧提示**：触控转发后 2.5s 仍无新帧时提示"网络不稳定，正在等待画面恢复…"，画面恢复即消失（静止画面本就没有新帧，故只在触控后判停帧；首帧上屏前不提示）；视频通道 5 秒无帧时经控制通道发送 TCP urgent 字节做**半连接探测**（静止画面长时间无帧属正常，探测只在此时区分"手机侧已死"——对端已 RST 则探测立即失败、按断线收尾，不会画面冻死）；控制通道新增**读取线程**解析手机 → 车机的 device 消息（剪贴板按格式跳过、心跳仅 1 字节），**收到过心跳后**若控制通道 35s 无任何字节（心跳约每 10s 一字节）且无视频帧即判定"手机端无响应"按断线收尾（旧版手机无心跳，该检测完全不启用，不会误判）。

## 构建方法

标准 Gradle 工程（AGP 8.7.x + Gradle 8.9，无 AndroidX、无任何第三方依赖，minSdk 24 / targetSdk 34）：

- **Android Studio**：直接打开本目录，等待同步完成后 Run / Build APK；
- **命令行**：`./gradlew assembleDebug`（首次需先生成 wrapper：`gradle wrapper --gradle-version 8.9`，或使用 Android Studio 自带的 Gradle）。

产物：`app/build/outputs/apk/debug/app-debug.apk`，adb 安装到车机：`adb install app-debug.apk`。

## 使用方法

1. 车机 WiFi 连接**手机热点**（手机侧「互联服务」随 ROM 自启，监听 27183 端口）；
2. 打开本 App 即**自动连接**：手机热点下手机就是车机 WiFi 的网关，App 启动时读取网关 IP 预填并自动发起连接（上次手动输入过非默认地址时不被网关覆盖、不自动连接）；连不上（或未连 WiFi 读不到网关）才回到手动输入，修改 IP 后点击「连接」（端口默认 `27183`，记住上次输入）。连接阶段的瞬时失败会**自动重试**最多 3 次（手机正忙时等待更久），期间按返回键可取消，最终失败才需人工检查；
3. 连接成功后进入全屏投屏：车机屏幕即手机虚拟屏内容，直接触摸车机屏幕即可操作手机端车机桌面；
4. 投屏中：
   - **返回键** → 注入手机虚拟屏的 BACK（虚拟屏灭屏时则注入 POWER 点亮），不会退出投屏；
   - **双击左上角**（约 96dp 见方区域内两次点击，间隔 < 400ms）或**长按返回键** → 退出投屏，返回连接界面；
5. 断线（手机端结束会话、网络异常等）会自动 Toast 提示原因并回到连接界面，可重新发起连接。

## 协议说明

权威文档：[`carlink-scrcpy/docs/carlink-protocol.md`](https://github.com/kuaicn/carlink-scrcpy/blob/master/docs/carlink-protocol.md)。实现已与手机端源码逐字段核对（`ControlMessageReader.java`、`Controller.java`、`Streamer.java`）。关键格式速查：

### 握手（控制通道，车机先发言）

每条消息 = `4 字节大端长度` + `UTF-8 JSON`：

- 车机 → 手机：`{"type":"hello","width":W,"height":H,"dpi":D,"codecs":["h264","h265"]}`（W/H/D 为车机屏幕真实参数，手机以此创建 VirtualDisplay 与编码器；仅当编码器要求更粗对齐时先向下对齐）；
- 手机 → 车机：`{"type":"ready","codec":"h264","videoPort":P}`；握手失败时手机直接关闭连接（也可能回 `{"type":"error",...}`）。

### 视频通道（手机 → 车机，车机主动连 `videoPort`）

```
+0  4B   codec id（大端）：h264 = 0x68323634，h265 = 0x68323635（ASCII "h264"/"h265"）
+4  packet 序列，每包：
     8B  pts_and_flags（大端 s64）：bit62=config 包，bit61=keyframe，低 61 位=pts（微秒）
     4B  载荷长度 N（大端 u32，不含本 12 字节头）
     NB  裸 Annex-B 数据
```

无设备名 meta、无 dummy byte、无 session meta 包；config 包以 `BUFFER_FLAG_CODEC_CONFIG` 喂入解码器。

### 控制消息（车机 → 手机，握手后的控制通道上）

本项目实际发送两种（其余类型见协议文档）：

- **INJECT_TOUCH_EVENT（type=2，共 32 字节）**：
  `type(u8) | action(u8) | pointerId(s64) | x(s32) | y(s32) | screenW(u16) | screenH(u16) | pressure(u16 定点，按下/移动=0xffff，抬起=0) | actionButton(s32)=0 | buttons(s32)=0`
  - 只发普通 `DOWN(0)/MOVE(2)/UP(1)`：服务端按 pointer 数量自行转换 `POINTER_DOWN/UP`（客户端发 POINTER_* 反而会导致服务端 pointer 状态泄漏）；
  - `screenW/screenH` 必须填**当前视频宽高**，否则服务端整条丢弃（`PositionMapper.map` 校验）；
  - 本地收到 `ACTION_CANCEL` 时对所有活跃 pointer 补发 `UP`（协议无 CANCEL 语义）；
- **BACK_OR_SCREEN_ON（type=4，共 2 字节）**：`type(u8) | action(u8)`，action 为 `KeyEvent.ACTION_DOWN(0)/UP(1)`，按下/抬起各发一条。

### device 消息（手机 → 车机，控制通道反方向）

由 `DeviceMessageReader` 读取线程解析，载荷不消费；收到的任何字节都刷新手机存活时间戳：

- **CLIPBOARD（type=0）**：`type(u8) | u32 长度 | UTF-8 文本`，按格式跳过（剪贴板同步未实现；长度超过 256 KiB 视为失步，改逐字节重同步）；
- **ACK_CLIPBOARD（type=1）**：`type(u8) | s64 sequence`，跳过 8 字节载荷；
- **HEARTBEAT（type=3）**：仅 1 个 type 字节、无载荷；支持心跳的手机约每 10s 发一次，是车机端「手机无响应」检测的输入（见功能清单「断线处理」）；车机端只读取，**不回包**；
- 未知类型：无法得知载荷长度，丢弃该 1 字节后在下一字节重新解析（最迟随下一个心跳字节恢复同步）。

两条 socket 均设置 `TCP_NODELAY`。任一通道断开 → 整个会话终止。手机侧另有**会话看门狗**：30s 无任何通道 I/O 进展（含每 10s 一次的心跳写出）即强制终止会话并回收唯一会话槽位，车机断电/断网形成的悬挂会话不会永久占用手机。

## 目录结构

```
app/src/main/
├── AndroidManifest.xml                  # 权限：INTERNET + ACCESS_NETWORK_STATE + ACCESS_WIFI_STATE
├── java/com/carlink/headunit/
│   ├── MainActivity.java                # 连接界面（IP/端口输入与校验、状态显示、记住上次输入、网关自动连接）
│   ├── ProjectionActivity.java          # 全屏投屏：会话编排、沉浸式、触控监听、返回键/退出手势、断开原因中文化
│   ├── net/
│   │   ├── Protocol.java                # 协议常量与序列化（握手帧、触控/返回消息、大端读写）
│   │   ├── CarLinkSession.java          # 两条 TCP 通道的建立（握手）、持有与关闭；视频读超时转控制通道半连接探测
│   │   └── DeviceMessageReader.java     # 控制通道读取线程：解析/跳过 device 消息，心跳驱动手机死亡检测
│   ├── video/
│   │   ├── FitCenter.java               # 渲染路径的 fit-center 计算：SurfaceView 尺寸取视频在父布局中的等比适配矩形（触控映射不用它，另按视图拉伸做逐轴逆变换）
│   │   ├── PacketReader.java            # 视频流解析：codec id + 12 字节包头 + Annex-B 载荷（半包即报错，不当时正常 EOF）
│   │   └── VideoDecoder.java            # MediaCodec 硬解包装：喂包、出帧渲染、尺寸回调；解码器致命错误上抛结束会话
│   └── touch/
│       ├── TouchEventConverter.java     # MotionEvent → 触控消息（fit-center 映射、多点、历史批次、手势抑制）
│       └── TouchMessageSender.java      # 独立发送线程 + 有界 BlockingQueue（溢出先丢 MOVE）序列化写控制 socket
└── res/                                 # 布局（连接页/投屏页）、中文文案、自适应图标
```

## 已知限制

与协议文档「断线与错误语义」及当前骨架定位一致：

- **无音频**：协议本身不传输音频；
- **无自动发现**：无 mDNS/广播自动搜索；仅在手机热点场景按 WiFi 网关自动推断手机 IP 并自动连接一次，其余情况仍需手动输入；
- **无自动重连**：连接建立阶段的瞬时失败会自动重试（见功能清单），但会话断开后即回连接界面，需手动重新连接（有半连接探测与心跳检测兜底：手机侧死亡会按断线正常收尾，但不会自动重连）；
- **单向会话**：同一时刻只允许一个会话，手机端会话完全结束后才能开始新会话；
- 视频尺寸在握手时确定、会话期间不变（不支持旋转/动态分辨率）；实际尺寸为车机屏幕分辨率按编码器对齐要求**向下取整**后的值（对齐值 ≤16 时 1920x720 等常见分辨率不变）；
- 触控映射按「视频宽高 = 解码器上报尺寸」做 fit-center 计算：即使发生上述对齐取整，映射基准也是解码器实际上报尺寸，正常路径下无 letterbox、无缩放误差；
- 控制通道的 device 消息（手机 → 车机）由读取线程解析：心跳用于上述存活检测，剪贴板同步等内容按格式跳过、暂不消费。
