import { creq, decodeToken, methodData, postToFrame } from './threeds.js';

const $ = (id) => document.getElementById(id);
const FINAL = new Set(['COMPLETED', 'REFUSED', 'FAILED']);
const POLL_MS = 500;
const WATCH_AFTER_FINAL_MS = 120_000;   // keep watching: an unfixed bug or a late webhook can still change the status

const FIX_INFO = {
  c3: 'C3: a 704 on the challenge step means the other path already submitted it',
  s1: 'S1 (Stripe): the return and the webhook race, the first final outcome wins',
};

let run = null;   // the payment in progress

// ------------------------------------------------------------------ timeline

function log(text, kind = '') {
  const li = document.createElement('li');
  li.className = kind;
  const t = run ? `+${((performance.now() - run.startedAt) / 1000).toFixed(3)} s` : new Date().toLocaleTimeString();
  li.innerHTML = `<time></time><span></span>`;
  li.querySelector('time').textContent = t;
  li.querySelector('span').textContent = text;
  const list = $('log');
  list.appendChild(li);
  list.scrollTop = list.scrollHeight;          // scroll the list only, never the page
}

function showStatus(status) {
  const el = $('status');
  el.textContent = status;
  el.className = 'status ' + status.toLowerCase();
}

// ------------------------------------------------------------------ paygate

async function api(method, path, body, acceptedErrors = []) {
  const res = await fetch(path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
  if (!res.ok && !(acceptedErrors.includes(res.status) && json)) {
    const err = new Error(`${method} ${path} answered ${res.status}${json?.detail ? ': ' + json.detail : ''}`);
    err.status = res.status;
    throw err;
  }
  return json;
}

async function loadFixes() {
  try {
    renderFixes(await api('GET', '/_demo/fixes'));
    $('conn').textContent = 'paygate: connected';
    $('conn').className = 'pill ok';
  } catch (e) {
    $('conn').textContent = 'paygate: unreachable';
    $('conn').className = 'pill bad';
    $('fixes').innerHTML = '<p class="hint">paygate did not answer. Is it running, with demo endpoints on?</p>';
  }
}

function renderFixes(fixes) {
  const box = $('fixes');
  box.innerHTML = '';
  for (const [name, on] of Object.entries(fixes)) {
    const label = document.createElement('label');
    label.className = 'switch';
    label.innerHTML = '<input type="checkbox"><span class="track"></span><span class="text"></span>';
    const input = label.querySelector('input');
    input.checked = on;
    label.querySelector('.text').textContent = FIX_INFO[name] || name;
    input.addEventListener('change', async () => {
      try {
        renderFixes(await api('PUT', '/_demo/fixes', { [name]: input.checked }));
        log(`fix ${name.toUpperCase()} switched ${input.checked ? 'on' : 'off'}`, 'note');
      } catch (e) {
        input.checked = !input.checked;
        log(e.message, 'bad');
      }
    });
    box.appendChild(label);
  }
}

// ------------------------------------------------------------------ payment

async function pay(event) {
  event.preventDefault();
  stopWatching();
  resetThreeDs();
  $('log').innerHTML = '';
  $('payment-id').textContent = '';

  const psp = $('psp').value;
  const pan = ($('card').value === 'custom' ? $('custom-pan').value : $('card').value).replace(/\D/g, '');
  if (psp === 'adyen' && pan.length < 12) {
    log('enter a card number', 'bad');
    return;
  }
  const body = {
    merchantReference: 'shop-' + Math.random().toString(36).slice(2, 10),
    psp,
    amount: { value: Math.round(parseFloat($('amount').value) * 100), currency: $('currency').value },
    paymentMethod: psp === 'stripe' ? { id: $('stripe-pm').value } : {
      type: 'scheme',
      encryptedCardNumber: 'test_' + pan,
      encryptedExpiryMonth: 'test_03',
      encryptedExpiryYear: 'test_2030',
      encryptedSecurityCode: 'test_737',
    },
  };
  const what = psp === 'stripe' ? `Stripe ${body.paymentMethod.id}` : `Adyen card ${pan.slice(0, 4)}…${pan.slice(-4)}`;
  run = {
    startedAt: performance.now(), id: null, status: null, finalStatus: null, finalAt: 0,
    methodFor: new Set(), challengeFor: new Set(), creq: null, acsUrl: null, told: 0, toldFinal: null,
    silentMethod: psp === 'adyen' && $('silent-method').checked, timer: null, busy: false,
  };
  $('pay').disabled = true;
  log(`POST /payments  ${body.merchantReference}, ${body.amount.value / 100} ${body.amount.currency}, ${what}`);
  try {
    const view = await api('POST', '/payments', body, [502]);
    run.id = view.transactionId;
    $('payment-id').textContent = run.id;
    handle(view);
    startWatching();
  } catch (e) {
    log(e.message, 'bad');
    $('pay').disabled = false;
  }
}

function handle(view) {
  if (view.status !== run.status) {
    const before = run.status;
    run.status = view.status;
    showStatus(view.status);
    if (run.finalStatus && FINAL.has(before)) {
      log(`status changed AFTER the payment was final: ${before} → ${view.status}`, 'bad');
    } else {
      log(`status ${view.status}`, FINAL.has(view.status) ? view.status.toLowerCase() : '');
    }
    if (FINAL.has(view.status) && !run.finalStatus) {
      run.finalStatus = view.status;
      run.finalAt = performance.now();
      $('pay').disabled = false;
      log(`watching for late changes for ${WATCH_AFTER_FINAL_MS / 1000} s (a late CRes, a race, a webhook)`, 'note');
    }
  }
  const action = view.action;
  if (view.status === 'PENDING_CHALLENGE' && action?.type === 'redirect') showRedirect(action);
  if (!action?.token) return;
  if (view.status === 'PENDING_METHOD') runMethod(action, view.threeDsMethodNotificationUrl);
  if (view.status === 'PENDING_CHALLENGE') showChallenge(action);
}

/** Stripe: its own 3DS page in the iframe; it sends the browser to paygate's return URL when the shopper is done. */
function showRedirect(action) {
  if (run.challengeFor.has(action.url)) return;
  run.challengeFor.add(action.url);
  const slot = $('challenge-slot');
  slot.className = '';
  slot.innerHTML = '';
  const frame = document.createElement('iframe');
  frame.name = 'challenge-frame';
  frame.title = '3DS challenge';
  // the return is a redirect to paygate's local address: the frame may reach the local network (Chrome/Edge then ask)
  frame.setAttribute('allow', 'local-network-access; local-network; loopback-network');
  frame.src = action.url;
  slot.appendChild(frame);
  run.creq = null;
  $('abandon').disabled = false;
  log(`challenge: Stripe's 3DS page from ${host(action.url)}; it returns the browser to paygate`);
}

/** Method step: a hidden iframe posts threeDSMethodData to the ACS, which collects the browser data and posts back to paygate. */
async function runMethod(action, notificationUrl) {
  const token = decodeToken(action.token);
  const transId = token.threeDSServerTransID;
  if (run.methodFor.has(transId)) return;
  run.methodFor.add(transId);
  if (run.silentMethod) {
    log('method step: the issuer stays silent (nothing is posted); paygate gives up when its method timeout runs out', 'note');
    return;
  }
  if (token.threeDSMethodUrl) {
    postToFrame('method-frame', token.threeDSMethodUrl, { threeDSMethodData: methodData(transId, notificationUrl) });
    log(`method step: hidden iframe posts to ${host(token.threeDSMethodUrl)}; the ACS answers paygate at ${notificationUrl}`);
  } else {
    // no method URL: nothing to collect, tell paygate directly
    const data = new URLSearchParams({ threeDSMethodData: methodData(transId, notificationUrl) });
    await fetch('/3ds/method', { method: 'POST', body: data });
    log('method step: the card has no method URL; paygate told directly');
  }
}

/** Challenge: the CReq is posted into a visible iframe; the ACS posts the CRes to paygate's notification URL when done. */
function showChallenge(action) {
  const token = decodeToken(action.token);
  if (run.challengeFor.has(token.acsTransID)) return;
  run.challengeFor.add(token.acsTransID);
  const slot = $('challenge-slot');
  slot.className = '';
  slot.innerHTML = '';
  const frame = document.createElement('iframe');
  frame.name = 'challenge-frame';
  frame.title = '3DS challenge';
  frame.setAttribute('allow', 'local-network-access; local-network; loopback-network');
  slot.appendChild(frame);
  run.creq = creq(token);
  run.acsUrl = token.acsURL;
  postToFrame('challenge-frame', run.acsUrl, { creq: run.creq });
  $('repost').disabled = false;
  $('abandon').disabled = false;
  log(`challenge: CReq posted to ${host(token.acsURL)}; the CRes goes to paygate's notification URL`);
}

function repostCreq() {
  if (!run?.creq || !document.querySelector('iframe[name="challenge-frame"]')) return;
  postToFrame('challenge-frame', run.acsUrl, { creq: run.creq });
  log('challenge: CReq posted again (as a page refresh does)', 'note');
}

function abandon() {
  resetThreeDs();
  log($('psp').value === 'stripe'
    ? "challenge closed by the shopper: at paygate's pending timeout it refuses the payment and cancels the PaymentIntent"
    : "challenge closed by the shopper: paygate's watchdog submits 'abandoned' when its challenge timeout runs out", 'note');
}

function resetThreeDs() {
  const slot = $('challenge-slot');
  slot.className = 'empty';
  slot.textContent = 'The issuer\'s challenge appears here.';
  $('repost').disabled = true;
  $('abandon').disabled = true;
}

// ------------------------------------------------------------------ polling

function startWatching() {
  $('stop-watch').disabled = false;
  run.timer = setInterval(poll, POLL_MS);
}

function stopWatching() {
  if (run?.timer) {
    clearInterval(run.timer);
    run.timer = null;
    log('stopped watching', 'note');
  }
  $('stop-watch').disabled = true;
}

async function poll() {
  const mine = run;                       // answers for an earlier run (still in flight when Pay was pressed) are dropped
  if (!mine?.id || mine.busy) return;
  if (mine.finalAt && performance.now() - mine.finalAt > WATCH_AFTER_FINAL_MS) {
    stopWatching();
    return;
  }
  mine.busy = true;
  try {
    const view = await api('GET', `/payments/${mine.id}`);
    if (run !== mine) return;
    handle(view);
    const told = await api('GET', `/_demo/payments/${mine.id}/notifications`);
    if (run !== mine) return;
    showNotifications(told);
  } catch (e) {
    if (run === mine) log(e.message, 'bad');
  } finally {
    mine.busy = false;
  }
}

/** What paygate told the merchant. A second final notification is the bug a duplicate path causes (e.g. C3 unfixed). */
function showNotifications(list) {
  for (const n of list.slice(run.told)) {
    if (!FINAL.has(n.status)) {
      log(`merchant told ${n.status}`, 'note');
    } else if (run.toldFinal) {
      log(`merchant told ${n.status} AGAIN: a second final notification (first: ${run.toldFinal})`, 'bad');
    } else {
      run.toldFinal = n.status;
      log(`merchant told ${n.status}`, 'note');
    }
  }
  run.told = list.length;
}

function host(url) {
  try { return new URL(url).host; } catch { return url; }
}

// ------------------------------------------------------------------ wiring

$('pay-form').addEventListener('submit', pay);
$('card').addEventListener('change', () => { $('custom-wrap').hidden = $('card').value !== 'custom'; });
$('psp').addEventListener('change', () => {
  for (const el of document.querySelectorAll('[data-psp]')) el.hidden = el.dataset.psp !== $('psp').value;
  $('custom-wrap').hidden = $('psp').value !== 'adyen' || $('card').value !== 'custom';
});
$('repost').addEventListener('click', repostCreq);
$('abandon').addEventListener('click', abandon);
$('stop-watch').addEventListener('click', stopWatching);
loadFixes();
