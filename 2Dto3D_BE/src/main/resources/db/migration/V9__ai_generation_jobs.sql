CREATE TABLE ai_generation_jobs (
    generation_job_id UUID PRIMARY KEY,
    user_id INTEGER NOT NULL,
    retry_of UUID,
    result_model_id INTEGER,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    prompt VARCHAR(255) NOT NULL,
    style VARCHAR(60) NOT NULL,
    quality VARCHAR(60) NOT NULL,
    input_mode VARCHAR(16) NOT NULL,
    input_artifact_key VARCHAR(512),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    provider_reference VARCHAR(255),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    error_message VARCHAR(500),
    result_artifact_key VARCHAR(512),
    processing_lease_owner VARCHAR(128),
    processing_lease_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_ai_generation_jobs_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_ai_generation_jobs_retry FOREIGN KEY (retry_of)
        REFERENCES ai_generation_jobs (generation_job_id) ON DELETE RESTRICT,
    CONSTRAINT fk_ai_generation_jobs_result_model FOREIGN KEY (result_model_id)
        REFERENCES model_3d (model_id) ON DELETE RESTRICT,
    CONSTRAINT uq_ai_generation_jobs_owner_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_ai_generation_jobs_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_ai_generation_jobs_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_ai_generation_jobs_idempotency_key CHECK (length(trim(idempotency_key)) > 0),
    CONSTRAINT ck_ai_generation_jobs_request_hash CHECK (length(request_hash) = 64)
);

CREATE INDEX ix_ai_generation_jobs_owner_history
    ON ai_generation_jobs (user_id, created_at DESC, generation_job_id DESC);

CREATE INDEX ix_ai_generation_jobs_pending_claim
    ON ai_generation_jobs (status, processing_lease_until, created_at)
    WHERE status IN ('PENDING', 'PROCESSING');
