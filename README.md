# FastDL Android

轻量、可靠的 Android 直链下载器。粘贴链接即可下载，支持 HTTP/HTTPS、GitHub Release 资源、断点续传、暂停/取消和前台通知进度。

## 功能

- 自动解析常见 GitHub 下载页并使用最终资源直链
- 8 / 16 / 32 / 64 / 128 路 Range 分片，可在高级设置中选择
- 网络不稳定时自动降档，服务器不支持 Range 时自动单流回退
- 显示百分比、实时速度和下载结果；支持队列中继续粘贴链接
- 默认保存到 `Download/FastDL`，也可选择其他文件夹，完成后直接打开
- 支持蓝色、粉色两套渐变液态玻璃主题
- 内置反馈入口，可提交文字、图片和视频到站点
- FTP、ed2k 不在 Android 版本范围内

## 构建

在已安装 Android SDK 的 Windows 终端执行：

```powershell
gradlew.bat :app:assembleDebug
```

生成文件：`app\build\outputs\apk\debug\app-debug.apk`

发布版使用：

```powershell
gradlew.bat :app:assembleRelease
```

项目使用 Android SDK Platform 35。首次构建请在 Android Studio 的 SDK Manager 安装 Android 35、Build Tools 和命令行工具。

## 隐私

下载内容只写入用户选择的本地目录。用户主动提交反馈时，应用只上传反馈文字、所选图片/视频；不会上传下载文件。反馈接口位于项目维护者自己的站点。

## 许可证

PolyForm Noncommercial 1.0.0：允许个人、学习、研究和其他非商业使用，禁止商业使用。详见 [LICENSE](LICENSE)。
