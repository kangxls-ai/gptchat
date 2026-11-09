# MyAI · GPT Chat Gateway

MyAI 是一个个人 AI 聊天客户端和 Java 网关。Android、Windows 和 macOS 客户端通过加密 WebSocket 连接同一服务，共享对话历史。网关使用服务主机上的 Codex CLI 执行文本和附件任务，并把结果送回客户端。

## 你可以做什么

- 新建对话、继续追问，或用关键词搜索标题、消息和附件名称。
- 发送文字、图片或文件，预览图片或将附件保存到本机。
- 在回复格式中选择生成图片、Word、PDF 或 Excel。
- 用已有的服务器地址和配对密码将第二台设备连到同一网关。

## 选择客户端

| 平台 | 下载或构建 |
| --- | --- |
| Windows x64 | 从 [GitHub Actions](https://github.com/kangxls-ai/gptchat/actions) 的成功构建下载 `MyAI-windows-x64`，完整解压后运行 `MyAI.exe`。 |
| macOS | Apple Silicon 使用 `MyAI-macos-arm64`，Intel 使用 `MyAI-macos-x64`。解压下载内容后打开 DMG。CI 构建未使用 Developer ID 签名及公证。 |
| Android 8+ | 首次体验可安装 `MyAI-android-debug`。覆盖现有安装要用与原 APK 相同的签名密钥。 |
| Windows / macOS 通用 | 已安装 Java 21 时，可运行 `java -jar myai-desktop.jar`。 |

## 从源码开始

```sh
git clone https://github.com/kangxls-ai/gptchat.git
cd gptchat
mvn -B verify
```

产物位于 `server/target/myai-server.jar` 和 `desktop/target/myai-desktop.jar`。使用已有服务时，直接在桌面客户端设置中填写现有公网端点和配对密码。

- [部署与构建说明](docs/DEPLOYMENT.md)
- [客户端使用说明](docs/USAGE.md)
- [WebSocket 协议与数据边界](docs/PROTOCOL.md)

| 目录 | 内容 |
| --- | --- |
| `server/` | Java WSS 网关、对话存储、文件读取和生成 |
| `desktop/` | Windows 和 macOS 共用的 Java Swing 客户端 |
| `android/` | Android 原生客户端和 Gradle 构建定义 |
| `scripts/` | 服务启动、配对信息显示和桌面打包脚本 |
