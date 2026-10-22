-- Additive upgrade after 20261016_dating_recovery.sql. No data or role grants changed.
-- Existing FAILED and legacy RETRY entries keep NULL; never invent historical reasons.
SET @dating_reason_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='dating_deduction_history' AND column_name='reason'
);
SET @dating_reason_ddl = IF(@dating_reason_exists=0,
    'ALTER TABLE dating_deduction_history ADD COLUMN reason varchar(500) NULL COMMENT ''人工重试原因；自动失败及升级前历史为空''',
    'SELECT 1'
);
PREPARE dating_reason_stmt FROM @dating_reason_ddl;
EXECUTE dating_reason_stmt;
DEALLOCATE PREPARE dating_reason_stmt;
