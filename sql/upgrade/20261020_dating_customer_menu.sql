-- Move the existing customer archive menu, keeping its ID, children and permissions.
-- Run after the dating billing menu migration. No business records are changed.
CREATE TEMPORARY TABLE dating_customer_menu_guard (
    ok int NOT NULL CHECK (ok = 1)
);
START TRANSACTION;
INSERT INTO dating_customer_menu_guard
SELECT IF(
    (SELECT COUNT(*) FROM sys_menu WHERE component='system/payingUsers/index' AND menu_type='C')=1
    AND (SELECT COUNT(*) FROM sys_menu WHERE path='dating' AND parent_id=0
        AND menu_type='M' AND status='0' AND visible='0' AND COALESCE(perms,'')='')=1,
    1,0);
SET @customer_menu=(SELECT menu_id FROM sys_menu WHERE component='system/payingUsers/index' AND menu_type='C');
SET @dating_root=(SELECT menu_id FROM sys_menu WHERE path='dating' AND parent_id=0 AND menu_type='M');
INSERT INTO dating_customer_menu_guard
SELECT IF(NOT EXISTS(
    SELECT 1 FROM sys_menu sibling JOIN sys_menu customer ON customer.menu_id=@customer_menu
    WHERE sibling.parent_id=@dating_root AND sibling.menu_id!=@customer_menu
        AND (sibling.path=customer.path OR sibling.menu_name=customer.menu_name)
),1,0);
-- Preserve navigation for roles already authorized for this page.
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT rm.role_id,@dating_root FROM sys_role_menu rm
WHERE rm.menu_id=@customer_menu
    AND NOT EXISTS(SELECT 1 FROM sys_role_menu parent
        WHERE parent.role_id=rm.role_id AND parent.menu_id=@dating_root);
UPDATE sys_menu SET parent_id=@dating_root,order_num=0,
    update_by='admin',update_time=CURRENT_TIMESTAMP
WHERE menu_id=@customer_menu AND (parent_id!=@dating_root OR order_num!=0);
COMMIT;
DROP TEMPORARY TABLE dating_customer_menu_guard;
