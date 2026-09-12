import { useStore } from '../../state/store';
import { ACHIEVEMENTS } from '../../state/slices/gamificationSlice';

export default function StatsTab() {
  const unlocked = useStore((s) => s.unlocked);
  const rareEvents = useStore((s) => s.rareEvents);
  const addRareEvent = useStore((s) => s.addRareEvent);
  const changeRareEvent = useStore((s) => s.changeRareEvent);
  const deleteRareEvent = useStore((s) => s.deleteRareEvent);

  return (
    <div id="stats-sec" className="tsec active">
      <div className="bl">Досягнення</div>
      <div id="achs" style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 14 }}>
        {ACHIEVEMENTS.map((a) => (
          <div className={'ai' + (unlocked.includes(a.id) ? ' on' : '')} key={a.id}>
            <div className="aico">{a.ico}</div>
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
