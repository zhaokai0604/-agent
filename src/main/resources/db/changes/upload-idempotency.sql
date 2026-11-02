CREATE TABLE log_upload_request (
    user_id INTEGER NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, request_key)
);
CREATE INDEX idx_upload_request_task ON log_upload_request(task_id);
