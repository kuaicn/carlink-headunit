# CarLink 车机端（carlink-headunit）

车机互联系统的**车机端 App**：运行在 Android 车机上的普通应用（无任何系统权限），负责把手机画面全屏投到车机屏幕，并把车机的触控操作回传给手机。

在整个 CarLink 方案中，手机侧由两个仓库组成（一句话带过）：

- [carlink-scrcpy](https://github.com/kuaicn/carlink-scrcpy)：scrcpy server 的魔改版，以库形式运行在手机端「互联服务」privapp 进程内，负责采集虚拟屏画面、H.264/H.265 编码推流、接收并注入触控事件；
- carlink-launcher：运行在手机虚拟屏上的车机桌面（投屏画面的内容来源）。

本仓库是第三条腿：车机侧客户端。

## 功能清单

- 连接界面：输入手机 IP（默认 `192.168.43.1`，记住上次输入）与控制端口（默认 `27183`），显示连接状态与断开原因；
- 全屏投屏：沉浸式全屏 + 屏幕常亮，MediaCodec 硬件解码（H.264/H.265，按本机解码器能力与手机协商），SurfaceView 渲染，即时出帧（低时延优先）；
- 触控回传：单点/多点触控按 scrcpy 控制协议回传（含历史采样点批量回放，提升滑动平滑度），fit-center 坐标映射（含 letterbox 处理）；
- 按键行为：投屏中按**返回键** = 向手机虚拟屏注入 BACK（不退出投屏）；**双击屏幕左上角** = 退出投屏、回到连接界面；
- 断线处理：任一通道断开（手机侧结束、网络异常、Surface 销毁）即整体收尾，Toast 提示原因并返回连接界面。

## 构建方法

标准 Gradle 工程（AGP 8.7.x + Gradle 8.9，无 AndroidX、无任何第三方依赖，minSdk 24 / targetSdk 34）：

- **Android Studio**：直接打开本目录，等待同步完成后 Run / Build APK；
- **命令行**：`./gradlew assembleDebug`（首次需先生成 wrapper：`gradle wrapper --gradle-version 8.9`，或使用 Android Studio 自带的 Gradle）。

产物：`app/build/outputs/apk/debug/app-debug.apk`，adb 安装到车机：`adb install app-debug.apk`。

## 使用方法

1. 车机 WiFi 连接**手机热点**（手机侧「互联服务」随 ROM 自启，监听 27183 端口）；
2. 打开本 App，输入手机 IP（手机热点下通常是网关地址 `192.168.43.1`）与端口 `27183`，点击「连接」；
3. 连接成功后进入全屏投屏：车机屏幕即手机虚拟屏内容，直接触摸车机屏幕即可操作手机端车机桌面；
4. 投屏中：
   - **返回键** → 注入手机虚拟屏的 BACK（虚拟屏灭屏时则注入 POWER 点亮），不会退出投屏；
   - **双击左上角**（约 96dp 见方区域内两次点击，间隔 < 400ms）→ 退出投屏，返回连接界面；
5. 断线（手机端结束会话、网络异常等）会自动 Toast 提示原因并回到连接界面，可重新发起连接。

## 协议说明

权威文档：[`carlink-scrcpy/docs/carlink-protocol.md`](https://github.com/kuaicn/carlink-scrcpy/blob/master/docs/carlink-protocol.md)。实现已与手机端源码逐字段核对（`ControlMessageReader.java`、`Controller.java`、`Streamer.java`）。关键格式速查：

### 握手（控制通道，车机先发言）

每条消息 = `4 字节大端长度` + `UTF-8 JSON`：

- 车机 → 手机：`{"type":"hello","width":W,"height":H,"dpi":D,"codecs":["h264","h265"]}`（W/H/D 为车机屏幕真实参数，手机原样创建 VirtualDisplay 与编码器）；
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

两条 socket 均设置 `TCP_NODELAY`。任一通道断开 → 整个会话终止。

## 目录结构

```
app/src/main/
├── AndroidManifest.xml                  # 权限：INTERNET + ACCESS_NETWORK_STATE
├── java/com/carlink/headunit/
│   ├── MainActivity.java                # 连接界面（IP/端口输入、状态显示、记住上次输入）
│   ├── ProjectionActivity.java          # 全屏投屏：会话编排、沉浸式、触控监听、返回键/退出手势
│   ├── net/
│   │   ├── Protocol.java                # 协议常量与序列化（握手帧、触控/返回消息、大端读写）
│   │   └── CarLinkSession.java          # 两条 TCP 通道的建立（握手）、持有与关闭
│   ├── video/
│   │   ├── PacketReader.java            # 视频流解析：codec id + 12 字节包头 + Annex-B 载荷
│   │   └── VideoDecoder.java            # MediaCodec 硬解包装：喂包、出帧渲染、尺寸回调
│   └── touch/
│       ├── TouchEventConverter.java     # MotionEvent → 触控消息（fit-center 映射、多点、历史批次）
│       └── TouchMessageSender.java      # 独立发送线程 + BlockingQueue 序列化写控制 socket
└── res/                                 # 布局（连接页/投屏页）、中文文案、自适应图标
```

## 已知限制

与协议文档「断线与错误语义」及当前骨架定位一致：

- **无音频**：协议本身不传输音频；
- **无自动发现**：需手动输入手机 IP，无 mDNS/广播自动搜索；
- **无心跳与自动重连**：断线即回连接界面，需手动重新连接；
- **单向会话**：同一时刻只允许一个会话，手机端会话完全结束后才能开始新会话；
- 视频尺寸固定为握手时上报的车机屏幕分辨率，会话期间不变（不支持旋转/动态分辨率）；
- 触控映射按「视频宽高 = 解码器上报尺寸」做 fit-center 计算；因协议保证视频尺寸等于车机屏幕真实分辨率，正常路径下无 letterbox、无缩放误差；
- 控制通道仅写不读：手机 → 车机的 device 消息（剪贴板同步等）暂未消费。
