# 无线 ADB 投屏（Android 控制端）

在 Android 手机/平板上，通过**无线 ADB**镜像并控制另一台安卓设备（电视盒子、手机、平板等）。  
画面长边铺满、等比留黑边；支持多点触控、悬浮导航球、远程音频、编码参数，以及前台服务保活（切后台不断联）。

> 本项目是独立实现的 **Android 控制端客户端**，在设备侧使用官方 **[scrcpy](https://github.com/Genymobile/scrcpy)** 的 `scrcpy-server`，协议兼容 scrcpy 2.7。  
> **不是** Genymobile/scrcpy 官方仓库的 fork，也不属于官方产品。

## 功能

- **等比长边铺满**：TextureView + AspectFit，横竖屏 / 手机↔盒子不变形  
- **无线 ADB**：IP:端口连接；多点触控；悬浮球（返回 / 主页 / 多任务 / 音量± / 断开）  
- **远程音频**：转发被控端声音（需 Android 11+；设置中可选 Opus / AAC / PCM）  
- **常见 scrcpy 参数**：max-size、码率、fps、H.264/H.265、保持唤醒、结束熄屏等  
- **后台不断联**：`MirrorSessionService` 前台服务持有会话；Activity 只挂载/卸载 Surface  

## 依赖与致谢（原始项目信息）

| 组件 | 来源 | 许可 |
|------|------|------|
| **scrcpy-server**（嵌入 `core-adb` assets） | [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) | [Apache-2.0](https://github.com/Genymobile/scrcpy/blob/master/LICENSE) |
| **dadb**（纯 Kotlin ADB 客户端） | [mobile-dev-inc/dadb](https://github.com/mobile-dev-inc/dadb)（Maven：`dev.mobile:dadb`） | Apache-2.0 |
| 本仓库客户端代码（协议解析、解码、UI、会话服务） | 本项目自研 | **GPL-3.0** |

scrcpy 官方说明与桌面端用法请参阅：  
https://github.com/Genymobile/scrcpy

本客户端**未复制**其他 GPL 手机端移植项目的源码；仅使用官方 Apache-2.0 的 server 二进制/协议，并自行实现控制端。

## 许可说明（GPL-3.0 是否可行）

**可行。**

- 官方 **scrcpy**（含 server）为 **Apache-2.0**。  
- Apache-2.0 与 **GPLv3** 兼容：可以将 Apache-2.0 组件用于 GPLv3 项目，合并后的发行版按 **GPLv3** 再分发。  
- 必须保留 scrcpy / dadb 等上游的版权与 Apache-2.0 声明（见 `NOTICE`）。  
- 本仓库整体以 **GNU GPL v3** 授权（见 `LICENSE`）。若你再分发本 App，需遵守 GPLv3（含提供对应源代码等义务）。

## 构建

环境：Android SDK、JDK 17。

```bat
gradlew.bat :app:assembleDebug
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`  
（发布包也可在 GitHub Releases 下载。）

## 使用

1. 被控端开启「无线调试」，或执行 `adb tcpip 5555`；与控制端同一 Wi‑Fi  
2. 控制端安装本 App，输入 IP 与端口  
3. 首次连接时在被控端点允许 RSA 密钥  
4. 建议允许通知，并在系统设置中忽略电池优化  
5. 需要声音时：设置中开启远程音频（默认 Opus）；被控端需 Android 11+，并有正在播放的内容  

## 模块

| 模块 | 说明 |
|------|------|
| `app` | Compose UI（连接 / 设置 / 镜像） |
| `core-adb` | dadb + 嵌入的 scrcpy-server |
| `core-protocol` | 音视频解复用、控制消息、启动参数 |
| `core-video` | MediaCodec 视频解码、AspectFit、音频播放 |
| `core-session` | 前台服务、重连、保活 |

更多保活说明见 [`docs/KEEPALIVE.md`](docs/KEEPALIVE.md)。

## 免责声明

本软件仅用于你有权访问与控制的设备。请遵守当地法律与设备厂商政策。作者不对滥用承担责任。
