// Виконує JS-вираз у WebView Потоку через CDP: node cdp-eval.js "expr"
const expr = process.argv[2];
const http = require('http');

function getWsUrl() {
  return new Promise((res, rej) => {
    http.get('http://localhost:9222/json/list', r => {
      let b = ''; r.on('data', c => b += c);
      r.on('end', () => res(JSON.parse(b)[0].webSocketDebuggerUrl));
    }).on('error', rej);
  });
}

(async () => {
  const ws = new WebSocket(await getWsUrl());
  ws.onopen = () => {
    ws.send(JSON.stringify({
      id: 1, method: 'Runtime.evaluate',
      params: { expression: expr, returnByValue: true, awaitPromise: true }
    }));
  };
  ws.onmessage = e => {
    const m = JSON.parse(e.data);
    if (m.id === 1) {
      if (m.result.exceptionDetails) console.log('EXCEPTION:', JSON.stringify(m.result.exceptionDetails.exception));
      else console.log(JSON.stringify(m.result.result.value));
      ws.close(); process.exit(0);
    }
  };
  setTimeout(() => { console.log('TIMEOUT'); process.exit(1); }, 15000);
})();
