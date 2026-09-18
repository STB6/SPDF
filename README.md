# SPDF

<img src="assets/logo.svg" alt="SPDF logo" width="128" height="128">

English · [简体中文](README.zh-CN.md)

PDF reader for Android

## Features

- Choose from three page flow directions: down, right, or left.
- Dark Reader offers color inversion and hue-preserving inversion, with options to follow the system theme and skip dark documents automatically.
- Supports global and per-session settings.
- Supports English and Simplified Chinese, with Material 3.

## Build

Install Android SDK 37, set `sdk.dir` in `local.properties`, then run:

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

By default, debug uses a local debug key. Release is unsigned. Custom signing can be configured in `keystore.properties` using `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`.

APKs support Android 8.0 or later. Builds include `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, and a universal APK. Install a signed APK with `adb install -r <apk>`.
