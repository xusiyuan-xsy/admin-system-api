CREATE TABLE sys_user (
 user_id bigint PRIMARY KEY, user_name varchar(64), nick_name varchar(64),
 status char(1) DEFAULT '0', del_flag char(1) DEFAULT '0'
);
INSERT INTO sys_user(user_id,user_name,nick_name) VALUES (10,'alice','Alice'),(20,'bob','Bob');
CREATE TABLE sys_paying_user (
 id bigint PRIMARY KEY, name varchar(100), phone varchar(30), status char(1),
 create_by varchar(64), service_status varchar(30) DEFAULT 'NOT_STARTED', del_flag char(1) DEFAULT '0',
 matchmaker_id bigint
);
CREATE TABLE dating_package (
 package_id bigint AUTO_INCREMENT PRIMARY KEY, package_name varchar(100), package_type char(1) DEFAULT '0',
 price decimal(10,2), limit_expire char(1), service_duration int DEFAULT 0,
 limit_service_times char(1), service_times int, description varchar(500), cover_image varchar(255),
 sort_order int DEFAULT 0, status char(1), del_flag char(1) DEFAULT '0',
 selection_mode varchar(10), service_option_codes varchar(2000), rule_version int DEFAULT 0,
 create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp
);
CREATE TABLE dating_service_option (
 option_id bigint AUTO_INCREMENT PRIMARY KEY, code varchar(50) UNIQUE,
 name varchar(100), completion_standard varchar(500), status char(1),
 create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp
);
CREATE TABLE dating_order (
 order_id bigint AUTO_INCREMENT PRIMARY KEY, order_no varchar(40) UNIQUE,
 user_id bigint, package_id bigint, package_name varchar(100), amount decimal(10,2),
 service_times int, rule_snapshot varchar(20000), status varchar(20), request_key varchar(64),
 paid_amount decimal(10,2), payment_method varchar(20), payment_reference varchar(100),
 paid_at timestamp, confirmed_by varchar(64), confirmed_at timestamp, cancel_reason varchar(500),
 create_by varchar(64), create_time timestamp, remark varchar(500), update_by varchar(64), update_time timestamp,
 pending_user_id bigint GENERATED ALWAYS AS (CASE WHEN status='UNPAID' THEN user_id ELSE NULL END),
 UNIQUE(create_by,request_key), UNIQUE(pending_user_id)
);
CREATE TABLE dating_entitlement (
 entitlement_id bigint AUTO_INCREMENT PRIMARY KEY, order_id bigint UNIQUE REFERENCES dating_order(order_id),
 user_id bigint, total_times int, used_times int, remaining_times int, status varchar(30), rule_snapshot varchar(20000),
 create_by varchar(64), create_time timestamp, exhausted_at timestamp,
 active_user_id bigint GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN user_id ELSE NULL END),
 UNIQUE(active_user_id), CHECK(total_times=used_times+remaining_times AND used_times>=0 AND remaining_times>=0)
);
CREATE TABLE dating_payment_record (
 payment_id bigint AUTO_INCREMENT PRIMARY KEY, order_id bigint UNIQUE REFERENCES dating_order(order_id),
 paid_amount decimal(10,2), payment_method varchar(20), payment_reference varchar(100),
 paid_at timestamp, confirmed_by varchar(64), confirmed_at timestamp, remark varchar(500),
 UNIQUE(payment_method,payment_reference)
);
CREATE TABLE dating_entitlement_ledger (
 ledger_id bigint AUTO_INCREMENT PRIMARY KEY, entitlement_id bigint REFERENCES dating_entitlement(entitlement_id),
 order_id bigint, user_id bigint, entry_type varchar(20), delta int, balance_before int, balance_after int,
 create_by varchar(64), create_time timestamp, pair_id bigint, event_id bigint,
 opening_order_id bigint GENERATED ALWAYS AS (CASE WHEN entry_type='OPEN' THEN order_id ELSE NULL END),
 UNIQUE(opening_order_id), UNIQUE(pair_id,user_id,entry_type),
 CHECK(balance_before+delta=balance_after)
);
INSERT INTO sys_paying_user(id,name,phone,status,create_by) VALUES
 (1,'Customer A','13800000001','0','alice'),(2,'Customer B','13800000002','0','bob'),
 (3,'Customer C','13800000003','0','alice');
INSERT INTO dating_service_option(code,name,completion_standard,status,create_by,create_time) VALUES
 ('FRIEND_ADDED','Friends','Both confirmed friends','0','alice',CURRENT_TIMESTAMP),
 ('MEETING_COMPLETED','Meeting','Both confirmed meeting','0','alice',CURRENT_TIMESTAMP);
