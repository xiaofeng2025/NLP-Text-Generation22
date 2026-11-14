# 手动测试清单

无法自动化的检查都记在这里，每个阶段验收时逐项勾选。

## 阶段 0：硬件探测（probe App）

### 准备

- [ ] USB 摄像头已插在盒子上
- [ ] 盒子已打开开发者模式和 USB 调试，电脑执行 `adb connect <盒子IP>:5555` 后 `adb devices` 能看到盒子

### 安装并启动

```
cd android
./gradlew :probe:assembleDebug
adb install -r probe/build/outputs/apk/debug/probe-debug.apk
adb shell am start -n com.familycall.probe/.MainActivity
```

编译前需要让 Gradle 找到 Android SDK：设置环境变量 `ANDROID_HOME`，或在 `android/local.properties` 里写一行 `sdk.dir=<SDK 路径>`（该文件不提交）。

### 在电视上逐项操作

屏幕左边是报告，右边上方是预览画面，右边下方是六个按钮。用遥控器方向键移动，确认键执行。焦点移到左边报告上时，上下键可以滚动报告。

- [ ] 启动后左边自动出现“结论摘要”，没有闪退
- [ ] 摘要里“USB 摄像头（UVC 视频接口）”显示“找到 1 个”
- [ ] 摘要里“USB 音频接口”显示“有”
- [ ] 按“Camera2 预览”：首次会弹相机权限框，选允许；10 秒左右后摘要里出现“通过”或“失败”
- [ ] 按“UVC 预览”：可能弹 USB 授权框，选确定（有“默认用于该 USB 设备”就勾上）；右上方出现摄像头实时画面，摘要里出现“通过”和帧率
- [ ] 按“录音 5 秒并回放”：首次会弹录音权限框；对着摄像头说话 5 秒，随后电视里能听到刚才的声音
- [ ] 按“保存报告”，状态栏显示保存路径
- [ ] 退出 App 再打开，重新按“UVC 预览”：记录这次还弹不弹 USB 授权框
- [ ] 重启盒子后打开 App，按“UVC 预览”：记录这次还弹不弹 USB 授权框

### 取回报告

```
adb pull /sdcard/Android/data/com.familycall.probe/files/probe-report.md docs/probe-report.md
```

上面的命令取不到时，任选一个备用办法：

```
adb shell run-as com.familycall.probe cat files/probe-report.md > docs/probe-report.md
adb logcat -d -s ProbeReport
```

### 需要反馈的信息

- `docs/probe-report.md` 全文（或电视屏幕的照片）
- Camera2 预览和 UVC 预览，哪个能看到画面，画面是否流畅、有没有花屏或倒置
- 录音回放是否听得清，有没有明显杂音
- 退出重进、重启盒子后，USB 授权框是否还会弹
- 如果 App 闪退：闪退前按的是哪个按钮，并附上 `adb logcat -d > crash.txt` 的输出

### 验收标准

电视上能看到摄像头实时画面（Camera2 或 UVC 至少一条路径通过），录音回放有声音。
