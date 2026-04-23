SET NAMES utf8mb4;

-- ----------------------------
-- 档案表（自身信息）
-- ----------------------------
drop table if exists sys_paying_user;
create table sys_paying_user (
  id                bigint(20)      not null auto_increment              comment '主键ID',
  phone             varchar(11)     not null                             comment '手机号（登录账号）',
  password          varchar(100)    not null                             comment '登录密码（加密存储）',
  last_login_ip     varchar(128)    default null                         comment '最后登录IP',
  last_login_time   datetime        default null                         comment '最后登录时间',
  name              varchar(20)     default null                         comment '姓名',
  sex               char(1)         default '0'                          comment '性别（0男 1女）',
  birth_year        smallint(4)     not null                             comment '出生年份',
  zodiac            varchar(10)     default null                         comment '属相',
  height            smallint(3)      not null                             comment '身高(cm)',
  weight            smallint(3)      not null                             comment '体重(斤)',
  hometown          varchar(50)     default null                         comment '家乡',
  current_city      varchar(50)     default null                         comment '现居城市',
  education         char(1)         default null                         comment '学历(0无要求 1小学 2初中 3高中 4大专 5本科 6硕士 7博士 8博士后)',
  occupation        varchar(50)     default null                         comment '职业',
  monthly_income    decimal(10, 2)  default null                         comment '月收入(元)',
  family_members    varchar(100)    default null                         comment '家庭成员描述',
  has_car           char(1)         default '0'                          comment '是否有车（0无 1有）',
  has_house         char(1)         default '0'                          comment '是否有房（0无 1有）',
  house_desc        varchar(200)    default null                         comment '房产情况描述',
  hobbies           varchar(200)    default null                         comment '兴趣爱好',
  advantages        varchar(200)    default null                         comment '个人优点/闪光点',
  smoke_habit       char(1)         default '0'                          comment '抽烟情况（0无 1偶尔 2经常）',
  drink_habit       char(1)         default '0'                          comment '喝酒情况（0无 1偶尔 2经常）',
  has_tattoo        char(1)         default '0'                          comment '是否有纹身（0无 1有）',
  accept_long_dist  char(1)         default '0'                          comment '是否接受异地（0否 1是）',
  photos            json            default null                         comment '相册图片URL数组，如["url1","url2"]',
  status            char(1)         default '0'                          comment '状态（0启用 1禁用）',
  del_flag          char(1)         default '0'                          comment '删除标志（0存在 2删除）',
  remark            varchar(500)    default null                         comment '备注',
  create_by         varchar(64)     default ''                           comment '创建者',
  create_time       datetime                                             comment '创建时间',
  update_by         varchar(64)     default ''                           comment '更新者',
  update_time       datetime                                             comment '更新时间',
  primary key (id),
  unique key uk_phone (phone)
) engine=innodb auto_increment=1 comment = '档案-自身信息表';





-- ----------------------------
-- 初始化数据
-- ----------------------------
insert into sys_paying_user (
  phone, password,
  sex, birth_year, zodiac, height, weight,
  hometown, current_city,
  education, occupation, monthly_income,
  family_members,
  has_car, has_house, house_desc,
  hobbies, advantages,
  smoke_habit, drink_habit, has_tattoo,
  accept_long_dist,
  status, del_flag, create_by, create_time
) values (
  '13800000000', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIbDr9G',
  '0', 2000, '虎', 170, 100,
  '汕头潮南', '深圳',
  '0', '跨境电商', 20000.00,
  '父母、哥哥、姐姐',
  '0', '0', '商品房',
  '运动', '情绪稳定、性格开朗',
  '2', '0', '0',
  '0',
  '0', '0', 'admin', sysdate()
);




-- ----------------------------
-- 要求表
-- ----------------------------
drop table if exists sys_paying_requirement;
create table sys_paying_requirement (
  id                bigint(20)      not null auto_increment              comment '主键ID',
  paying_id         bigint(20)      not null                             comment '关联档案ID（sys_paying_user.id）',
  age_min           smallint(4)     default null                         comment '要求对方最小出生年份（如1999）',
  age_max           smallint(4)     default null                         comment '要求对方最大出生年份（如2004）',
  hometown_require  varchar(100)    default null                         comment '要求对方家乡',
  city_require      varchar(100)    default null                         comment '要求对方所在城市',
  education         char(1)         default '0'                          comment '要求对方学历（0无要求 1小学及以上 2初中及以上 3高中及以上 4大专及以上 5本科及以上 6硕士及以上 7博士及以上 8博士后）',
  height_min        smallint(3)      default null                        comment '要求对方最低身高(cm)',
  height_max        smallint(3)      default null                        comment '要求对方最高身高(cm)',
  no_tattoo         char(1)         default '1'                          comment '要求无纹身（0不限 1要求无纹身）',
  accept_long_dist  char(1)         default '0'                          comment '是否接受异地（0否 1是）',
  smoke_habit       char(1)         default '0'                          comment '抽烟情况（0无 1偶尔 2经常）',
  drink_habit       char(1)         default '0'                          comment '喝酒情况（0无 1偶尔 2经常）',
  has_car           char(1)         default '0'                          comment '是否有车（0无 1有）',
  has_house         char(1)         default '0'                          comment '是否有房（0无 1有）',
  other_require     varchar(500)    default null                         comment '其他要求',
  create_by         varchar(64)     default ''                           comment '创建者',
  create_time       datetime                                             comment '创建时间',
  update_by         varchar(64)     default ''                           comment '更新者',
  update_time       datetime                                             comment '更新时间',
  primary key (id)
) engine=innodb auto_increment=1 comment = '档案-要求表';


-- ----------------------------
-- 初始化数据
-- ----------------------------

insert into sys_paying_requirement (
  id,
  paying_id,
  age_min, age_max,
  hometown_require, city_require,
  education, 
  height_min, height_max,
  no_tattoo,
  accept_long_dist, smoke_habit, drink_habit, has_car, has_house,
  other_require,
  create_by, create_time
) values (
  1,
  1,
  1999, 2004,
  '汕头', '深圳',
  '0',
  155, 180,
  '1',
  '0', '0', '0', '0', '0',
  '无特殊要求',
  'admin', sysdate()
);