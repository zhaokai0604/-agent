-- No FK to log_analysis_task: a deleted task retains its fencing tombstone.
CREATE TABLE log_task_runtime (
    task_id VARCHAR(64) PRIMARY KEY,
    execution_id VARCHAR(64),
    instance_id VARCHAR(64),
    phase VARCHAR(64) NOT NULL,
    runtime_status VARCHAR(32) NOT NULL,
    heartbeat_at TIMESTAMP,
    lease_until TIMESTAMP,
    attempt INTEGER NOT NULL DEFAULT 0,
    input_path TEXT,
    input_sha256 VARCHAR(64),
    report_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED',
    ai_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED',
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);
CREATE INDEX idx_log_runtime_lease ON log_task_runtime(runtime_status, lease_until);
