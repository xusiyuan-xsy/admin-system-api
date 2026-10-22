create table sys_user(user_id bigint primary key, user_name varchar(64));
insert into sys_user values(10,'alice'),(20,'bob');
create table sys_paying_user(
 id bigint auto_increment primary key, phone varchar(11) unique, password varchar(100),
 name varchar(50), sex char(1), birth_year int, zodiac varchar(10), height int, weight int,
 hometown varchar(100),current_city varchar(100),education char(1),occupation varchar(100),
 monthly_income decimal(10,2),family_members varchar(200),has_car char(1),has_house char(1),
 house_desc varchar(200),hobbies varchar(200),advantages varchar(200),smoke_habit char(1),
 drink_habit char(1),has_tattoo char(1),accept_long_dist char(1),photos varchar(2000),
 status char(1) default '0',service_status varchar(30) default 'NOT_STARTED',
 matchmaker_id bigint,del_flag char(1) default '0',remark varchar(500),
 create_by varchar(64),create_time timestamp,update_by varchar(64),update_time timestamp
);
create table sys_paying_requirement(
 id bigint auto_increment primary key,paying_id bigint,age_min int,age_max int,
 hometown_require varchar(100),city_require varchar(100),education char(1),
 height_min int,height_max int,no_tattoo char(1),accept_long_dist char(1),
 smoke_habit char(1),drink_habit char(1),has_car char(1),has_house char(1),
 other_require varchar(500),remark varchar(500),create_by varchar(64),create_time timestamp,update_by varchar(64),update_time timestamp
);
create table dating_order(order_id bigint primary key,user_id bigint);
create table dating_entitlement(entitlement_id bigint primary key,user_id bigint);
create table dating_pair(pair_id bigint primary key,user_low_id bigint,user_high_id bigint);
create table dating_assignment_history(id bigint primary key,user_id bigint);
create table dating_success_case(case_id bigint primary key);
create table dating_success_case_member(case_id bigint,user_id bigint);
insert into sys_paying_user(id,phone,password,name,sex,birth_year,height,create_by) values
 (1,'13800000001','HASH','A','0',1990,175,'alice'),
 (2,'13800000002','HASH','B','1',1990,170,'bob'),
 (3,'13800000003','HASH','C','1',1990,170,'alice'),
 (4,'13800000004','HASH','D','1',1990,170,'alice');
