# SPDF

<img src="assets/logo.svg" alt="SPDF 图标" width="128" height="128">

[English](README.md) · 简体中文

Android PDF 阅读器

## 功能

- 支持向下、向右和向左三种页面延伸方向，适配多种阅读场景。
- Dark Reader 支持直接反色和保留色相的反色，可跟随系统深浅色并自动跳过深色文档。
- 支持全局设置与当前会话设置。
- 支持简体中文和英文，采用 Material 3。

## 构建

安装 Android SDK 37，在 `local.properties` 中设置 `sdk.dir`，然后运行：

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

默认 debug 使用本机调试密钥。release 不签名。可通过 `keystore.properties` 中的 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword` 指定签名配置。

APK 支持 Android 8.0 及以上版本，提供 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` 和 universal 五种安装包。使用 `adb install -r <apk>` 安装已签名的 APK。
