-- Migration: Add project_key column to project_config table
-- Run this on existing databases to add the project_key feature

ALTER TABLE project_config 
ADD COLUMN project_key VARCHAR(64) NOT NULL DEFAULT '' AFTER id;

-- Generate project_key for existing records
UPDATE project_config 
SET project_key = MD5(CONCAT(project_name, version, DATE_FORMAT(created_at, '%Y%m%d%H%i%s')))
WHERE project_key = '';

-- Add unique constraint (optional, for data integrity)
-- ALTER TABLE project_config ADD UNIQUE INDEX idx_project_key (project_key);
