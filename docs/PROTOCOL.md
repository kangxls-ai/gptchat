# MyAI 网关协议

客户端与网关使用根路径的 WSS 连接。配对密码由网关证书 SHA-256 指纹和随机 256 位密钥组成，中间以点分隔。客户端先校验证书指纹，再发送密钥。所有对话、搜索和文件操作均要求认证。

消息是 UTF-8 JSON。协议版本 3 支持对话和附件。附件数据使用 Base64，每条消息最多一个附件，解码后最大 5 MiB。

| 请求 | 字段 | 响应 |
| --- | --- | --- |
| `auth` | `version`、`token` | `ready`，可含 `historySearch`，随后发送 `conversations` |
| `history` | `conversation`、可选游标 `before` | `history`，包含任务、是否有更早记录和游标 |
| `search` | `query`、`requestId` | `search`，返回请求 ID 和匹配对话 |
| `submit` | UUID `id`、UUID `conversation`、`prompt`、`mode`、可选 `attachment` | `task`，状态依次是排队、执行、完成或失败 |
| `attachment` | 任务 `id`、`side` 为 `input` 或 `output` | `attachment`，含文件元数据和 Base64 数据 |

`mode` 可为 `chat`、`image`、`docx`、`pdf`、`xlsx`。附件上传对象包含 `name` 和 `data`。同一任务 UUID 重传时会返回已有状态。

网关用任务文件保存元数据，附件按 UUID 独立存储。每次读取历史按对话分页。生成 Office 和 PDF 时，模型返回结构化内容，由 Java 负责写文件。网关以只读 Codex 沙箱运行，关闭 shell 和网页搜索。显式图片模式使用 Codex 内置图片工具。
