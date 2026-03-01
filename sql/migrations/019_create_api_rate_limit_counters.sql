CREATE TABLE IF NOT EXISTS api_rate_limit_counters (
    rate_key VARCHAR(255) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    request_count INTEGER NOT NULL,
    updated_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (rate_key, window_start)
);

CREATE INDEX IF NOT EXISTS idx_api_rate_limit_counters_updated_em
    ON api_rate_limit_counters (updated_em);
