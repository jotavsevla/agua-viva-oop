ALTER TABLE atendimentos_idempotencia
ADD COLUMN IF NOT EXISTS request_hash VARCHAR(64);
