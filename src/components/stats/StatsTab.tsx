import { useStore } from '../../state/store';
import { ACHIEVEMENTS } from '../../state/slices/gamificationSlice';
import Icon from '../common/Icon';
import '../../styles/notes-focus-parity.css';

export default function StatsTab() {
  const tasks = useStore((s) => s.tasks);
  const unlocked = useStore((s) => s.unlocked);
  const streak = useStore((s) => s.streak);
  const negCount = useStore((s) => s.negCount);
  const done = useStore((s) => s.done);
  const level = useStore((s) => s.level);
  const xpTotal = useStore((s) => s.xpTotal);
  const rareEvents = useStore((s) => s.rareEvents);
  const addRareEvent = useStore((s) => s.addRareEvent);
  const changeRareEvent = useStore((s) => s.changeRareEvent);
  const deleteRareEvent = useStore((s) => s.deleteRareEvent);

  return (
    <div id="stats-sec" className="tsec active">
      <div className="streak-c">
        <div className="stats-streak-icon" aria-hidden="true"><Icon name="repeat" size={28} /></div>
        <div>
          <div className="stats-streak-value">{streak}</div>
          <div className="stats-streak-label">Серія (збережене значення)</div>
        </div>
        <div className="stats-harmful">
          <div>Шкідливих</div>
          <strong>{negCount}</strong>
        </div>
      </div>
      <div id="sg">
        <div className="sc2"><div className="sv">{done}</div><div className="sl">Виконано</div></div>
        <div className="sc2"><div className="sv">{tasks.filter((t) => !t.trashed && !t.someday).length}</div><div className="sl">Задач</div></div>
        <div className="sc2"><div className="sv">{level}</div><div className="sl">Рівень</div></div>
        <div className="sc2"><div className="sv">{xpTotal}</div><div className="sl">Загальний досвід</div></div>
      </div>
      <div className="bl">Досягнення</div>
      <div id="achs" style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 14 }}>
        {ACHIEVEMENTS.map((a) => (
          <div className={'ai' + (unlocked.includes(a.id) ? ' on' : '')} key={a.id}>
            <div className="aico"><Icon name={unlocked.includes(a.id) ? 'check' : 'chart'} /></div>
            <div className="anm">{a.nm}</div>
          </div>
        ))}
      </div>

      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
        <div className="bl" style={{ margin: 0 }}>Рідкісні події</div>
        <button
          className="ab"
          onClick={() => {
            const nm = window.prompt('Назва події (наприклад: Зайві витрати, Борг):');
            if (!nm || !nm.trim()) return;
            const dir = window.confirm('Лічильник зменшується? (ОК = так, Скасувати = зростає)') ? 'down' : 'up';
            addRareEvent(nm, dir);
          }}
        >
          + Подія
        </button>
      </div>
      <div id="rare-list">
        {!rareEvents.length ? (
          <div style={{ fontSize: 11, color: 'var(--t3)', textAlign: 'center', padding: 10 }}>Немає подій — натисни +</div>
        ) : (
          rareEvents.map((r) => (
            <div key={r.id} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '7px 0', borderBottom: '1px solid var(--b1)' }}>
              <span style={{ flex: 1, fontSize: 13 }}>{r.nm}</span>
              <button
                className="rc-btn"
                onClick={() => changeRareEvent(r.id, -1)}
                style={{ background: 'none', border: '1px solid var(--b2)', borderRadius: 6, padding: '2px 8px', cursor: 'pointer', fontSize: 14, color: 'var(--t2)' }}
              >
                −
              </button>
              <span style={{ minWidth: 28, textAlign: 'center', fontSize: 15, fontWeight: 700, color: 'var(--z)' }}>{r.val}</span>
              <button
                className="rc-btn"
                onClick={() => changeRareEvent(r.id, 1)}
                style={{ background: 'none', border: '1px solid var(--b2)', borderRadius: 6, padding: '2px 8px', cursor: 'pointer', fontSize: 14, color: 'var(--t2)' }}
              >
                +
              </button>
              <span style={{ fontSize: 10, color: 'var(--t3)' }}>{r.dir === 'down' ? '↓' : '↑'}</span>
              <button className="ab" onClick={() => deleteRareEvent(r.id)} style={{ color: 'var(--t3)' }}>✕</button>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
