# HEIC 转换器（J2H）

将相册中选定目录里的所有 JPG 批量转换成 HEIC（质量 95），保留全部 EXIF 元数据（GPS、相机信息、拍摄时间等），转换通过校验后删除原 JPG。后台前台服务运行，锁屏继续转换。

> **要求 Android 11（API 30）以上。** 早期版本没有 `Bitmap.compress(HEIC, ...)`。

## 安装包下载

每次推送到 `main` 分支后，GitHub Actions 会自动构建一个 debug 签名的 APK，发布在 [Releases](../../releases) 页面（标签 `build-N`）。手机浏览器打开 Releases、点最新一个 build、下载 `app-debug.apk` 即可安装。

> 第一次安装可能需要在系统设置里允许"未知来源安装"。

## 使用步骤

1. 打开 App，点右下角 ➕ 选一个相册目录（系统 SAF 选择器，需点击「选择此目录」并授予权限）。可重复选多个目录。
2. 点「开始转换」，App 进入前台服务，状态栏出现进度通知，可锁屏。
3. 完成后通知关闭，列表显示成功/失败/跳过统计。

## 关键技术点 / 已知限制

- **EXIF 写入靠手写容器修补**：Android 没有官方 API 把 EXIF 写到 HEIC，本项目在 `HeifExifInjector.kt` 里手工解析 ISO/IEC 23008-12 容器，向 `meta` box 增加 `Exif` item 并把 EXIF 二进制块追加到文件末尾。
- **删除策略 = 校验通过后再删（Q3=B）**：转换后立即重新解码 HEIC、用 `ExifInterface` 读回 EXIF 关键字段，任何一项校验失败 → 保留原 JPG，写日志说明原因。
- **极小色彩偏差**：必须 decode→encode（Android 限制），不调整任何主观参数（亮度/对比度/饱和度），但 YUV→RGB→YUV 的轮回会引入肉眼基本不可见的偏差。
- **后台限制**：Android 13+ 强制前台服务必须显示通知，无法做到完全无感。
- **签名**：用 debug keystore 签名（CI 上自动生成），仅适合个人安装，不能上架商店。

## 代码结构

```
app/src/main/java/com/wjbyt/j2h/
├── MainActivity.kt              入口 + SAF 目录选择
├── ui/HomeScreen.kt             Compose UI
├── storage/TreeUriStore.kt      持久化目录 URI
├── exif/JpegExifExtractor.kt    JPEG APP1 → 裸 TIFF
├── heif/HeifExifInjector.kt     HEIF 容器修补，注入 Exif item
├── heif/HeicConverter.kt        单文件转换流水线 + 校验 + 删除
└── work/
    ├── JpgScanner.kt                 递归枚举 SAF 树
    └── ConversionForegroundService.kt 前台服务，逐张转换
```

## 本地编译（可选）

如果你已经装了 Android Studio：

```sh
git clone https://github.com/wjbyt/J2H.git
cd J2H
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```
