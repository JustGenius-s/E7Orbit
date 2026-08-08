# E7 Orbit

E7 Orbit 是面向 Android 模拟器的第七史诗国服自动化助手。首版只实现秘密商店书签刷新，并将购买操作限制在经过两次图像验证的白名单商品。

## 首版环境

- MuMu 12，Android 11 或更高
- 横屏分辨率；识图模板会按实际画面等比缩放
- 第七史诗国服包名 `com.zlongame.cn.epicseven`
- 侧载 APK，不需要 Root
- APK 包含 `x86_64` 与 `arm64-v8a`；ARM64 真机仍需满足固定分辨率要求

## 技术

- Kotlin、Jetpack Compose、Material 3
- MediaProjection 屏幕捕获
- AccessibilityService 点击、滑动与悬浮窗
- OpenCV 模板匹配
- Coroutines、StateFlow、DataStore

## 开发构建

1. 安装 JDK 17、Android SDK Platform 36 和 Build Tools 36.0.0。
2. 将 `local.properties` 中的 `sdk.dir` 指向本机 Android SDK。
3. 执行：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

Debug APK 位于 `app\build\outputs\apk\debug\`。

## 识图模板

国服识图模板随 APK 一并打包；主页“识图模板”应显示已加载。游戏更新可能使模板失效，低置信度或未知页面会触发安全停止并自动保留诊断截图，可通过 `.\tools\export-diagnostics.ps1` 导出分析。

## 装备导入

1. 在首页开启“装备抓包”，完成 Android VPN 授权。
2. 冷启动第七史诗并打开一次背包。
3. 返回 E7 Orbit，点击“停止”；应用会关闭 VPN 并异步解析装备。
4. 在“数据 → 装备”中查看、搜索和按部位筛选装备。
5. 点击“导出 gear.txt”，可在 Fribbels Optimizer 中通过 Merge 导入。

装备抓包只保存游戏 `3333/5222` 端口的连接载荷。停止抓包后，载荷会提交至 Fribbels 公开客户端使用的远端解析接口；解析需要联网，结果会保存在应用私有目录。抓包使用本地 VPN 转发，不能与其他 Android VPN 同时运行。

## 诊断日志

- 实时查看：`E:\Lib\AndroidSdk\platform-tools\adb.exe logcat -s E7Orbit`
- 导出文件与失败截图：`.\tools\export-diagnostics.ps1`
- 持久日志位于导出目录的 `logs` 子目录。

日志记录页面阶段、每个模板的实际置信度与阈值、截图序号和尺寸、目标坐标、手势结果、暂停/停止原因。页面识别失败时会保存触发失败的原始帧，便于区分模板、阈值、分辨率或时序问题。
