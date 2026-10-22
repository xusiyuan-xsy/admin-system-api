-- MySQL 8.0.16+. Apply 20261013_dating_billing.sql first. Back up before deployment.
-- Repeatable migration. No legacy matches, payments or ownership are inferred.
SET NAMES utf8mb4;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sys_paying_user' AND column_name='matchmaker_id'),
 'SELECT 1','ALTER TABLE sys_paying_user ADD COLUMN matchmaker_id bigint NULL, ADD INDEX idx_dating_matchmaker(matchmaker_id)');
PREPARE migration FROM @ddl;
EXECUTE migration;
DEALLOCATE PREPARE migration;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dating_entitlement' AND column_name='exhausted_at'),
 'SELECT 1','ALTER TABLE dating_entitlement ADD COLUMN exhausted_at datetime NULL');
PREPARE migration FROM @ddl;
EXECUTE migration;
DEALLOCATE PREPARE migration;

CREATE TABLE IF NOT EXISTS dating_assignment_history (
 id bigint AUTO_INCREMENT PRIMARY KEY, user_id bigint NOT NULL, previous_id bigint, next_id bigint NOT NULL,
 reason varchar(500) NOT NULL, create_by varchar(64) NOT NULL, create_time datetime NOT NULL,
 KEY idx_assignment_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dating_pair (
 pair_id bigint AUTO_INCREMENT PRIMARY KEY, user_low_id bigint NOT NULL, user_high_id bigint NOT NULL,
 progress varchar(30) NOT NULL, create_by varchar(64) NOT NULL, create_time datetime NOT NULL,
 UNIQUE KEY uk_dating_pair(user_low_id,user_high_id), KEY idx_pair_high(user_high_id),
 CONSTRAINT ck_pair_order CHECK(user_low_id<user_high_id),
 CONSTRAINT ck_pair_progress CHECK(progress IN ('COMMUNICATING','IN_PROGRESS','FOLLOWING','REJECTED','SUCCESS_CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dating_pair_participant (
 pair_id bigint NOT NULL, user_id bigint NOT NULL, entitlement_id bigint NOT NULL,
 rule_snapshot text NOT NULL, deduct_status varchar(20) NOT NULL, blocked_reason varchar(500), ledger_id bigint,
 PRIMARY KEY(pair_id,user_id),
 CONSTRAINT fk_participant_pair FOREIGN KEY(pair_id) REFERENCES dating_pair(pair_id),
 CONSTRAINT fk_participant_entitlement FOREIGN KEY(entitlement_id) REFERENCES dating_entitlement(entitlement_id),
 CONSTRAINT ck_participant_status CHECK(deduct_status IN ('PENDING','DEDUCTED','BLOCKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dating_recommendation (
 id bigint AUTO_INCREMENT PRIMARY KEY, pair_id bigint NOT NULL, reason varchar(500) NOT NULL,
 low_intention varchar(20) NOT NULL, high_intention varchar(20) NOT NULL,
 request_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 create_by varchar(64) NOT NULL, create_time datetime NOT NULL,
 UNIQUE KEY uk_recommendation_request(create_by,request_key),
 CONSTRAINT fk_recommendation_pair FOREIGN KEY(pair_id) REFERENCES dating_pair(pair_id),
 CONSTRAINT ck_low_intention CHECK(low_intention IN ('PENDING','WILLING','REJECTED')),
 CONSTRAINT ck_high_intention CHECK(high_intention IN ('PENDING','WILLING','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dating_pair_intention (
 id bigint AUTO_INCREMENT PRIMARY KEY, recommendation_id bigint NOT NULL, user_id bigint NOT NULL,
 intention varchar(20) NOT NULL, reason varchar(500) NOT NULL, create_by varchar(64) NOT NULL, create_time datetime NOT NULL,
 CONSTRAINT fk_intention_recommendation FOREIGN KEY(recommendation_id) REFERENCES dating_recommendation(id),
 CONSTRAINT ck_intention CHECK(intention IN ('WILLING','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dating_appointment (
 appointment_id bigint AUTO_INCREMENT PRIMARY KEY, pair_id bigint NOT NULL, planned_at datetime NOT NULL,
 place varchar(200) NOT NULL, status varchar(20) NOT NULL, completed_at datetime,
 create_by varchar(64) NOT NULL, create_time datetime NOT NULL,
 CONSTRAINT fk_appointment_pair FOREIGN KEY(pair_id) REFERENCES dating_pair(pair_id),
 CONSTRAINT ck_appointment_status CHECK(status IN ('SCHEDULED','COMPLETED','CANCELLED','NO_SHOW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dating_appointment_history (
 id bigint AUTO_INCREMENT PRIMARY KEY, appointment_id bigint NOT NULL, action varchar(20) NOT NULL,
 planned_at datetime NOT NULL, place varchar(200) NOT NULL, reason varchar(500) NOT NULL,
 create_by varchar(64) NOT NULL, create_time datetime NOT NULL,
 CONSTRAINT fk_appointment_history FOREIGN KEY(appointment_id) REFERENCES dating_appointment(appointment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dating_service_event (
 event_id bigint AUTO_INCREMENT PRIMARY KEY, pair_id bigint NOT NULL,
 option_code varchar(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 occurred_at datetime NOT NULL, note varchar(500) NOT NULL, evidence varchar(200),
 request_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, appointment_id bigint,
 create_by varchar(64) NOT NULL, create_time datetime NOT NULL,
 UNIQUE KEY uk_event_request(pair_id,request_key), UNIQUE KEY uk_event_appointment(appointment_id),
 CONSTRAINT fk_event_pair FOREIGN KEY(pair_id) REFERENCES dating_pair(pair_id),
 CONSTRAINT fk_event_appointment FOREIGN KEY(appointment_id) REFERENCES dating_appointment(appointment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dating_deduction_task (
 task_id bigint AUTO_INCREMENT PRIMARY KEY, event_id bigint NOT NULL, pair_id bigint NOT NULL, user_id bigint NOT NULL,
 status varchar(20) NOT NULL, outcome varchar(20), attempts int NOT NULL DEFAULT 0,
 last_error varchar(500), next_attempt_at datetime NOT NULL,
 UNIQUE KEY uk_task_event_user(event_id,user_id), KEY idx_task_ready(status,next_attempt_at,task_id),
 CONSTRAINT fk_task_event FOREIGN KEY(event_id) REFERENCES dating_service_event(event_id),
 CONSTRAINT fk_task_participant FOREIGN KEY(pair_id,user_id) REFERENCES dating_pair_participant(pair_id,user_id),
 CONSTRAINT ck_task_status CHECK(status IN ('READY','DONE','ERROR')),
 CONSTRAINT ck_task_outcome CHECK(outcome IS NULL OR outcome IN ('PENDING','BLOCKED','DEDUCTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Keep opening idempotency while allowing multiple different pairs per paid order.
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dating_entitlement_ledger' AND column_name='opening_order_id'),
 'SELECT 1','ALTER TABLE dating_entitlement_ledger ADD COLUMN opening_order_id bigint GENERATED ALWAYS AS (CASE WHEN entry_type=''OPEN'' THEN order_id ELSE NULL END) STORED, ADD UNIQUE KEY uk_opening_order(opening_order_id)');
PREPARE migration FROM @ddl;
EXECUTE migration;
DEALLOCATE PREPARE migration;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='dating_entitlement_ledger' AND index_name='uk_opening_ledger'),
 'ALTER TABLE dating_entitlement_ledger DROP INDEX uk_opening_ledger','SELECT 1');
PREPARE migration FROM @ddl;
EXECUTE migration;
DEALLOCATE PREPARE migration;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dating_entitlement_ledger' AND column_name='pair_id'),
 'SELECT 1','ALTER TABLE dating_entitlement_ledger ADD COLUMN pair_id bigint NULL, ADD COLUMN event_id bigint NULL, ADD UNIQUE KEY uk_pair_user_deduction(pair_id,user_id,entry_type), ADD CONSTRAINT fk_ledger_pair FOREIGN KEY(pair_id) REFERENCES dating_pair(pair_id), ADD CONSTRAINT fk_ledger_event FOREIGN KEY(event_id) REFERENCES dating_service_event(event_id), ADD CONSTRAINT ck_service_deduction CHECK(entry_type<>''SERVICE_DEDUCT'' OR (pair_id IS NOT NULL AND event_id IS NOT NULL AND delta=-1))');
PREPARE migration FROM @ddl;
EXECUTE migration;
DEALLOCATE PREPARE migration;

SET @root=(SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='dating' ORDER BY menu_id LIMIT 1);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '红娘分配',@root,5,'assignments','dating/assignments/index','C','0','0','dating:assignment:list','user','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE component='dating/assignments/index');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '配对服务',@root,6,'matches','dating/matches/index','C','0','0','dating:match:list','peoples','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE component='dating/matches/index');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,menu_type,visible,status,perms,create_by,create_time)
SELECT p.label,m.menu_id,1,'#','F','0','0',p.perm,'admin',CURRENT_TIMESTAMP
FROM (
 SELECT '分配与转交' label,'dating:assignment:edit' perm,'dating/assignments/index' component
 UNION ALL SELECT '配对详情','dating:match:query','dating/matches/index'
 UNION ALL SELECT '创建推荐','dating:match:add','dating/matches/index'
 UNION ALL SELECT '登记双方意向','dating:match:intention','dating/matches/index'
 UNION ALL SELECT '登记服务事实','dating:event:add','dating/matches/index'
 UNION ALL SELECT '安排见面','dating:appointment:add','dating/matches/index'
 UNION ALL SELECT '更新见面','dating:appointment:edit','dating/matches/index'
 UNION ALL SELECT '重试扣次','dating:deduction:retry','dating/matches/index'
 UNION ALL SELECT '全部婚恋服务数据','dating:service:all','dating/matches/index'
) p JOIN sys_menu m ON m.component=p.component
WHERE NOT EXISTS(SELECT 1 FROM sys_menu existing WHERE existing.perms=p.perm);
