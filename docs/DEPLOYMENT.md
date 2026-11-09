# 部署与构建

## 连接已经运行的网关

已在 Windows 电脑上运行 MyAI 网关时，桌面和手机只需使用同一服务器地址及配对密码。桌面客户端也可以从这台电脑直接连接 `127.0.0.1:4000`。从其他设备连接时，使用经过配置的穿透地址或网关局域网地址。

## 新建网关

准备 Java 21 和已登录的 Codex CLI。用将要运行服务的系统用户执行 `codex login status`，必要时执行 `codex login`。如果 Codex 不在 `PATH` 上，可以把 `GATEWAY_CODEX` 设为其可执行文件的绝对路径。

从 [GitHub Actions](https://github.com/kangxls-ai/gptchat/actions) 的成功构建下载 `MyAI-server-standalone` 并完整解压。目录里包含 `myai-server.jar`、各平台脚本和这份说明。如果从源码构建，在仓库根目录执行 `mvn -B -pl server -am package`，JAR 位于 `server/target/myai-server.jar`，脚本位于 `scripts/`。

### 直接用 JAR 启动

Windows、macOS 和 Linux 都可以在 JAR 所在目录执行：

```sh
java -jar myai-server.jar
```

默认监听 `127.0.0.1:4000`，状态写到当前目录下的 `gateway-state/`。首次启动会生成证书和配对密钥。可通过参数指定地址、端口和状态目录：

```sh
java -jar myai-server.jar --bind 127.0.0.1 --port 4000 --state-dir "./gateway-state"
java -jar myai-server.jar --help
```

也可以用环境变量 `MYAI_STATE_DIR` 指定状态目录。已有的 `java -jar myai-server.jar 地址 端口` 形式仍可使用。

### 用平台脚本启动和导出密码

以下命令在解压后的独立部署包目录运行。如果直接使用源码仓库，在命令的脚本路径前加 `scripts/`。

**Windows PowerShell**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Start-Gateway.ps1
```

看到 `Listening` 后，在另一个 PowerShell 窗口运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Export-Pairing.ps1
```

可通过 `-BindAddress`、`-Port` 和 `-StateDirectory` 自定义启动。导出时用相同的 `-StateDirectory`。原有 `-DataDirectory` 接受的是状态目录的上一级。

**macOS**

```bash
bash start-macos.sh
# 看到 Listening 后，在另一个终端窗口运行
bash export-pairing.sh
```

**Linux**

```bash
bash start-linux.sh
# 看到 Listening 后，在另一个终端窗口运行
bash export-pairing.sh
```

macOS 和 Linux 启动脚本接受 JAR 的同一组参数。使用自定义状态目录时，可以设置 `MYAI_STATE_DIR`，或者运行 `bash export-pairing.sh "/路径/gateway-state"`。原有 `MYAI_DATA_DIR` 仍指向状态目录的上一级。

导出脚本把客户端可直接粘贴的完整密码写到**脚本同目录的 `pairing.txt`**，并仅显示保存路径。Windows 使用当前用户专属的文件访问控制列表，macOS 和 Linux 使用 `600` 权限。重新导出会替换该文件。把解压目录放在用户私有位置，并将导出文件与历史、证书一起留在本机。源码仓库会忽略这个文件。

独立部署包内的启动脚本默认使用包目录下的 `gateway-state/`。源码仓库中的启动脚本默认使用 `server-data/gateway-state/`。如果原生 JAR 从其他目录启动，导出时应指定它启动日志中显示的状态目录。旧的 `Show-Pairing.ps1` 和 `show-pairing.sh` 可用于显式在终端显示密码。

### PDF 字体

macOS 和 Linux 上生成 PDF 时，在启动前设置 `MYAI_PDF_FONT`，指向包含所需字形的 TrueType `.ttf`。使用 `.ttc` 时，同时设置 `MYAI_PDF_FONT_FACE` 为字体的 PostScript 名称。Windows 默认使用 Microsoft YaHei。

### 跨网络访问

若穿透客户端和网关运行在同一台主机，穿透目标使用 `127.0.0.1:4000`。选择 TCP 或 TLS 透传，让客户端验证网关证书。设备的服务器地址使用穿透服务分配的公网主机名或 IP 和端口。使用可信局域网直连时，选择网关的局域网 IP 作为绑定地址，并按需要为私有网段放行端口。

## Windows 和 macOS 打包

在对应系统用 JDK 21 运行 `mvn -B verify` 后执行该平台的脚本。

| 平台 | 命令 | 产物 |
| --- | --- | --- |
| Windows | `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\package-windows.ps1` | `dist/windows/MyAI/`，包含 EXE 和 Java 运行环境 |
| macOS | `bash scripts/package-macos.sh` | `dist/macos/*.dmg`，架构取决于构建 JDK |

GitHub Actions 生成 Windows x64、macOS ARM64 和 macOS Intel 三种桌面包。CI 的 ARM64 包通过 `bash scripts/package-macos-arm64.sh` 构建，脚本使用 macOS 的 Xcode 命令行工具生成 ARM64 启动器，并校验内置 Temurin ARM64 运行时的 SHA-256 和可执行文件架构。到成功运行的 **Artifacts** 下载对应平台文件。Windows 应完整保留 EXE 所在目录。项目版本为 0.4.0，macOS 打包要求版本首位大于零，所以 DMG 使用包版本 1.4.0。用于正式分发的 macOS 包需要开发者自己的 Developer ID 证书和 Apple 公证。

## Android 构建

用 Android Studio 打开 `android/`，或准备 JDK 17、Gradle 8.11.1、Android SDK 35 后在仓库根目录运行：

```sh
gradle -p android :app:assembleDebug
```

APK 在 `android/app/build/outputs/apk/debug/`。公开 CI 使用调试签名。已经安装其他签名的 APK 时，用该 APK 原来的签名密钥签发升级包。把签名密钥保存在仓库外。

## 数据和升级

- 网关把证书私钥、配对密钥、任务和附件保存在状态目录中。启动日志会显示其实际路径。
- 升级前关闭服务并备份状态目录。新版本继续使用同一个数据目录，原证书和配对可继续使用。
- 要撤销旧密码，停止网关并删除**状态目录内**的 `pairing.txt`，然后重启服务、重新导出密码并配对。脚本同目录的 `pairing.txt` 是给客户端使用的副本。
- 主机需保持开机联网，服务退出或电脑休眠期间设备无法访问。
