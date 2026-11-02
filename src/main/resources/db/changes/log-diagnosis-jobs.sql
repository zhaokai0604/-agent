-- Separate attempts preserve request idempotency and fence late model replies.
CREATE TABLE log_diagnosis_job (
    run_id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    instance_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt INTEGER NOT NULL,
    deadline_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP,
    error_code VARCHAR(64),
    result_text TEXT,
    CONSTRAINT uq_diagnosis_request UNIQUE(task_id, request_id),
    CONSTRAINT uq_diagnosis_attempt UNIQUE(task_id, attempt)
);
CREATE INDEX idx_diagnosis_deadline ON log_diagnosis_job(status, deadline_at);
CREATE INDEX idx_diagnosis_task ON log_diagnosis_job(task_id, attempt);
-- Old task diagnoses did not distinguish model failure from successful output.
UPDATE log_task_runtime SET ai_status='UNTRACKED' WHERE ai_status='NOT_REQUESTED';
