import { useState } from 'react';
import { useStore } from '../../state/store';
import { ULBL } from '../../state/slices/routineSlice';
import { ENERGY_LABELS } from '../../constants';
import PriorityPicker from '../tasks/PriorityPicker';

function PrioritySlot({ index }: { index: number }) {
  const tasks = useStore((s) => s.tasks);
  const priorities = useStore((s) => s.priorities);
  const clearPriority = useStore((s) => s.clearPriority);
  const [pickerOpen, setPickerOpen] = useState(false);

  const tid = priorities[index];
  const t = tid ? tasks.find((x) => x.id === tid) : null;

  return (
    <>
      <div className="prio-slot" onClick={() => setPickerOpen(true)}>
        <span className="prio-n">#{index + 1}</span>
        {t ? (
          <>
            <span className="prio-task" style={t.done ? { textDecoration: 'line-through', opacity: 0.5 } : undefined}>
              {t.done ? '✓ ' : ''}{t.title}
            </span>
            {t.zoneName && (
              <span style={{ fontSize: 10, color: t.zoneColor || '#888', fontFamily: "'Space Mono',monospace", flexShrink: 0 }}>
                {t.zoneName}
              </span>
            )}
            <button
              className="prio-clr"
              onClick={(e) => {
                e.stopPropagation();
                clearPriority(index);
              }}
            >
              ✕
            </button>
          </>
        ) : (
          <span className="prio-empty">Обрати з задач...</span>
        )}
      </div>
      {pickerOpen && <PriorityPicker slot={index} onClose={() => setPickerOpen(false)} />}
    </>
  );
}

function RoutineList() {
  const recur = useStore((s) => s.recur);
  const incRecur = useStore((s) => s.incRecur);
  const decRecur = useStore((s) => s.decRecur);
  const toggleRecurCheck = useStore((s) => s.toggleRecurCheck);
  const deleteRecur = useStore((s) => s.deleteRecur);
  const addRecur = useStore((s) => s.addRecur);

  return (
    <div id="recur-bar">
      <div className="rb-head">
        <span className="rb-lbl">Рутина дня</span>
        <div className="rb-btns">
          <button
            className="rb-add"
            onClick={() => {
              const nm = window.prompt('Назва корисної звички:');
              if (nm && nm.trim()) addRecur(nm, 'count', '#7ed321', false);
            }}
          >
            + Корисна
          </button>
          <button
            className="rb-add neg"
            onClick={() => {
              const nm = window.prompt('Назва шкідливої звички:');
              if (nm && nm.trim()) addRecur(nm, 'count', '#e24b4a', true, 5);
            }}
          >
            − Шкідлива
          </button>
        </div>
      </div>
      <div className="rc-list">
        {recur.map((r) => {
          const ul = ULBL[r.unit] || '';
          const negStyle = { borderLeftColor: r.neg ? 'var(--neg)' : r.color };
          return (
            <div className="rc-item" key={r.id} style={negStyle}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="rc-nm">
                  {r.nm} {r.neg && <span className="neg-tag">−{r.negXp} досвіду</span>}
                </div>
                <div className="rc-stat">
                  {r.unit === 'check' ? (r.done ? '✓ виконано' : 'не виконано') : `${r.val} ${ul} сьогодні${r.done ? ' · ✓' : ''}`}
                </div>
              </div>
              <div className="rc-ctrl">
                {r.unit === 'check' ? (
                  <button className={'rc-check' + (r.done ? ' on' : '')} onClick={() => toggleRecurCheck(r.id)}>
                    {r.done ? '✓ Виконано' : 'Виконати'}
                  </button>
                ) : (
                  <>
                    <button className="rc-b" onClick={() => decRecur(r.id)}>−</button>
                    <span className="rc-v">
                      {r.val}
                      <span style={{ fontSize: 9, color: 'var(--t3)', marginLeft: 2 }}>{ul}</span>
                    </span>
                    <button className={'rc-b' + (r.neg ? ' neg-b' : ' pri')} onClick={() => incRecur(r.id)}>
                      +{r.step}{ul}
                    </button>
                  </>
                )}
                <button className="rc-del" onClick={() => deleteRecur(r.id)}>✕</button>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function FocusTracker() {
  const focusChips = useStore((s) => s.focusChips);
  const currentFocus = useStore((s) => s.currentFocus);
  const focusLog = useStore((s) => s.focusLog);
  const setFocus = useStore((s) => s.setFocus);
  const clearFocus = useStore((s) => s.clearFocus);
  const addFocusChip = useStore((s) => s.addFocusChip);
  const removeFocusChip = useStore((s) => s.removeFocusChip);
  const [custom, setCustom] = useState('');
  const [expanded, setExpanded] = useState(false);

  const log = expanded ? focusLog.slice().reverse() : focusLog.slice(-5).reverse();
  const stats: Record<string, number> = {};
  focusLog.forEach((e) => {
    if (e.dur && e.dur > 0) stats[e.val] = (stats[e.val] || 0) + e.dur;
  });

  const sendCustom = () => {
    const v = custom.trim();
    if (v) {
      addFocusChip(v);
      setCustom('');
    }
  };

  return (
    <div id="focus-tracker">
      <div className="ft-head">
        <span className="ft-lbl">🧠 Гіперфокус</span>
        <span className="ft-cur">{currentFocus || '—'}</span>
        <button
          onClick={clearFocus}
          style={{ background: 'none', border: '1px solid var(--b2)', borderRadius: 5, fontSize: 10, color: 'var(--t3)', cursor: 'pointer', padding: '2px 7px', fontFamily: "'Syne',sans-serif", flexShrink: 0 }}
          title="Скинути"
        >
          ✕
        </button>
      </div>
      <div className="ft-chips">
        {focusChips.map((v) => (
          <div className={'ftc' + (currentFocus === v ? ' sel' : '')} key={v} style={{ display: 'inline-flex', alignItems: 'center', gap: 3 }}>
            <span onClick={() => setFocus(v)}>{v}</span>
            <span onClick={() => removeFocusChip(v)} style={{ cursor: 'pointer', opacity: 0.6 }}>✕</span>
          </div>
        ))}
      </div>
      <div className="ft-row">
        <input
          type="text"
          className="ft-inp"
          value={custom}
          onChange={(e) => setCustom(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && sendCustom()}
          placeholder="Інше..."
        />
        <button className="ft-ok" onClick={sendCustom}>OK</button>
      </div>
      <div className="ft-log">
        {!focusLog.length ? (
          <div style={{ fontSize: 10, color: 'var(--t3)', padding: '3px 0' }}>Ще немає переключень...</div>
        ) : (
          <>
            {expanded && !!Object.keys(stats).length && (
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 6, paddingBottom: 6, borderBottom: '1px solid var(--b1)' }}>
                {Object.keys(stats).map((k) => (
                  <span key={k} style={{ background: 'color-mix(in srgb,var(--z) 12%,transparent)', border: '1px solid color-mix(in srgb,var(--z) 25%,transparent)', borderRadius: 9, padding: '2px 8px', fontSize: 10, color: 'var(--z)' }}>
                    {k}: {stats[k]}хв
                  </span>
                ))}
              </div>
            )}
            {log.map((e, i) => (
              <div className="ft-entry" key={i}>
                <span className="fte-t">{e.time}</span>
                <span className="fte-v">{e.val}</span>
                {e.dur ? <span className="fte-d">{e.dur} хв</span> : <span className="fte-d" style={{ color: 'var(--z)' }}>зараз</span>}
              </div>
            ))}
            <div style={{ marginTop: 4, textAlign: 'center' }}>
              <button
                onClick={() => setExpanded(!expanded)}
                style={{ background: 'none', border: 'none', fontSize: 10, color: 'var(--t3)', cursor: 'pointer', fontFamily: 'Syne,sans-serif' }}
              >
                {expanded ? '▲ Згорнути' : `▼ Вся історія (${focusLog.length})`}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

export default function StateTab() {
  const energy = useStore((s) => s.energy);
  const setEnergy = useStore((s) => s.setEnergy);

  return (
    <div id="state-sec" className="tsec active">
      <div id="morning-card">
        <h4>🎯 Пріоритети дня</h4>
        <PrioritySlot index={0} />
        <PrioritySlot index={1} />
        <PrioritySlot index={2} />
        <div className="nrj-row">
          <span className="nrj-lbl">Енергія:</span>
          <div className="nrj-btns">
            {['😴', '😐', '🙂', '😊', '🔥'].map((ico, i) => (
              <div className={'nrj-btn' + (energy === i + 1 ? ' sel' : '')} key={i} onClick={() => setEnergy((i + 1) as 1 | 2 | 3 | 4 | 5)}>
                {ico}
              </div>
            ))}
          </div>
          <span id="nrj-lbl">{energy ? ENERGY_LABELS[energy] : ''}</span>
        </div>
      </div>

      <RoutineList />
      <FocusTracker />
    </div>
  );
}
