-- When the method step began: the method-step timeout ends payments whose method step never finished.
ALTER TABLE payment ADD COLUMN method_started_at TIMESTAMPTZ;
CREATE INDEX payment_method_watch ON payment (status, method_started_at);

-- Every webhook event paygate accepted, as received. Adyen may deliver the same event more than once.
CREATE TABLE psp_event (
    id                BIGSERIAL PRIMARY KEY,
    payment_id        UUID REFERENCES payment (id),       -- null when the event names no known payment
    event_code        TEXT        NOT NULL,
    success           BOOLEAN     NOT NULL,
    psp_reference     TEXT        NOT NULL,
    merchant_reference TEXT,
    body              JSONB       NOT NULL,
    received_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (payment_id, psp_reference, event_code, success)   -- one row per event and payment
);
CREATE INDEX psp_event_payment ON psp_event (payment_id);

-- Cancellations paygate sent to the PSP, e.g. to release an authorisation it had already given up on.
CREATE TABLE psp_cancel (
    id                    BIGSERIAL PRIMARY KEY,
    payment_id            UUID        NOT NULL REFERENCES payment (id),
    payment_psp_reference TEXT        NOT NULL,
    status                TEXT        NOT NULL,           -- SENT (the PSP accepted the request) or ERROR
    detail                TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX psp_cancel_payment ON psp_cancel (payment_id);
