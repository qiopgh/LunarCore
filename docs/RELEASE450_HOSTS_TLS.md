# 本地 hosts / TLS 登录阶段交付（2026-09-21）

> **当前不是完整客户端兼容性证明。** 指定专项会话在本地合成账号测试中观察到密码接口与 combo 登录接口均返回 HTTP 200，客户端随后呈现用户协议提示。该成功窗口依赖独立 WinHTTP 运行时修复，不能归因于本补丁构建的 DLL 单独生效。尚未观察到该窗口的 `LoginFinish`、网关会话或稳定登录后界面。

## 范围、分工与固定输入

沿用任务 `20260910-s0-s1-01a089ac` 和项目既有 scope，不创建 Goal。主任务只接收专项产物、重构源码补丁、验证包的一致性并交付文档；不逆向、不调试、不启动或操作游戏。所有此类专项操作由用户指定会话完成。

- 客户端固定为 `20260813-0422-V4.5Live-16121062-OSPRODWin4.5.0-OSLive`。
- LunarCore 候选基线为 `700e06d69e314d9445cc13d759821d71d6495a9c`；本次不改 Java、生产 JAR、资源、数据库或存档。
- Echium、il2cure、Odin 的固定来源、提交、许可证线索及哈希见 [sources.json](../tools/echium-login/sources.json)。Echium 原有声明保留，未将未知许可改写为授权。
- 原始证据、DLL、客户端资源、诊断脚本、截图、主机路径和运行配置保留在项目外私有证据目录，不入库。
- 仅使用既有 60 个精确域名和本地合成账号；不读取或使用正式凭据，不改变系统代理或证书，不进行战斗。
- 用户已明确当前是可接受该提示的本地测试，主任务已转交专项继续验收。此确认不代表已经点击、已经登录或同意其他协议；下一次实际结果单独记录。

## 可入库内容

| 文件 | 用途 |
|---|---|
| `tools/echium-login/echium-login.patch` | 从固定 Echium 提交重构的纯文本源码补丁；包含专项交付的 8 个源码文件差异 |
| `tools/echium-login/tls-scope-check/main.odin` | 专项交付的原生合成用例，保留已验证字节 |
| `tools/echium-login/run_tls_scope_check.py` | 合成用例入口，仅用一次性工作目录；不读取游戏文件或加载官方 DLL |
| `tools/echium-login/verify_tls_package.py` | 检查来源哈希、配置、精确域清单、交付边界与可选重构源码 |
| `tools/echium-login/Echium.local-tls.example.json` | 默认 `local_tls_mitm=false` 的示例，不自动部署、不改系统设置 |
| `tools/echium-login/managed-domains.txt` | 已固定的 60 域输入，不是系统 hosts 副本 |
| `tools/echium-login/local-login-observation.json` | 专项 run25 的脱敏观察和证据摘要；明确 observer 与 DLL 的证明边界 |

现有 [登录阶段历史](RELEASE450_LOGIN_ACCESS.md) 保留原始失败和时间边界。来源清单的 `observed_history` 保存旧窗口计数；`observed` 描述本节对应的最新已观察窗口，不把旧的“密码请求为 0”误用为当前结论。

## Evidence → Finding → Path

### E1：精确域与端口的原生三态

专项修改的准确符号为 `patches.apn_tls_url_allowed`。基线错误允许清单内域的显式 `:444`；修改后只允许 HTTPS 隐式 443 或显式 `:443`，并拒绝空 authority、userinfo、HTTP、伪子域和清单外同根子域。同一个 easy handle 转到域外 URL 时恢复 peer/host 校验。

专项使用同一 Odin 合成用例的原生退出状态为 **BASELINE 1 / MODIFIED 0 / ROLLBACK 1**：

- 基线与回滚：`TLS_SCOPE_NATIVE_FAIL failures=2`，错误端口获准，复用句柄保持 `peer=0 host=0`。
- 修改：`TLS_SCOPE_NATIVE_PASS`，7 个 URL 断言符合预期，域外复用时 `peer=1 host=1`。
- 修改源码哈希：`96cc99848c4712909ebebb56bdb84474627adaad3c57c5b2dbf1afdb0416495e`。
- 已运行的 DLL 哈希：`43dcd8a96d9a950f61a467fd608fb6534c5e3afeff943d1c1630ed8321b1194b`。
- 专项已验证独立回滚恢复原始哈希并重新应用修改。主任务没有重新执行客户端相关原生测试，而是核对交付与补丁重构的 8 个源码哈希全部一致。

**Finding：**源码范围限制与回滚有原生证据，但这不证明目标游戏登录成功，也不证明 Windows DLL 可在 Linux 使用。

### E2：真正成功的窗口包含独立 WinHTTP 修复

专项 `formal-login-hosts-443-tls-25` 实际组合为：上述 exact-60 DLL、固定运行配置和独立运行时脚本。专项报告说明真实密码请求经过 HYPass / WinHTTP；不能再假定 APN/libcurl 候选覆盖该链。

交付记录中的精确改动位于 `installProxyPathHooks`，字段为 `WINHTTP_OPTION_IGNORE_CERT_REVOCATION_OFFLINE`（option 155）。这些内容仅记录专项已验证结果，主任务未自行逆向或调试。详细运行时脚本和控制器留在私有证据目录，尚未把它们当作可直接发布的原生 DLL 功能。

- run22 基线：`secure_failure_flags=1`，`async_error=12017`。
- run25 修改：局部选项应用成功，随后本地密码与 combo 登录接口均为 HTTP 200。
- 原始脚本哈希：`3f85b30f21628de1254d37c1aa96f8bf6926b20232889598a6b1e713e8e3e03e`。
- 源结果 JSON 哈希：`968a8042a7aa0115cf375bd91608bec8f2e681f73a10a955cc0ec5ce3ab059df`。

**Finding：**已解决的是该组合下“请求未抵达本地服务”的已观察阻断；不把 HTTP 状态推导为业务或完整会话成功。原生 DLL 的对应工程化与新窗口验证仍由专项负责。

### E3：客户端状态与清理

run25 的 400、1100、2500、15000 ms 截图均记录协议提示，专项在该窗口没有勾选或接受。主任务已核对相关交付、截图和清理记录共 17 个文件的 SHA-256，未执行交付中的启动、观察或清理脚本。

专项清理记录为 `all_started_processes_exited=true`、`system_network_equal=true`，剩余进程、TCP、UDP 均为空；原 sidecar/config 字节恢复。用户的后续确认允许专项在相同本地测试边界处理该提示，但不回写 run25 的历史事实。

**Finding：**运行环境清理成功；该窗口停在协议提示，完整会话未验证。

### P1：工程重构与后续路径

固定来源 → 专项源码/原生三态 → 主任务纯文本补丁重构与包一致性 → 专项独立原生 WinHTTP 候选 → 专项执行用户确认后的本地登录窗口 → 按实际会话与界面判定。

主任务第一次从 Git 导出源码时，仓库换行配置使 archive 产生 CRLF，真实 `git apply` 退出 1；失败目录和输出保留。改用与生成补丁一致的逐命令 `core.autocrlf=false`、`core.eol=lf` 后补丁应用退出 0，8 个源码哈希全部匹配。未修改全局 Git 配置，未放宽补丁匹配来制造通过。

首次仓库 `git diff --check` 又对补丁文件的上下文标记空格报告退出 2。正式交付沿用本项目原有零上下文补丁格式，以 `--unidiff-zero` 应用并重新验证相同源码哈希；没有删除源码空白、关闭空白检查或修改 hooks。

## 安全的离线核验入口

在仓库根目录执行以下命令只读取交付数据，不启动客户端或调试器：

```powershell
python tools/echium-login/verify_tls_package.py
```

若另有已按 `sources.json` 固定提交准备的 Echium 一次性源码目录 `work/echium-source`，先在该目录外应用本补丁，再核对源码。每行独立执行：

```powershell
git -c core.autocrlf=false -c core.eol=lf -C work/echium-source apply --unidiff-zero ../../tools/echium-login/echium-login.patch
python tools/echium-login/verify_tls_package.py --source work/echium-source
```

原生合成用例由专项执行并提供上述三态记录；入口支持 `--source`、`--odin`、`--work`，并拒绝复用已有输出目录。包检查不会代替原生测试，更不会代替真实游戏验收。

## 事务与限制

本轮继续扩展项目外既有 `MODIFIED_FILE.md`、`DIFF_FILE.patch`、`VERIFICATION.txt`、`ROLLBACK.sh` 四角色。纯交付包三态另行记录，不能与 TLS 原生三态混写；hosts 的历史三态为 2/0/2，系统 hosts 当前维持已确认的 60 域托管态。

- 不声称原生 Linux 验证、DLL 二进制可重现构建或完整正式客户端兼容。
- 不将诊断脚本直接发布为生产修复；新原生候选必须拥有自己的来源、回滚和真实测试结果。
- 尚未观察 `LoginFinish`、网关会话与稳定登录后界面时，`real_local_login_verified` 保持 false。
