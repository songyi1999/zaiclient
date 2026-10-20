# ZaiClient — ZAI 工作台安卓客户端

[![Build APK](https://github.com/songyi1999/zaiclient/actions/workflows/build.yml/badge.svg)](https://github.com/songyi1999/zaiclient/actions/workflows/build.yml)

[企业智能共享平台 (zai)](http://112.25.153.242:30000/songyi/zai) 的安卓原生客户端。
技术栈与 [FreeFit](https://github.com/songyi1999/freefit) 同款：Kotlin + Jetpack Compose + Material3，单 Activity + Navigation。

## 下载安装

- 稳定版：到 [Releases](https://github.com/songyi1999/zaiclient/releases) 下载 `ZaiClient-release.apk`（打 `v*` 标签自动发布）
- 尝鲜版：Actions 构建产物（每次 push 自动打包，需登录 GitHub 下载 artifact）

默认连接 `http://zai.xinpanmen.com:9002`，可在登录页或设置里改成自己的部署地址。

## 功能（v0.4.0）

- 界面参考 WorkBuddy：近黑底色 + 圆角卡片 + 翠绿强调，四 Tab（对话/记录/发现/我的）
- 对话首页：问候语 + 场景建议芯片（换一换）+ 底部输入条，左滑抽屉切换/新建会话
- 登录（用户名/密码 → JWT，自动续期；记住密码；服务器地址折叠在"高级"里）
- AI 对话：SSE 流式输出、思考过程展示、轻量 Markdown 渲染
- OTA 自动升级：启动检查新版本弹窗提醒，设置里可手动"查看最新版本"，应用内下载安装

## 后端对接

接口前缀 `/api`：

| 接口 | 用途 |
| --- | --- |
| POST /auth/login, /auth/refresh | 登录与令牌刷新（401 拦截器自动重放） |
| GET /auth/me | 用户信息 |
| GET /conversations | 会话列表 |
| GET /conversations/:id/messages | 历史消息 |
| PATCH /conversations/:id | 归档 |
| POST /agents/default/chat | SSE 流式对话（conversation/token/reasoning/done/error 事件，15s 心跳） |

## 构建

Android Studio 打开项目根目录直接 Run；或命令行（需 JDK 17 + Android SDK）：

```bash
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

Gradle 仓库走阿里云镜像（见 settings.gradle.kts），国内网络可直接同步。release 签名密钥 `app/zaiclient.keystore` 随仓库提交（个人项目做法，与 FreeFit 一致）。

## 发布新版本

```bash
# 改 app/build.gradle.kts 里的 versionCode/versionName 后：
git tag v0.x.0
git push origin v0.x.0
# CI 自动构建并在 GitHub Releases 发布 ZaiClient-release.apk
```
