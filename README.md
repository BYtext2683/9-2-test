# 9-2-test

## MediaAlbum（媒体相册）

一个本地私有的媒体相册 App：导入 webp / gif / png / jpg / heic / avif / mp4 / mkv 等文件，
按分类管理，支持滑动播放、快速多选、批量重命名、拖拽排序。

- 包名：`com.example.mediaalbum`
- 文件存放：App 私有目录（`Android/data/com.example.mediaalbum/files/media`），卸载时才删除
- 每次 push 会自动构建 debug APK，在 Actions 页面下载（artifact：`MediaAlbum-debug-apk`）

### 播放控制

- **播放方向**：播放页工具栏 → 播放方向，可切换「跟随系统 / 竖屏播放 / 横屏播放」
- **全屏 / 窗口**：播放页工具栏 → 全屏播放；全屏下点右上角悬浮按钮退出
- 两项设置都会记住，下次进入播放页自动恢复

### 更新安装不丢文件（重要）

更新安装后 App 内的文件不会丢失，依赖两点，请保持：

1. **签名密钥必须固定。** 签名不一致的包无法覆盖安装，只能先卸载再装，文件就会丢。
   - 推荐：在仓库 **Settings → Secrets and variables → Actions → New repository secret**
     添加 4 个 secret（名字固定）：
     - `MEDIAALBUM_KEYSTORE_BASE64`：密钥库文件的 base64 内容
     - `MEDIAALBUM_STORE_PASSWORD`：密钥库密码
     - `MEDIAALBUM_KEY_ALIAS`：别名（如 `mediaalbum`）
     - `MEDIAALBUM_KEY_PASSWORD`：密钥密码
   - 没配 secret 时，CI 会退而求其次使用缓存里自动生成的密钥，一般也一致；
     但缓存被清理后密钥会变，需要重装一次。
2. **始终用同一来源的包更新**（要么一直用 CI 产物，要么一直本地打同一个包），不要混用。

另外 App 每次启动会自愈：磁盘上还在、但数据库记录丢失的文件会被重新登记
（比如数据库被重置后，所有文件会自动回到相册里，只是分类会变成“未分类”）；
记录还在、文件却不在的会被清理，避免网格里出现打不开的项。

> 注意：切换签名密钥（比如从“没配 secret 的旧包”换成“配了 secret 的新包”）时，
> 新旧包签名不同，仍需卸载重装一次；此后再更新都不会再丢文件。

### 本地构建

需要 JDK 17 + Android SDK（platform 34 / build-tools 34.0.0）+ Gradle 8.9：

```bash
cd MediaAlbum
gradle :app:assembleDebug
```

如需本地构建与 CI 使用同一签名，设置环境变量后再构建：
`MEDIAALBUM_STORE_FILE` / `MEDIAALBUM_STORE_PASSWORD` / `MEDIAALBUM_KEY_ALIAS` / `MEDIAALBUM_KEY_PASSWORD`。
