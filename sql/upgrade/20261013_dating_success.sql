-- MySQL 8.0.16+. Apply billing and workflow migrations first; back up before deploying.
-- Repeatable. Never infer success from legacy customer status.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS dating_success_case (
 case_id bigint AUTO_INCREMENT PRIMARY KEY, pair_id bigint NOT NULL, recommendation_id bigint NOT NULL,
 success_at datetime NOT NULL, confirmed_by varchar(64) NOT NULL, note varchar(500) NOT NULL,
 evidence varchar(200) NOT NULL, create_time datetime NOT NULL,
 UNIQUE KEY uk_success_pair(pair_id), KEY idx_success_date(success_at,case_id),
 CONSTRAINT fk_success_pair FOREIGN KEY(pair_id) REFERENCES dating_pair(pair_id),
 CONSTRAINT fk_success_recommendation FOREIGN KEY(recommendation_id) REFERENCES dating_recommendation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dating_success_case_member (
 case_id bigint NOT NULL, user_id bigint NOT NULL, user_name varchar(100) NOT NULL,
 matchmaker_id bigint, matchmaker_name varchar(100), retained_times int NOT NULL,
 PRIMARY KEY(case_id,user_id), UNIQUE KEY uk_success_user(user_id), KEY idx_success_matchmaker(matchmaker_id,case_id),
 CONSTRAINT fk_success_member FOREIGN KEY(case_id) REFERENCES dating_success_case(case_id),
 CONSTRAINT ck_success_retained CHECK(retained_times>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Terminal states. Each alteration is atomic and guarded by its new constraint name.
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='dating_pair' AND constraint_name='ck_pair_progress_v3'),
 'SELECT 1','ALTER TABLE dating_pair DROP CHECK ck_pair_progress, ADD CONSTRAINT ck_pair_progress_v3 CHECK(progress IN (''COMMUNICATING'',''IN_PROGRESS'',''FOLLOWING'',''REJECTED'',''SUCCESS_CLOSED'',''TERMINATED''))');
PREPARE migration FROM @ddl;
EXECUTE migration;
DEALLOCATE PREPARE migration;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='dating_pair_participant' AND constraint_name='ck_participant_status_v3'),
 'SELECT 1','ALTER TABLE dating_pair_participant DROP CHECK ck_participant_status, ADD CONSTRAINT ck_participant_status_v3 CHECK(deduct_status IN (''PENDING'',''DEDUCTED'',''BLOCKED'',''STOPPED''))');
PREPARE migration FROM @ddl;
EXECUTE migration;
DEALLOCATE PREPARE migration;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='dating_appointment' AND constraint_name='ck_appointment_status_v3'),
 'SELECT 1','ALTER TABLE dating_appointment DROP CHECK ck_appointment_status, ADD CONSTRAINT ck_appointment_status_v3 CHECK(status IN (''SCHEDULED'',''COMPLETED'',''CANCELLED'',''NO_SHOW'',''TERMINATED''))');
PREPARE migration FROM @ddl;
EXECUTE migration;
DEALLOCATE PREPARE migration;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='dating_deduction_task' AND constraint_name='ck_task_status_v3'),
 'SELECT 1','ALTER TABLE dating_deduction_task DROP CHECK ck_task_status, ADD CONSTRAINT ck_task_status_v3 CHECK(status IN (''READY'',''DONE'',''ERROR'',''CANCELLED''))');
PREPARE migration FROM @ddl;
EXECUTE migration;
DEALLOCATE PREPARE migration;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='dating_deduction_task' AND constraint_name='ck_task_outcome_v3'),
 'SELECT 1','ALTER TABLE dating_deduction_task DROP CHECK ck_task_outcome, ADD CONSTRAINT ck_task_outcome_v3 CHECK(outcome IS NULL OR outcome IN (''PENDING'',''BLOCKED'',''DEDUCTED'',''STOPPED''))');
PREPARE migration FROM @ddl;
EXECUTE migration;
DEALLOCATE PREPARE migration;

SET @root=(SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='dating' ORDER BY menu_id LIMIT 1);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '成功案例',@root,7,'success-cases','dating/success-cases/index','C','0','0','dating:success:list','star','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE component='dating/success-cases/index');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,menu_type,visible,status,perms,create_by,create_time)
SELECT p.label,m.menu_id,1,'#','F','0','0',p.perm,'admin',CURRENT_TIMESTAMP
FROM (
 SELECT '成功结案' label,'dating:success:close' perm,'dating/matches/index' component
 UNION ALL SELECT '成功案例详情','dating:success:query','dating/success-cases/index'
) p JOIN sys_menu m ON m.component=p.component
WHERE NOT EXISTS(SELECT 1 FROM sys_menu existing WHERE existing.perms=p.perm);
