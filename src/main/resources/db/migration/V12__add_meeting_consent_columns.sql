ALTER TABLE meeting
    ADD COLUMN consent_agreed BOOLEAN DEFAULT FALSE,
    ADD COLUMN consent_notice_text VARCHAR(1000),
    ADD COLUMN consent_confirmed_at TIMESTAMP NULL,
    ADD COLUMN consent_confirmed_by VARCHAR(100);