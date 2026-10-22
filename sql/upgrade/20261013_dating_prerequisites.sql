-- Optional prerequisite for databases where dating_package has never been created.
-- Creates only the missing table; never drops data or inserts sample packages.
-- Apply before 20261013_dating_billing.sql. Existing profile/menu tables are required.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS dating_package (
    package_id bigint NOT NULL AUTO_INCREMENT,
    package_name varchar(100) NOT NULL,
    package_type char(1) NOT NULL DEFAULT '0',
    price decimal(10,2) NOT NULL,
    limit_expire char(1) NOT NULL DEFAULT '0',
    service_duration int NOT NULL DEFAULT 0,
    limit_service_times char(1) NOT NULL DEFAULT '0',
    service_times int NOT NULL DEFAULT 0,
    description varchar(500) DEFAULT '',
    cover_image varchar(255) DEFAULT '',
    sort_order int NOT NULL DEFAULT 0,
    status char(1) NOT NULL DEFAULT '0',
    del_flag char(1) NOT NULL DEFAULT '0',
    create_by varchar(64) NOT NULL DEFAULT '',
    create_time datetime NOT NULL,
    update_by varchar(64) NOT NULL DEFAULT '',
    update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (package_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
