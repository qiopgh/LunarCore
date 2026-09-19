# 固定正式构建：茧错误响应的有限字段适配

## 范围

本次只完成 `PacketStartCocoonStageScRsp(null)` 的错误响应适配和离线回归。
目标构建是 `20260813-0422-V4.5Live-16121062-OSPRODWin4.5.0-OSLive`。
**第 3 步客户端接入等待用户配合，本次不启动客户端、SDK 或游戏服务。**

旧错误响应把数值 `1` 编码在字段 `5`，正文为 `28 01`。新增独立版本化投影后，
同一构造器输入输出字段 `13`、数值 `1`，正文为 `68 01`，消息编号为 `1479`。
这里改变的是字段号，不是把错误码值改成 `13`。

成功分支仍保留旧实现，未进行正式构建适配或验收；不能用它证明成功战斗可用。
本次不恢复其他消息、不填充未知 `BattleInfo`、不修改旧版生成类和 `GameConstants.VERSION`，
也不新增启动器、帧编解码器或密码学实现。

## 来源与生成

- 基线：已有 `codex/s0-s1-baseline`，提交 `2d338af76002bace4118356d50c63e6f83e1b238`。
- 字段来源：Himeko 固定提交的描述表，与已有正式构建静态候选交叉核对；
  消息编号沿用已有运行时类型绑定，不因 beta 项目名字相似就整套替换。
- 完整来源、哈希及边界：`docs/release450-cocoon-error-source.json`。
- 手写 schema：`src/release450/proto/start_cocoon_stage_error.proto`。
- 原生成链：`protoc 3.24.3` → `protoc-gen-quickbuf 1.3.1`，运行库沿用 `1.4`。
- 生成输出：`src/generated/release450/emu/lunarcore/proto/v450/StartCocoonStageErrorOuterClass.java`。

`release450` 是独立 source set；不要把新 schema 放进旧版生成目录，也不要手工编辑生成输出。
默认输入目录由 protobuf 插件注册，避免重复添加相同目录。旧版 `src/generated/main` 保持原样。

固定的 Windows `protoc 3.24.3` 在本轮中文检出路径检查中报 `directory does not exist`，
实际目录存在，诊断输出中的路径已出现编码损坏。此项明确记录为生成工具的路径限制，
不是协议测试通过。本次实际实现工作树使用 ASCII 路径，生成和回归已通过；
含空格的 ASCII 路径已独立重新生成，输出 SHA-256 与当前工作树完全一致。
不要为此修改系统代码页、全局代理或临时升级整套工具链。

## 复现

先让 `JAVA_HOME` 指向已安装的 Java 21，使用已有 Gradle 缓存。以下命令在仓库工作树内执行。
首次缺少固定版本生成器时，先去掉 `--offline`，让原 Gradle 依赖解析下载声明版本；
后续使用缓存离线执行，不升级生成器或运行库。

```powershell
$env:GENERATE_PROTO = 'false'
.\gradlew.bat --offline --no-daemon --console=plain --max-workers=4 generateRelease450Proto release450CocoonErrorTest s0BaselineTest jar
```

`GENERATE_PROTO=false` 用于保留旧版生成目录，不妨碍显式运行新的独立生成任务。

成功标记：

```text
BODY_HEX=6801
FRAME_HEX=9d74c71405c70000000000026801d7a152c8
RELEASE450_COCOON_ERROR_TESTS_PASSED=8
S0_BASELINE_TESTS_PASSED=10
```

新增测试直接调用原响应构造器、真实生成类与 `BasePacket`。包含错误字段、错误值和截断输入负例，
并确认旧生成类仍编码为 `28 01`。`check` 已依赖该测试，不会在常规检查时静默遗漏。

独立验证测试器确实能识别坏帧：

```powershell
.\gradlew.bat --offline --no-daemon --console=plain --max-workers=4 release450CocoonErrorTest -Prelease450NegativeControl=true
```

此命令故意翻转首魔数，应返回非零退出码；它不是候选成功用例，不应并入全绿运行结果。

## 状态解释

原同输入正文检查为 BASELINE 退出 `2`；修复后 MODIFIED 实测退出 `0`；
在独立副本恢复本阶段源码后，ROLLBACK 实测重新退出 `2`、正文恢复为 `28 01`。
本阶段回滚保留上阶段已经补齐的编号 `1479`，不混同更早的 `-1 / 1479 / -1` 编号试验。

离线成功只证明本错误分支的字段投影、生成和封包成立，不证明正式客户端已经登录、进入场景或完成战斗。
原生 Linux 未验证，Git sh 的回滚验证不替代原生 Linux 构建。
