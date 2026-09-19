# 4.5 正式构建的最小链候选适配

## 结论与边界

本批基于已经验证的 `6896dbdcf8308b74276ffe4c3f198178bc087120`，复用 LunarCore 原有 Java、Gradle、protoc、Quickbuf、BasePacket、会话分发和业务处理器。新增的是默认关闭的有限候选映射，不是新的服务端、封包器或密码学框架。目标构建固定为 `20260813-0422-V4.5Live-16121062-OSPRODWin4.5.0-OSLive`；未修改原 `GameConstants.VERSION` 来伪装兼容。

本地合成请求已经过原业务完成登录、角色/编队、场景、单场茧战斗的创建和结果提交，并在断开后读取相同玩家数据。这里不包含官方客户端运行、真实 KCP 连接、客户端战斗计算、正式认证或官方会话解密。HTTP 200、合成结果成功及数据库读取不等于正式客户端验收。

## 来源与可复现输入

- 主线：[qiopgh/LunarCore](https://github.com/qiopgh/LunarCore)，原作者保留为 [Melledy/LunarCore](https://github.com/Melledy/LunarCore)。
- 参考：[Himeko-Nova-SR](https://git.xeondev.com/HonkaiSlopRail/himeko-nova-sr)，`master@3b304b5a1b1cc0d478e285c3dcb0d4a555ed0910`。
- 参考 `protocol/src/protocol.pb.zig` SHA-256：`87f92065b507d339d6f202da59c8b10af111ce64dd4d139c8f5dbfcd1dae4cb2`。
- 参考为 4.5 beta 边界，不能将全部字段或编号提升为固定正式版证据；保留上游声明，不改写为已获得额外授权。
- 固定资源检出：`turnbasedgamedata@8dc7843723cf6f2d6acafee0b3fb152c90994208`；资源原件不随源码分发。
- 生成链沿用 protoc 3.24.3、Quickbuf generator 1.3.1、runtime 1.4。手写业务与生成文件分开。

`tools/extract_release450_candidate.py` 只选择最小链的 75 个消息投影和 33 个消息编号；依赖枚举保留所选字段需要的值。字段号、类型和打包属性来自参考描述表，混淆和未选字段记录在 `src/main/resources/release450-candidate/schema.json` 的 `omitted_fields`，不猜值填空。其中 `StartCocoonStageScRsp=1479` 有既有正式构建锚点，其余编号仍为参考候选。错误响应已有的字段 13／值 1 适配保持有效；本批补成功战斗正文的候选字段与嵌套转换。

## 实际调用链与改动

1. 原 HTTP handler 构造 dispatch/gateway → 有限候选投影 → 原 Base64 输出；网关仍使用原 KCP 路线，不实现新传输。
2. 原 `GameSession.onMessage` 检查帧头、无符号编号、头长、正文长度和尾魔数 → `GameServerPacketHandler.handle`。
3. 仅候选模式进入 `Release450Candidate.receive`，用生成类型读取请求、转换到旧业务输入，再经过原状态检查和原 handler。
4. 原 handler、资源、玩家、编队、场景和 `BattleService` 处理状态；原响应经过 `Release450Candidate.outgoing` 投影后，仍交原 `BasePacket.build`。
5. 当前战斗查询复用已发送的战斗正文，避免重复生成随机种子；结果提交校验 battleId、stageId 和当前状态，拒绝错误或重复结果。

具体具名转换包括 `wave→waveCount`、多命途角色/技能树字段、怪物波次/阶段字段、网关资源 URL，以及 64 位 `loginRandom` 回显。请求投影不是完整协议恢复；`GetBasicInfoCsReq` 的空消费投影只表示原业务不读取正文，不声称该消息实际上没有字段。

## 如何启用和验证

候选模式默认关闭。只有显式配置 `candidate450.enabled=true`、`candidate450.localAccountUid`，且 HTTP 和游戏服务的绑定/公布地址均为回环时才允许启用。该账号必须是隔离本地数据库中的测试账号；正式认证字段仍未恢复，不使用官方 token，不开放公网。

仍通过现有 `java -jar LunarCore.jar` 启动，不引入其他启动器。准备客户端联调前，应另行固定现成重定向组件与资源配置；本批测试不执行该动作。诊断使用 `logOptions.sessionDiagnostics=true`，只记会话状态、方向、编号、长度和分类，不记正文、凭据或异常中的业务数据；不要为采样开启旧的完整包日志。

以下命令在仓库根执行。`$ReferenceRoot`、`$ResourceRoot` 分别由操作者设置为已有参考和固定资源目录，不会自动下载资源。

```powershell
$env:GENERATE_PROTO = 'false'
python tools/extract_release450_candidate.py --reference-root "$ReferenceRoot" --check
./gradlew.bat --offline --no-daemon --console=plain --max-workers=4 candidate450Test release450CocoonErrorTest s0BaselineTest jar
./gradlew.bat --offline --no-daemon --console=plain --max-workers=4 candidate450ResourceSmoke "-Pcandidate450Resources=$ResourceRoot"
./gradlew.bat --offline --no-daemon --console=plain --max-workers=4 candidate450Test -Pcandidate450NegativeControl=true
```

最后一条是故意失败的负面控制，预期非零，不计为产品失败。常规离线测试 55 项、原错误响应 8 项、S0 10 项通过。外部资源测试 28 项通过：使用原生产 handler、独立回环内存 MongoDB 和 HTTP，并固定角色 8001、茧 1201、阶段 1043010；不使用真实账号。结果提交后体力减少 40，错 ID 和重复结果不扣体力，断开并重新登录读取相同 UID 与体力。数据库服务重启或落盘恢复未在本批声称通过。

测试退出会关闭 HTTP、MongoClient、内存数据库、计时器和目录监视器；原监视器对主动关闭输出 `ClosedWatchServiceException` 是已观察的清理日志。资源全量载入时，非本切片的部分场景分组缺失警告保留；固定切片依赖有专门断言，不能因此声称所有地图资源完整。Windows 实测；原生 Linux 未实测。继承固定生成器在中文路径上的既有限制，不修改系统代码页或更换生成器规避。

## 未解决事项

- 正式版认证字符串和部分登录通知仍无可靠映射。未知消息会记录 `CANDIDATE_UNMAPPED`，不回退旧格式、不伪造成功；这不证明客户端不需要它们。
- 结算 HP/SP 属性仍混淆。候选暂不向原结算业务传这些未映射属性，保留已有 HP/SP；这不是完整战斗状态适配。
- 开战与结算测试提交的是合成胜利请求，没有模拟或证明实际战斗胜利。
- 75 个投影来自有限参考，不是 75 个正式消息均已恢复。正式线格式、时序和必需通知须以用户配合的真实客户端运行来确认。

## 采样准备与下一步

复用项目已有 `w04_4a_startup.py` 和 `w04_4a_serializer_observer.js`，没有新写启动器。私有采样包保留完整 483 项身份清单，仅选择已具名 `StartCocoonStageScRsp` 的三个入口（一个 WriteTo、两个 MergeFrom），持续 180 秒，事件上限 128，每目标输出上限 16。保留 `STOP.request`、客户端保留、脚本卸载、会话分离、目标存活及文件身份检查。旧 Python 路径失效已记录；通过现有 Python 调用原 Frida 17.18.0 库，未更新观察器或库版本。

已执行原观察器单元测试、窄范围准备函数及 JS 虚拟上下文检查，包含配置越界、过期身份、重复目标、不可执行地址、事件限额、到期停止和部分安装回收。本批没有启动客户端，没有安装真实 Hook。

**观察器现在只记录入口事件，正文读取仍为 0 字节，解密未验证。** `args[1]` 不能未经布局确认当作 byte[]。下一阶段由用户配合正常登录和一次普通茧操作，先验证窄范围命中与可靠分离；命中后针对已确认对象布局准备必要正文样本。官方正常操作与本地候选接入分开，不向官方注入或回放合成请求。

证据关系：原回归输出支持离线映射结论；原资源/业务测试输出支持本地合成闭环结论；观察器静态与模拟检查只支持“采样准备”，不支持真实命中或解密。三类证据不可相互代替。完整命令、逐字输出、源码摘要、补丁重建与独立回滚记录保存在项目私有四角色交付中。
