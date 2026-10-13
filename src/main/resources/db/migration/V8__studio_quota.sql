CREATE TABLE studio_quota (
    scope VARCHAR(120) PRIMARY KEY,
    window_until DATETIME(6) NOT NULL,
    used_requests INT NOT NULL,
    used_tokens BIGINT NOT NULL,
    cooldown_until DATETIME(6) NULL
);
ALTER TABLE job ADD COLUMN quota_wait_until DATETIME(6) NULL;
