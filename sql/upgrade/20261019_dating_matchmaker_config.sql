-- Additive migration; no configuration is enabled until the first configuration is created.
SET NAMES utf8mb4;

SET @add_requirement_remark = IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='sys_paying_requirement' AND column_name='remark') = 0,
 'ALTER TABLE sys_paying_requirement ADD COLUMN remark varchar(500) NULL COMMENT ''需求备注'' AFTER other_require',
 'SELECT 1');
PREPARE matchmaker_schema_statement FROM @add_requirement_remark;
EXECUTE matchmaker_schema_statement;
DEALLOCATE PREPARE matchmaker_schema_statement;

CREATE TABLE IF NOT EXISTS dating_matchmaker_config (
 config_id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '红娘功能配置标识',
 name varchar(100) NOT NULL COMMENT '配置名称',
 watermark_text varchar(100) NOT NULL DEFAULT '' COMMENT '新上传照片水印文字，空表示不加水印',
 min_age int NULL COMMENT '允许的最小年龄，按当前年份换算出生年份，空表示沿用原规则',
 max_age int NULL COMMENT '允许的最大年龄，与最小年龄同时为空或同时填写',
 min_height int NULL COMMENT '允许的最低身高，单位厘米，空表示沿用原规则',
 max_height int NULL COMMENT '允许的最高身高，单位厘米',
 required_fields varchar(1000) NOT NULL COMMENT '需求必填字段编码JSON数组，空数组表示不增加必填限制',
 version bigint NOT NULL DEFAULT 1 COMMENT '乐观锁版本，防止覆盖并发修改',
 create_by varchar(64) NOT NULL COMMENT '创建账号',
 create_time datetime NOT NULL COMMENT '创建时间',
 update_by varchar(64) NOT NULL COMMENT '最近修改账号',
 update_time datetime NOT NULL COMMENT '最近修改时间',
 CONSTRAINT ck_matchmaker_age CHECK (
   (min_age IS NULL AND max_age IS NULL) OR
   (min_age IS NOT NULL AND max_age IS NOT NULL AND min_age >= 18 AND max_age <= 120 AND min_age <= max_age)),
 CONSTRAINT ck_matchmaker_height CHECK (
   (min_height IS NULL AND max_height IS NULL) OR
   (min_height IS NOT NULL AND max_height IS NOT NULL AND min_height >= 100 AND max_height <= 250 AND min_height <= max_height)),
 CONSTRAINT ck_matchmaker_version CHECK(version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='红娘功能配置方案，仅默认方案参与业务规则';

CREATE TABLE IF NOT EXISTS dating_matchmaker_config_state (
 state_id int NOT NULL PRIMARY KEY COMMENT '固定为1，全局默认配置单例及事务锁',
 default_config_id bigint NULL COMMENT '唯一默认配置标识，无配置时为空',
 update_by varchar(64) NOT NULL COMMENT '最近切换默认配置的账号',
 update_time datetime NOT NULL COMMENT '最近切换默认配置时间',
 CONSTRAINT ck_matchmaker_state CHECK(state_id = 1),
 CONSTRAINT fk_matchmaker_default FOREIGN KEY(default_config_id) REFERENCES dating_matchmaker_config(config_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='红娘默认配置指针，串行化首条创建和默认切换';

INSERT INTO dating_matchmaker_config_state(state_id,default_config_id,update_by,update_time)
SELECT 1,NULL,'admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM dating_matchmaker_config_state WHERE state_id=1);

-- Register under the existing dating module; do not silently grant roles new privileges.
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,
 menu_type,visible,status,perms,icon,create_by,create_time)
SELECT '红娘功能配置',m.parent_id,90,'matchmakerConfig','dating/matchmakerConfig/index',1,1,
 'C','0','0','dating:matchmakerConfig:list','system','admin',CURRENT_TIMESTAMP
FROM sys_menu m WHERE m.component='dating/serviceOptions/index'
 AND NOT EXISTS(SELECT 1 FROM sys_menu e WHERE e.perms='dating:matchmakerConfig:list')
ORDER BY m.menu_id LIMIT 1;

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,menu_type,visible,status,perms,create_by,create_time)
SELECT a.label,m.menu_id,a.sort,'#','F','0','0',a.permission,'admin',CURRENT_TIMESTAMP
FROM sys_menu m CROSS JOIN (
 SELECT '新增红娘配置' label,1 sort,'dating:matchmakerConfig:add' permission
 UNION ALL SELECT '修改红娘配置',2,'dating:matchmakerConfig:edit'
 UNION ALL SELECT '删除红娘配置',3,'dating:matchmakerConfig:remove'
 UNION ALL SELECT '设置默认红娘配置',4,'dating:matchmakerConfig:default'
) a
WHERE m.perms='dating:matchmakerConfig:list'
 AND NOT EXISTS(SELECT 1 FROM sys_menu e WHERE e.perms=a.permission);
