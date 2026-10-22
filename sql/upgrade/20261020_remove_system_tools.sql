-- Remove retired tool menus and the RuoYi website link.
-- Keep business tables and historical generator metadata tables untouched.
-- Apply after the baseline seed. Safe to replay.
DROP TEMPORARY TABLE IF EXISTS retired_tool_menu_ids;
DROP TEMPORARY TABLE IF EXISTS retired_tool_menu_guard;
CREATE TEMPORARY TABLE retired_tool_menu_ids (menu_id BIGINT PRIMARY KEY);
CREATE TEMPORARY TABLE retired_tool_menu_guard (ok INT NOT NULL CHECK (ok=1));
INSERT INTO retired_tool_menu_ids VALUES
    (3),(4),(115),(116),(117),(1055),(1056),(1057),(1058),(1059),(1060);

START TRANSACTION;
-- Refuse to remove IDs repurposed for another module.
INSERT INTO retired_tool_menu_guard
SELECT IF(COUNT(*)=0,1,0)
FROM sys_menu m JOIN retired_tool_menu_ids t ON t.menu_id=m.menu_id
WHERE COALESCE((
    (m.menu_id=3 AND m.parent_id=0 AND m.path='tool')
    OR (m.menu_id=4 AND m.parent_id=0 AND m.path IN
        ('http://ruoyi.vip','https://ruoyi.vip','http://www.ruoyi.vip','https://www.ruoyi.vip'))
    OR (m.menu_id IN (115,116,117) AND m.parent_id=3 AND m.component IN
        ('tool/build/index','tool/gen/index','tool/swagger/index'))
    OR (m.menu_id BETWEEN 1055 AND 1060 AND m.parent_id=116 AND m.perms LIKE 'tool:gen:%')
),0)=0;
-- Custom children must be reviewed, not silently deleted or orphaned.
INSERT INTO retired_tool_menu_guard
SELECT IF(COUNT(*)=0,1,0)
FROM sys_menu m JOIN retired_tool_menu_ids parent ON parent.menu_id=m.parent_id
WHERE m.menu_id NOT IN (3,4,115,116,117,1055,1056,1057,1058,1059,1060);
DELETE rm FROM sys_role_menu rm JOIN retired_tool_menu_ids t ON t.menu_id=rm.menu_id;
DELETE m FROM sys_menu m JOIN retired_tool_menu_ids t ON t.menu_id=m.menu_id;
COMMIT;
DROP TEMPORARY TABLE retired_tool_menu_guard;
DROP TEMPORARY TABLE retired_tool_menu_ids;
