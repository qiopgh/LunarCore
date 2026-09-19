# 仅登录阶段的接入预检

当前阶段止于真实客户端完成本地登录，不包含固定角色战斗闭环。已有生产候选仍以 `95a5c9a` 的最小初始化实现为基线；本次只扩展原测试入口，不替换启动器、SDK、封包器或密码学实现。

## 复用原资源测试入口

在已有 Java、Gradle 缓存与固定资源准备好的环境中执行：

```powershell
./gradlew.bat --offline --no-daemon --console=plain --max-workers=4 candidate450ResourceSmoke -Pcandidate450Resources="$env:LUNARCORE_RESOURCE_DIR" -Pcandidate450LoginOnly=true
```

`LUNARCORE_RESOURCE_DIR` 必须指向已有、来源固定的外部资源目录；测试不下载资源。原生 Linux 可使用仓库的 `./gradlew`，但本次未在原生 Linux 执行。

`candidate450LoginOnly=true` 让原 `Candidate450ResourceSmoke` 执行以下有限链路后退出：

- 回环 `query_dispatch`、`query_gateway`，使用现有候选定义解码。
- 原 SDK 与 AppLogin 对同一隔离账号的响应，并核对候选会话账号一致。
- 原 token、login、LoginFinish 处理器；核对内容同步 `7503` 先于完成响应 `36`，内容列表来自已加载资源。
- 确认请求轨迹只有三项登录请求，随后沿原断开路径清理 HTTP、数据库、监视器和计时器。

未设置该属性时保留原完整资源测试行为；仅登录阶段必须显式传入 `true`，不运行旧战斗链。

## 配置一致性与边界

`loginOptions.accountName`、本地 SDK 实际账号和 `candidate450.localAccountUid` 必须对应同一隔离账号。仅设置候选 UID 不会自动改变 AppLogin 的账号选择。

现成 Echium 的 `sdk_url` 必须与 LunarCore 回环 HTTP 地址一致。上游配置省略 `patch_rsa`、`patch_censorship` 时会继承源码中的启用默认值，不能把缺少字段理解为关闭；本阶段候选配置显式关闭两项无关修改。该配置尚不表示 Echium 的入口签名、装载方式或正式客户端已验证，不直接复制 DLL 到游戏目录。

这些测试使用本地明文合成 SDK 输入，不验证 AppLogin 的正式加密输入。原资源测试没有 KCP 监听，也未运行正式客户端；通过后仍须完成客户端版本绑定、实际传输与登录画面验收。`-dispatch` 模式只提供 SDK/dispatch，不提供 `query_gateway`，不能据此把 404 当成完整服务端没有 gateway 实现。

## 本次验证结果

原生 Windows 上，Gradle 原入口和同输入独立 Java 调用均通过 14 项仅登录检查；LoginFinish 实际输出顺序为 `[7503,36]`，内容同步覆盖 46 项已加载资源，请求轨迹仅为 token、login、LoginFinish。独立恢复原测试源码并重新编译后，旧入口再次拒绝 `--login-only`，与修改前一致。

隔离配置的真实 SDK HTTP 检查返回码为 `[0,0,0,-201,0]`；修改前两条登录路径指向不同账号，修改后与候选 UID 一致，独立回滚后重新观察到不一致。三轮原服务端都通过 `stop` 正常退出，退出码为原实现的 `1000`，监听端口释放。原配置、测试数据库、上游 Echium 和生产 JAR 字节不变。

资源加载中已有的缺失组警告、主动清理监视器时的 `ClosedWatchServiceException` 均保留在私有原始记录中；这些结果不扩展为完整场景或正式客户端验收。

## MITM与原KCP库的登录预检

原入口可追加 `-Pcandidate450Mitm=true`，必须同时保留 `-Pcandidate450LoginOnly=true`。对应Java参数为资源目录后追加 `--login-only --mitm`。本模式仅连接回环地址，需要先准备现有mitmdump的两个反向代理入口：

- HTTP入口`127.0.0.1:21001`转发至原SDK后端`127.0.0.1:21000`。
- UDP入口`127.0.0.1:12907`转发至原游戏后端`127.0.0.1:12906`，gateway公开的是MITM入口端口。

测试调用仓库原`lib/kcp.jar`中的`KcpClient`、`KcpServer`与`Ukcp`，握手、分片、确认、发送和重组均由原库完成。应用封包和收帧继续使用`Release450Candidate`、`BasePacket`与`GameSession`；没有新增协议、密码学或启动框架。原库`connect`返回握手占位对象，测试使用`onConnected`回调提供的实际会话。

本次原生Windows实测17项通过，MITM观察到4条HTTP响应和11条UDP元数据，两侧端口与服务端记录关联一致；token、login、LoginFinish真实经过UDP往返，仍先收到7503再收到36，内容列表46项。系统代理、代理环境及信任证书清单前后一致。

保留首次失败：本地`close()`发出的20字节控制包经过MITM，但5秒内远端会话没有注销。后续按原配置的30秒超时等待，实测约30秒后原服务端进入INACTIVE并注销玩家。这里验证的是有界超时清理，不宣称控制包能立即断开；没有修改传输库来制造通过。真实客户端窗口结束还需验证自身关闭与采集退出，不能由本预检替代。

用户离开电脑后的真实窗口操作复用项目现有管理员Python控制器，仅补目标窗口截图动作。每次先核对新鲜截图与身份，再执行必要点击；不另搭桌面自动化框架，不推进剧情或战斗。正常RunAs提升已通过无副作用探针，但真实游戏截图与点击仍待客户端接入准备完成后验证。
