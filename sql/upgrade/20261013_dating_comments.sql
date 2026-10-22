-- MySQL 8.0.16+. Run AFTER the billing, workflow and success migrations.
-- Repeatable documentation-only migration for the canonical three-phase schema.
-- Back up and verify schema compatibility first. Preserve types, defaults,
-- nullability, generated expressions, indexes and constraints when editing.
SET NAMES utf8mb4;
SET SESSION lock_wait_timeout = 15;

ALTER TABLE dating_package
 COMMENT = '婚恋服务套餐配置，购买后以订单和权益规则快照为准',
 MODIFY COLUMN package_id bigint NOT NULL AUTO_INCREMENT COMMENT '套餐ID',
 MODIFY COLUMN package_name varchar(100) NOT NULL COMMENT '套餐名称',
 MODIFY COLUMN package_type char(1) NOT NULL DEFAULT '0' COMMENT '套餐类型：0基础套餐，1VIP套餐',
 MODIFY COLUMN price decimal(10,2) NOT NULL COMMENT '套餐价格，单位元',
 MODIFY COLUMN limit_expire char(1) NOT NULL DEFAULT '0' COMMENT '是否限制服务天数：0否，1是；当前收费流程仅支持0',
 MODIFY COLUMN service_duration int NOT NULL DEFAULT 0 COMMENT '服务时长，单位天；不限时套餐为0',
 MODIFY COLUMN limit_service_times char(1) NOT NULL DEFAULT '0' COMMENT '是否限制服务次数：0否，1是；当前收费流程要求1',
 MODIFY COLUMN service_times int NOT NULL DEFAULT 0 COMMENT '套餐包含的服务次数，销售时须为正整数',
 MODIFY COLUMN description varchar(500) DEFAULT '' COMMENT '套餐介绍',
 MODIFY COLUMN cover_image varchar(255) DEFAULT '' COMMENT '套餐封面图片地址',
 MODIFY COLUMN sort_order int NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
 MODIFY COLUMN status char(1) NOT NULL DEFAULT '0' COMMENT '套餐状态：0启用，1停用',
 MODIFY COLUMN del_flag char(1) NOT NULL DEFAULT '0' COMMENT '删除标志：0正常，2已删除',
 MODIFY COLUMN create_by varchar(64) NOT NULL DEFAULT '' COMMENT '创建人后台账号',
 MODIFY COLUMN create_time datetime NOT NULL COMMENT '创建时间',
 MODIFY COLUMN update_by varchar(64) NOT NULL DEFAULT '' COMMENT '最后更新人后台账号',
 MODIFY COLUMN update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后更新时间',
 MODIFY COLUMN selection_mode varchar(10) DEFAULT NULL COMMENT '服务选项选择模式：SINGLE单选，MULTI多选；扣次均要求所选条件全部满足',
 MODIFY COLUMN service_option_codes text COMMENT '套餐可选服务选项编码，JSON数组，对应dating_service_option.code',
 MODIFY COLUMN rule_version int NOT NULL DEFAULT 0 COMMENT '服务规则版本号，用于版本校验及购买快照';

ALTER TABLE dating_service_option
 COMMENT = '婚恋可配置服务选项及完成核实标准',
 MODIFY COLUMN option_id bigint NOT NULL AUTO_INCREMENT COMMENT '服务选项ID',
 MODIFY COLUMN code varchar(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '唯一选项编码：FRIEND_ADDED成功加好友，MEETING_COMPLETED完成见面，也支持自定义编码',
 MODIFY COLUMN name varchar(100) NOT NULL COMMENT '服务选项名称',
 MODIFY COLUMN completion_standard varchar(500) NOT NULL COMMENT '判定服务选项完成的核实标准',
 MODIFY COLUMN status char(1) NOT NULL DEFAULT '0' COMMENT '选项状态：0启用，1停用；停用不改变历史快照',
 MODIFY COLUMN create_by varchar(64) NOT NULL COMMENT '创建人后台账号',
 MODIFY COLUMN create_time datetime NOT NULL COMMENT '创建时间',
 MODIFY COLUMN update_by varchar(64) DEFAULT NULL COMMENT '最后更新人后台账号',
 MODIFY COLUMN update_time datetime DEFAULT NULL COMMENT '最后更新时间';

ALTER TABLE dating_order
 COMMENT = '婚恋服务订单，确认足额收款后开通服务权益',
 MODIFY COLUMN order_id bigint NOT NULL AUTO_INCREMENT COMMENT '订单ID',
 MODIFY COLUMN order_no varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '唯一业务订单号',
 MODIFY COLUMN user_id bigint NOT NULL COMMENT '购买客户ID，关联sys_paying_user.id',
 MODIFY COLUMN package_id bigint NOT NULL COMMENT '来源套餐ID，关联dating_package.package_id',
 MODIFY COLUMN package_name varchar(100) NOT NULL COMMENT '创建订单时的套餐名称快照',
 MODIFY COLUMN amount decimal(10,2) NOT NULL COMMENT '订单应收金额，单位元',
 MODIFY COLUMN service_times int NOT NULL COMMENT '订单购买服务次数快照',
 MODIFY COLUMN rule_snapshot text NOT NULL COMMENT '购买规则JSON快照，保存所选服务条件，按ALL全部满足判断扣次',
 MODIFY COLUMN status varchar(20) NOT NULL DEFAULT 'UNPAID' COMMENT '订单状态：UNPAID待收款，PAID已收款，CANCELLED已取消',
 MODIFY COLUMN request_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建订单幂等标识，与创建人联合唯一',
 MODIFY COLUMN paid_amount decimal(10,2) DEFAULT NULL COMMENT '实际收款金额，单位元；确认时须等于订单应收金额',
 MODIFY COLUMN payment_method varchar(20) DEFAULT NULL COMMENT '收款方式编码',
 MODIFY COLUMN payment_reference varchar(100) DEFAULT NULL COMMENT '付款流水或收据编号',
 MODIFY COLUMN paid_at datetime DEFAULT NULL COMMENT '实际付款时间',
 MODIFY COLUMN confirmed_by varchar(64) DEFAULT NULL COMMENT '收款确认人后台账号',
 MODIFY COLUMN confirmed_at datetime DEFAULT NULL COMMENT '系统确认收款时间',
 MODIFY COLUMN cancel_reason varchar(500) DEFAULT NULL COMMENT '取消原因，包含成功结案自动取消未付款订单的原因',
 MODIFY COLUMN create_by varchar(64) NOT NULL COMMENT '订单创建人后台账号，仅作审计，不代表当前客户归属',
 MODIFY COLUMN create_time datetime NOT NULL COMMENT '订单创建时间',
 MODIFY COLUMN remark varchar(500) DEFAULT NULL COMMENT '订单备注',
 MODIFY COLUMN update_by varchar(64) DEFAULT NULL COMMENT '最后更新人后台账号',
 MODIFY COLUMN update_time datetime DEFAULT NULL COMMENT '最后更新时间',
 MODIFY COLUMN pending_user_id bigint GENERATED ALWAYS AS (CASE WHEN status='UNPAID' THEN user_id ELSE NULL END) STORED COMMENT '待收款客户生成列，唯一索引限制每位客户最多一张待收款订单';

ALTER TABLE dating_entitlement
 COMMENT = '已收款订单开通的服务权益，保存次数余额及购买规则快照',
 MODIFY COLUMN entitlement_id bigint NOT NULL AUTO_INCREMENT COMMENT '服务权益ID',
 MODIFY COLUMN order_id bigint NOT NULL COMMENT '来源已收款订单ID，每个订单仅开通一份权益',
 MODIFY COLUMN user_id bigint NOT NULL COMMENT '客户ID，关联sys_paying_user.id',
 MODIFY COLUMN total_times int NOT NULL COMMENT '购买总次数，等于已用次数与剩余次数之和',
 MODIFY COLUMN used_times int NOT NULL DEFAULT 0 COMMENT '已实际扣除的服务次数，结案不将未用次数计入消费',
 MODIFY COLUMN remaining_times int NOT NULL COMMENT '剩余服务次数，成功结案后保留历史值但不可继续使用',
 MODIFY COLUMN status varchar(30) NOT NULL COMMENT '权益状态：ACTIVE有效，EXHAUSTED已耗尽，SUCCESS_CLOSED成功结案',
 MODIFY COLUMN rule_snapshot text NOT NULL COMMENT '购买规则JSON快照，套餐后续修改不影响该快照',
 MODIFY COLUMN create_by varchar(64) NOT NULL COMMENT '权益开通人后台账号',
 MODIFY COLUMN create_time datetime NOT NULL COMMENT '权益开通时间',
 MODIFY COLUMN active_user_id bigint GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN user_id ELSE NULL END) STORED COMMENT '有效权益客户生成列，唯一索引限制每位客户最多一份有效权益',
 MODIFY COLUMN exhausted_at datetime DEFAULT NULL COMMENT '次数耗尽时间，用于校验已发生服务的补录时间';

ALTER TABLE dating_payment_record
 COMMENT = '婚恋订单收款凭证记录，每个订单最多一条确认收款记录',
 MODIFY COLUMN payment_id bigint NOT NULL AUTO_INCREMENT COMMENT '收款记录ID',
 MODIFY COLUMN order_id bigint NOT NULL COMMENT '已收款订单ID，关联dating_order.order_id且唯一',
 MODIFY COLUMN paid_amount decimal(10,2) NOT NULL COMMENT '确认收款金额，单位元',
 MODIFY COLUMN payment_method varchar(20) NOT NULL COMMENT '收款方式编码，与凭证编号联合唯一',
 MODIFY COLUMN payment_reference varchar(100) NOT NULL COMMENT '付款流水或收据编号，防止同一收款重复使用',
 MODIFY COLUMN paid_at datetime NOT NULL COMMENT '实际付款时间',
 MODIFY COLUMN confirmed_by varchar(64) NOT NULL COMMENT '收款确认人后台账号',
 MODIFY COLUMN confirmed_at datetime NOT NULL COMMENT '系统确认收款时间',
 MODIFY COLUMN remark varchar(500) DEFAULT NULL COMMENT '收款备注';

ALTER TABLE dating_entitlement_ledger
 COMMENT = '服务权益次数变动流水，记录开通与配对扣次，不删除历史流水',
 MODIFY COLUMN ledger_id bigint NOT NULL AUTO_INCREMENT COMMENT '次数流水ID',
 MODIFY COLUMN entitlement_id bigint NOT NULL COMMENT '服务权益ID，关联dating_entitlement.entitlement_id',
 MODIFY COLUMN order_id bigint NOT NULL COMMENT '权益来源订单ID',
 MODIFY COLUMN user_id bigint NOT NULL COMMENT '客户ID，关联sys_paying_user.id',
 MODIFY COLUMN entry_type varchar(20) NOT NULL COMMENT '流水类型：OPEN开通权益，SERVICE_DEDUCT服务扣次',
 MODIFY COLUMN delta int NOT NULL COMMENT '次数变动量，开通为购买次数，服务扣次为-1',
 MODIFY COLUMN balance_before int NOT NULL COMMENT '变动前剩余次数',
 MODIFY COLUMN balance_after int NOT NULL COMMENT '变动后剩余次数，等于变动前次数加变动量',
 MODIFY COLUMN create_by varchar(64) NOT NULL COMMENT '操作人账号或系统处理标识',
 MODIFY COLUMN create_time datetime NOT NULL COMMENT '流水产生时间',
 MODIFY COLUMN opening_order_id bigint GENERATED ALWAYS AS (CASE WHEN entry_type='OPEN' THEN order_id ELSE NULL END) STORED COMMENT '开通订单生成列，唯一索引防止同一订单重复开通',
 MODIFY COLUMN pair_id bigint DEFAULT NULL COMMENT '扣次所属配对ID；同一配对每位客户最多扣一次，开通流水为空',
 MODIFY COLUMN event_id bigint DEFAULT NULL COMMENT '触发扣次的服务事实ID，开通流水为空';

ALTER TABLE dating_assignment_history
 COMMENT = '客户负责红娘的分配与转交历史',
 MODIFY COLUMN id bigint NOT NULL AUTO_INCREMENT COMMENT '分配历史ID',
 MODIFY COLUMN user_id bigint NOT NULL COMMENT '被分配客户ID，关联sys_paying_user.id',
 MODIFY COLUMN previous_id bigint DEFAULT NULL COMMENT '原负责红娘后台用户ID，首次分配时为空',
 MODIFY COLUMN next_id bigint NOT NULL COMMENT '新负责红娘后台用户ID，关联sys_user.user_id',
 MODIFY COLUMN reason varchar(500) NOT NULL COMMENT '分配或转交原因',
 MODIFY COLUMN create_by varchar(64) NOT NULL COMMENT '执行分配或转交的后台账号',
 MODIFY COLUMN create_time datetime NOT NULL COMMENT '分配或转交时间';

ALTER TABLE dating_pair
 COMMENT = '无方向客户配对，按双方客户ID排序去重，重复推荐共用同一配对',
 MODIFY COLUMN pair_id bigint NOT NULL AUTO_INCREMENT COMMENT '配对ID',
 MODIFY COLUMN user_low_id bigint NOT NULL COMMENT '较小的客户ID，关联sys_paying_user.id',
 MODIFY COLUMN user_high_id bigint NOT NULL COMMENT '较大的客户ID，必须大于user_low_id',
 MODIFY COLUMN progress varchar(30) NOT NULL COMMENT '进度：COMMUNICATING沟通意向中，IN_PROGRESS双方已同意，FOLLOWING服务跟进中，REJECTED暂不继续，SUCCESS_CLOSED成功结案，TERMINATED已终止',
 MODIFY COLUMN create_by varchar(64) NOT NULL COMMENT '首次建立配对的后台账号',
 MODIFY COLUMN create_time datetime NOT NULL COMMENT '首次建立配对时间';

ALTER TABLE dating_pair_participant
 COMMENT = '配对参与方及固定权益规则快照，双方独立判断全部条件，每人最多扣一次',
 MODIFY COLUMN pair_id bigint NOT NULL COMMENT '配对ID，与客户ID组成联合主键',
 MODIFY COLUMN user_id bigint NOT NULL COMMENT '参与客户ID，关联sys_paying_user.id',
 MODIFY COLUMN entitlement_id bigint NOT NULL COMMENT '最初绑定权益ID，续单不自动改绑新权益',
 MODIFY COLUMN rule_snapshot text NOT NULL COMMENT '参与方购买规则JSON快照，所选条件全部满足才扣次',
 MODIFY COLUMN deduct_status varchar(20) NOT NULL COMMENT '扣次状态：PENDING待满足条件，DEDUCTED已扣次，BLOCKED业务阻塞，STOPPED已停止',
 MODIFY COLUMN blocked_reason varchar(500) DEFAULT NULL COMMENT '业务阻塞或停止服务原因',
 MODIFY COLUMN ledger_id bigint DEFAULT NULL COMMENT '成功扣次流水ID，关联dating_entitlement_ledger.ledger_id';

ALTER TABLE dating_recommendation
 COMMENT = '红娘人工推荐记录，同一配对可以多次推荐，保留每次推荐意向',
 MODIFY COLUMN id bigint NOT NULL AUTO_INCREMENT COMMENT '推荐记录ID',
 MODIFY COLUMN pair_id bigint NOT NULL COMMENT '所属配对ID，关联dating_pair.pair_id',
 MODIFY COLUMN reason varchar(500) NOT NULL COMMENT '推荐理由',
 MODIFY COLUMN low_intention varchar(20) NOT NULL COMMENT '较小客户ID一方意向：PENDING待确认，WILLING愿意继续，REJECTED拒绝',
 MODIFY COLUMN high_intention varchar(20) NOT NULL COMMENT '较大客户ID一方意向：PENDING待确认，WILLING愿意继续，REJECTED拒绝',
 MODIFY COLUMN request_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '推荐请求幂等标识，与创建人联合唯一',
 MODIFY COLUMN create_by varchar(64) NOT NULL COMMENT '创建推荐的后台账号',
 MODIFY COLUMN create_time datetime NOT NULL COMMENT '推荐创建时间';

ALTER TABLE dating_pair_intention
 COMMENT = '每次推荐中双方意向的登记历史，分别记录每位客户的确认',
 MODIFY COLUMN id bigint NOT NULL AUTO_INCREMENT COMMENT '意向登记历史ID',
 MODIFY COLUMN recommendation_id bigint NOT NULL COMMENT '推荐记录ID，关联dating_recommendation.id',
 MODIFY COLUMN user_id bigint NOT NULL COMMENT '表达意向的客户ID',
 MODIFY COLUMN intention varchar(20) NOT NULL COMMENT '客户意向：WILLING愿意继续，REJECTED拒绝',
 MODIFY COLUMN reason varchar(500) NOT NULL COMMENT '意向核实说明或拒绝原因',
 MODIFY COLUMN create_by varchar(64) NOT NULL COMMENT '登记意向的后台账号',
 MODIFY COLUMN create_time datetime NOT NULL COMMENT '意向登记时间';

ALTER TABLE dating_appointment
 COMMENT = '配对见面预约，仅确认实际完成后才生成完成见面的服务事实',
 MODIFY COLUMN appointment_id bigint NOT NULL AUTO_INCREMENT COMMENT '见面预约ID',
 MODIFY COLUMN pair_id bigint NOT NULL COMMENT '所属配对ID，关联dating_pair.pair_id',
 MODIFY COLUMN planned_at datetime NOT NULL COMMENT '计划见面时间',
 MODIFY COLUMN place varchar(200) NOT NULL COMMENT '计划见面地点',
 MODIFY COLUMN status varchar(20) NOT NULL COMMENT '预约状态：SCHEDULED待见面，COMPLETED已完成，CANCELLED已取消，NO_SHOW爽约，TERMINATED结案终止',
 MODIFY COLUMN completed_at datetime DEFAULT NULL COMMENT '实际完成见面时间，仅完成状态有值',
 MODIFY COLUMN create_by varchar(64) NOT NULL COMMENT '创建预约的后台账号',
 MODIFY COLUMN create_time datetime NOT NULL COMMENT '预约创建时间';

ALTER TABLE dating_appointment_history
 COMMENT = '见面预约操作历史，保留安排、改期、取消、爽约、完成及结案终止记录',
 MODIFY COLUMN id bigint NOT NULL AUTO_INCREMENT COMMENT '预约操作历史ID',
 MODIFY COLUMN appointment_id bigint NOT NULL COMMENT '见面预约ID，关联dating_appointment.appointment_id',
 MODIFY COLUMN action varchar(20) NOT NULL COMMENT '操作：SCHEDULE安排，RESCHEDULE改期，CANCEL取消，NO_SHOW爽约，COMPLETE确认完成，TERMINATE结案终止',
 MODIFY COLUMN planned_at datetime NOT NULL COMMENT '本次操作记录的计划见面时间快照',
 MODIFY COLUMN place varchar(200) NOT NULL COMMENT '本次操作记录的见面地点快照',
 MODIFY COLUMN reason varchar(500) NOT NULL COMMENT '操作原因或核实说明，结案终止时包含案例编号',
 MODIFY COLUMN create_by varchar(64) NOT NULL COMMENT '操作人后台账号',
 MODIFY COLUMN create_time datetime NOT NULL COMMENT '操作记录时间';

ALTER TABLE dating_service_event
 COMMENT = '已核实的配对服务事实，不从预约或成功结案自动推断未发生的服务',
 MODIFY COLUMN event_id bigint NOT NULL AUTO_INCREMENT COMMENT '服务事实ID',
 MODIFY COLUMN pair_id bigint NOT NULL COMMENT '所属配对ID，关联dating_pair.pair_id',
 MODIFY COLUMN option_code varchar(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已完成服务选项编码，应存在于配对参与方规则快照中',
 MODIFY COLUMN occurred_at datetime NOT NULL COMMENT '服务实际发生时间，不是系统登记时间',
 MODIFY COLUMN note varchar(500) NOT NULL COMMENT '服务完成核实说明',
 MODIFY COLUMN evidence varchar(200) DEFAULT NULL COMMENT '核实凭证编号，当前不存储凭证文件',
 MODIFY COLUMN request_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事实登记幂等标识，与配对ID联合唯一',
 MODIFY COLUMN appointment_id bigint DEFAULT NULL COMMENT '来源见面预约ID且唯一；非见面事实为空',
 MODIFY COLUMN create_by varchar(64) NOT NULL COMMENT '登记服务事实的后台账号',
 MODIFY COLUMN create_time datetime NOT NULL COMMENT '服务事实登记时间';

ALTER TABLE dating_deduction_task
 COMMENT = '服务事实触发的逐人扣次任务，双方独立处理，支持失败重试',
 MODIFY COLUMN task_id bigint NOT NULL AUTO_INCREMENT COMMENT '扣次任务ID',
 MODIFY COLUMN event_id bigint NOT NULL COMMENT '触发任务的服务事实ID，与客户ID联合唯一',
 MODIFY COLUMN pair_id bigint NOT NULL COMMENT '所属配对ID',
 MODIFY COLUMN user_id bigint NOT NULL COMMENT '本任务处理的客户ID，与配对ID关联参与方',
 MODIFY COLUMN status varchar(20) NOT NULL COMMENT '任务状态：READY待处理，DONE已处理但不一定扣次，ERROR异常待核查，CANCELLED已取消',
 MODIFY COLUMN outcome varchar(20) DEFAULT NULL COMMENT '处理结果：PENDING条件未全满足，BLOCKED业务阻塞，DEDUCTED已扣次，STOPPED已停止；未处理可为空',
 MODIFY COLUMN attempts int NOT NULL DEFAULT 0 COMMENT '处理异常累计次数，人工重试时重置为0',
 MODIFY COLUMN last_error varchar(500) DEFAULT NULL COMMENT '最近异常的通用提示，详细技术错误保留在后端日志',
 MODIFY COLUMN next_attempt_at datetime NOT NULL COMMENT '后台恢复任务允许下次执行的时间';

ALTER TABLE dating_success_case
 COMMENT = '红娘撮合成功结案记录，同一配对仅计一个内部成功案例',
 MODIFY COLUMN case_id bigint NOT NULL AUTO_INCREMENT COMMENT '成功案例ID',
 MODIFY COLUMN pair_id bigint NOT NULL COMMENT '成功配对ID且唯一，关联dating_pair.pair_id',
 MODIFY COLUMN recommendation_id bigint NOT NULL COMMENT '结案来源推荐ID，关联dating_recommendation.id',
 MODIFY COLUMN success_at datetime NOT NULL COMMENT '实际脱单确认时间，统计日期依据',
 MODIFY COLUMN confirmed_by varchar(64) NOT NULL COMMENT '核实并登记成功结案的后台账号',
 MODIFY COLUMN note varchar(500) NOT NULL COMMENT '双方脱单结果的核实说明',
 MODIFY COLUMN evidence varchar(200) NOT NULL COMMENT '脱单核实凭证编号，非凭证文件内容',
 MODIFY COLUMN create_time datetime NOT NULL COMMENT '案例系统登记时间';

ALTER TABLE dating_success_case_member
 COMMENT = '成功案例成员快照，保留结案归属和余次，首版每位客户只允许结案一次',
 MODIFY COLUMN case_id bigint NOT NULL COMMENT '成功案例ID，与客户ID组成联合主键',
 MODIFY COLUMN user_id bigint NOT NULL COMMENT '成功客户ID且全表唯一，防止重复结案',
 MODIFY COLUMN user_name varchar(100) NOT NULL COMMENT '结案时客户姓名快照',
 MODIFY COLUMN matchmaker_id bigint DEFAULT NULL COMMENT '结案时负责红娘后台用户ID快照',
 MODIFY COLUMN matchmaker_name varchar(100) DEFAULT NULL COMMENT '结案时负责红娘昵称快照',
 MODIFY COLUMN retained_times int NOT NULL COMMENT '事实结算后、关闭权益前的有效及耗尽权益剩余次数合计，仅保留历史不可再消费';

ALTER TABLE sys_paying_user
 MODIFY COLUMN service_status varchar(30) NOT NULL DEFAULT 'NOT_STARTED' COMMENT '婚恋服务状态：NOT_STARTED未开通，SERVING服务中，NOT_RENEWED次数耗尽未续单，SUCCESS_CLOSED成功结案',
 MODIFY COLUMN matchmaker_id bigint DEFAULT NULL COMMENT '当前负责红娘的后台用户ID，关联sys_user.user_id；未分配时为空';
