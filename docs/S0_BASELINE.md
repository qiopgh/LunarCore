# S0 工程与旧协议回归基线

本入口只验证 LunarCore 旧版生成类与封包行为，不声明正式版 4.5.0 可接入。测试不运行 `LunarCore.main`，不创建 HTTP/KCP 监听、数据库或客户端进程。

## 固定输入

- 源码基线：`04b5de871c62895091c4d65243b2e03997e66f23`，上游 `lite` 声明版本 4.2.0。
- 实测环境：原生 Windows x64、Temurin 21.0.12.1、仓库 Gradle wrapper 8.14.4。
- 本轮依赖版本、解析产物和 SHA-256：`docs/s0-dependencies.lock.json`。这是观测清单，不是自动强制依赖校验。
- `GENERATE_PROTO=false`，直接使用已有生成 Java；本轮没有伪造缺失的旧 `.proto` 输入。
- 无正式账号、游戏资源或网络样本；`FIXTURE_UID` 与 `SYNTHETIC_TOKEN` 均为合成字符串。

## 在独立工作树运行

先由操作者进入隔离的本分支检出，并让 `JAVA_HOME` 指向已安装的 Java 21。以下 PowerShell 命令在原生 Windows 实测；独立缓存自动放系统临时目录，不改变全局代理或其他项目。

```powershell
$env:GRADLE_USER_HOME = Join-Path ([IO.Path]::GetTempPath()) ('lunarcore-s0-' + [Guid]::NewGuid().ToString('N'))
$env:GENERATE_PROTO = 'false'
.\gradlew.bat --no-daemon --console=plain --max-workers=4 clean jar check
```

只跑最小回归（使用同一检出和缓存）：

```powershell
.\gradlew.bat --no-daemon --console=plain --max-workers=4 s0BaselineTest
```

成功标记为 `S0_BASELINE_TESTS_PASSED=10`，`check` 已依赖此任务，普通构建验收不会静默跳过它。

故意翻转黄金帧 magic 的负面控制必须返回非零退出码：

```powershell
.\gradlew.bat --no-daemon --console=plain --max-workers=4 s0BaselineTest -Ps0NegativeControl=true
```

实测为退出码 1，异常 `AssertionError: frame magic 不符`。不要把此命令加入要求全绿的正常流水线。

## 覆盖范围与边界

| 检查 | 实际对象与期望 |
|---|---|
| 黄金样本解码 | 真实 `PlayerGetTokenCsReq`：字段 2、7、10 分别为账号标识、合成 token、平台值 |
| 编码与 round-trip | 真实生成器按 10→2→7 输出，解析器同样接受 2→7→10 的合法顺序 |
| 未知字段 | 旧生成器丢弃字段 127；这是被固定的旧行为，不是推荐的新协议策略 |
| 截断消息 | 真实 Quickbuf 拒绝截断字符串与截断 varint |
| 真实发包器 | `BasePacket` 的大端 magic、CmdId、headerLength、bodyLength、body 与 tail |
| 空包与 CmdId | 65535 可编码；65536 截断为 0，-1 编码为 65535；不代表上层 `GameSession.send` 允许这些值 |
| 清除 | `clear()` 清空字段存在性并编码为空消息 |

测试内 `requireFrame` 只是发包断言，不是生产接收解析器；不据此声称真实接收路径已经完成边界加固。Protobuf 线格式以字段号及类型为依据，字段输出顺序不是通用规范保证，详见 [官方编码说明](https://protobuf.dev/programming-guides/encoding/)。

## 已观察的工程结论

两个独立干净检出均实际执行 `clean jar` 成功。两份 JAR 各有 20,724 个非目录 ZIP 条目，条目内容摘要相同；原始字节不同，统一 ZIP DOS 时间戳后字节摘要一致。结论是内容级可重复构建，不是原始 JAR 字节完全可复现。新增测试后的 `clean jar check` 亦通过。

原生 Linux 尚未验证；Windows 的结果不代替 Linux。依赖已弃用特性警告保留，不在 S0 批量升级插件。`kcp.jar` 的可重建来源与旧 schema 缺口也保留，不伪称供应链已全闭合。

## 隔离与恢复

本阶段只在开发分支及独立副本实施。后续运行服务器必须另用专用配置目录、测试存档和测试账号；本测试不要求任何真实存档，也不读取安装客户端缓存。不要在正式客户端目录运行构建或放置参考项目附带的 launcher/DLL。

用户主工作树保留旧提交；本分支只包含 `build.gradle`、本测试类和本阶段说明/依赖清单。回退时应在新的开发分支上逆向应用本次提交，而非重置已有用户改动；本轮交付另附已实测的隔离副本回滚脚本与补丁。
