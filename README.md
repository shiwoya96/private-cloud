# 私有云备份（Android）

一个原生 Java Android 客户端，用于把用户通过系统目录选择器授权的手机目录备份到自己的
WebDAV 或 SMB 服务器，并从远端快照恢复。应用不申请传统的全盘存储权限。

## 功能范围

- WebDAV：HTTP(S) 地址、用户名、密码和远端目录。
- SMB：主机、端口、共享、域、用户名、密码和共享内目录；客户端限定 SMB 2.0.2 至 SMB 3.1.1。
- 通过 Android Storage Access Framework 选择并持久授权一个手机目录。
- 测试连接、立即备份、读取远端快照、选择快照恢复，以及进度和取消反馈。
- 备份和恢复由 WorkManager 一次性任务执行；长时间传输使用数据同步前台服务，通知展示受系统权限设置影响。
- 快照包含文件清单和 SHA-256 摘要；恢复总是创建新目录并重新校验文件，不静默覆盖原文件。
- 服务器配置、密码和 SAF 目录 URI 作为一个整体，使用 Android Keystore 中的
  不可导出 AES-GCM 密钥加密后再持久化。

## 推荐：完全远程构建

本项目的默认交付方式是 GitHub Actions。Android SDK、Gradle、依赖解析（含关键加密依赖版本
校验）、lint、单元测试、APK 打包和签名结构校验全部在 GitHub 托管 runner 上完成；本机或
手机只需上传源码并下载构建产物，不需要安装这些开发工具，也不会占用本地 CPU 进行编译。

1. 把本目录提交并推送到一个 GitHub 仓库。
2. `push` 或拉取请求会自动触发 **Android CI**；也可以在 **Actions → Android CI → Run workflow**
   手动触发。
3. 等待 `Lint, unit tests and debug APK` 任务成功，再下载页面底部的 APK Artifact。

## 可选：开发与本地构建

需要：

- JDK 17
- Android SDK Platform 35 和 Build Tools 34.0.0
- Gradle 8.9

本仓库不提交 Gradle Wrapper JAR。Android Studio 可以直接同步工程；命令行可执行：

```bash
sdkmanager "platforms;android-35" "build-tools;34.0.0"
gradle --no-daemon --max-workers=2 :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

Debug APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已启用 USB 调试的设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 从 GitHub Actions 下载 APK

1. 打开仓库的 **Actions** 页面。
2. 选择最近一次成功的 **Android CI** 运行。
3. 在页面底部 **Artifacts** 下载 `private-cloud-debug-apk-<运行编号>-<尝试编号>`。
4. 解压后得到 debug APK 和 SHA-256 校验文件；核对后，在测试设备上允许从该文件来源安装，
   或使用 `adb install -r`。

Linux/macOS 可在解压目录执行：

```bash
sha256sum -c app-debug.apk.sha256
```

该产物使用 Android 自动生成的 debug 密钥签名，只适合测试，不应当作正式发布包。Artifact
默认保留 14 天。正式发布需要自行保护 release keystore、启用签名配置并审查依赖和许可证。

## 安全说明

- 优先使用 HTTPS WebDAV。由于当前界面明确支持 `http://`，Manifest 和网络安全配置暂时允许
  明文 HTTP；HTTP 会暴露凭据和文件内容，只应在可信隔离局域网中临时使用。产品若改为
  HTTPS-only，必须同时把 `usesCleartextTraffic` 和网络安全配置改为 `false`。
- 不要把 SMB 445 端口直接暴露到互联网，建议仅在可信局域网或 VPN 中使用。服务端应启用
  SMB 3 加密；客户端禁用 SMB1，但协议版本本身不等于传输必然加密。
- 连接密码由应用使用 Android Keystore 和 AES-GCM 加密持久化。不要把密码写入日志、Intent、
  未加密的 SharedPreferences、备份清单或远端元数据。
- 每个 WorkManager 任务携带与自身 UUID 绑定的加密不可变配置；任务排队后再修改
  界面配置，不会让旧任务误用新服务器、密码或目录。
- 应用设置 `allowBackup=false`，并在旧版与 Android 12+ 备份规则中排除所有应用数据域，防止
  连接配置和凭据进入系统云备份或设备迁移。
- 网络配置只信任系统 CA。自签名证书应使用由受信任 CA 签发的证书或通过受管理设备安装为
  系统信任锚；不要在代码中关闭证书或主机名校验。
- 恢复前核对手机目标目录和快照。恢复引擎会创建独立目录、不覆盖原文件，并校验大小与
  SHA-256；当前界面也会持续显示恢复安全提醒。

## 已知限制

- Android 系统会限制对部分根目录和应用私有目录的 SAF 授权，这是平台安全行为。
- 不同 NAS 对 WebDAV 方法、SMB 方言、文件名和时间戳的支持不同，需要在真实设备与目标服务
  上测试大文件、Unicode 文件名、断网重试和取消。
- 当前版本只提供手动触发的一次性备份与恢复，不提供定时备份。WorkManager 会管理任务调度，
  但系统仍可能根据网络、省电策略和前台服务时限延后或停止工作；传输不保证字节级断点续传。
- 取消、断网或进程终止可能在远端留下不可见的未提交快照数据，或在手机上留下独立的
  部分恢复目录；已提交的旧快照与恢复目录外的原文件不会被删除或覆盖。
- 远端快照内容不做端到端加密；安全性依赖 HTTPS 或 SMB 服务端的传输保护和服务器自身
  的存储安全。
- 远程 CI 当前只生成 debug APK；发布签名、应用商店元数据和自动发布不在 CI 范围内。

第三方依赖与许可证见 [`LICENSES/THIRD_PARTY_NOTICES.md`](LICENSES/THIRD_PARTY_NOTICES.md)。
