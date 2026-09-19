# LoginFinish 的最小初始化候选

## 结果与范围

在 `0b03d791cf834d203b05e62a53d57c2fb072698e` 的有限候选上，只补一处发送时序：候选模式的 `HandlerPlayerLoginFinishCsReq.handle` 先发送 `ContentPackageSyncDataScNotify(7503)`，再沿原 handler 发送 `PlayerLoginFinishScRsp(36)`。关闭候选模式时，旧路径不变。

这不是正式客户端已验证的必需通知。固定正式版的已有样本来自用户“先注销、再登录”，只观测到 Token/Login 两个响应候选，没有覆盖 LoginFinish、注销协议或缓存清理。本补丁只把已经存在的消息投影接到固定参考的候选调用位置，不提升真实客户端接入或战斗验收状态。

## 为什么只补这一条

- 固定参考为 Himeko-Nova-SR `3b304b5a1b1cc0d478e285c3dcb0d4a555ed0910`，版本边界仍为其自述的 4.5 beta。
- `gameserver/src/services/login.zig:onPlayerLoginFinish` 明确先发送内容同步通知，再发送完成响应。
- 当前候选已经有 `ContentPackageSyncDataScNotify=7503`、消息字段 `data=3` 以及依赖定义，不需要新增编号、重建 schema 或手改生成文件。
- 改动前真实执行原收包/分发/handler 链只得到 `[36]`，说明存在“有定义但没有接到发送路径”的局部缺口。
- 通知正文直接复用 `PacketContentPackageGetDataScRsp` 的原资源构造器，经现成 `Release450Candidate` 投影转换；不照抄参考中的硬编码资源 ID，也不修改资源表或账号进度。
- 原分发器先执行会话状态检查，候选收包入口先执行回环边界与 Protobuf 解析。初始化仍在这些检查之后，不另造状态检查或传输实现。

原登录阶段的七次未映射通知、LoginFinish 后档案响应的缺口仍保留；没有将它们批量猜测补齐。

## 本地认证与会话边界

原 Username/Token/Combo SDK 路径保留，先前本地 SDK 检查的 `0/0/0/-201` 结果不等于当前正式 SDK 已兼容。候选游戏请求仍显式绑定 `candidate450.localAccountUid` 对应的隔离本地账号，不消费未知的正式认证字符串。

`AppLoginHandler` 原实现中尚有请求解密未实现的说明；本轮没有将其改写为支持加密请求，也没有引入密码学框架。固定 Himeko 的 Token handler 只填写 UID 和成功码，不能由此推断正式客户端不需要其他会话字段。参考的 TCP 会话也不能替代本项目原 KCP 的验证，当前封包和传输继续不变。

候选默认关闭，启用仍受已有配置与回环限制约束。`ACTIVE` 是服务端原状态，不作为客户端已收到初始化的证明。

## 执行相关回归

先沿项目既有记录设置当前可用的 `JAVA_HOME`、`GRADLE_USER_HOME`，不要因为默认缓存缺依赖而重新选择工具或重装库。在仓库根执行：

```powershell
$env:GENERATE_PROTO = 'false'
./gradlew.bat --offline --no-daemon --console=plain --max-workers=4 candidate450Test jar
```

`Candidate450Regression` 调用新增的 `LoginFinishInitializationRegression.verify()`。本轮实测总计 68 项通过，其中新增 13 项检查覆盖：通知/应答顺序、单条合成内容资源、重复请求、资源不变、三个不允许的会话状态、异常正文、非回环拒绝、关闭候选时旧发送调用不变。

测试沿用原 BasePacket、GameSession、GameServerPacketHandler、Quickbuf 与真实 LoginFinish handler，仅替换最终网络传输为记录端，并临时设置合成资源表；退出时恢复表内容和配置。未启动 HTTP、数据库、游戏服务或客户端。无传输对象时不会把旧缓存发送调用伪称为实际发出帧。

同输入独立探针的目标行为为：基线 `[36]`、候选 `[7503, 36]`、恢复原源码后再次 `[36]`。完整的命令、退出码、逐字输出、源码补丁与独立回滚由本任务私有四角色交付保存。已有 17 项登录样本对照仅将 LoginFinish 的预期发送顺序更新为本补丁行为，再次通过；两个返回码字段、未映射通知和认证边界没有被改写。

## 后续

下一阶段先核对现成客户端重定向组件、回环 SDK/网关和隔离账号是否形成一致的本地接入配置；准备好之前不启动游戏。真正的正式客户端验收需要单独观察这条最小路径，不能以本地测试、源代码顺序或进程存活替代。

原生 Linux 未新增验证；本轮 Windows 上的 Git sh 回滚不代表原生 Linux 测试。
