-- Use SVG names available in admin-page. Only menu icons are changed.
-- Run after the customer and matchmaker configuration menus are installed.
CREATE TEMPORARY TABLE dating_menu_icons_guard (
    ok int NOT NULL CHECK (ok = 1)
);
START TRANSACTION;
INSERT INTO dating_menu_icons_guard
SELECT IF(
    (SELECT COUNT(*) FROM sys_menu
     WHERE component='system/payingUsers/index' AND menu_type='C')=1
    AND (SELECT COUNT(*) FROM sys_menu
     WHERE component='dating/matchmakerConfig/index' AND menu_type='C')=1,
    1,0);
UPDATE sys_menu SET icon='user'
WHERE component='system/payingUsers/index' AND menu_type='C'
    AND NOT (icon <=> 'user');
UPDATE sys_menu SET icon='system'
WHERE component='dating/matchmakerConfig/index' AND menu_type='C'
    AND NOT (icon <=> 'system');
COMMIT;
DROP TEMPORARY TABLE dating_menu_icons_guard;
