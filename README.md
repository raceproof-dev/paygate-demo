# paygate-demo

A small payment gateway (Spring Boot + Postgres) for 3-D Secure 2 with two PSPs: Adyen (native 3DS2) and Stripe
(PaymentIntents with Stripe's own 3DS page). It shows two race conditions of 3-D Secure flows and their fixes, each
behind a switch you can flip at runtime:

| Id | Race | Fix off | Fix on |
|---|---|---|---|
| C3 | Adyen: the shopper's challenge result (CRes) arrives just as the gateway's challenge watchdog gives up. Both submit the challenge result with the same idempotency key | The loser's "already submitted" answer (704) is recorded as FAILED, overwriting the winner's result; the merchant hears two outcomes | A 704 on the challenge step means the other path already submitted it: no change, and only the first final answer is recorded and told to the merchant |
| S1 | Stripe: the browser's return (possibly reloaded) and the webhook report the outcome at the same time | Each report is applied and told to the merchant | The first final outcome wins |

paygate-demo is a demonstration, not a production gateway: **other races of these flows are deliberately left
unfixed**. To find out which races your own gateway has, test it with [raceproof](https://pypi.org/project/raceproof/)
and its PSP packs.

### Running C3 and S1 automatically

By hand (the checkout page below, against your own Adyen or Stripe test account) a race is hard to hit: it needs two
messages within a few milliseconds. raceproof aims them hundreds of times, but C3 and S1 need its fake Adyen and fake
Stripe, which are in the commercial packs `raceproof-adyen` and `raceproof-stripe` (a time-limited trial is available
on request). Each pack has a ready config for paygate-demo; then:

```bash
raceproof fake adyen                               # the fake Adyen on 127.0.0.1:9100 (paygate's default)
raceproof run C3 --config lab.yaml                 # with C3 on: passes; PUT {"c3": false} to /_demo/fixes: fails
```

Without a pack, `pip install raceproof && raceproof demo` shows the same kind of race (R1) on a toy shop, for free.

## Run

```bash
docker compose up -d                 # Postgres on 127.0.0.1:5544
mvn spring-boot:run                  # paygate on 127.0.0.1:8090
```

paygate needs a PSP to talk to: your own PSP test account, or a fake PSP. Settings come from environment variables
(see `src/main/resources/application.yml`):

| Variable | Default | Meaning |
|---|---|---|
| `ADYEN_BASE_URL` | `http://127.0.0.1:9100/checkout/v71` | Your Adyen test endpoint, or a fake |
| `ADYEN_API_KEY` | empty | Your Adyen test API key |
| `ADYEN_MERCHANT_ACCOUNT` | `TestMerchant` | Your merchant account (not the API user's name) |
| `ADYEN_WEBHOOK_USERNAME`, `ADYEN_WEBHOOK_PASSWORD`, `ADYEN_WEBHOOK_HMAC_KEY` | empty | The webhook's Basic auth and HMAC key (hex); unset = every webhook is rejected |
| `PAYGATE_METHOD_TIMEOUT` | `10m` | How long the method step may take before paygate refuses the payment itself |
| `PAYGATE_CHALLENGE_TIMEOUT` | `11m` | Challenge watchdog timeout; shorten it (e.g. `5s`) to reach the C3 race by hand |
| `PAYGATE_WATCHDOG_SCAN_MS` | `1000` | How often the watchdog looks for timed-out challenges |
| `STRIPE_BASE_URL` | `http://127.0.0.1:9200` | `https://api.stripe.com`, or a fake |
| `STRIPE_API_KEY` | empty | Your Stripe test secret key |
| `STRIPE_WEBHOOK_SECRET` | empty | The webhook endpoint's signing secret (`whsec_...`); unset = every Stripe webhook is rejected |
| `PAYGATE_STRIPE_RETURN_BASE_URL` | = public base URL | Where Stripe sends the browser back (the browser's own GET) |
| `PAYGATE_STRIPE_PENDING_TIMEOUT` | `20m` | How long paygate waits for a Stripe outcome before it refuses the payment and cancels the PaymentIntent |
| `PAYGATE_FIX_C3`, `PAYGATE_FIX_S1` | `true` | Start values of the fix switches |

## Demo checkout page

`checkout/` is a small shop page (Vite, plain JS) that starts a payment and runs 3DS in the browser: the hidden
method iframe, then the issuer's challenge in a visible iframe. It switches the fixes and keeps watching the status
for two minutes after it is final, so a late change (an unfixed race, a webhook) shows up in its timeline.

```bash
cd checkout
npm install
npm run dev                          # http://127.0.0.1:5173, proxies /payments, /3ds and /_demo to paygate
```

- The issuer's ACS posts back from inside the iframes to `PAYGATE_PUBLIC_BASE_URL` (default `http://127.0.0.1:8090`),
  so that address must be reachable from the browser (or set it to a tunnel's https address).
- Adyen test cards: expiry 03/2030, CVC 737. The test challenge's password is `password`.
- **C3 by hand:** shorten `PAYGATE_CHALLENGE_TIMEOUT`, switch C3 off in the Fixes panel, pay, and finish the challenge
  right as the timeout runs out. The timeline flags a second final status.
- **S1 by hand:** pay with Stripe's 3DS test method, switch S1 off, authorise; the return and the webhook both report the
  outcome.

## API

| Endpoint | Who calls it |
|---|---|
| `POST /payments` `{transactionId?, merchantReference, psp?: adyen\|stripe, amount:{value,currency}, paymentMethod?}` | the merchant |
| `GET /payments/{id}` | the merchant |
| `POST /3ds/method`, `POST /3ds/challenge` | the issuer's ACS |
| `POST /3ds/method/skip` `{threeDSServerTransID}` | the shopper's browser, when the method step took 10 s (goes on with threeDSCompInd N) |
| `POST /webhooks/adyen`, `POST /webhooks/stripe` | the PSPs |
| `GET /stripe/return/{id}` | the shopper's browser, from Stripe's 3DS page |
| `GET/PUT /_demo/fixes` e.g. `{"c3": false}` | you, to switch fixes at runtime |
| `GET /_demo/payments/{id}/notifications` | the demo page: what the merchant was told, in order |

## Test

```bash
mvn test        # needs Docker (Testcontainers Postgres)
```

## License

Apache-2.0. See [LICENSE](LICENSE).
