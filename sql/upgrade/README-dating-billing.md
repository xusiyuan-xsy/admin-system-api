# 婚恋收费开通模块部署

## 本次范围

套餐配置 -> 创建待收款订单 -> 人工确认足额收款 -> 自动开通服务权益。

- 套餐支持单选/多选，快照中的判断方式固定为 ALL。
- 本阶段仅支持不限时、正整数次数、正金额套餐。旧套餐需人工完善服务规则后才允许销售，不自动改写旧套餐或推断历史收款。
- 收款确认、收款凭证、权益、OPEN 次数流水、客户 SERVING 状态在同一事务提交。
- 同一客户只允许一张待收款订单、一份服务中权益；相同请求标识重试不重复创建订单；重复确认相同收款不重复开通。
- 金额必须与订单完全相同。收款方式 + 凭证号唯一；现金须填写独立收据编号。不同信息的重复确认会被拒绝。
- 普通账号按客户当前负责红娘访问客户、订单和权益；未分配客户回退到客户 `create_by`。订单创建人字段仅保留审计含义，转交后原负责人不再因创建过订单而拥有访问权限。管理员或 `dating:billing:all` 权限可跨账号操作。
- 套餐/选项停用、改名、改价不改变已创建订单快照；未收款订单可取消，已收款订单不可通过本模块删除、取消或修改收款。

## 升级步骤

地区基础数据是客户档案的独立依赖：新环境或缺少 `base_administrative_division` 的环境，还需备份并执行 `sql/china_administrative_division.sql`，不能只执行以下 `dating_*` 迁移。详见 [地区初始化说明](README-regions.md)。

1. 备份数据库，在预发布环境确认 MySQL >= 8.0.16，现有 `sys_paying_user`、`sys_menu` 已存在。若从未创建 `dating_package`，先执行 `20261013_dating_prerequisites.sql`，它只补建缺失套餐表，不删除数据、不插入示例套餐；已有同名表仍需核对字段。
2. 在实际业务数据库执行 `20261013_dating_billing.sql`。脚本不包含 DROP；DDL 在 MySQL 中会自动提交，不是可整体回滚的迁移。正式执行前检查同名表/字段是否曾被其他分支创建，IF NOT EXISTS 不会修复不一致结构。
3. 当前代码已经接入第三阶段，还必须依次执行 `20261013_dating_workflow.sql`、`20261013_dating_success.sql`，最后执行 `20261013_dating_comments.sql` 补齐中文表注释和字段注释。注释脚本仅适用于完成三阶段迁移且结构一致的库，可重复执行。参见 [第二阶段部署说明](README-dating-workflow.md) 和 [第三阶段部署说明](README-dating-success.md)，然后部署新后端、新前端并重启。即使结案开关关闭，也须先完成三阶段迁移再启动新版本。不要在真实库重新运行旧的 association.sql / matchmaking_profile.sql，它们包含删表。
4. 超级管理员重新登录，检查“婚恋服务”菜单。原套餐菜单存在时保留原位置，不再添加重复入口。
5. 在角色管理中按职责授权菜单及按钮。收款权限与创建订单权限分离；跨账号权限仅授予审核后的管理角色。需编辑套餐的账号同时授予套餐查询权限，需查看订单的账号授予订单详情权限。
6. 打开原套餐管理页面，设置固定次数、不限时、单选/多选及服务选项；保存后创建测试订单，确认真实测试收款后核对权益和流水。

## 页面和接口

| 页面 | 默认路径 | 源码 |
| --- | --- | --- |
| 服务选项 | /dating/serviceOptions | src/views/dating/serviceOptions/index.vue |
| 套餐配置 | 保留原路径；首次创建为 /dating/packages | src/views/system/datingPackage/index.vue |
| 服务订单 | /dating/orders | src/views/dating/orders/index.vue |
| 服务权益 | /dating/entitlements | src/views/dating/entitlements/index.vue |

接口前缀 `/dating`：service-options、customers、packages、orders、orders/{id}、
orders/{id}/confirm-payment、orders/{id}/cancel、entitlements。
套餐接口保留 `/system/datingPackage`。列表采用原项目 pageNum/pageSize 分页规则。

注意：现有前端字典约定套餐状态 `0=启用，1=停用`，本次遵循该约定，未按旧 SQL 注释反转历史数据。
服务状态新增 `NOT_STARTED`、`SERVING`，为后续 `NOT_RENEWED`、`SUCCESS_CLOSED` 预留。
权益状态预留 `ACTIVE`、`EXHAUSTED`、`SUCCESS_CLOSED`。

## 后续模块边界

第一阶段只覆盖收费开通；当前第二阶段已增加人工配对、服务事实核实、独立扣次和耗尽未续单，详见 [第二阶段部署说明](README-dating-workflow.md)。
第二阶段迁移已调整原 OPEN 流水唯一索引，增加配对/用户扣次唯一约束，不能跳过该迁移。
第三阶段已实现成功结案，保留历史剩余次数、停止服务并计入成功案例；默认关闭，业务核实标准确认后才开启。退款、在线支付或限时权益尚未实现。

## 本地验证与联调

- 后端：在后端根目录执行 `mvn -pl ruoyi-admin -am test`。新增 11 项 H2/MyBatis/Spring 事务集成测试，覆盖并发创建/收款、重复提交、金额校验、权限范围、快照、失败回滚和套餐版本冲突。
- 前端：在前端根目录执行 `npm run build:prod`。
- 页面回归：先启动 Vite，再执行 `node tests/dating-billing.browser.mjs`。需本机 Chrome 和 Playwright；可用 `npm install --no-save --package-lock=false playwright` 安装测试工具。该测试拦截 API，仅验证页面交互，不连接真实收款接口。默认地址为 `http://127.0.0.1:5173`，可用 `DATING_UI_URL` 覆盖。
- 迁移验证：已在隔离的 MySQL 8.0.45 空库加载仓库基线后，连续执行升级脚本两次，确认无重复菜单/选项；未执行到实际业务库。
- 本机 8080 当前为其他项目，不是本项目后端。联调前启动本项目后端及其数据库、Redis，使用可用端口，例如 8081，并在前端 `.env.development.local` 设置 `VITE_API_TARGET=http://127.0.0.1:8081` 后重启 Vite。未设置时保持原有 8080 默认值。
- 当前验证不等同于实际业务环境端到端联调；正式使用前必须执行升级、部署后端、授权，并完成一次实际环境验收。
