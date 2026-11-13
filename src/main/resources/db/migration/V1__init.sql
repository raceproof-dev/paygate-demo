-- One row per payment, with its current status.
CREATE TABLE payment (
    id                   UUID PRIMARY KEY,
    merchant_reference   TEXT        NOT NULL,
    amount_value         BIGINT      NOT NULL,
    currency             CHAR(3)     NOT NULL,
    status               TEXT        NOT NULL,
    three_ds_trans_id    TEXT UNIQUE,
    payment_data         TEXT,
    psp_reference        TEXT,
    challenge_started_at TIMESTAMPTZ,
    watchdog_fired_at    TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX payment_challenge_watch ON payment (status, challenge_started_at);

-- What the merchant was told, in order. A real gateway would send these as callbacks;
-- paygate records them so a test can count, e.g., how many final statuses the merchant heard.
CREATE TABLE merchant_notification (
    id          BIGSERIAL PRIMARY KEY,
    payment_id  UUID        NOT NULL REFERENCES payment (id),
    status      TEXT        NOT NULL,
    detail      JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX merchant_notification_payment ON merchant_notification (payment_id);

-- Written when paygate gives up on a payment without a definitive PSP answer (an "artificial" refusal or failure).
-- A later success from the PSP must then be reconciled; reconciled_at records that it was.
CREATE TABLE refusal_marker (
    id            BIGSERIAL PRIMARY KEY,
    payment_id    UUID        NOT NULL REFERENCES payment (id),
    reason        TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    reconciled_at TIMESTAMPTZ
);
CREATE INDEX refusal_marker_payment ON refusal_marker (payment_id);
