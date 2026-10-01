# FastDL Android

> **许可证：PolyForm Noncommercial 1.0.0。** 源码公开，允许个人、学习、研究及其他非商业用途；任何商业使用均不被许可。详见 [LICENSE](LICENSE)。

原生 Android 下载器，移植 `D:\fastdl` 的直链下载策略：HTTP/HTTPS Range 分片、断点续传、暂停、取消、通知栏前台服务进度。

界面只需要粘贴下载链接。程序会自动选择并发、分片、内存缓冲与可用网络，不提供会拖慢启动或造成误配的手动选项。

## 构建

在已配置 Android SDK 的终端中执行：

```powershell
D:\gradle\gradle-9.7.1\bin\gradle.bat :app:assembleDebug
```

若 SDK 未由环境变量发现，在项目根目录创建 `local.properties`：

```properties
sdk.dir=C\:\\Android\\Sdk
```

APK 输出：`app\build\outputs\apk\debug\app-debug.apk`。GitHub Release 会附带可直接安装的 APK。

项目使用标准 Android SDK Platform 35；请在 SDK Manager 安装该平台。当前 `D:\ad\platforms\android-37.0` 不是 Android Gradle Plugin 可识别的标准平台目录，不能替代它。

## 当前范围

- 支持 HTTP/HTTPS 直链、多线程 Range 下载与服务器不支持 Range 时的单流回退；自动启用最高 128 路并发，并在网络异常时自动降至 64、32、16 路。
- 自动选择内存缓冲或磁盘分片：小文件避免多余的分片写入，大文件使用高并发 Range 分片。
- 任务数据和临时分片保存在应用专属 Downloads 目录；已完成文件也在此目录。
- Android 需要以前台通知保持大文件下载；暂停或关闭通知中的取消按钮会保留/删除临时文件。
- Wi-Fi 与移动数据同时可用时自动轮换连接；网络策略和设备限制会自动回退。
- FTP、ed2k、Windows 网卡绑定、连接清理和 aria2 模式不适用于此 Android 版本，未移植。
