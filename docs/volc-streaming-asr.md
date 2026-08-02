# PhoneLlama 本地流式 ASR 接入文档（Volcengine 线级兼容）

本文档说明如何通过 **WebSocket 二进制协议** 调用 PhoneLlama 设备上的 **SenseVoice-Small** 流式语音识别服务。

该接口在帧格式、事件号、握手 Header、音频封装上与火山引擎 `bigmodel_async` **线级兼容**。现有 `VoiceStickAsrClient` / `VolcengineAsrProtocol` 客户端通常只需修改 Endpoint，即可从云端切换到手机本地。

实现参考：

- `app/src/main/java/com/google/ai/edge/gallery/edgeserver/asr/volc/VolcAsrProtocol.kt`
- `app/src/main/java/com/google/ai/edge/gallery/edgeserver/asr/volc/VolcAsrWebSocketServer.kt`
- `app/src/main/java/com/google/ai/edge/gallery/edgeserver/asr/volc/VolcAsrSession.kt`

---

## 1. 前置条件

| 条件 | 说明 |
| --- | --- |
| App 版本 | v1.0.43 及以上（自动补标点） |
| 模型 | 在 App 内激活 **SenseVoice-Small**（Audio Scribe） |
| 标点模型 | 首次激活 SenseVoice 时自动下载 sherpa `ct-transformer` 中英标点（约 65MB）；未就绪前先返回无标点文本 |
| Edge Server | 在 App 内开启 Edge Server（默认 HTTP **8888**） |
| 网络 | 客户端与手机在同一局域网，或已通过 `adb reverse` 转发端口 |

> 若 SenseVoice 未激活，`StartSession` 会返回错误帧（`45000152`），提示先加载模型。

---

## 2. 接口地址

| 项 | 值 |
| --- | --- |
| 协议 | WebSocket（**Binary Message**，非文本帧） |
| 路径（推荐） | `/api/v3/sauc/bigmodel_async` |
| 路径（兼容别名） | `/v3/sauc/bigmodel_async`（部分 VoiceStick 客户端使用此形式，服务端同样接受） |
| 默认端口 | **HTTP 端口 + 1**（HTTP=8888 时，WS=**8889**） |
| 完整 URL 示例 | `ws://192.168.1.100:8889/api/v3/sauc/bigmodel_async` |
| 本机同设备（VoiceStick） | `ws://127.0.0.1:8889/api/v3/sauc/bigmodel_async` 或 `ws://127.0.0.1:8889/v3/sauc/bigmodel_async` |

自定义 HTTP 端口时，WS 端口同步为 `httpPort + 1`。

在 App 的 **Edge Server** 页面「Test & Connect」区域可查看当前设备的 `ws://` 地址。

### 本机调试（adb reverse）

```bash
adb reverse tcp:8888 tcp:8888
adb reverse tcp:8889 tcp:8889
```

然后客户端连接：

```
ws://127.0.0.1:8889/api/v3/sauc/bigmodel_async
```

---

## 3. 握手鉴权（WebSocket HTTP Upgrade）

连接时在 **握手请求头** 携带以下字段（与火山一致；本地服务默认**校验通过**，不连云端）：

| Header | 必填 | 说明 | 示例 |
| --- | --- | --- | --- |
| `X-Api-Key` | 是 | API Key（本地可填任意非空值） | `local-dev-key` |
| `X-Api-Resource-Id` | 是 | 资源 ID | `volc.seedasr.sauc.duration` |
| `X-Api-Request-Id` | 是 | 连接级请求 UUID | `fe1e96cc-279f-469c-872e-d0127c55b416` |
| `X-Api-Sequence` | 是 | 连接阶段固定 `-1` | `-1` |

路径须为 `/api/v3/sauc/bigmodel_async` 或兼容别名 `/v3/sauc/bigmodel_async`，否则连接会被拒绝（close code `1008`）。

---

## 4. 调用时序（必须按顺序）

```text
1. WebSocket 握手（带 Headers）
2. 客户端 → StartConnection        (event=1)
3. 服务端 → ConnectionStarted        (event=50)
4. 客户端 → StartSession             (event=100, 带 session_id)
5. 服务端 → SessionStarted           (event=150)
6. 客户端 → TaskRequest 循环         (event=200, payload=Ogg Opus 字节)
7. 服务端 → AsrResponse 多次         (event=451, 含累计文本)
8. 客户端 → FinishSession            (event=102)
9. 服务端 → SessionFinished          (event=152, 最终文本)
10. 服务端关闭 WebSocket（或客户端主动关闭）
```

取消识别：会话进行中发送 `CancelSession (event=101)`，服务端回 `SessionCanceled (151)` 并关闭连接。

音频可在 `SessionStarted` 之前到达，服务端会**排队**，会话就绪后自动 flush。

---

## 5. 二进制帧格式

所有业务消息均为 **WebSocket Binary Message**。多字节整数均为 **大端（Big-Endian）**。

### 5.1 通用布局

```text
偏移   长度   字段
0      1      protocol_version_header   固定 0x11
1      1      message_type_and_flags
                  高 4 bit = messageType
                  低 4 bit = flags（客户端发送通常为 0x04）
2      1      serialization_and_compression
                  高 4 bit = serialization（JSON=0x01，原始音频=0x00）
                  低 4 bit = compression（固定 0x00）
3      1      reserved                  固定 0x00
4      4      event                     BE32 事件号
8      ...    [可选] session_id 块
              4      session_id_len    BE32
              N      session_id        UTF-8
...    4      payload_len              BE32
...    M      payload                  JSON 或 Ogg Opus 原始字节
```

### 5.2 messageType

| messageType | 含义 | 用途 |
| --- | --- | --- |
| `0x01` | JSON 控制事件 | StartConnection / StartSession / FinishSession / CancelSession |
| `0x02` | 音频任务 | TaskRequest（payload = Ogg Opus） |
| `0x09` | 服务端结果 | 识别结果（本实现主要用 0x0B 事件帧） |
| `0x0B` | 服务端事件 | ConnectionStarted / SessionStarted / 451 / 152 等 |
| `0x0F` | 错误帧 | 业务错误 |

### 5.3 事件号

#### 客户端 → 服务端

| event | 名称 | 说明 |
| --- | --- | --- |
| 1 | START_CONNECTION | 建立连接上下文 |
| 100 | START_SESSION | 开始识别会话（须带 `session_id`） |
| 101 | CANCEL_SESSION | 取消会话 |
| 102 | FINISH_SESSION | 音频发送完毕 |
| 200 | TASK_REQUEST | 上传一帧 Ogg Opus 音频 |

#### 服务端 → 客户端

| event | 名称 | 说明 |
| --- | --- | --- |
| 50 | CONNECTION_STARTED | 可发 StartSession |
| 150 | SESSION_STARTED | 可发音频 |
| 151 | SESSION_CANCELED | 已取消 |
| 152 | SESSION_FINISHED | 会话结束，含最终文本 |
| 451 | ASR_RESPONSE | 识别结果（累计全文，partial） |

---

## 6. 各消息说明

### 6.1 StartConnection（event=1）

- **messageType**：`0x01`
- **session_id**：不携带
- **payload**：JSON（连接级包装）

```json
{
  "namespace": "BidirectionalASR",
  "event": 0,
  "req_params": {
    "user": { "uid": "phonellama-local" },
    "audio": {
      "format": "ogg",
      "codec": "opus",
      "rate": 16000,
      "bits": 16,
      "channel": 1
    },
    "request": {
      "model_name": "bigmodel",
      "enable_nonstream": true,
      "show_utterances": false,
      "result_type": "full",
      "enable_ddc": true,
      "resource_id": "volc.seedasr.sauc.duration"
    }
  }
}
```

**时机**：WebSocket `onOpen` 后立即发送。
**期望响应**：`ConnectionStarted (50)`。

### 6.2 StartSession（event=100）

- **messageType**：`0x01`
- **session_id**：必填（客户端生成的 UUID）
- **payload**：`req_params` 本体（结构与上表 `req_params` 相同，无外层 `namespace`）

**时机**：收到 `50` 后发送。
**期望响应**：`SessionStarted (150)`，`session_id` 与客户端一致。

### 6.3 TaskRequest（event=200）— 流式音频

- **messageType**：`0x02`
- **session_id**：必填
- **serialization**：`0x00`
- **payload**：原始 **Ogg Opus** 字节（非 Base64，非 JSON）

| 音频参数 | 值 |
| --- | --- |
| 容器 | Ogg |
| 编码 | Opus |
| 采样率 | 16000 Hz |
| 声道 | 1（mono） |

与 VoiceStick 一致：BLE 裸 Opus 经 `OggOpusMuxer` 封装后按块上送即可。

### 6.4 FinishSession（event=102）

- **messageType**：`0x01`
- **session_id**：必填
- **payload**：与 StartConnection 相同结构的 JSON

**时机**：最后一块音频已发送。
**期望响应**：`SessionFinished (152)`，随后连接关闭。

### 6.5 CancelSession（event=101）

- 参数同 FinishSession
- **期望响应**：`SessionCanceled (151)`

---

## 7. 服务端响应解析

### 7.1 事件帧（messageType = 0x0B，flags = 0x04）

```text
header(4) + BE32(event) + BE32(session_id_len) + session_id + BE32(payload_len) + payload(UTF-8 JSON)
```

| event | 客户端处理 |
| --- | --- |
| 50 | 发送 StartSession |
| 150 | 标记会话就绪，flush 排队音频 |
| 451 | 从 payload 提取 `result.text`，作为 partial 回调 |
| 152 | 提取最终 `result.text`，作为 final 回调 |
| 151 | 会话已取消 |

### 7.2 识别结果 JSON 格式

```json
{
  "result": {
    "text": "累计识别全文"
  }
}
```

文本提取规则：

1. 优先取 `result.text`
2. 若无 `result`，尝试顶层 `text`
3. 否则将 payload 原文当作纯文本

### 7.3 错误帧（messageType = 0x0F）

```text
header(4) + BE32(error_code) + BE32(message_size) + message(UTF-8)
```

| 错误码 | 含义 |
| --- | --- |
| 45000001 | 参数非法 / 时序错误 |
| 45000151 | 音频格式非法 |
| 45000152 | SenseVoice 未加载 |
| 55000031 | 服务端内部错误 |

---

## 8. 与火山云的差异（迁移时注意）

| 项 | 火山云端 | PhoneLlama 本地 |
| --- | --- | --- |
| Endpoint | `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async` | `ws://PHONE-IP:8889/api/v3/sauc/bigmodel_async` |
| TLS | 必须 `wss` | 局域网 `ws`（无 TLS） |
| 鉴权 | 真实 API Key / 计费 | Header 形式兼容，本地默认放行 |
| 引擎 | 云端大模型 | 设备端 **SenseVoice**（sherpa-onnx） |
| 标点 | 云端结果通常带标点 | SenseVoice 本身无标点；v1.0.43+ 用 sherpa OfflinePunctuation 后处理自动补标点 |
| 流式形态 | 真流式 + VAD | **模拟流式**：静音切段（约 800ms）+ 离线解码 |
| partial | 云端实时 | 静音切段或约 1.5s 周期推送 451 |

---

## 9. 客户端伪代码

```text
ws = WebSocket.connect(
  "ws://PHONE-IP:8889/api/v3/sauc/bigmodel_async",
  headers = {
    "X-Api-Key": "local",
    "X-Api-Resource-Id": "volc.seedasr.sauc.duration",
    "X-Api-Request-Id": <uuid>,
    "X-Api-Sequence": "-1"
  }
)

session_id = <new uuid>

onOpen:
  send(buildJsonFrame(START_CONNECTION, session_id=null, connectionJson))

onBinaryMessage(frame):
  parsed = parseFrame(frame)
  if parsed.messageType == ERROR:
    onError(parsed.event, parsed.payload)
    return
  switch parsed.event:
    case 50:
      send(buildJsonFrame(START_SESSION, session_id, sessionJson))
    case 150:
      ready = true
      flushPendingOgg()
    case 451:
      onPartial(extractText(parsed.payload))
    case 152:
      onFinal(extractText(parsed.payload) or latestPartial)
      close()
    case 151:
      onCancel()
      close()

when oggChunkReady:
  if not ready:
    pending.append(oggChunk)
  else:
    send(buildAudioFrame(TASK_REQUEST, session_id, oggChunk))

when recordingStopped:
  send(buildJsonFrame(FINISH_SESSION, session_id, connectionJson))
```

### 从 VoiceStick 迁移

仅需修改 Endpoint 与协议实现中的 base URL：

```kotlin
// 原火山
// wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async

// 本地 PhoneLlama
ws://<手机局域网IP>:8889/api/v3/sauc/bigmodel_async
```

`VolcengineAsrProtocol` 帧构造/解析、`VoiceStickAsrClient` 状态机、Ogg Opus 封装逻辑可保持不变。

---

## 10. 批量转写（HTTP，非流式）

若不需要 WebSocket 流式，仍可使用 OpenAI 兼容 HTTP 接口：

```bash
curl -X POST http://PHONE-IP:8888/v1/audio/transcriptions \
  -F "file=@speech.wav"
```

响应：

```json
{"text":"你好","language":"zh","events":[]}
```

流式 WS 与 HTTP 批量接口可并存；均需 SenseVoice-Small 已激活。

---

## 11. 常见问题

**Q: `SocketTimeoutException: failed to connect to /127.0.0.1 (port 8889)`？
A: 这是 **TCP 连不上端口**，与路径 `/api` 无关。请先打开 PhoneLlama → Edge Server 开关为 **Running**，状态栏应显示 `HTTP …8888 · WS :8889`。重装 APK 后 Edge Server 会停止，需重新打开。可用设备上 `curl http://127.0.0.1:8888/health` 确认 HTTP 已起；对 8889 做 WebSocket Upgrade 应返回 `101`。

**Q: 连接成功但 StartSession 报错 45000152？**
A: 在 App 内先激活 SenseVoice-Small，并确认 Edge Server 已启动。`/health` 的 `model` 字段应为 `SenseVoice-Small`。

**Q: 收不到 451，只有 152 有字？
A: 说话段太短或静音不足 800ms，可能只在 FinishSession 后一次性出结果。可适当延长语句或检查 Ogg Opus 是否正常解码。

**Q: 路径错误被拒绝？
A: URL 须为 `/api/v3/sauc/bigmodel_async` 或兼容别名 `/v3/sauc/bigmodel_async`。路径错误时一般是握手后立刻 close `1008`，**不会**表现为 `SocketTimeoutException`。

**Q: 电脑浏览器能录音测吗？
A: Web UI 的 HTTP 录音受浏览器安全策略限制；流式 ASR 请用原生客户端或 VoiceStick 推 Ogg Opus。

**Q: 识别结果没有逗号、句号？
A: SenseVoice 本身不输出标点。v1.0.43+ 会在激活 SenseVoice 后自动下载 sherpa OfflinePunctuation（约 65MB），就绪后对 HTTP/WS 结果自动补标点。首次激活后请等下载完成再测；也可预先执行 `scripts/fetch-sherpa-punct.sh` 再 `adb push` 到
`/sdcard/Android/data/com.phonellama.app/files/sherpa-punct/`。

---

## 12. 版本记录

| 版本 | 说明 |
| --- | --- |
| v1.0.43 | SenseVoice 结果自动补标点（sherpa ct-transformer int8，首次激活后台下载） |
| v1.0.39 | 首次提供 Volcengine 线级兼容 WS 流式 ASR（端口 8889） |
| v1.0.40 | Edge Server 状态栏展示 WS:8889；同时接受 `/api/v3/...` 与 `/v3/...` 路径别名 |
