-- Synthetic authentication fixtures, loaded only after dating-archive-schema.sql in H2.
alter table sys_user add (
 dept_id bigint, nick_name varchar(64), email varchar(64), avatar varchar(255),
 phonenumber varchar(11), password varchar(100), sex char(1), status char(1) default '0',
 del_flag char(1) default '0', login_ip varchar(128), login_date timestamp,
 pwd_update_date timestamp, create_by varchar(64), create_time timestamp,
 update_by varchar(64), update_time timestamp, remark varchar(500)
);
create table sys_dept(
 dept_id bigint primary key,parent_id bigint,ancestors varchar(100),dept_name varchar(50),
 order_num int,leader varchar(64),status char(1)
);
create table sys_role(
 role_id bigint primary key,role_name varchar(64),role_key varchar(64),role_sort int,
 data_scope char(1),menu_check_strictly boolean default true,dept_check_strictly boolean default true,
 status char(1) default '0',del_flag char(1) default '0',create_time timestamp,remark varchar(500)
);
create table sys_user_role(user_id bigint,role_id bigint,primary key(user_id,role_id));
create table sys_menu(
 menu_id bigint primary key,parent_id bigint default 0,menu_name varchar(64),
 path varchar(200),component varchar(255),`query` varchar(255),route_name varchar(64),
 visible char(1) default '0',status char(1) default '0',perms varchar(100),
 is_frame int default 1,is_cache int default 0,menu_type char(1) default 'F',
 icon varchar(100),order_num int default 0,create_time timestamp
);
create table sys_role_menu(role_id bigint,menu_id bigint,primary key(role_id,menu_id));
create table sys_config(
 config_id bigint primary key,config_name varchar(100),config_key varchar(100),
 config_value varchar(500),config_type char(1),create_by varchar(64),create_time timestamp,
 update_by varchar(64),update_time timestamp,remark varchar(500)
);
create table sys_logininfor(
 info_id bigint auto_increment primary key,user_name varchar(64),status char(1),
 ipaddr varchar(128),login_location varchar(255),browser varchar(50),os varchar(50),
 msg varchar(255),login_time timestamp
);
insert into sys_config(config_id,config_key,config_value) values
 (1,'sys.account.captchaEnabled','true');
insert into sys_user(user_id,user_name) values
 (30,'finance'),(40,'manager'),(50,'viewer'),(60,'disabled'),(70,'disabled_role');
update sys_user set status='1' where user_id=60;
insert into sys_role(role_id,role_name,role_key,role_sort,data_scope) values
 (10,'Matchmaker','matchmaker',1,'5'),(30,'Finance','finance',2,'5'),
 (40,'Service manager','service_manager',3,'1'),(50,'No permission','viewer',4,'5'),
 (70,'Disabled role','disabled_role',5,'1');
update sys_role set status='1' where role_id=70;
insert into sys_user_role values(10,10),(20,10),(30,30),(40,40),(50,50),(60,10),(70,70);
insert into sys_menu(menu_id,menu_name,path,component,menu_type,perms) values
 (100,'Archives','payingUsers','system/payingUsers/index','C','system:payingUsers:list'),
 (101,'Query',null,null,'F','system:payingUsers:query'),
 (102,'Edit',null,null,'F','system:payingUsers:edit'),
 (103,'Export',null,null,'F','system:payingUsers:export'),
 (104,'All billing',null,null,'F','dating:billing:all'),
 (105,'All service',null,null,'F','dating:service:all'),
 (106,'Disabled remove',null,null,'F','system:payingUsers:remove');
update sys_menu set status='1' where menu_id=106;
insert into sys_role_menu values
 (10,100),(10,101),(10,102),(10,103),(10,106),
 (30,100),(30,101),(30,104),
 (40,100),(40,101),(40,105),
 (70,100),(70,101),(70,105);
