CREATE TABLE ops_skill_release (
    fingerprint VARCHAR(64) PRIMARY KEY,
    payload LONGTEXT NOT NULL,
    skill_count INTEGER NOT NULL,
    evaluation_json LONGTEXT NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE ops_skill_version (
    skill_id VARCHAR(64) NOT NULL,
    version VARCHAR(32) NOT NULL,
    contract_fingerprint VARCHAR(64) NOT NULL,
    first_release VARCHAR(64) NOT NULL,
    PRIMARY KEY(skill_id,version),
    FOREIGN KEY(first_release) REFERENCES ops_skill_release(fingerprint)
);
CREATE TABLE ops_skill_active (
    singleton_id INTEGER PRIMARY KEY,
    fingerprint VARCHAR(64),
    previous_fingerprint VARCHAR(64),
    generation BIGINT NOT NULL,
    activated_by VARCHAR(64) NOT NULL,
    activated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(fingerprint) REFERENCES ops_skill_release(fingerprint),
    FOREIGN KEY(previous_fingerprint) REFERENCES ops_skill_release(fingerprint)
);
INSERT INTO ops_skill_active(singleton_id,generation,activated_by) VALUES(1,0,'SYSTEM');
CREATE TABLE ops_skill_activation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    from_fingerprint VARCHAR(64),
    to_fingerprint VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    actor VARCHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    generation BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(to_fingerprint) REFERENCES ops_skill_release(fingerprint)
);
CREATE TABLE ops_skill_run_pin (
    run_id VARCHAR(64) PRIMARY KEY,
    release_fingerprint VARCHAR(64) NOT NULL,
    requester VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(release_fingerprint) REFERENCES ops_skill_release(fingerprint)
);
