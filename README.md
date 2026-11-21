# Aerial for B862AV3.2-M

面向中国移动魔百和中兴 B862AV3.2-M（四川移动固件，Android 9）的 Aerial 电视桌面适配版。项目的目标是在保留运营商桌面和直播能力的前提下，让盒子开机后进入简洁的 Aerial 桌面，或按设置打开直播应用。

## Fork 来源

本项目 fork 自彭楚金维护的开源 Android TV 桌面，以该仓库的提交 `9129859` 为基线。感谢上游作者与贡献者。[pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv) · [上游基线](https://github.com/pengchujin/aerial-tv/commit/912985970adf2f0f8885462f997b49bc29720540)

## 这个 fork 解决什么问题

这款盒子的固件在开机时会直接启动运营商桌面，即使已经把 Aerial 设为默认桌面。从运营商桌面切到 Aerial、再打开直播应用时，原桌面可能短暂闪现，用户也不清楚直播是否正在启动。

- **开机过渡**。在匹配的盒子上尝试提前打开 Aerial。获得「在其他应用上层显示」权限后，用当前桌面壁纸和启动提示覆盖切换过程。开机完成后，按设置打开所选应用。可在 Aerial 首页长按应用磁贴，选择「开机时打开」。
- **统一桌面与直播首页的背景**。Aerial 通过只读接口向 Zee 电视直播提供当前桌面壁纸，供直播首页用作背景。桌面采用静态壁纸，保留独立屏保。
- **本地版本独立更新**。移除了上游应用内的版本检查和 APK 下载、安装入口，以便使用本项目自己的签名和构建包手动更新。

适配在应用层完成，无需刷机。建议保留运营商桌面及系统组件，以便继续使用盒子的原有直播入口。

## 从源码构建

使用 JDK 17 和 Android SDK 36，在仓库根目录执行以下命令构建 Standard Release（Windows PowerShell）。

```powershell
.\gradlew.bat :app:assembleStandardRelease
```

APK 输出到 `app/build/outputs/apk/standard/release/`。覆盖安装时，APK 须与盒子上已安装的 Aerial 使用相同签名。

## 许可证

项目沿用上游 MIT 许可证，Inter 字体采用 SIL Open Font License。[MIT 许可证](LICENSE) · [字体许可证](FONT-LICENSE-OFL.txt)
