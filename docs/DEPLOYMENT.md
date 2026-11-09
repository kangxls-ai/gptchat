# 部署与构建

## 连接已经运行的网关

已在 Windows 电脑上运行 MyAI 网关时，桌面和手机只需使用同一服务器地址及配对密码。桌面客户端也可以从这台电脑直接连接 `127.0.0.1:4000`。从其他设备连接时，使用经过配置的穿透地址或网关局域网地址。

## 新建网关

准备 JDK 21、Maven 3.9 和已登录的 Codex CLI。用将要运行服务的系统用户执行 `codex login status`，必要时执行 `codex login`。在仓库根目录运行 `mvn -B verify`。

### Windows

在仓库根目录打开 PowerShell，启动网关。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Gateway.ps1 -BindAddress 127.0.0.1 -Port 4000
```

保持服务窗口打开，在另一个 PowerShell 窗口中读取配对密码。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Show-Pairing.ps1
```

两个脚本都支持 `-DataDirectory`。使用同一个目录来访问同一组证书、配对密钥和历史记录。

### macOS

可直接连接 Windows 上的网关。需要在 Mac 上独立运行网关时，可从仓库根目录运行以下命令。

```bash
bash scripts/start-gateway.sh 127.0.0.1 4000
# 在另一个终端窗口中读取配对密码
bash scripts/show-pairing.sh
```

两个脚本都支持环境变量 `MYAI_DATA_DIR`。macOS 上生成 PDF 时，在启动前设置 `MYAI_PDF_FONT`，指向包含所需字形的 TrueType `.ttf`。使用 `.ttc` 时，同时设置 `MYAI_PDF_FONT_FACE` 为字体的 PostScript 名称。Windows 默认使用 Microsoft YaHei。网关在 Mac 上运行需要该电脑自己的 Codex CLI 登录。

### 跨网络访问

若穿透客户端和网关运行在同一台主机，穿透目标使用 `127.0.0.1:4000`。选择 TCP 或 TLS 透传，让客户端验证网关证书。设备的服务器地址使用穿透服务分配的公网主机名或 IP 和端口。使用可信局域网直连时，选择网关的局域网 IP 作为绑定地址，并按需要为私有网段放行端口。

## Windows 和 macOS 打包

在对应系统用 JDK 21 运行 `mvn -B verify` 后执行该平台的脚本。

| 平台 | 命令 | 产物 |
| --- | --- | --- |
| Windows | `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\package-windows.ps1` | `dist/windows/MyAI/`，包含 EXE 和 Java 运行环境 |
| macOS | `bash scripts/package-macos.sh` | `dist/macos/*.dmg`，架构取决于构建 JDK |

GitHub Actions 分别在 Windows x64、macOS ARM64 和 macOS Intel 构建。到成功运行的 **Artifacts** 下载对应平台文件。Windows 应完整保留 EXE 所在目录。用于正式分发的 macOS 包需要开发者自己的 Developer ID 证书和 Apple 公证。

## Android 构建

用 Android Studio 打开 `android/`，或准备 JDK 17、Gradle 8.11.1、Android SDK 35 后在仓库根目录运行：

```sh
gradle -p android :app:assembleDebug
```

APK 在 `android/app/build/outputs/apk/debug/`。公开 CI 使用调试签名。已经安装其他签名的 APK 时，用该 APK 原来的签名密钥签发升级包。把签名密钥保存在仓库外。

## 数据和升级

- 默认状态目录为 `server-data/gateway-state`。网关保存证书私钥、配对密钥、任务和附件，均应存放在该系统用户的私有目录。
- 升级前关闭服务并备份状态目录。新版本继续使用同一个数据目录，原证书和配对可继续使用。
- 要撤销旧密码，停止网关并删除状态目录中的 `pairing.txt`，再启动、读取新密码并重新配对。证书和历史存于其他文件。
- 主机需保持开机联网，服务退出或电脑休眠期间设备无法访问。
