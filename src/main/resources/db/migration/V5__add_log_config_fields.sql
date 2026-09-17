-- Add log configuration fields to project_config table
ALTER TABLE project_config
  ADD COLUMN log_directory VARCHAR(512) NULL COMMENT '日志目录',
  ADD COLUMN log_file_name VARCHAR(256) NULL COMMENT '日志文件名';
