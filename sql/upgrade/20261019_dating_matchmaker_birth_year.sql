-- Run after 20261019_dating_matchmaker_config.sql, with config writes paused.
-- Historical min_age/max_age names now store birth years, matching requirement age_min/age_max.
SET NAMES utf8mb4;

SET @drop_matchmaker_age_check = IF(
 (SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE constraint_schema=DATABASE() AND table_name='dating_matchmaker_config'
    AND constraint_name='ck_matchmaker_age' AND constraint_type='CHECK') > 0,
 'ALTER TABLE dating_matchmaker_config DROP CHECK ck_matchmaker_age',
 'SELECT 1');
PREPARE birth_year_statement FROM @drop_matchmaker_age_check;
EXECUTE birth_year_statement;
DEALLOCATE PREPARE birth_year_statement;

-- Snapshot both endpoints before updating so MySQL assignment order cannot swap them incorrectly.
CREATE TEMPORARY TABLE tmp_matchmaker_birth_year AS
SELECT config_id, min_age AS old_min_age, max_age AS old_max_age
FROM dating_matchmaker_config
WHERE min_age BETWEEN 18 AND 120 AND max_age BETWEEN min_age AND 120;

UPDATE dating_matchmaker_config c
JOIN tmp_matchmaker_birth_year old ON old.config_id=c.config_id
SET c.min_age=YEAR(CURRENT_DATE)-old.old_max_age,
    c.max_age=YEAR(CURRENT_DATE)-old.old_min_age,
    c.version=c.version+1,
    c.update_time=CURRENT_TIMESTAMP;

DROP TEMPORARY TABLE tmp_matchmaker_birth_year;

ALTER TABLE dating_matchmaker_config
 MODIFY COLUMN min_age int NULL COMMENT '允许的最早出生年份，含边界，空表示沿用原规则，不作年龄换算',
 MODIFY COLUMN max_age int NULL COMMENT '允许的最晚出生年份，含边界，与最早年份同时为空或同时填写';

-- The service additionally rejects future years using the current server year.
SET @add_matchmaker_year_check = IF(
 (SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE constraint_schema=DATABASE() AND table_name='dating_matchmaker_config'
    AND constraint_name='ck_matchmaker_birth_year' AND constraint_type='CHECK') = 0,
 'ALTER TABLE dating_matchmaker_config ADD CONSTRAINT ck_matchmaker_birth_year CHECK (
    (min_age IS NULL AND max_age IS NULL) OR
    (min_age IS NOT NULL AND max_age IS NOT NULL AND min_age >= 1900 AND max_age <= 9999 AND min_age <= max_age))',
 'SELECT 1');
PREPARE birth_year_statement FROM @add_matchmaker_year_check;
EXECUTE birth_year_statement;
DEALLOCATE PREPARE birth_year_statement;
