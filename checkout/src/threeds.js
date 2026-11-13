// The browser side of native 3-D Secure 2 (EMVCo): reading Adyen's action token, and building the messages the
// browser posts to the issuer's ACS. Adyen's token is standard base64 JSON; threeDSMethodData and the CReq are
// base64url JSON without padding.

const utf8 = new TextEncoder();
const utf8Decoder = new TextDecoder();

export function decodeToken(token) {
  const binary = atob(token.replace(/\s+/g, ''));
  return JSON.parse(utf8Decoder.decode(Uint8Array.from(binary, (c) => c.charCodeAt(0))));
}

export function base64Url(value) {
  const bytes = utf8.encode(JSON.stringify(value));
  let binary = '';
  bytes.forEach((b) => { binary += String.fromCharCode(b); });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** What the browser posts to the ACS's method URL; the ACS posts it back to threeDSMethodNotificationURL. */
export function methodData(threeDSServerTransID, threeDSMethodNotificationURL) {
  return base64Url({ threeDSServerTransID, threeDSMethodNotificationURL });
}

/** The challenge request. Window size 05 = full width of the iframe. */
export function creq(challengeToken, windowSize = '05') {
  return base64Url({
    threeDSServerTransID: challengeToken.threeDSServerTransID,
    acsTransID: challengeToken.acsTransID,
    messageVersion: challengeToken.messageVersion || '2.2.0',
    messageType: 'CReq',
    challengeWindowSize: windowSize,
  });
}

/** Posts a form into a named iframe, as an ACS expects (a browser form post, not fetch). */
export function postToFrame(frameName, url, fields) {
  const form = document.createElement('form');
  form.method = 'POST';
  form.action = url;
  form.target = frameName;
  form.hidden = true;
  for (const [name, value] of Object.entries(fields)) {
    const input = document.createElement('input');
    input.type = 'hidden';
    input.name = name;
    input.value = value;
    form.appendChild(input);
  }
  document.body.appendChild(form);
  form.submit();
  form.remove();
}
