import { useStore } from '../../state/store';
import type { DateKey } from '../../types';
import { ENERGY_LABELS } from '../../constants';

function truncate18(s: string): string {
  return s.length > 18 ? s.slice(0, 18) + '…' : s;
}
function hmOf(iso: string): string {
  try {
    return new Date(iso).toLocaleTimeString('uk', { hour: '2-digit', minute: '2-digit' });
  } catch {
    return '';
  }
}
function dateOf(iso: string): string {
  try {
    const d = new Date(iso);
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  } catch {
    return '';
  }
}

export function DayAnalysis({ ds, isFuture }: { ds: DateKey; isFuture: boolean }) {
  const dayStats = useStore((s) => s.dayStats);
  const st = dayStats(ds);
  const routArr = Object.keys(st.routineDone).map((k) => k + ': ' + st.routineDone[k]);

  return (
    <div className="pd-analysis" style={{ background: 'var(--s2)', borderRadius: 8, padding: '9px 11px', marginBottom: 9, fontSize: 11, color: 'var(--t2)' }}>
      <div style={{ fontSize: 9, textTransform: 'uppercase', letterSpacing: '.06em', color: 'var(--t3)', marginBottom: 5 }}>
        {isFuture ? 'План на день' : 'Аналіз дня'}
      </div>
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        <span title="Виконано задач">✅ {st.tasksDone} задач</span>
        <span title="Додано нотаток">📝 {st.notes} нотаток</span>
        <span title="Переключень гіперфокусу">🧠 {st.focusSwitches}</span>
        <span title="Енергія">{ENERGY_LABELS[st.energy] || '—'} енергія</span>
      </div>
      {!!st.focusList.length && <div style={{ color: 'var(--t3)', marginTop: 4 }}>Фокус: {st.focusList.slice(0, 5).join(' · ')}</div>}
      {!!routArr.length && <div style={{ color: 'var(--t3)', marginTop: 3 }}>Рутина: {routArr.join(' · ')}</div>}
    </div>
  );
}

export function DayJournal({ ds }: { ds: DateKey }) {
  const tasks = useStore((s) => s.tasks);
  const qnotes = useStore((s) => s.qnotes);
  const folders = useStore((s) => s.folders);
  const focusLog = useStore((s) => s.focusLog);
  const planItems = useStore((s) => s.planItems);
  const dayBlocks = useStore((s) => s.dayBlocks);
  const zones = useStore((s) => s.zones);

  const jr: { tm: string; ic: string; tx: string }[] = [];
  tasks.filter((t) => t.created && dateOf(t.created) === ds).forEach((t) => jr.push({ tm: hmOf(t.created), ic: '🆕', tx: `створено «${truncate18(t.title)}»` }));
  tasks.filter((t) => t.doneDate === ds).forEach((t) => jr.push({ tm: t.doneAt || '', ic: '✅', tx: t.title }));
  qnotes.filter((n) => n.date === ds).forEach((n) => {
    const f = folders.find((x) => x.id === n.folder);
    jr.push({ tm: n.time || '', ic: f ? f.ico : '📝', tx: `створено «${truncate18(n.txt)}»` });
  });
  (focusLog || []).filter((f) => f.date === ds).forEach((f) => jr.push({ tm: f.time || '', ic: '🧠', tx: 'Фокус: ' + f.val }));
  jr.sort((a, b) => (a.tm || '').localeCompare(b.tm || ''));

  const jrRight: { ic: string; tx: string }[] = [];
  (planItems[ds] || []).filter((it) => it.isEvent).forEach((it) => jrRight.push({ ic: '🎉', tx: (it.time ? it.time + ' ' : '') + it.title }));
  dayBlocks(ds).forEach((b) => {
    const z = zones.find((zz) => zz.id === b.zoneId);
    jrRight.push({ ic: '🕐', tx: (z ? z.nm : 'зона') + ' ' + b.s + '–' + b.e });
  });

  return (
    <div className="pd-journal">
      <div className="pd-jrnl-h">
        📜 Журнал дня <span style={{ color: 'var(--t3)', fontWeight: 400, textTransform: 'none', letterSpacing: 0 }}>· {jr.length}</span>
      </div>
      {jrRight.map((e, i) => (
        <div className="jrow jrow-r" key={'r' + i}>
          <span className="jtxt jtxt-r">{e.tx}</span>
          <span className="jic">{e.ic}</span>
        </div>
      ))}
      {jr.length ? (
        jr.map((e, i) => (
          <div className="jrow" key={i}>
            <span className="jtime">{e.tm || '·'}</span>
            <span className="jic">{e.ic}</span>
            <span className="jtxt">{e.tx}</span>
          </div>
        ))
      ) : !jrRight.length ? (
        <div style={{ fontSize: 11, color: 'var(--t3)', padding: '4px 0 4px 11px' }}>
          Поки порожньо — виконуй задачі, лови нотатки, відмічай фокус.
        </div>
      ) : null}
    </div>
  );
}
