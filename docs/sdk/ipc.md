# Agent 进程协议（ev-agentd）

插件的 agent 循环跑在一个独立进程里：`ev-agentd`，静态链接的 aarch64 ELF，随插件 jar 的
`assets/bin/` 分发。宿主启动它，用 **stdio 上的 JSON-lines** 对话：一行一条消息，UTF-8。

为什么是进程而不是同一个 JVM：

- 崩溃隔离。模型循环里的 panic、内存爆掉、死循环，不会带走宿主。
- 语言自由。循环用 Rust 写（rig），Kotlin 侧只剩"把宿主的能力递过去"。
- 能力边界清楚。进程里没有 Android Context、没有文件权限、没有网络栈——它想做事只能开口，
  而每次开口都要经过宿主，宿主因此可以审计、可以拒绝。

## 谁负责什么

| 能力 | 归属 |
| --- | --- |
| 模型循环、多轮、工具调用编排、上下文裁剪 | `ev-agentd` |
| HTTP 请求（含 TLS、代理、证书） | 宿主（应用进程的 OkHttp） |
| 工具实现 | 宿主（`ToolRegistry`） |
| 危险工具审批 | 宿主（`ApprovalService`，人来点） |
| 会话历史、展示 | 宿主 |
| 日志 | 双方各自的 stderr / logcat |

进程里没有任何密钥：`apiKey` 每轮随命令传进去，不落盘；HTTP 也由宿主发，密钥根本不需要离开宿主。

## 消息形状

每条消息一个 JSON 对象。

| 方向 | `id` | `method` | `params` | `result` / `error` |
| --- | --- | --- | --- | --- |
| 宿主 → 进程 | 数字 | 有 | 有 | — |
| 进程 → 宿主 | 数字 | — | — | 二选一 |
| 进程 → 宿主 | 字符串 `h1`… | 有 | 有 | — |
| 宿主 → 进程 | 字符串 `h1`… | — | — | 二选一 |
| 进程 → 宿主 | — | `event` | 有 | — |

两套 id 空间靠类型分开：命令用数字，进程自己的反向请求用字符串。响应按 id 的类型归位。

```json
{"id": 7, "method": "turn", "params": { ... }}
{"id": 7, "result": {"accepted": true, "run_id": "r-1"}}
{"id": "h1", "method": "tool.call", "params": { ... }}
{"id": "h1", "result": {"text": "...", "images": []}}
{"method": "event", "params": {"run_id": "r-1", "kind": "done", "text": "..."}}
```

## 命令（宿主 → 进程）

### `hello`

```json
{"id": 1, "method": "hello", "params": {}}
{"id": 1, "result": {"name": "ev-agentd", "protocol": 1, "version": "0.1.0"}}
```

`protocol` 与宿主支持的版本不一致时，宿主应report 而不是猜。

### `ping`

`{"pong": true}`。用来判断进程还活着。

### `turn`

立刻回 `{"accepted": true, "run_id": ...}`，随后用 event 推进；**结果不走这条响应**。
同一个 `run_id` 再发一次会取代上一轮（旧轮被中止）。

```json
{
  "run_id": "r-1",
  "provider": {
    "base_url": "https://api.deepseek.com/v1",
    "api_key": "sk-...",
    "headers": {"X-Title": "EV"}
  },
  "model": "deepseek-chat",
  "preamble": "你是……",
  "history": [
    {"role": "user", "text": "早上好"},
    {"role": "assistant", "text": "早上好，有什么可以帮你？"}
  ],
  "prompt": "现在几点？",
  "tools": [
    {
      "name": "device_time",
      "description": "读取设备当前时间",
      "parameters": {"type": "object", "properties": {}},
      "dangerous": false
    }
  ],
  "max_turns": 16
}
```

- `provider.base_url` 是 **OpenAI Chat Completions** 形状的端点，进程会请求 `{base_url}/chat/completions`
  （`endpointMode` 只支持这一种）。
- `provider.headers` 并入每个请求；请求自带的同名头优先，所以用户自己配的 `Authorization`
  压得过 `api_key`。
- `history` 由宿主提供，进程不保存会话。只认 `user` / `assistant`，其它角色丢弃。
- `preamble` 只影响这一轮，不落任何地方；缺省用进程内置的一段。
- `tools[].parameters` 是 JSON Schema；缺省视为 `{"type":"object","properties":{}}`。
- `max_turns` 是模型调用轮数上限，缺省 16。

### `cancel`

```json
{"id": 9, "method": "cancel", "params": {"run_id": "r-1"}}
{"id": 9, "result": {"cancelled": true, "run_id": "r-1"}}
```

中止在跑的那一轮，不会再有该 `run_id` 的事件。轮次已经结束时回 `cancelled: false`
（不是错误）。

## 反向请求（进程 → 宿主）

进程阻塞等待响应；超时由宿主自己决定（建议 60s，HTTP 可更长）。宿主必须回一条带同 id 的消息，
否则那一轮会一直挂着。

### `http.request`

```json
{"id": "h1", "method": "http.request", "params": {
  "method": "POST",
  "url": "https://api.deepseek.com/v1/chat/completions",
  "headers": {"content-type": "application/json", "authorization": "Bearer sk-..."},
  "body_base64": "eyJtb2RlbCI6..."
}}
```

响应体（`body_base64` 可以很长）：

```json
{"status": 200, "headers": {"content-type": "application/json"}, "body_base64": "..."}
```

非 2xx 也要照常回 `status` 和 body：进程把状态码、body、头一起交给 rig，错误信息里才有服务商的原文。

带 `"stream": true` 时是**流式**：宿主先用请求自己的 id 回一条只有 `status` / `headers` 的结果，
然后用无 id 的通知把 body 分块推回来，最后收尾：

```json
{"method": "http.chunk", "params": {"request_id": "h1", "data_base64": "..."}}
{"method": "http.end",   "params": {"request_id": "h1"}}
```

`http.end` 可带 `"error"` 表示读 body 时出错。分块顺序就是 body 的顺序，SSE 由进程侧解析。

`multipart` 未接，会直接报错——宿主收到说明协议实现落后了。

### `tool.call`

```json
{"id": "h2", "method": "tool.call", "params": {
  "run_id": "r-1", "tool_name": "device_time", "arguments_json": "{}"
}}
{"id": "h2", "result": {"text": "2026-11-13T21:30+08:00", "images": []}}
```

`images[].mime_type` + `images[].base64`（不带 `data:` 前缀）会作为图片内容块交给模型，
用于截图一类的工具。

### `approval.request`

只在 `tools[].dangerous == true` 时发，**永远在 `tool.call` 之前**。

```json
{"id": "h3", "method": "approval.request", "params": {
  "run_id": "r-1", "tool_name": "shell", "arguments_json": "{\"cmd\":\"reboot\"}"
}}
{"id": "h3", "result": {"decision": "allow_once"}}
```

`decision` 取值与宿主 `ApprovalDecision` 一致：`allow_once` / `allow_session` / `deny` / `unavailable`。

- `allow_*` 才执行。
- `deny`：不执行，模型收到"用户拒绝了这次调用"。
- `unavailable`（没有人回答、通道坏了、解析不出）：不执行，模型收到"没有人能确认"。
  两者必须分开——把超时记成用户拒绝，等于在日志里伪造了一个从未发生的决定。
- 审批通道断开、响应格式不对，一律按 `unavailable` 处理（fail-closed）。

## 事件（进程 → 宿主）

```json
{"method": "event", "params": {"run_id": "r-1", "kind": "tool_call", "tool_name": "device_time", "arguments_json": "{}"}}
```

| `kind` | 字段 | 含义 |
| --- | --- | --- |
| `tool_call` | `tool_name`, `arguments_json` | 这一调用已经过了审批，正要执行 |
| `tool_result` | `tool_name`, `text`, `refused?` | 执行完；`refused: true` 表示是审批拒绝、没有执行 |
| `error` | `text` | 非致命问题（工具名非法、宿主没接上等），这一轮可能继续 |
| `done` | `text` | 这一轮的最终文本。**永远是最后一个事件**，失败时 `text` 为空 |

失败时先 `error` 再 `done("")`。宿主看到 `done` 就结束这一轮的 UI 状态。

| `text` | `text` | 模型正在产出的文本增量，拼起来就是 `done` 的全文 |

`text` 是逐块到达的，UI 直接追加即可；`done` 里仍带完整文本，作为这一轮的唯一真相。

## 进程生命周期

- 宿主启动：`ev-agentd`，工作目录指向插件私有目录，`stderr` 接 logcat（tag `EV`）。
- 就绪判定：`hello` 成功。
- 进程不主动退出。宿主退出、管道关闭时读循环结束，在跑的任务被中止，进程以 0 退出。
- 一轮卡住时的兜底：`cancel(run_id)`；进程失联则杀掉重开，宿主重发这一轮——历史在宿主手里，
  重发是安全的。

## 打包与启动

- 二进制在插件 jar 的 `assets/bin/ev-agentd`，首次激活时解到 `files/bin/`（可执行位 0755），
  之后按 jar 的版本号覆盖。
- 静态链接、无 glibc 依赖：设备上不需要任何运行时。musl 静态 ELF 在 Android 上直接可执行。
- 目标三元组 `aarch64-unknown-linux-musl`，链接器 `rust-lld` + 自带 crt，构建不需要 C 工具链。

## 待接

1. `multipart`（图片输入、文件上传）。
2. 每轮 `usage`（token 计数）随 `done` 一起回。
3. 工具事件里的 `call_id`（目前进程没有把它带进事件，UI 用 `名字#序号` 对齐）。
