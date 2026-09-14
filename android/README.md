# 小说一键换名 Android

## 本地验证

要求 JDK 17、Android SDK Platform 36、Build Tools 36.0.0 和可用的 API 36 设备或模拟器。

```bash
npm ci
npm test
npm run build:android-runtime
./android/gradlew -p android :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug
```

Debug APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。

## Release

Release 构建不会使用 Debug 密钥，也不会自动生成长期私钥。密钥保管者应在仓库外创建、备份并设置 `signing/README.md` 列出的四个环境变量；缺少任一变量时构建会失败。

```bash
./android/gradlew -p android clean :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
npm run android:release:verify
```

验证成功后得到 `dist/小说一键换名-android-0.2.1.apk` 和同名 `.sha256` 文件。发布前仍需在真实设备验证首次安装、覆盖升级及阅读数据保留。
