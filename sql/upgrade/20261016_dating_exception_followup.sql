-- Additive exception follow-up only; no balance, deduction, role grant or terminal-state changes.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS dating_exception_followup (
 task_id bigint NOT NULL PRIMARY KEY COMMENT '关联扣次任务，一任务一份责任跟进记录',
 handler varchar(64) NULL COMMENT '当前认领账号，释放后为空，不赋予额外数据权限',
 status varchar(20) NOT NULL COMMENT 'UNASSIGNED未认领 IN_PROGRESS处理中 WAITING待核实，不表示扣次结果',
 due_at datetime NULL COMMENT '人工设置的跟进截止时间，为空则不提示逾期',
 version bigint NOT NULL COMMENT '乐观锁版本，每次认领或跟进递增',
 update_by varchar(64) NOT NULL COMMENT '最近操作账号',
 update_time datetime(3) NOT NULL COMMENT '最近跟进时间',
 KEY idx_exception_handler(handler,status),
 KEY idx_exception_due(due_at),
 CONSTRAINT ck_exception_followup_status CHECK(status IN ('UNASSIGNED','IN_PROGRESS','WAITING')),
 CONSTRAINT ck_exception_followup_owner CHECK(
   (status='UNASSIGNED' AND handler IS NULL AND due_at IS NULL)
   OR (status IN ('IN_PROGRESS','WAITING') AND handler IS NOT NULL)),
 CONSTRAINT ck_exception_followup_version CHECK(version>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='婚恋扣次异常责任跟进，不修改任务及权益结果';

CREATE TABLE IF NOT EXISTS dating_exception_followup_history (
 history_id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '责任跟进历史标识',
 task_id bigint NOT NULL COMMENT '关联扣次任务标识',
 version bigint NOT NULL COMMENT '本次操作完成后的跟进版本',
 action varchar(20) NOT NULL COMMENT 'CLAIM认领 UPDATE更新进度 RELEASE释放责任',
 handler varchar(64) NULL COMMENT '操作完成后的认领账号，释放时为空',
 status varchar(20) NOT NULL COMMENT '操作完成后的跟进状态，不表示扣次已解决',
 due_at datetime NULL COMMENT '本次保存的人工截止时间，允许为空',
 note varchar(500) NOT NULL COMMENT '认领、跟进或释放说明，禁止记录密码等秘密',
 create_by varchar(64) NOT NULL COMMENT '实际操作账号',
 create_time datetime(3) NOT NULL COMMENT '跟进历史记录时间',
 UNIQUE KEY uk_exception_history_version(task_id,version),
 CONSTRAINT ck_exception_followup_action CHECK(action IN ('CLAIM','UPDATE','RELEASE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='婚恋扣次异常责任跟进历史，恢复或转交后保留';

-- Permission registration only; no role grants.
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,menu_type,visible,status,perms,create_by,create_time)
SELECT '异常责任跟进',m.menu_id,21,'#','F','0','0','dating:exception:handle','admin',CURRENT_TIMESTAMP
FROM sys_menu m WHERE m.component='dating/matches/index'
 AND NOT EXISTS(SELECT 1 FROM sys_menu existing WHERE existing.perms='dating:exception:handle')
ORDER BY m.menu_id LIMIT 1;
