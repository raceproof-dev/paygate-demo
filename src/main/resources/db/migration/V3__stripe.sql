-- Which PSP handles a payment. For Stripe, psp_reference holds the PaymentIntent id.
ALTER TABLE payment ADD COLUMN psp TEXT NOT NULL DEFAULT 'adyen';
CREATE INDEX payment_psp_reference ON payment (psp_reference);

-- psp_cancel.status: SENT (Adyen accepted the cancel request; its outcome follows as a webhook),
-- CANCELED (Stripe answered with the canceled PaymentIntent) or ERROR.
