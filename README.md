# 小说一键换名

在阅读小说网页、TXT 或 EPUB 时，把角色原名替换成你熟悉的新名字。换名在设备本地完成，不需要注册账号，也不会上传阅读内容和规则。

## Android 下载

当前公开版本是 Android `0.2.0 (3) Debug Beta`，用于功能内测。

### 直接安装

[下载 Android 0.2.0 APK](https://github.com/kkkkkk12138/xiaoshuo-huanming/releases/download/v0.2.0-android-beta/xiaoshuo-yijian-huanming-android-0.2.0-debug.apk)

适合只想安装使用的用户。Android 会提示是否允许浏览器或文件管理器“安装未知应用”，安装完成后可以关闭这项授权。

也可以进入 [Android 0.2.0 Beta 发布页](https://github.com/kkkkkk12138/xiaoshuo-huanming/releases/tag/v0.2.0-android-beta) 查看版本说明和校验文件。

> 请只下载 `xiaoshuo-yijian-huanming-android-0.2.0-debug.apk`。不要下载 `Source code (zip)`、`Source code (tar.gz)` 或名称以 `DO-NOT-USE-old-signature-` 开头的旧文件，它们不能用于正常覆盖升级。

### 覆盖升级

已安装 `0.1.1 (2)` 的用户可直接打开新版 APK 覆盖安装到 `0.2.0 (3)`，无需卸载旧版；换名规则、最近阅读和阅读位置会保留。若系统提示签名不一致，请确认下载的是上方标准文件名 APK，而不是旧签名文件。

> 当前 APK 仍使用与 `0.1.1 (2)` 相同的测试证书，仅适合内测。未来正式版若切换长期 Release 证书，可能无法直接覆盖此测试版。

### 本次修复

- 修复窄屏和鸿蒙设备上的阅读页按钮溢出、状态栏遮挡。
- 规则应用增加“正在生效”和替换数量反馈，保存后立即刷新正文。
- 修复“打开网页链接”、TXT 文件名、TXT/EPUB 阅读进度等问题。

## Android 使用方法

### 阅读网页

1. 在夸克、微信或其他 App 中打开无需登录即可阅读的小说网页。
2. 点击网页的“分享”。
3. 在分享面板中选择“小说一键换名”。
4. 进入阅读页后打开“规则”，填写原名和新名。
5. 点击“全部生效”。

如果分享面板没有显示本应用，点击“更多”查找；仍未出现时，先从桌面打开一次“小说一键换名”，再重新分享。

### 阅读 TXT 或 EPUB

1. 从桌面打开“小说一键换名”。
2. 点击“打开 TXT / EPUB”。
3. 在系统文件选择器中选择本地文件。
4. 打开规则面板，添加换名规则并生效。

支持常见编码 TXT 和无 DRM 的可重排 EPUB2/EPUB3。受 DRM 保护、固定版式或损坏的 EPUB 不支持。

## 常见问题

### 为什么网页提示不支持登录

应用不会读取夸克、微信或其他浏览器的 Cookie，也不提供网站登录。需要登录或付费才能访问的正文，请在正规渠道下载 TXT/EPUB 后再从本地打开。

### 安装时提示风险怎么办

GitHub 下载的 APK 不经过应用商店，Android 会显示“未知来源”提示。请确认下载地址属于本仓库 Release，并核对 SHA-256；不要从陌生群聊或重新打包的网站下载安装。

APK SHA-256：

```text
232caabd7814b7ec53930813344e8b178d791acf210945666c99da6ffdae9153
```

### 名字没有全部替换

Canvas、图片文字、部分 Shadow DOM 或特殊网页组件无法直接修改。动态加载的普通网页正文会继续监听并替换。

### 替换后为什么换行变化

新名字和原名字数不同时，网页会按原有排版规则重新换行，这是正常现象。

## 平台状态

| 平台 | 当前状态 | 安装方式 |
|---|---|---|
| Android | 公开 Beta | 从 GitHub Release 下载 APK |
| iPhone / iPad Safari | 开发测试中 | 当前仅支持 Xcode 真机测试 |
| Mac Safari | 开发测试中 | 当前仅支持 Xcode 构建 |
| Chrome / Edge 桌面版 | 兼容验证中 | 暂未提供普通用户安装包 |

iPhone、iPad 和 Mac 正式版需要通过 TestFlight 或 App Store 分发，不能直接安装 Android APK。

## 隐私边界

- 不读取其他浏览器的 Cookie、密码或浏览记录。
- 不上传网页、TXT、EPUB、换名规则或阅读历史。
- 不申请通讯录、位置、相册、无障碍或屏幕录制权限。
- 网页仅支持无需登录即可访问的公开链接。
- 所有规则和阅读记录保存在当前设备。

完整说明见 [Android 隐私说明](docs/android/PRIVACY.md)。

<details>
<summary>开发者构建与测试</summary>

### 项目结构

- `android/`：Jetpack Compose Android App
- `apple/小说一键换名/`：iOS 与 macOS Safari Extension 工程
- `src/android-runtime/`：Android WebView 使用的换名运行时
- `build/webextension/`：标准 WebExtension 核心产物

### WebExtension 与 Safari

```bash
npm install
npm test
npm run build
npm run apple:sync
```

Xcode 构建与签名见：

- `docs/safari/XCODE_BUILD_AND_RUN.md`
- `docs/safari/APP_STORE_CONNECT_UPLOAD.md`

### Android

要求 JDK 17、Android SDK 36、Build Tools 36.0.0：

```bash
npm install
npm run build:android-runtime
./android/gradlew -p android :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Android 构建与正式签名见 `android/README.md`。

</details>

## 已知边界

- 当前 GitHub Android 包是 Debug Beta，不是正式长期签名版本。
- 公开网页不代表所有网站均可正常加载，网站可能限制 WebView 或外部访问。
- Canvas、Shadow DOM、图片文字和部分自绘组件不保证覆盖。
- 将高频普通词设为原名可能产生误替换。
