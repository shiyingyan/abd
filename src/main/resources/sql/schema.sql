-- Auto Deploy Tool - Database Schema
-- Compatible with MySQL 8.0+ and H2 (MySQL mode)

create database if not exists auto_deploy;

-- Users table
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password VARCHAR(128) NOT NULL,
    password_salt VARCHAR(64) NOT NULL,
    status INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP NULL
);

-- Project config table
CREATE TABLE IF NOT EXISTS project_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_name VARCHAR(128) NOT NULL,
    version VARCHAR(64) NOT NULL,
    git_repo_url VARCHAR(512) NOT NULL,
    git_branch VARCHAR(128) DEFAULT 'main',
    git_auth_env_key VARCHAR(128),
    build_command VARCHAR(1024) NOT NULL,
    build_work_dir VARCHAR(512),
    deploy_server_host VARCHAR(128),
    deploy_server_port INT,
    deploy_server_user VARCHAR(64),
    deploy_auth_env_key VARCHAR(128),
    deploy_target_path VARCHAR(512),
    deploy_source_path VARCHAR(512),
    start_command VARCHAR(1024),
    restart_command VARCHAR(1024),
    language_type VARCHAR(32),
    language_version VARCHAR(32),
    custom_install_dir VARCHAR(512),
    project_dir VARCHAR(512),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- System settings table
CREATE TABLE IF NOT EXISTS system_settings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    setting_key VARCHAR(64) NOT NULL UNIQUE,
    setting_value TEXT,
    description VARCHAR(256),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Environment variable references table
CREATE TABLE IF NOT EXISTS env_var_refs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    var_key VARCHAR(128) NOT NULL UNIQUE,
    description VARCHAR(256),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Build records table
CREATE TABLE IF NOT EXISTS build_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_name VARCHAR(128) NOT NULL,
    package_name VARCHAR(256),
    version VARCHAR(64) NOT NULL,
    repo_url VARCHAR(512),
    build_time TIMESTAMP NOT NULL,
    build_user VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    build_mode VARCHAR(32) NOT NULL,
    log_file_path VARCHAR(512),
    deploy_status VARCHAR(32),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Deploy environment table
CREATE TABLE IF NOT EXISTS deploy_environment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL UNIQUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Server info table
CREATE TABLE IF NOT EXISTS server_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    host VARCHAR(128) NOT NULL,
    port INT DEFAULT 22,
    user VARCHAR(64),
    auth_env_key VARCHAR(128),
    server_type VARCHAR(32) NOT NULL COMMENT 'BUILD or DEPLOY',
    environment_id BIGINT COMMENT 'FK to deploy_environment, only for DEPLOY type',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_server_env FOREIGN KEY (environment_id) REFERENCES deploy_environment(id) ON DELETE SET NULL
);

-- Project environment server association table
CREATE TABLE IF NOT EXISTS project_env_server (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    environment_id BIGINT NOT NULL,
    server_id BIGINT NOT NULL,
    deploy_enabled TINYINT DEFAULT 1 COMMENT 'Whether to deploy to this server, 1=deploy 0=skip',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pes_project FOREIGN KEY (project_id) REFERENCES project_config(id) ON DELETE CASCADE,
    CONSTRAINT fk_pes_env FOREIGN KEY (environment_id) REFERENCES deploy_environment(id) ON DELETE CASCADE,
    CONSTRAINT fk_pes_server FOREIGN KEY (server_id) REFERENCES server_info(id) ON DELETE CASCADE
);

-- Insert default system settings
INSERT INTO system_settings (setting_key, setting_value, description) VALUES
    ('max_concurrent', '20', 'Max concurrent builds')
    ON DUPLICATE KEY UPDATE setting_value = setting_value;
INSERT INTO system_settings (setting_key, setting_value, description) VALUES
    ('log_retention_days', '7', 'Build log retention days')
    ON DUPLICATE KEY UPDATE setting_value = setting_value;
INSERT INTO system_settings (setting_key, setting_value, description) VALUES
    ('history_retention_days', '90', 'Build history retention days')
    ON DUPLICATE KEY UPDATE setting_value = setting_value;
