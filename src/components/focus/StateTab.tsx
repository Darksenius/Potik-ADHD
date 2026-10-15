import { useState } from 'react';
import { useStore } from '../../state/store';
import { hasTaskCounter } from '../../utils/taskCounter';
import TaskItem from '../tasks/TaskItem';
import '../../styles/daily-tasks.css';
import { ENERGY_LABELS } from '../../constants';
import PriorityPicker from '../tasks/PriorityPicker';
import { canPrioritizeTask } from '../../utils/taskSchedule';
import { useClock } from '../../hooks/useClock';
import Icon from '../common/Icon';

function PrioritySlot({ index }: { index: number }) {
  useClock();
  const tasks = useStore((s) => s.tasks);
  const priorities = useStore((s) => s.priorities);
  const clearPriority = useStore((s) => s.clearPriority);
  const [pickerOpen, setPickerOpen] = useState(false);

  const tid = priorities[index];
  const t = tid != null ? tasks.find((x) => x.id === tid && canPrioritizeTask(x)) : null;

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

function DailyTasks() {
  useClock();
  const tasks = useStore(s => s.tasks);
  const priorities = useStore(s => s.priorities);
  const requestNewTaskEditor = useStore(s => s.requestNewTaskEditor);
  const [showCompleted, setShowCompleted] = useState(false);
  const recurring = tasks.filter(t => !t.trashed && !t.someday
    && (hasTaskCounter(t) || t.type === 'negative' || t.repeat !== 'none'));
  const completed = recurring.filter(t => t.done).length;
  const visible = recurring.filter(t => showCompleted || !t.done);

  return <section className="daily-tasks" aria-labelledby="daily-tasks-title">
    <div className="section-title"><h2 id="daily-tasks-title">Повсякденні задачі</h2></div>
    <p className="section-hint">Лічильники та повторювані справи — ті самі задачі, що в Плані й «Усі задачі». Задай одиницю, крок, ціль і потрібну зону. Щоденне повторення обнуляє прогрес наступного дня.</p>
    <div className="daily-task-actions">
      <button className="secondary-action" onClick={() => requestNewTaskEditor(undefined, { type: 'counter', repeat: 'daily', counterTarget: 0 })}><Icon name="plus" /> Лічильник</button>
      <button className="secondary-action" onClick={() => requestNewTaskEditor(undefined, { type: 'simple', repeat: 'daily' })}><Icon name="repeat" /> Повторювана задача</button>
    </div>
    {completed > 0 && <button className="secondary-action daily-completed" onClick={() => setShowCompleted(!showCompleted)}>{showCompleted ? 'Сховати виконані' : `Виконані (${completed})`}</button>}
    <div className="daily-task-list">{visible.map(t => <TaskItem key={t.id} t={t} isPriority={priorities.includes(t.id)} visibleIds={visible.map(task => task.id)} allowMove={false} allView quickCounter />)}</div>
    {!visible.length && <p className="empty">{completed ? 'Усе виконано. Нове повторення з’явиться за розкладом.' : 'Створи власні лічильники: вода в мл, рух у хвилинах чи будь-яка дія в разах.'}</p>}
  </section>;
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
        <span className="ft-lbl"><Icon name="focus" /> Гіперфокус</span>
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
        <h4><Icon name="quest" /> Пріоритети дня</h4>
        <PrioritySlot index={0} />
        <PrioritySlot index={1} />
        <PrioritySlot index={2} />
        <div className="nrj-row">
          <span className="nrj-lbl">Енергія:</span>
          <div className="nrj-btns">
            {[1, 2, 3, 4, 5].map(value => (
              <button type="button" aria-label={'Енергія: ' + ENERGY_LABELS[value]} aria-pressed={energy === value} className={'nrj-btn' + (energy === value ? ' sel' : '')} key={value} onClick={() => setEnergy(value as 1 | 2 | 3 | 4 | 5)}>
                {value}
              </button>
            ))}
          </div>
          <span id="nrj-lbl">{energy ? ENERGY_LABELS[energy] : ''}</span>
        </div>
      </div>

      <DailyTasks />
      <FocusTracker />
    </div>
  );
}
