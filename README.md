# StegoTank

> 把秘密藏进一张图片 —— 图片隐写算法聚合的原生 Android 应用。

StegoTank 是一个把 **LSB 隐写 / RAC 图隐 / 幻影坦克 / 光棱坦克 / 图片混淆** 等图片隐写与混淆算法整合进一个原生 Android 应用的聚合项目。所有处理均在本地完成，图片不会上传到任何服务器。

算法与实现致敬以下上游项目：

| 模块 | 上游项目 | 原作者 |
| --- | --- | --- |
| LSB 隐写 | [RobinDavid/LSB-Steganography](https://github.com/RobinDavid/LSB-Steganography) · [aagarwal1012/Image-Steganography-Library-Android](https://github.com/aagarwal1012/Image-Steganography-Library-Android) | Robin David · aagarwal1012 |
| RAC 图隐 | [rac-hide](https://github.com/tuoPzf/rac-hide) | [tuoPzf](https://github.com/tuoPzf) |
| 光棱坦克 | [Mirage_Decode](https://github.com/TankFactory/Mirage_Decode) | TankFactory |
| 幻影坦克 | [Mirage_Colored](https://github.com/TankFactory/Mirage_Colored) | TankFactory |
| 图片混淆 | [ObfuscationUtils](https://github.com/2195517546/ObfuscationUtils) | 2195517546 |

UI 组件基于 [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix)。

## 功能

- **LSB 隐写**：文字或文件藏进 PNG 像素末 2 位，可选 AES 加密
- **隐写检测**：扫描图片，给出「确定 / 疑似 / 未发现」判断提示
- **嵌入历史**：自动记录 LSB 嵌入记录，可查看与清空
- **RAC 图隐**：封面藏秘密图，支持画质容量调节与鲁棒性模拟
- **幻影坦克**：白底 / 黑底呈现不同画面
- **光棱坦克**：双权重合成与曝光调节
- **图片混淆**：Arnold 置乱等 8+ 种混淆算法

## 应用信息

- 包名：`com.setgo.tank`
- 应用名：StegoTank
- 当前正式版：`v1.0.6`（versionCode 76）
- 更新日志见 [Releases](https://github.com/Gz3192019/StegoTank/releases)

## 构建

### GitHub Actions（推荐）

仓库内置 [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml)，推送到 `main` 并打 tag `v1.0.x`（或手动触发）即可自动编译 APK 并发布到 Releases。

### 本地构建

```bash
# 环境：JDK 21+ / Gradle 9.6（wrapper 已内置）
cd android-compose
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

## 隐私与安全

隐写 ≠ 加密。本工具用于研究、版权水印与合法隐蔽通信；除 LSB 的可选 AES 加密外，各模块不具备密码学意义上的保密性，请勿用于保护真正敏感的信息。详见 [SECURITY.md](SECURITY.md)。

## 贡献

欢迎提交 Issue 与 PR，请阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 与 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。

## 许可

[GPL-3.0-or-later](LICENSE) · 尊重各上游项目的开源许可与原作者署名。
