# NLP-Text-Generation22

安卓手机与小米盒子之间的 1 对 1 家庭视频通话 App。完整方案见 [PLAN.md](PLAN.md)。

## 当前进度

阶段 0（硬件探测）：`android/probe` 是只装在盒子上的探测 App，用来确认盒子能否驱动 USB 摄像头和麦克风。安装、操作步骤和需要反馈的信息见 [docs/test-checklist.md](docs/test-checklist.md)。

## 目录

```
android/   Android 工程（Gradle Kotlin DSL），目前只有 probe 模块
docs/      手动测试清单；探测报告取回后存为 docs/probe-report.md
PLAN.md    开发方案
```

`server/` 以及 `android` 下的 `core`、`mobile`、`tv` 模块在后续阶段加入。
