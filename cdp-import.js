// Одноразовий тест: імпорт бекапу в апку через mergeState (як кнопка «Імпорт»)
const fs = require('fs');
const http = require('http');
const backup = fs.readFileSync(process.argv[2], 'utf8');

const expr = `(function(){
  var d = ${backup};
  var added = mergeState(d);
  saveState();
  renderTasks(); renderRecur(); renderZones(); renderQN();
  renderSomeday(); renderPlanner(); renderPrios(); renderFocusChips(); updXP();
  updateClock();
  return JSON.stringify({
    added: added,
    priorities: S.priorities,
    prioTitles: S.priorities.map(function(id){ var t=S.tasks.find(function(x){return x.id===id;}); return t?t.title.slice(0,25):null; }),
    zones: S.zones.map(function(z){return z.id+':'+z.nm;}),
    sampleZoneTasks: S.tasks.filter(function(t){return t.zoneName==='Підйом';}).map(function(t){return t.zoneId;}),
    folders: S.folders.length, tasks: S.tasks.length
  });
})()`;

http.get('http://localhost:9222/json/list', r => {
  let b = ''; r.on('data', c => b += c);
  r.on('end', () => {
    const ws = new WebSocket(JSON.parse(b)[0].webSocketDebuggerUrl);
    ws.onopen = () => ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression: expr, returnByValue: true } }));
    ws.onmessage = e => {
      const m = JSON.parse(e.data);
      if (m.id === 1) {
        if (m.result.exceptionDetails) console.log('EXCEPTION:', JSON.stringify(m.result.exceptionDetails));
        else console.log(m.result.result.value);
        process.exit(0);
      }
    };
  });
});
setTimeout(() => { console.log('TIMEOUT'); process.exit(1); }, 20000);
