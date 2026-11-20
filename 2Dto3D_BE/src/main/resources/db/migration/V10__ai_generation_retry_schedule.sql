ALTER TABLE ai_generation_jobs
    ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX ix_ai_generation_jobs_retry_schedule
    ON ai_generation_jobs (status, next_attempt_at, created_at)
    WHERE status = 'PENDING';
