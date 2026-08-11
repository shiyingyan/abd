CREATE TABLE IF NOT EXISTS build_queue_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    config_id BIGINT NOT NULL,
    project_name VARCHAR(128) NOT NULL,
    target_branch VARCHAR(128) NOT NULL,
    deploy_environments VARCHAR(512) NULL COMMENT '环境ID，逗号分隔',
    deploy_servers VARCHAR(1024) NULL COMMENT '服务器ID列表，逗号分隔',
    build_mode VARCHAR(32) NOT NULL DEFAULT 'LOCAL',
    selected_modules TEXT NULL,
    auto_deploy TINYINT DEFAULT 1,
    status INT NOT NULL DEFAULT 3 COMMENT '0=成功 1=失败 2=执行中 3=排队中 4=已取消',
    priority INT NOT NULL DEFAULT 0,
    submit_time DATETIME NOT NULL,
    start_time DATETIME NULL,
    completion_time DATETIME NULL,
    build_record_id BIGINT NULL COMMENT '关联构建记录ID',
    build_task_id VARCHAR(32) NULL COMMENT '关联内存任务ID',
    worktree_path VARCHAR(512) NULL,
    error_message VARCHAR(1024) NULL,
    log_file_path VARCHAR(512) NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status (status),
    INDEX idx_user_project (username, config_id),
    INDEX idx_priority_submit (priority DESC, submit_time ASC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE build_records ADD COLUMN queue_task_id BIGINT NULL COMMENT '关联队列任务ID';
