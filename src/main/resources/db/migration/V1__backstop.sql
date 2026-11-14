CREATE TABLE merchant (
 id VARCHAR(80) PRIMARY KEY, status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
 version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE merchant_feed (
 merchant_id VARCHAR(80) PRIMARY KEY REFERENCES merchant(id), revision BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE merchant_event (
 id VARCHAR(80) PRIMARY KEY, merchant_id VARCHAR(80) NOT NULL REFERENCES merchant(id),
 type VARCHAR(12) NOT NULL, amount NUMERIC(19,2) NOT NULL, currency VARCHAR(3) NOT NULL,
 occurred_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX merchant_event_window ON merchant_event(merchant_id, occurred_at);
CREATE TABLE risk_flag (
 merchant_id VARCHAR(80) REFERENCES merchant(id), rule VARCHAR(24), ratio DOUBLE PRECISION NOT NULL,
 last_seen_at TIMESTAMP(6) NOT NULL, PRIMARY KEY(merchant_id, rule)
);
CREATE TABLE merchant_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, merchant_id VARCHAR(80) REFERENCES merchant(id),
 actor VARCHAR(80) NOT NULL, action VARCHAR(24) NOT NULL,
 from_status VARCHAR(10) NOT NULL, to_status VARCHAR(10) NOT NULL,
 reason VARCHAR(500) NOT NULL, at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX merchant_audit_history ON merchant_audit(merchant_id, id);
