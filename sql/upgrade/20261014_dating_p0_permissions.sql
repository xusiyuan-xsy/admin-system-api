-- P0 archive permissions. Back up sys_menu/sys_role_menu before deployment.
-- Requires the existing archive menu. No customer data or global scope grants.
START TRANSACTION;
SET @archive_menu = (
    SELECT menu_id FROM sys_menu
    WHERE component='system/payingUsers/index' ORDER BY menu_id LIMIT 1
);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,menu_type,visible,status,perms,create_by,create_time)
SELECT p.label,@archive_menu,p.sort_order,'#','F','0','0',p.perm,'admin',CURRENT_TIMESTAMP
FROM (
    SELECT '档案列表' label,'system:payingUsers:list' perm,1 sort_order
    UNION ALL SELECT '档案详情','system:payingUsers:query',2
    UNION ALL SELECT '新增档案','system:payingUsers:add',3
    UNION ALL SELECT '修改档案','system:payingUsers:edit',4
    UNION ALL SELECT '删除档案','system:payingUsers:remove',5
    UNION ALL SELECT '导出档案','system:payingUsers:export',6
    UNION ALL SELECT '导入档案','system:payingUsers:import',7
    UNION ALL SELECT '查看择偶需求','system:payingRequirement:query',8
    UNION ALL SELECT '新增择偶需求','system:payingRequirement:add',9
    UNION ALL SELECT '修改择偶需求','system:payingRequirement:edit',10
    UNION ALL SELECT '查询匹配','system:payingRequirement:search',11
) p
WHERE @archive_menu IS NOT NULL
  AND NOT EXISTS(SELECT 1 FROM sys_menu m WHERE m.perms=p.perm);

-- Preserve the equivalent search capability for legacy search-button holders.
-- Keep legacy menus for rollback; grant no requirement write capabilities.
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT rm.role_id,target.menu_id
FROM sys_role_menu rm
JOIN sys_menu legacy ON legacy.menu_id=rm.menu_id
JOIN sys_menu target ON target.perms='system:payingRequirement:search' AND target.status='0'
WHERE legacy.perms='system:payingUsers:queryMatching' AND legacy.status='0'
  AND NOT EXISTS(SELECT 1 FROM sys_role_menu existing
                 WHERE existing.role_id=rm.role_id AND existing.menu_id=target.menu_id);
COMMIT;

-- Review these holders manually: the legacy "document" permission is ambiguous.
-- Do NOT translate it into add/edit/query, or copy unrelated system:user grants.
SELECT rm.role_id,m.menu_id,m.perms
FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id
WHERE m.perms IN ('system:payingUsers:document','system:payingUsers:queryMatching')
ORDER BY rm.role_id,m.perms;
SELECT @archive_menu AS archive_menu_id;
