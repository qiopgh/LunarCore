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


## Echium最小候选与首次真实窗口（2026-09-20）

本节补充上述历史边界，不把HTTP成功写成登录成功。固定正式构建与既有LunarCore生产JAR不变；只对固定Echium副本应用`tools/echium-login/echium-login.patch`，不替换主线、不新增启动器或密码学框架。

### 必要差异和复现

- `cfg.patch_rsa=false`、`cfg.patch_censorship=false`、`cfg.sdk_url=http://127.0.0.1:21001`：配置缺失、损坏或缺字段时，继续关闭两项无关修改并经过MITM。
- `patched_apn_alloc`的HTTPS域名分支增加`.hoyoverse.com`，与正式海外客户端SDK入口匹配；保留原字符串分配、路径和查询参数处理。
- `sources.json`固定两个上游提交和Odin便携包摘要。原`deps.ps1`与`build.ps1`实跑通过；未修改全局PATH。原生Linux未验证。

复现使用一次性目录：把两个测试目录与补丁复制过去，再将固定提交的Echium源码副本放在同级`echium`目录。先在该副本运行上游`deps.ps1`，用`git apply --unidiff-zero`应用固定版本的零上下文补丁后运行原`build.ps1`；不要把第三方源码、编译器或游戏文件纳入仓库。以下命令在该一次性目录执行，Odin仅加入当前进程PATH：

```powershell
odin build ./config-check -vet -strict-style -o:speed -out:config-check.exe
odin build ./apn-check -vet -strict-style -o:speed -out:apn-check.exe
./config-check.exe
./apn-check.exe
```

当前目录没有`Echium.json`时，两项候选测试都退出0。配置测试直接调用原`cfg.load`；缺失、非法JSON、只有`log_level`三种输入的基线/修改/独立恢复源码并重新编译结果均为2/0/2，显式完整配置为0/0/0。APN测试直接调用原`patched_apn_alloc`并仅替换其接收回调；国内域、海外域、非目标域三个合成URL的整组结果为2/0/2。测试不会启动游戏或联网，不模拟配置解码或另写封包。

### 实际观察与失败保留

一次受控窗口中，Echium报告地址、Unity URL和APN共三个接入点安装成功；MITM记录23条HTTP响应，其中2条是启动前合成预检、21条来自真实客户端。客户端访问了dispatch、SDK配置与combo登录路径，但未观察到客户端gateway、UDP/KCP或登录完成响应。HTTP 200不代表SDK业务返回码为0，本轮未读取请求正文、认证头或官方凭据。

管理员Python已真实尝试截图，但因`FOREGROUND_NOT_TARGET`拒绝；随后原窗口调整与游戏关闭均遇到`INTERACTIVE_DESKTOP_UNAVAILABLE`。没有成功截图或发送点击，不绕过桌面限制。关闭游戏后的无输入提升检查仍返回同一错误；当前只能确认交互桌面不可访问，不能仅凭错误断定是锁屏、会话切换或其他具体原因。

原采样器三项观察点已卸载、session已detach、RPC线程已停止；其原退出码5和`TARGET_WINDOW_UNRESPONSIVE:before_stop`保留。正常关闭未完成，随后用原精确身份清理脚本核对本轮PID、创建时间和路径后终止，实测退出0、游戏进程归零。临时新增DLL/配置已按摘要撤回，MITM退出0，服务端经原stop退出1000，端口释放；系统代理、代理环境及信任证书快照一致。

下一步仅在交互桌面恢复可用后，复用上述产物和MITM窗口，以简短管理员Python截图确认界面，再执行必要的本地合成账号登录动作。当前Goal仍仅登录且未完成，不重复全量回归，不推进剧情或战斗。

## SDK原生URL分派与无人值守登录结果（2026-09-20）

交互桌面恢复后，已继续使用同一正式构建、同一回环MITM、同一管理员Python控制器和同一隔离账号输入；没有修改系统代理、hosts、证书或保护设置。当前正式补丁新增的生产差异仅覆盖两个固定SDK模块的URL字符串赋值点：

- `HoYoNetworkSDK.dll`固定摘要为`5dab06df...30d206`，唯一调用模式位于RVA `0x310e`，原字符串赋值目标为`0x1d60`。
- `HoYoSDKNetworkFallback.dll`固定摘要为`31327b52...cd5d96`，唯一调用模式位于RVA `0x8a41`，原字符串赋值目标为`0xc630`。
- 两个hook都复用原目标函数复制URL字节，只把`.hoyoverse.com`、`.mihoyo.com`、`.bhsr.com`和`.starrails.com`的authority替换成`cfg.sdk_url`，路径、查询和片段原样保留；非目标域、带userinfo的URL和非HTTP(S)输入不改。
- URL诊断只记录来源、scheme和host；不记录路径、查询、认证头、正文、账号、密码或票据。

`tools/echium-login/sdk-constructor-bindings.json`使用相对客户端路径，不包含主机私有映射。可在一次性工作目录中运行：

```powershell
python tools/echium-login/run_sdk_native_check.py `
  --source <应用补丁后的echium目录> `
  --bindings tools/echium-login/sdk-constructor-bindings.json `
  --client-root <固定正式客户端根目录> `
  --odin <固定Odin可执行文件> `
  --work <一次性输出目录>
```

固定DLL哈希、唯一模式和rel32目标共2项通过；两个真实patched回调对10组正反URL共20次均匹配。相同入口的基线、修改和独立回滚结果为`2/0/2`；回滚副本的三个原文件哈希等于上游固定提交，新增`patches/sdk_native.odin`不存在，active候选随后保持修改态。最终原`build.ps1`、配置检查和APN三例检查均退出0。

真实窗口中，两个hook都报告`SDK_URL_ASSIGN_READY`，证明固定模式在目标进程中可安装；验证码出现前没有观察到两个原生分派点的URL调用。管理员脚本已实际完成目标窗口截图、“登录其他账号”点击、账号和密码逐字段合成输入以及“进入游戏”点击；截图确认账号为`SLICE_FIXTURE`、密码为9个掩码字符。提交后客户端出现滑块验证码，未拖动、未关闭或绕过，也未把验证码页面当作登录成功。

该提交窗口经MITM观察到19条HTTP响应元数据，但`loginByPassword`、`/account/risky/api/check`、gateway和UDP事件均为0。曾离线定位`ZFEmbedWeb.dll`的CEF请求回调并构建受限路径候选，但在目标StarRail进程中等待120秒仍未加载该模块，也没有对应子进程；该错误路线已从正式补丁撤销，仅保留私有分析证据。没有通过修改`disable_mmt`或其他风险配置制造通过。

后续真实窗口确认验证码来自`AccountPlatNative.dll`的生产认证链路。该DLL在全局构造阶段建立`passport_env` URL表；仅把APN字符串Hook提前到等待`GameAssembly`之前仍不充分，因为`LoadLibrary`返回时全局构造已经结束，提前Hook窗口也没有产生APN URL事件。最终候选继续保留提前载入顺序，并在固定DLL中唯一绑定运行时endpoint查找函数`0x37aed0`：原函数返回配置URL后，候选读取其MSVC `std::string`输出，仅对既有允许域替换authority并沿原字符串赋值函数写回，因此不依赖全局构造时机。LunarCore只把现有`AppLoginHandler`复用到`/account/ma-cn-passport/app/loginByPassword`，不新增认证、启动、封包或密码学框架。隔离回归使用合成账号，正式账号和凭据不作为测试输入；真实客户端完成状态仍以MITM观察到该路径并进入本地会话为准。

此前各个已完成窗口结束后，观察器均完成hook卸载、session detach和RPC线程停止，客户端正常退出0；临时DLL、配置和日志撤回，服务端沿原`stop`退出1000，MITM退出0，五个相关端口释放。固定客户端文件哈希未变；系统代理、代理环境和信任证书快照在规范化JSON后完全相同。

APN提前Hook但尚未加入endpoint返回值Hook的窗口记录到14条本地MITM HTTP响应，安装顺序成立，但运行会话为远程会话且没有前台窗口，原截图安全门拒绝输入；因此未触发密码登录、gateway或UDP。观察器、客户端、MITM、服务端与端口已清理，系统网络快照相同；本轮sidecar因终端策略阻止删除而保留，未换解释器绕过。当前仅登录Goal仍未完成。下一次只在交互桌面恢复且存在可验证前台窗口后部署包含endpoint返回值Hook的新DLL并复用同一合成账号，继续观察MITM认证路径、gateway/UDP、Cmd19/Cmd13、`7503→36`和首个稳定登录后界面；不使用正式账号，也不把HTTP、进程存活或合成测试提升为真实本地登录通过。
