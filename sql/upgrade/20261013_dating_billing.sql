-- MySQL 8.0.16+. Back up first, select the application database, then run once.
-- No existing payments or entitlements are inferred from legacy customer/package data.
SET NAMES utf8mb4;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dating_package' AND column_name='selection_mode'),
 'SELECT 1', 'ALTER TABLE dating_package ADD COLUMN selection_mode varchar(10) NULL');
PREPARE migration FROM @ddl;
EXECUTE migration;
DEALLOCATE PREPARE migration;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dating_package' AND column_name='service_option_codes'),
 'SELECT 1', 'ALTER TABLE dating_package ADD COLUMN service_option_codes text NULL');
PREPARE migration FROM @ddl;
EXECUTE migration;
DEALLOCATE PREPARE migration;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='dating_package' AND column_name='rule_version'),
 'SELECT 1', 'ALTER TABLE dating_package ADD COLUMN rule_version int NOT NULL DEFAULT 0');
PREPARE migration FROM @ddl;
EXECUTE migration;
DEALLOCATE PREPARE migration;
ALTER TABLE dating_package MODIFY COLUMN package_name varchar(100) NOT NULL;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sys_paying_user' AND column_name='service_status'),
 'SELECT 1', 'ALTER TABLE sys_paying_user ADD COLUMN service_status varchar(30) NOT NULL DEFAULT ''NOT_STARTED''');
PREPARE migration FROM @ddl;
EXECUTE migration;
DEALLOCATE PREPARE migration;

CREATE TABLE IF NOT EXISTS dating_service_option (
 option_id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
 code varchar(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 name varchar(100) NOT NULL, completion_standard varchar(500) NOT NULL,
 status char(1) NOT NULL DEFAULT '0',
 create_by varchar(64) NOT NULL, create_time datetime NOT NULL,
 update_by varchar(64), update_time datetime,
 UNIQUE KEY uk_option_code(code),
 CONSTRAINT ck_option_status CHECK(status IN ('0','1'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO dating_service_option(code,name,completion_standard,status,create_by,create_time)
SELECT 'FRIEND_ADDED','成功加好友','双方已成功添加好友，并由红娘核实记录','0','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM dating_service_option WHERE code='FRIEND_ADDED');
INSERT INTO dating_service_option(code,name,completion_standard,status,create_by,create_time)
SELECT 'MEETING_COMPLETED','完成见面','双方已完成实际见面，并由红娘核实记录','0','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM dating_service_option WHERE code='MEETING_COMPLETED');

CREATE TABLE IF NOT EXISTS dating_order (
 order_id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
 order_no varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id bigint NOT NULL, package_id bigint NOT NULL, package_name varchar(100) NOT NULL,
 amount decimal(10,2) NOT NULL, service_times int NOT NULL, rule_snapshot text NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'UNPAID',
 request_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 paid_amount decimal(10,2), payment_method varchar(20), payment_reference varchar(100),
 paid_at datetime, confirmed_by varchar(64), confirmed_at datetime, cancel_reason varchar(500),
 create_by varchar(64) NOT NULL, create_time datetime NOT NULL, remark varchar(500),
 update_by varchar(64), update_time datetime,
 pending_user_id bigint GENERATED ALWAYS AS (CASE WHEN status='UNPAID' THEN user_id ELSE NULL END) STORED,
 UNIQUE KEY uk_order_no(order_no), UNIQUE KEY uk_order_request(create_by,request_key),
 UNIQUE KEY uk_pending_user(pending_user_id),
 KEY idx_order_user(user_id,status), KEY idx_order_owner(create_by,order_id),
 CONSTRAINT ck_order_amount CHECK(amount>0 AND service_times>0),
 CONSTRAINT ck_order_status CHECK(status IN ('UNPAID','PAID','CANCELLED')),
 CONSTRAINT ck_order_paid CHECK(status<>'PAID' OR
   (paid_amount IS NOT NULL AND paid_amount=amount AND payment_method IS NOT NULL
    AND payment_reference IS NOT NULL AND paid_at IS NOT NULL AND confirmed_by IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dating_entitlement (
 entitlement_id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
 order_id bigint NOT NULL, user_id bigint NOT NULL,
 total_times int NOT NULL, used_times int NOT NULL DEFAULT 0, remaining_times int NOT NULL,
 status varchar(30) NOT NULL, rule_snapshot text NOT NULL,
 create_by varchar(64) NOT NULL, create_time datetime NOT NULL,
 active_user_id bigint GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN user_id ELSE NULL END) STORED,
 UNIQUE KEY uk_entitlement_order(order_id), UNIQUE KEY uk_active_user(active_user_id),
 KEY idx_entitlement_user(user_id),
 CONSTRAINT fk_entitlement_order FOREIGN KEY(order_id) REFERENCES dating_order(order_id),
 CONSTRAINT ck_entitlement_balance CHECK(total_times>0 AND used_times>=0 AND remaining_times>=0 AND total_times=used_times+remaining_times),
 CONSTRAINT ck_entitlement_status CHECK(status IN ('ACTIVE','EXHAUSTED','SUCCESS_CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dating_payment_record (
 payment_id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, order_id bigint NOT NULL,
 paid_amount decimal(10,2) NOT NULL, payment_method varchar(20) NOT NULL,
 payment_reference varchar(100) NOT NULL, paid_at datetime NOT NULL,
 confirmed_by varchar(64) NOT NULL, confirmed_at datetime NOT NULL, remark varchar(500),
 UNIQUE KEY uk_payment_order(order_id),
 UNIQUE KEY uk_payment_reference(payment_method,payment_reference),
 CONSTRAINT fk_payment_order FOREIGN KEY(order_id) REFERENCES dating_order(order_id),
 CONSTRAINT ck_payment_amount CHECK(paid_amount>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dating_entitlement_ledger (
 ledger_id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, entitlement_id bigint NOT NULL,
 order_id bigint NOT NULL, user_id bigint NOT NULL, entry_type varchar(20) NOT NULL,
 delta int NOT NULL, balance_before int NOT NULL, balance_after int NOT NULL,
 create_by varchar(64) NOT NULL, create_time datetime NOT NULL,
 UNIQUE KEY uk_opening_ledger(order_id,entry_type),
 CONSTRAINT fk_ledger_entitlement FOREIGN KEY(entitlement_id) REFERENCES dating_entitlement(entitlement_id),
 CONSTRAINT ck_ledger_balance CHECK(balance_before>=0 AND balance_after>=0 AND balance_before+delta=balance_after)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Menu insertion is repeatable. Do not grant cross-owner access to ordinary roles.
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,menu_type,visible,status,icon,create_by,create_time)
SELECT '婚恋服务',0,5,'dating','M','0','0','peoples','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=0 AND path='dating');
SET @root=(SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='dating' ORDER BY menu_id LIMIT 1);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '服务选项',@root,1,'serviceOptions','dating/serviceOptions/index','C','0','0','dating:option:list','list','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE component='dating/serviceOptions/index');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '服务订单',@root,3,'orders','dating/orders/index','C','0','0','dating:order:list','shopping','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE component='dating/orders/index');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '服务权益',@root,4,'entitlements','dating/entitlements/index','C','0','0','dating:entitlement:list','validCode','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE component='dating/entitlements/index');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '套餐配置',@root,2,'packages','system/datingPackage/index','C','0','0','system:datingPackage:list','money','admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE component='system/datingPackage/index');

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,menu_type,visible,status,perms,create_by,create_time)
SELECT p.label, m.menu_id, 1, '#','F','0','0',p.perm,'admin',CURRENT_TIMESTAMP
FROM (
 SELECT '新增选项' label,'dating:option:add' perm,'dating/serviceOptions/index' component
 UNION ALL SELECT '修改选项','dating:option:edit','dating/serviceOptions/index'
 UNION ALL SELECT '创建订单','dating:order:add','dating/orders/index'
 UNION ALL SELECT '订单详情','dating:order:query','dating/orders/index'
 UNION ALL SELECT '确认收款','dating:order:confirmPayment','dating/orders/index'
 UNION ALL SELECT '取消订单','dating:order:cancel','dating/orders/index'
 UNION ALL SELECT '全部客户订单数据','dating:billing:all','dating/orders/index'
 UNION ALL SELECT '套餐详情','system:datingPackage:query','system/datingPackage/index'
 UNION ALL SELECT '新增套餐','system:datingPackage:add','system/datingPackage/index'
 UNION ALL SELECT '修改套餐','system:datingPackage:edit','system/datingPackage/index'
 UNION ALL SELECT '删除套餐','system:datingPackage:remove','system/datingPackage/index'
) p JOIN sys_menu m ON m.component=p.component
WHERE NOT EXISTS(SELECT 1 FROM sys_menu existing WHERE existing.perms=p.perm);
