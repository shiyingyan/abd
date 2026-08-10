-- Persist Shiro sessions to the database so logins survive application restarts.
CREATE TABLE IF NOT EXISTS shiro_session (
  session_id VARCHAR(128) NOT NULL PRIMARY KEY,
  session_data LONGTEXT,
  last_access_time DATETIME NOT NULL,
  expire_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_shiro_session_expire ON shiro_session (expire_at);
