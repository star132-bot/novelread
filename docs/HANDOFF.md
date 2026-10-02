# MKread 开发交接与任务状态

更新日期：2026-10-03。以 GitHub `feature/narration-cloud` 为开发基线，不合并另一工作树尚未提交的修改。私有服务器位置与运行记录保存在不入库的 `docs/private/`；本文不记录凭据或主机地址。

## T8 ✅ 提交已有功能代码

GitHub 已有四个主题提交：`246c888` 语音引擎与音色、`aa319fa` MKBook 与原地更新、`598b8fa` 云书库服务端、`a18ce6a` App 云同步。保留已发布历史，不重拆或强制推送。Windows 回归发现 TXT 测试夹具的 CRLF 差异，已统一测试预期的换行；App 单测 262 项，0 失败、1 项既有跳过，MKBook 工具 9 项通过；默认 debug 构建成功。

## T2 真实账号 App 端到端验证

- [x] 生产书库 OIDC 客户端已配置，服务端登录协议及权限测试完成。
- [x] 正式书库管理员身份已配置；安装账号已停用，角色和会话已撤销，保留历史审计关联。
- [ ] 使用真实账号在 Android 模拟器登录，浏览器回到 App。
- [ ] 同步示例书到「云书库」文件夹并打开阅读。

## T6 ✅ Android instrumented 测试（Claude，提交 `test: bring instrumented tests up to date`）

- [x] 补充 `Migration4To5Test`。
- [x] 运行 `:app:connectedDebugAndroidTest` 并修复失败：117 通过、5 跳过（其中 3 个为未安装音色包时跳过的冒烟测试），连续两次全绿。顺带修复朗读重启时高亮闪烁的真实问题。

## T4 Release 语音模型与签名（Claude，提交 `feat: downloadable voice packs`）

- [x] Release 只内置 Matcha（debug 265 MB / release 247 MB，原约 1 GB），新安装离线可朗读。
- [x] 云书库音色清单、模型包下载及完整性验证；Melo/Kokoro/ZipVoice 三个包已在生产发布，下载地址可被 Cloudflare 缓存。
- [x] App 真实下载、失败重试、安装和音色切换：模拟器实测下载 Melo（182 MB）→ 校验 → 安装 → 44.1 kHz 朗读。
- [ ] 私有 release 签名：已支持从 `~/.gradle/gradle.properties` 读取（见 `docs/cloud-library.md`），还没有正式 keystore，需要人工决定并生成。

## T7 Windows 脚本同步（Claude 负责）

- [ ] Windows 下载脚本与锁文件、shell 脚本对齐。
- [ ] 离线清单检查遵守云书库网络权限边界。

## T9 每书读音表及卷名（Claude 负责）

- [ ] 每书读音表接入朗读。
- [ ] 卷名持久化、迁移、导入和目录展示。

## 留给人工的任务

T5 真机性能与后台朗读、T10 服务器密码及 SSH 安全变更不由本次自动执行。T3 真实书籍发布需要提供书稿，示例书暂时保留。没有运行或仍需人工交互的步骤不标完成。
