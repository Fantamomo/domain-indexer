CREATE TABLE IF NOT EXISTS site_problems (
    "site" VARCHAR(255) PRIMARY KEY,
    item_id VARCHAR(64) NOT NULL,
    "problem" VARCHAR(64) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    record_type VARCHAR(16) NOT NULL,
    record_target VARCHAR(255) NOT NULL,
    endpoint VARCHAR(512) NOT NULL,
    details TEXT NOT NULL,
    remote_address VARCHAR(64) NULL,
    "exception" TEXT NULL,
    tech_facts TEXT NULL,
    first_occurred TIMESTAMP NOT NULL,
    last_occurred TIMESTAMP NOT NULL
);
