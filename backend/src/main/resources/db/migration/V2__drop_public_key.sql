-- End-to-end encryption was dropped, so the per-user public key has no consumer.
ALTER TABLE users DROP COLUMN public_key;