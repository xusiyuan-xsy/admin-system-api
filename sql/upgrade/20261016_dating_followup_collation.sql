-- Run after 20261016_dating_exception_followup.sql.
-- Align account references with sys_user.user_name, not the database default.
-- Preserve values, nullability, length, indexes and existing column comments.
-- Only utf8mb4 account schemas are supported; missing/unexpected metadata fails PREPARE.
SET NAMES utf8mb4;
SET SESSION lock_wait_timeout = 15;
SET @dating_account_collation = (
    SELECT collation_name FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='sys_user' AND column_name='user_name'
      AND character_set_name='utf8mb4'
);
SET @dating_handler_ddl = (
    SELECT IF(@dating_account_collation IS NULL, NULL,
        IF(collation_name=@dating_account_collation, 'SELECT 1',
            CONCAT('ALTER TABLE dating_exception_followup MODIFY COLUMN handler varchar(64)',
                ' CHARACTER SET utf8mb4 COLLATE ', @dating_account_collation,
                ' NULL DEFAULT NULL COMMENT ', QUOTE(column_comment))))
    FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='dating_exception_followup' AND column_name='handler'
      AND character_set_name='utf8mb4' AND column_type='varchar(64)' AND is_nullable='YES'
);
PREPARE dating_handler_stmt FROM @dating_handler_ddl;
EXECUTE dating_handler_stmt;
DEALLOCATE PREPARE dating_handler_stmt;

SET @dating_handler_ddl = (
    SELECT IF(@dating_account_collation IS NULL, NULL,
        IF(collation_name=@dating_account_collation, 'SELECT 1',
            CONCAT('ALTER TABLE dating_exception_followup_history MODIFY COLUMN handler varchar(64)',
                ' CHARACTER SET utf8mb4 COLLATE ', @dating_account_collation,
                ' NULL DEFAULT NULL COMMENT ', QUOTE(column_comment))))
    FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='dating_exception_followup_history' AND column_name='handler'
      AND character_set_name='utf8mb4' AND column_type='varchar(64)' AND is_nullable='YES'
);
PREPARE dating_handler_stmt FROM @dating_handler_ddl;
EXECUTE dating_handler_stmt;
DEALLOCATE PREPARE dating_handler_stmt;
