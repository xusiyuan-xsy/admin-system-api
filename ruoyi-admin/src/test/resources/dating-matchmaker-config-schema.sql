create table dating_matchmaker_config (
 config_id bigint auto_increment primary key,
 name varchar(100) not null, watermark_text varchar(100) not null default '',
 min_age int, max_age int, min_height int, max_height int,
 required_fields varchar(1000) not null, version bigint not null,
 create_by varchar(64) not null, create_time timestamp not null,
 update_by varchar(64) not null, update_time timestamp not null,
 constraint ck_matchmaker_birth_year check (
   (min_age is null and max_age is null) or
   (min_age is not null and max_age is not null and min_age >= 1900 and max_age <= 9999 and min_age <= max_age))
);
create table dating_matchmaker_config_state (
 state_id int primary key check(state_id=1),
 default_config_id bigint references dating_matchmaker_config(config_id),
 update_by varchar(64) not null, update_time timestamp not null
);
insert into dating_matchmaker_config_state values (1,null,'test',current_timestamp);
