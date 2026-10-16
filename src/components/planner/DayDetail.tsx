import { useStore } from '../../state/store';
import { fmtHuman } from '../../utils/date';
import { PLAN_TEMPLATES } from '../../constants';
import type { DateKey } from '../../types';
import { DayAnalysis, DayJournal } from './DayJournal';
import DayZones from './DayZones';
import DayTemplates from './DayTemplates';
import { canTogglePlannedTask, isCalendarRepeat, isDeferredOccurrenceOn, taskDeferredOccurrenceAt, taskDoneOn, taskOccursOn } from '../../utils/taskSchedule';
import { useTaskProjection } from '../../hooks/useTaskProjection';
import { questDeferralLabel } from '../../utils/questPolicy';
import { completedTaskOccurrences } from '../../utils/taskHistory';
import Icon from '../common/Icon';
import { runTaskCompletionWithReward } from '../../utils/taskRewardFeedback';

export default function DayDetail({ ds }: { ds: DateKey }) {
  const dayIsRest = useStore((s) => s.dayIsRest);
  const planSchedules = useStore((s) => s.planSchedules);
  const setPlanSchedule = useStore((s) => s.setPlanSchedule);
  const planDayLog = useStore((s) => s.planDayLog);
  const setDayNote = useStore((s) => s.setDayNote);
  const toggleRestDay = useStore((s) => s.toggleRestDay);
  const tasks = useStore((s) => s.tasks);
  const taskHistory = useStore((s) => s.taskHistory);
  const toggleTask = useStore((s) => s.toggleTask);
  const deleteTask = useStore((s) => s.deleteTask);
  const planItems = useStore((s) => s.planItems);
  const togglePlanItem = useStore((s) => s.togglePlanItem);
  const removePlanItem = useStore((s) => s.removePlanItem);
  const newTask = useStore((s) => s.requestNewTaskEditor);
  const editTask = useStore((s) => s.requestEditTask);
  const addPlanEvent = useStore((s) => s.addPlanEvent);
  const openKbdOverlay = useStore((s) => s.openKbdOverlay);

  const noteText = planDayLog[ds]?.note || '';
  const projection = useTaskProjection();
  const zones = useStore(s => s.zones);

  const today = new Date();
  const todayStr = today.getFullYear() + '-' + String(today.getMonth() + 1).padStart(2, '0') + '-' + String(today.getDate()).padStart(2, '0');
  const isPast = ds < todayStr;
  const isFuture = ds > todayStr;
  const isRest = dayIsRest(ds);
  const sched = planSchedules[ds] || '';
  const items = planItems[ds] || [];
  const displayTime = (t: typeof tasks[number]) => {
    const at = isDeferredOccurrenceOn(t, ds, projection) ? taskDeferredOccurrenceAt(t, projection) : undefined;
    return at === undefined ? t.schedTime || t.alarmTime || ''
      : new Date(at).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
  };
  const dayTasks = tasks.filter(t => taskOccursOn(t, ds, projection)).sort((a, b) => displayTime(a).localeCompare(displayTime(b)));
  const historicalRows = isPast
    ? completedTaskOccurrences(taskHistory, tasks).filter(event => event.date === ds)
    : [];
  const completedTaskIdentities = new Set(historicalRows.map(event => JSON.stringify([event.taskId, event.taskCreated])));
  const visibleDayTasks = dayTasks.filter(task => !isPast || !completedTaskIdentities.has(JSON.stringify([task.id, task.created])));

  return (
    <div className="plan-detail open">
      <div className="pdd-head">
        <span className="pdd-date">{fmtHuman(new Date(ds + 'T12:00:00'))}{isPast ? ' · минуле' : isFuture ? ' · план' : ' · сьогодні'}</span>
        <div style={{ display: 'flex', gap: 5 }}>
          <button
            className="pdd-add"
            style={{ background: 'var(--s3)', color: 'var(--t2)', border: '1px solid var(--b2)' }}
            onClick={() => newTask(ds)}
          >
            + Задача
          </button>
          <button
            className="pdd-add"
            onClick={() => {
              openKbdOverlay({
                hint: 'Подія на ' + fmtHuman(new Date(ds + 'T12:00:00')),
                placeholder: 'напр. ДН Оленки, Похід в кіно...',
                showTaskHint: false,
                onSend: (txt) => addPlanEvent(ds, txt),
              });
            }}
          >
            <Icon name="idea" /> Подія
          </button>
        </div>
      </div>

      {visibleDayTasks.map((t) => {
        const done = taskDoneOn(t, ds, taskHistory);
        const canToggle = !isPast && canTogglePlannedTask(t, ds, projection.now);
        return (
          <div className="plan-task-row" key={t.id}>
            <button disabled={!canToggle} title={isPast ? 'Історичне виконання не змінює поточну задачу' : !canToggle ? 'Це майбутнє виконання повтору' : undefined} aria-label={(t.type === 'negative' ? 'Зафіксувати: ' : done ? 'Повернути: ' : 'Виконати: ') + t.title} className={'plan-ck' + (done ? ' on' : '')} onClick={event => runTaskCompletionWithReward(event, () => { useStore.getState().resetRepeatingTasksFor(new Date()); toggleTask(t.id); })}>{done ? '✓' : ''}</button>
            <button onClick={() => editTask(t.id)} className="plan-task-nm" style={done ? { textDecoration: 'line-through', opacity: 0.5 } : undefined}>{t.title}{isCalendarRepeat(t) ? ' ↺' : ''}</button>
            {isDeferredOccurrenceOn(t, ds, projection)
              ? <span className="plan-task-deferral" title={questDeferralLabel(t, zones)}>Перенесено</span>
              : t.type === 'sched' && t.schedTime && <span style={{ fontSize: 10, color: 'var(--z)', fontFamily: 'monospace' }}>{t.schedTime}</span>}
            <button aria-label={'У кошик: ' + t.title} className="plan-rm" onClick={() => deleteTask(t.id)}>✕</button>
          </div>
        );
      })}

      {historicalRows.map((event) => (
        <div className="plan-task-row" key={'history:' + event.id} aria-label={'Історичне виконання: ' + event.title}>
          <button type="button" disabled title="Збережене історичне виконання" aria-label={'Виконано: ' + event.title} className="plan-ck on">✓</button>
          <span className="plan-task-nm" style={{ textDecoration: 'line-through', opacity: 0.5 }}>{event.title}</span>
          {event.time && <span style={{ fontSize: 10, color: 'var(--t3)', fontFamily: 'monospace' }}>{event.time}</span>}
          <span style={{ fontSize: 9, color: 'var(--t3)' }}>Історія</span>
        </div>
      ))}

      {items.map((it, i) => (
        it.isPrompt ? (
          <div className="plan-task-row plan-event" key={i}>
            <Icon name="calendar" />
            <span className="plan-task-nm" style={{ color: 'var(--t3)', fontStyle: 'italic' }}>{it.title || ''}</span>
            <button aria-label="Прибрати нагадування" className="plan-rm" onClick={() => removePlanItem(ds, i)}>✕</button>
          </div>
        ) : (
          <div className={'plan-task-row' + (it.isEvent ? ' plan-event' : '')} key={i}>
            <button className={'plan-ck' + (it.done ? ' on' : '')} onClick={event => runTaskCompletionWithReward(event, () => togglePlanItem(ds, i))}>
              {it.done ? <Icon name="check" /> : it.isEvent ? <Icon name="idea" /> : ''}
            </button>
            <span className="plan-task-nm" style={it.done ? { textDecoration: 'line-through', opacity: 0.5 } : undefined}>{it.title || ''}</span>
            {it.time && <span style={{ fontSize: 10, color: 'var(--t3)', fontFamily: 'monospace' }}>{it.time}</span>}
            <button className="plan-rm" onClick={() => removePlanItem(ds, i)}>✕</button>
          </div>
        )
      ))}

      {!visibleDayTasks.length && !historicalRows.length && !items.length && <div className="plan-empty">Порожньо — додай задачу, зону або подію</div>}

      <DayAnalysis ds={ds} isFuture={isFuture} />
      <DayJournal ds={ds} />

      <div style={{ margin: '4px 0 9px' }}>
        <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--t3)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 5 }}>
          <Icon name="note" /> Примітка дня
        </div>
        <textarea
          value={noteText}
          onChange={(e) => {
            setDayNote(ds, e.target.value);
          }}
          placeholder="Запиши, що згадав про цей день…"
          style={{ width: '100%', minHeight: 70, background: 'var(--s2)', border: '1px solid var(--b2)', borderRadius: 8, padding: '9px 11px', color: 'var(--t1)', fontFamily: "'Syne',sans-serif", fontSize: 12, lineHeight: 1.55, resize: 'vertical', outline: 'none' }}
        />
      </div>

      <div className="rest-toggle" onClick={() => toggleRestDay(ds)}>
        <Icon name="moon" />
        <span className="rt-lbl">Вихідний день</span>
        <div className={'rt-sw' + (isRest ? ' on' : '')} />
      </div>

      {isRest ? (
        <div style={{ fontSize: 11, color: 'rgba(189,16,224,.7)', padding: '3px 0 6px' }}><Icon name="calendar" /> Нагадування: заплануй тиждень</div>
      ) : (
        <>
          <div className="plan-type-row">
            <div className={'ptype' + (sched === '' ? ' act' : '')} onClick={() => setPlanSchedule(ds, '')} title="Без шаблону">Довільний</div>
            {PLAN_TEMPLATES.map((t) => (
              <div className={'ptype' + (sched === t.id ? ' act' : '')} key={t.id} onClick={() => setPlanSchedule(ds, t.id)} title={t.label}>
                {t.label}
              </div>
            ))}
          </div>
          {sched && (() => {
            const tmpl = PLAN_TEMPLATES.find((t) => t.id === sched);
            return tmpl ? <div style={{ fontSize: 10, color: 'var(--z)', padding: '2px 0 6px' }}><Icon name="calendar" /> Графік: {tmpl.label} ({tmpl.hours})</div> : null;
          })()}
          <DayZones key={ds} ds={ds} />
          <DayTemplates ds={ds} />
        </>
      )}


    </div>
  );
}
