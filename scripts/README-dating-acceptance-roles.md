# 异常跟进验收角色初始化

日期：2026-10-16。仅用于当前本地异常跟进验收的最小权限准备，不是完整运营岗位授权方案。默认只准备角色；用户后续明确要求创建账号时，使用末节的独立账号模式。

## 权限方案

| Profile | 角色名 | 角色标识 | 功能权限 |
| --- | --- | --- | --- |
| Owner | 验收-红娘跟进 | `dating_accept_owner` | `dating:match:list`、`dating:match:query`、`dating:exception:handle` |
| Supervisor | 验收-主管跟进 | `dating_accept_supervisor` | Owner 的三个权限及 `dating:service:all` |
| ReadOnly | 验收-只读配对 | `dating_accept_readonly` | `dating:match:list`、`dating:match:query` |
| Denied | 验收-无婚恋权限 | `dating_accept_denied` | 无 |

前三类同时关联“婚恋服务”父目录和“配对服务”页面，保证可以从配对列表进入隐藏的 `/dating/recovery/index`。无权限角色不关联业务菜单。

- 四个角色共 12 条菜单授权，启用状态；通用数据范围为“仅本人”（`data_scope=5`）。婚恋配对范围实际由当前负责人及 `dating:service:all` 校验，不以部门范围替代。
- 主管仅增加婚恋服务跨归属范围，不增加 `dating:billing:all`、`dating:recovery:query` 或系统管理权限。
- 不授予开单、收款、档案编辑、分配转交、创建推荐、登记事实、安排见面、重试扣次、结案、导出和通配权限。
- “红娘跟进”是本阶段验收角色，不是具备完整红娘工作权限的运营角色。
- 不绑定已有账号，不创建新账号、不生成密码，不修改 `admin`、`common`。

## 执行

后端目录先预检：

```powershell
$deployment = 'D:\后台项目\deployment-records\dating-followup-20261016_152853_731\deployment.json'
.\scripts\Initialize-DatingAcceptanceRoles.ps1 -Deployment $deployment
```

核对计划的四个角色和 12 条授权，再明确执行：

```powershell
.\scripts\Initialize-DatingAcceptanceRoles.ps1 -Deployment $deployment -Apply
```

以后更换后端发布版本时应使用对应的发布记录，不用旧记录绕过校验。初始化脚本核对正在运行的后端、监听地址、发布包哈希及包内/源码数据源配置；只允许本地 `127.0.0.1:3306/ry-vue`，连接凭证不出现在命令参数或报告中。

脚本不执行数据库 DDL，不在应用启动时自动运行，不需要服务重启。它通过独立 JDBC 连接写入角色配置，不产生应用层登录/操作审计；审计证据保存于受限本地目录，不应将其视作正常业务账号的操作记录。

## 防护及重复执行

1. 精确核对页面、父目录、所需权限的唯一性、层级和启用状态；父目录不能隐含额外权限。
2. 只接受全部不存在，或全部符合工具标记及完整权限方案的四个角色。重名、重标识、部分存在、停用、软删除、权限漂移、部门授权或悬空角色关联均停止，不自动覆盖/修复。
3. 写入前锁定角色及其关联、菜单行，在受限目录保存五张授权表的完整行快照并回读验证。目录仅当前用户、SYSTEM、Administrators 可访问。
4. 四个角色和菜单授权同一事务新增；验证新配置并逐行比较所有原授权数据。任何校验或 SQL 失败均回滚，不留下半套角色。
5. 完全相同的重复执行返回 `ALREADY_PRESENT`，不修改角色、已有绑定和授权；即使角色已被绑定账号，也不会重复添加或移除绑定。
6. 不存在的角色 ID 若被关联表引用，停止初始化，避免新增角色意外激活旧的悬空账号关联。

证据目录包含 `authorization-before.json`、`authorization-after.json`、`result.json` 和执行工具副本。预检只保存计划报告；实际写入才备份授权表。文件含权限及关联信息，不提交仓库或公开。

角色新增后的回退应通过管理员检查并停用本批验收角色；先确认是否已有用户绑定。不要整体覆盖授权表备份，也不要误删同期其他授权。备份仅涵盖五张授权表，不是全业务库或附件备份，未执行业务环境恢复演练。

## 隔离验证

```powershell
.\scripts\Test-DatingMySql.ps1 -IncludeWorkflowHttp -VerifyAcceptanceRoles
```

仅使用脚本新建的临时 MySQL 3307，并校验端口和数据目录。专门检查新增、重复执行、精确权限、原角色/账号保留、漂移拒绝、菜单异常、悬空关联、备份失败及中途 SQL 故障事务回滚，然后继续现有后端回归。不会连接业务库 3306 生成测试数据。

## 账号及实际验收

由管理员通过“系统管理 → 用户管理”创建独立验收账号，或明确选择授权的专用账号，每个账号仅绑定对应验收角色；不要叠加管理员、普通角色或其他运营角色来凑权限。密码由操作者在本地管理，不放入聊天、命令历史或文档。

新账号正常登录后，按工作区根目录的 `婚恋项目真实角色验收清单.md` 执行只读检查。角色配置完成不代表登录、界面、接口或业务流程已验收。

无任务样本时只能验证身份、菜单和列表访问。跨归属及认领/更新/释放需要经过确认的隔离测试数据；不在业务库制造真实收款、扣次或故障来补齐样本。

## 明确授权后的本地账号创建

用户明确要求代建账号时，可以使用本地初始化入口，不需要获取或读取现有管理员会话：

```powershell
.\scripts\Initialize-DatingAcceptanceRoles.ps1 -Deployment $deployment -CreateAccounts
.\scripts\Initialize-DatingAcceptanceRoles.ps1 -Deployment $deployment -CreateAccounts -Apply
```

该模式要求上述四个角色已经存在且权限完全匹配；创建 `accept_owner`、`accept_supervisor`、`accept_readonly`、`accept_denied`，每个仅绑定同名场景的验收角色。账号启用，不指定部门/岗位，不赋予额外权限。原有账号、密码、角色和授权不变。

密码各自使用密码学随机数生成，长度 20，兼容项目当前登录长度上限，包含大小写、数字和符号。数据库使用项目相同的 BCrypt 算法；明文仅在初始化进程内存和子进程标准输入中传递，不写入命令参数或普通文档。写库前使用当前 Windows 用户的 DPAPI 将四个 PSCredential 保存到受限目录的 `credentials.clixml` 并回读验证。

数据库写入在单个事务内完成，受限快照包含原账号密码摘要，仅供核对，不可公开。拒绝同名（包括数据库排序规则下等价名）账号、部分创建、额外绑定、停用、身份标记不符或悬空用户关联，不自动覆盖、恢复启用或重置密码。完整匹配的重复执行只报告已有账号，不生成新密码。失败后先检查报告和快照，不直接重试或删除账号。

成功报告为同目录的 `accounts-result.json`，只包含账号名、ID 和角色 ID。独立 JDBC 初始化不会产生应用层用户管理操作审计；不能称为已验证正常新增用户接口。无需重启服务，不修改验证码或结案开关，不绕过登录验收。

操作者在同一台电脑、同一 Windows 用户下运行以下命令查看密码（将路径换成成功创建时的实际凭据路径）：

```powershell
.\scripts\Show-DatingAcceptanceCredentials.ps1 -Vault '实际受限目录\credentials.clixml'
```

输入 `SHOW` 后仅在本地终端显示；不要录屏、开启 PowerShell 转录或把输出发到聊天。该文件不是跨机器可用的明文密码备份。账号密码经正常用户管理修改后，文件中的原密码即失效；验收结束后应停用账号或重置密码。

`-VerifyAcceptanceRoles` 隔离验证同时包含账号创建专项：随机密码策略、原账号保留、BCrypt 校验、准确角色绑定、备份失败、中途 SQL 回滚、同名冲突、重复执行不重置及授权漂移拒绝。只连接临时 3307，不在业务库生成测试客户或任务。
