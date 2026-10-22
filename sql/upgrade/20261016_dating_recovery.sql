-- Apply after the existing dating migrations. Back up before deployment.
-- Additive and repeatable; never infer failures or change balances/roles.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS dating_recovery_run (
 run_id varchar(36) NOT NULL PRIMARY KEY COMMENT '恢复批次唯一标识',
 instance_id varchar(36) NOT NULL COMMENT '应用启动实例标识，不含主机信息',
 started_at datetime(3) NOT NULL COMMENT '数据库记录的批次开始时间',
 finished_at datetime(3) NULL COMMENT '批次结束时间，未结束时为空',
 status varchar(20) NOT NULL COMMENT 'RUNNING执行中 SUCCEEDED扫描成功 PARTIAL任务失败 FAILED扫描失败',
 scanned int NOT NULL DEFAULT 0 COMMENT '本批扫描到的任务数，最多一百',
 processed int NOT NULL DEFAULT 0 COMMENT '处理调用正常返回数，不等于实际扣次人数',
 failed int NOT NULL DEFAULT 0 COMMENT '处理调用失败数',
 record_failures int NOT NULL DEFAULT 0 COMMENT '任务异常记录写入失败数',
 error_code varchar(40) NULL COMMENT '安全错误分类，不存储异常正文或SQL',
 KEY idx_recovery_instance(instance_id,started_at),
 KEY idx_recovery_unfinished(status,started_at),
 CONSTRAINT ck_recovery_status CHECK(status IN ('RUNNING','SUCCEEDED','PARTIAL','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='婚恋扣次后台恢复批次记录';

CREATE TABLE IF NOT EXISTS dating_deduction_history (
 history_id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '扣次异常历史标识',
 task_id bigint NOT NULL COMMENT '关联扣次任务标识',
 action varchar(20) NOT NULL COMMENT 'FAILED技术失败 RETRY人工重试',
 attempt int NOT NULL COMMENT '失败后的累计次数或人工重试前次数',
 error_code varchar(40) NOT NULL COMMENT '安全错误分类或MANUAL_RETRY，不存储异常正文',
 create_by varchar(64) NOT NULL COMMENT '后台执行标识或发起人工重试的账号',
 create_time datetime(3) NOT NULL COMMENT '历史记录时间',
 KEY idx_deduction_history_task(task_id,history_id),
 CONSTRAINT ck_deduction_history_action CHECK(action IN ('FAILED','RETRY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='婚恋扣次技术异常和人工重试历史，重试成功不删除';

-- Permissions are registered, never automatically granted to existing roles.
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,menu_type,visible,status,perms,create_by,create_time)
SELECT '扣次恢复监控',m.menu_id,20,'#','F','0','0','dating:recovery:query','admin',CURRENT_TIMESTAMP
FROM sys_menu m WHERE m.component='dating/matches/index'
 AND NOT EXISTS(SELECT 1 FROM sys_menu existing WHERE existing.perms='dating:recovery:query')
ORDER BY m.menu_id LIMIT 1;
