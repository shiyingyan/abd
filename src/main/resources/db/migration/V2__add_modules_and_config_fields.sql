-- V2: Add module scanning support and deployment config fields

-- Add install_dir, script_dir, and module scan tracking to project_config
ALTER TABLE project_config
  ADD COLUMN install_dir VARCHAR(512) NULL COMMENT '项目安装目录(部署服务器上的根目录)',
  ADD COLUMN script_dir VARCHAR(512) NULL COMMENT '执行脚本目录(start/restart命令的工作目录)',
  ADD COLUMN last_module_scan_at TIMESTAMP NULL COMMENT '最近模块扫描时间',
  ADD COLUMN last_module_scan_msg VARCHAR(512) NULL COMMENT '最近扫描结果消息';

-- Project module table for multi-module project support
CREATE TABLE IF NOT EXISTS project_module (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id BIGINT NOT NULL,
  module_key VARCHAR(256) NOT NULL COMMENT '项目内唯一标识(相对路径形式)',
  module_name VARCHAR(128) NOT NULL COMMENT '显示名称(artifactId或目录名)',
  module_path VARCHAR(512) NOT NULL COMMENT '相对构建工作目录的路径',
  parent_module_key VARCHAR(256) NULL COMMENT '父模块key(层级关系)',
  scanned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_project_module (project_id, module_key),
  CONSTRAINT fk_pm_project FOREIGN KEY (project_id) REFERENCES project_config(id) ON DELETE CASCADE
);

-- Add build selection and deploy info to build_records
ALTER TABLE build_records
  ADD COLUMN selected_modules TEXT NULL COMMENT 'JSON数组: 选中模块路径',
  ADD COLUMN selected_envs VARCHAR(512) NULL COMMENT '逗号分隔环境名',
  ADD COLUMN auto_deploy TINYINT NULL COMMENT '是否自动部署',
  ADD COLUMN artifact_paths TEXT NULL COMMENT 'JSON: 产物路径映射',
  ADD COLUMN build_server_host VARCHAR(128) NULL COMMENT '构建服务器地址';
