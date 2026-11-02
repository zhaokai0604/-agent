CREATE TABLE ops_team_run (
    run_id VARCHAR(64) PRIMARY KEY,
    user_id INTEGER NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    goal TEXT NOT NULL,
    skill_id VARCHAR(64) NOT NULL,
    skill_release VARCHAR(64) NOT NULL,
    skill_json TEXT NOT NULL,
    probes_json TEXT NOT NULL,
    state VARCHAR(32) NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1,
    execution_id VARCHAR(64) NOT NULL,
    instance_id VARCHAR(64) NOT NULL,
    deadline_at TIMESTAMP,
    window_started TIMESTAMP,
    remaining_ms BIGINT NOT NULL,
    model_calls INTEGER NOT NULL DEFAULT 0,
    next_event INTEGER NOT NULL DEFAULT 1,
    evidence_json TEXT,
    diagnosis_json TEXT,
    plan_json TEXT,
    plan_hash VARCHAR(64),
    error_code VARCHAR(64),
    write_started BOOLEAN NOT NULL DEFAULT FALSE,
    worker_claimed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_team_request UNIQUE(user_id,request_id)
);
CREATE INDEX idx_team_deadline ON ops_team_run(state,deadline_at);
CREATE INDEX idx_team_owner ON ops_team_run(user_id,created_at);
CREATE TABLE ops_team_event (
    run_id VARCHAR(64) NOT NULL,
    event_no INTEGER NOT NULL,
    execution_id VARCHAR(64) NOT NULL,
    actor VARCHAR(64) NOT NULL,
    kind VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(run_id,event_no)
);
-- Locks survive interruption. Only terminal verified completion or explicit human reconciliation releases them.
CREATE TABLE ops_team_resource_lock (
    resource_key VARCHAR(128) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    execution_id VARCHAR(64) NOT NULL
);
